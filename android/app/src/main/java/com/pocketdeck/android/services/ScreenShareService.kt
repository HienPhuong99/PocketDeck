package com.pocketdeck.android.services

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.pocketdeck.android.protocol.DeckLinkFraming
import com.pocketdeck.android.protocol.DeckLinkHostServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground `MediaProjection` service capturing Android screen -> Hardware H.264 (`MediaCodec`)
 * + internal device audio (`AudioPlaybackCapture` on Android 10+) and streaming over `DeckLinkHostServer`.
 */
class ScreenShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()

        val maxDim = intent?.getIntExtra(EXTRA_MAX_DIM, 1280) ?: 1280
        val bitrateBps = intent?.getIntExtra(EXTRA_BITRATE_BPS, 4_000_000) ?: 4_000_000
        val fps = intent?.getIntExtra(EXTRA_FPS, 60) ?: 60
        val captureAudio = intent?.getBooleanExtra(EXTRA_AUDIO, true) ?: true

        startCapture(resultCode, resultData, maxDim, bitrateBps, fps, captureAudio)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PocketDeck Chia sẻ màn hình",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)

        val stopIntent = Intent(this, ScreenShareService::class.java).apply { action = ACTION_STOP }
        val stopPending = android.app.PendingIntent.getService(
            this,
            1,
            stopIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("PocketDeck đang chia sẻ màn hình")
            .setContentText("Phiên kết nối mã hoá AES-256-GCM đang hoạt động")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng chia sẻ", stopPending)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        maxDim: Int,
        bitrateBps: Int,
        fps: Int,
        captureAudio: Boolean,
    ) {
        stopCapture()
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(resultCode, resultData) ?: return
        mp.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                    stopSelf()
                }
            },
            null,
        )
        projection = mp

        val dm = resources.displayMetrics
        val rawW = dm.widthPixels.coerceAtLeast(360)
        val rawH = dm.heightPixels.coerceAtLeast(640)
        val density = dm.densityDpi

        val longest = maxOf(rawW, rawH)
        val scale = if (longest > maxDim) maxDim.toFloat() / longest.toFloat() else 1.0f
        // Align to multiples of 16 for hardware H.264 encoder compatibility
        val encW = (((rawW * scale).toInt() / 16) * 16).coerceAtLeast(320)
        val encH = (((rawH * scale).toInt() / 16) * 16).coerceAtLeast(320)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // repeat every 100ms when static
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()
        videoEncoder = encoder

        virtualDisplay = mp.createVirtualDisplay(
            "PocketDeckScreen",
            encW,
            encH,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null,
        )

        running.set(true)
        activeInstance = this
        _isSharing.value = true

        videoThread = Thread({
            drainVideoLoop(encoder, encW, encH)
        }, "PocketDeck-ScreenH264").apply {
            isDaemon = true
            start()
        }

        if (captureAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            audioThread = Thread({
                captureInternalAudioLoop(mp)
            }, "PocketDeck-InternalAudio").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun requestKeyFrameAndResendConfig() {
        val enc = videoEncoder ?: return
        cachedCodecConfig?.let { cfg ->
            val host = hostServerRef
            if (host != null) {
                runBlocking {
                    host.broadcastVideo(DeckLinkFraming.VIDEO_KIND_CONFIG, 0L, lastWidth, lastHeight, cfg)
                }
            }
        }
        runCatching {
            val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            enc.setParameters(params)
        }
    }

    private fun drainVideoLoop(encoder: MediaCodec, width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        val info = MediaCodec.BufferInfo()
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val outIdx = try {
                encoder.dequeueOutputBuffer(info, 25_000)
            } catch (_: Exception) {
                break
            }
            if (outIdx >= 0) {
                val ob = encoder.getOutputBuffer(outIdx)
                if (ob != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.get(bytes, 0, info.size)
                    val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val kind = if (isConfig) {
                        cachedCodecConfig = bytes
                        DeckLinkFraming.VIDEO_KIND_CONFIG
                    } else {
                        DeckLinkFraming.VIDEO_KIND_DATA
                    }
                    hostServerRef?.let { server ->
                        runBlocking {
                            server.broadcastVideo(kind, info.presentationTimeUs, width, height, bytes)
                        }
                    }
                }
                runCatching { encoder.releaseOutputBuffer(outIdx, false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureInternalAudioLoop(mp: MediaProjection) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        var record: AudioRecord? = null
        try {
            val sampleRate = 48000
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4096)
            record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) return
            record.startRecording()
            val chunk = ByteArray(3840) // 20 ms of 48 kHz stereo PCM16
            var ptsUs = 0L
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val n = record.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val payload = if (n == chunk.size) chunk.copyOf() else chunk.copyOfRange(0, n)
                    hostServerRef?.let { server ->
                        runBlocking {
                            server.broadcastAudio(DeckLinkFraming.AUDIO_CODEC_PCM16, sampleRate, 2, ptsUs, payload)
                        }
                    }
                    ptsUs += (n.toLong() * 1_000_000L) / (sampleRate * 4L)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    private fun stopCapture() {
        running.set(false)
        _isSharing.value = false
        if (activeInstance === this) activeInstance = null

        videoThread?.interrupt()
        audioThread?.interrupt()
        runCatching { videoThread?.join(500) }
        runCatching { audioThread?.join(500) }
        videoThread = null
        audioThread = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching { videoEncoder?.stop() }
        runCatching { videoEncoder?.release() }
        videoEncoder = null

        runCatching { projection?.stop() }
        projection = null
        cachedCodecConfig = null
    }

    companion object {
        const val CHANNEL_ID = "pocketdeck_screen_share"
        const val NOTIF_ID = 7201
        const val ACTION_STOP = "com.pocketdeck.android.STOP_SHARE"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_MAX_DIM = "max_dim"
        const val EXTRA_BITRATE_BPS = "bitrate_bps"
        const val EXTRA_FPS = "fps"
        const val EXTRA_AUDIO = "audio"

        @Volatile
        var hostServerRef: DeckLinkHostServer? = null

        @Volatile
        var activeInstance: ScreenShareService? = null
            private set

        @Volatile
        private var cachedCodecConfig: ByteArray? = null
        @Volatile
        private var lastWidth: Int = 1080
        @Volatile
        private var lastHeight: Int = 2400

        private val _isSharing = MutableStateFlow(false)
        val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()
    }
}
