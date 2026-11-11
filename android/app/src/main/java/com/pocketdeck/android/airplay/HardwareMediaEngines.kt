package com.pocketdeck.android.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hardware-accelerated H.264 (`MediaCodec` `"video/avc"`) decoder rendering Annex-B access units
 * directly onto an Android `Surface`. Used for both AirPlay iPhone Mirroring and DeckLink Remote View.
 */
class HardwareVideoSurfaceDecoder(
    private val onResolutionChanged: (Int, Int) -> Unit = { _, _ -> },
) {
    private data class NalPacket(val data: ByteArray, val isConfig: Boolean)

    private val queue = LinkedBlockingQueue<NalPacket>(180)
    private val running = AtomicBoolean(false)
    private var worker: Thread? = null
    @Volatile
    private var lastCodecConfig: ByteArray? = null

    fun attachSurface(surface: Surface, initialWidth: Int = 1080, initialHeight: Int = 1920) {
        detachSurface()
        running.set(true)
        worker = Thread({
            decodeLoop(surface, initialWidth.coerceAtLeast(64), initialHeight.coerceAtLeast(64))
        }, "PocketDeck-HwH264Decoder").apply {
            isDaemon = true
            start()
        }
    }

    fun submitAnnexB(data: ByteArray, isConfig: Boolean = false) {
        if (data.isEmpty()) return
        if (isConfig) {
            lastCodecConfig = data.copyOf()
        }
        if (!queue.offer(NalPacket(data, isConfig))) {
            queue.poll()
            queue.offer(NalPacket(data, isConfig))
        }
    }

    fun detachSurface() {
        running.set(false)
        worker?.interrupt()
        try {
            worker?.join(600)
        } catch (_: InterruptedException) {
        }
        worker = null
        queue.clear()
    }

    private fun decodeLoop(surface: Surface, initW: Int, initH: Int) {
        var codec: MediaCodec? = null
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, initW, initH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()

            // Feed cached SPS/PPS immediately if stream already started before Surface attached
            lastCodecConfig?.let { cfg ->
                feedInput(codec, cfg, isConfig = true)
            }

            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L

            while (running.get() && !Thread.currentThread().isInterrupted) {
                val pkt = queue.poll(15, TimeUnit.MILLISECONDS)
                if (pkt != null) {
                    val flags = if (pkt.isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val ib = codec.getInputBuffer(inIdx)
                        if (ib != null && pkt.data.size <= ib.capacity()) {
                            ib.clear()
                            ib.put(pkt.data)
                            codec.queueInputBuffer(inIdx, 0, pkt.data.size, ptsUs, flags)
                            ptsUs += 16_666L
                        } else {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                        }
                    }
                }

                while (running.get()) {
                    val outIdx = codec.dequeueOutputBuffer(info, 0)
                    when {
                        outIdx >= 0 -> {
                            codec.releaseOutputBuffer(outIdx, surface.isValid)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            val w = of.getInteger(MediaFormat.KEY_WIDTH)
                            val h = of.getInteger(MediaFormat.KEY_HEIGHT)
                            if (w > 0 && h > 0) {
                                onResolutionChanged(w, h)
                            }
                        }
                        else -> break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun feedInput(codec: MediaCodec, bytes: ByteArray, isConfig: Boolean) {
        val idx = codec.dequeueInputBuffer(20_000)
        if (idx >= 0) {
            val ib = codec.getInputBuffer(idx)
            if (ib != null && bytes.size <= ib.capacity()) {
                ib.clear()
                ib.put(bytes)
                codec.queueInputBuffer(
                    idx,
                    0,
                    bytes.size,
                    0L,
                    if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
                )
            } else {
                codec.queueInputBuffer(idx, 0, 0, 0L, 0)
            }
        }
    }
}

/**
 * Hardware AAC-ELD / AAC-LC decoder (`MediaCodec` `"audio/mp4a-latm"`) + `AudioTrack` output
 * for AirPlay audio (`ct = 4` AAC-LC or `ct = 8` AAC-ELD, 44.1 kHz stereo).
 */
class AirPlayAudioPlayer {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var activeCt: Int = -1
    private val info = MediaCodec.BufferInfo()
    @Volatile
    var muted: Boolean = false

    @Synchronized
    fun submitPacket(codecType: Int, rawFrame: ByteArray) {
        if (rawFrame.isEmpty()) return
        if (codecType != 4 && codecType != 8) return // AAC-LC (4) & AAC-ELD (8) supported natively
        if (codec == null || activeCt != codecType) {
            recreateCodec(codecType)
        }
        val c = codec ?: return
        val t = track ?: return
        try {
            val inIdx = c.dequeueInputBuffer(5_000)
            if (inIdx >= 0) {
                val ib = c.getInputBuffer(inIdx)
                if (ib != null && rawFrame.size <= ib.capacity()) {
                    ib.clear()
                    ib.put(rawFrame)
                    c.queueInputBuffer(inIdx, 0, rawFrame.size, 0L, 0)
                } else {
                    c.queueInputBuffer(inIdx, 0, 0, 0L, 0)
                }
            }
            while (true) {
                val outIdx = c.dequeueOutputBuffer(info, 0)
                if (outIdx < 0) break
                val ob = c.getOutputBuffer(outIdx)
                if (ob != null && info.size > 0 && !muted) {
                    val pcm = ByteArray(info.size)
                    ob.position(info.offset)
                    ob.get(pcm, 0, info.size)
                    t.write(pcm, 0, pcm.size)
                }
                c.releaseOutputBuffer(outIdx, false)
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun recreateCodec(codecType: Int) {
        close()
        activeCt = codecType
        try {
            val sampleRate = 44100
            val csd = if (codecType == 4) {
                byteArrayOf(0x12, 0x10)
            } else {
                byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
            }
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            dec.configure(fmt, null, null, 0)
            dec.start()
            codec = dec

            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(8192)
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            at.play()
            track = at
        } catch (_: Exception) {
            close()
        }
    }

    @Synchronized
    fun flush() {
        try {
            codec?.flush()
            track?.pause()
            track?.flush()
            track?.play()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        activeCt = -1
    }
}

/**
 * Low-latency PCM-16LE stereo `AudioTrack` player for DeckLink remote Android/Windows audio.
 */
class PcmAudioPlayer(private val sampleRate: Int = 48000) {
    private var track: AudioTrack? = null
    @Volatile
    var muted: Boolean = false

    @Synchronized
    fun writePcm16Le(pcm: ByteArray) {
        if (muted || pcm.isEmpty()) return
        if (track == null) {
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                ).coerceAtLeast(8192)
                val at = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                at.play()
                track = at
            } catch (_: Exception) {
                return
            }
        }
        try {
            track?.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun close() {
        try {
            track?.stop()
        } catch (_: Exception) {
        }
        try {
            track?.release()
        } catch (_: Exception) {
        }
        track = null
    }
}
