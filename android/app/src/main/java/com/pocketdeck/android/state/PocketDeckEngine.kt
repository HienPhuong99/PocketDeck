package com.pocketdeck.android.state

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import com.pocketdeck.android.airplay.AirPlayAudioPlayer
import com.pocketdeck.android.airplay.AirPlayConfig
import com.pocketdeck.android.airplay.AirPlayEvents
import com.pocketdeck.android.airplay.AirPlayReceiver
import com.pocketdeck.android.airplay.AirPlayServerIdentity
import com.pocketdeck.android.airplay.HardwareVideoSurfaceDecoder
import com.pocketdeck.android.airplay.PcmAudioPlayer
import com.pocketdeck.android.protocol.AndroidLocalFileSystem
import com.pocketdeck.android.protocol.DeckLinkConnector
import com.pocketdeck.android.protocol.DeckLinkFraming
import com.pocketdeck.android.protocol.DeckLinkHostServer
import com.pocketdeck.android.protocol.DeckLinkSession
import com.pocketdeck.android.protocol.DirListing
import com.pocketdeck.android.protocol.FsRoot
import com.pocketdeck.android.protocol.PeerInfo
import com.pocketdeck.android.protocol.SessionCallbacks
import com.pocketdeck.android.protocol.TransferProgress
import com.pocketdeck.android.protocol.WebFilePortal
import com.pocketdeck.android.services.DiscoveryManager
import com.pocketdeck.android.services.RemoteControlService
import com.pocketdeck.android.services.ScreenShareService
import com.pocketdeck.android.services.formatPartnerId
import com.pocketdeck.android.services.localIpv4Addresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

data class ChatEntry(
    val id: String,
    val sender: String,
    val text: String,
    val timestampMs: Long,
    val isLocal: Boolean,
)

data class AppSettingsState(
    val deviceId: String = "100000000",
    val deviceName: String = "Android PocketDeck",
    val sessionPassword: String = "123456",
    val fixedPassword: String = "",
    val maxVideoDim: Int = 1280,
    val bitrateMbps: Int = 4,
    val fps: Int = 60,
    val shareInternalAudio: Boolean = true,
    val hostPort: Int = 7200,
    val lanIps: List<String> = emptyList(),
) {
    val formattedId: String get() = formatPartnerId(deviceId)
    val activePassword: String get() = fixedPassword.ifBlank { sessionPassword }
}

data class ActiveConnectionState(
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val isInbound: Boolean = false,
    val peerName: String = "",
    val peerId: String = "",
    val peerPlatform: String = "",
    val remoteIp: String = "",
    val remoteWidth: Int = 1080,
    val remoteHeight: Int = 2400,
)

data class AirPlayState(
    val receiverEnabled: Boolean = false,
    val rtspPort: Int = 0,
    val streamingClientName: String = "",
    val streamingModel: String = "",
    val videoWidth: Int = 1080,
    val videoHeight: Int = 1920,
    val audioMuted: Boolean = false,
)

data class WebPortalState(
    val running: Boolean = false,
    val url: String = "",
    val pin: String = "",
    val port: Int = 0,
    val sharedDirPath: String = "",
    val uploadedCount: Int = 0,
)

/**
 * Process-scoped engine coordinating DeckLink Host & Client sessions, Dual-Pane File Transfer,
 * Live Chat, Web QR Portal, LAN Discovery, and AirPlay Receiver across `MainActivity` & `ViewerActivity`.
 */
object PocketDeckEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    private var initialized = false

    private val _settings = MutableStateFlow(AppSettingsState())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    private val _connection = MutableStateFlow(ActiveConnectionState())
    val connection: StateFlow<ActiveConnectionState> = _connection.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = _transfers.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatEntry>>(emptyList())
    val chatMessages: StateFlow<List<ChatEntry>> = _chatMessages.asStateFlow()

    private val _unreadChat = MutableStateFlow(0)
    val unreadChat: StateFlow<Int> = _unreadChat.asStateFlow()

    private val _airPlay = MutableStateFlow(AirPlayState())
    val airPlay: StateFlow<AirPlayState> = _airPlay.asStateFlow()

    private val _webPortal = MutableStateFlow(WebPortalState())
    val webPortal: StateFlow<WebPortalState> = _webPortal.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    var discovery: DiscoveryManager? = null
        private set

    private var hostServer: DeckLinkHostServer? = null
    @Volatile
    var activeSession: DeckLinkSession? = null
        private set

    private var webFilePortal: WebFilePortal? = null
    private var airPlayReceiver: AirPlayReceiver? = null
    private lateinit var airPlayIdentity: AirPlayServerIdentity

    val remoteVideoDecoder = HardwareVideoSurfaceDecoder { w, h ->
        _connection.update { it.copy(remoteWidth = w, remoteHeight = h) }
    }
    val airPlayVideoDecoder = HardwareVideoSurfaceDecoder { w, h ->
        _airPlay.update { it.copy(videoWidth = w, videoHeight = h) }
    }
    val airPlayAudioPlayer = AirPlayAudioPlayer()
    val remotePcmPlayer = PcmAudioPlayer()

    val localFs = AndroidLocalFileSystem()

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) {
            refreshNetworkAddresses()
            return
        }
        initialized = true
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("pocketdeck_prefs", Context.MODE_PRIVATE)

        val rng = SecureRandom()
        val devId = prefs.getString("device_id", null) ?: run {
            val generated = (100_000_000 + rng.nextInt(900_000_000)).toString()
            prefs.edit().putString("device_id", generated).apply()
            generated
        }
        val defaultName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
        val devName = prefs.getString("device_name", defaultName) ?: defaultName
        val sessPass = "%06d".format(rng.nextInt(1_000_000))
        val fixedPass = prefs.getString("fixed_password", "") ?: ""
        val maxDim = prefs.getInt("max_dim", 1280)
        val bitrate = prefs.getInt("bitrate_mbps", 4)
        val fps = prefs.getInt("fps", 60)
        val shareAudio = prefs.getBoolean("share_audio", true)

        val (identity, seedHex) = AirPlayServerIdentity.fromHexOrGenerate(prefs.getString("airplay_seed", null))
        prefs.edit().putString("airplay_seed", seedHex).apply()
        airPlayIdentity = identity

        _settings.value = AppSettingsState(
            deviceId = devId,
            deviceName = devName,
            sessionPassword = sessPass,
            fixedPassword = fixedPass,
            maxVideoDim = maxDim,
            bitrateMbps = bitrate,
            fps = fps,
            shareInternalAudio = shareAudio,
            lanIps = localIpv4Addresses(),
        )

        startHostAndDiscovery()
    }

    fun defaultDownloadDir(): File {
        val pubDown = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val candidate = File(pubDown, "PocketDeck")
        if (runCatching { candidate.mkdirs(); candidate.canWrite() }.getOrDefault(false)) {
            return candidate
        }
        val extApp = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return (extApp ?: File(appContext.filesDir, "Downloads")).apply { mkdirs() }
    }

    fun refreshNetworkAddresses() {
        if (!initialized) return
        val ips = localIpv4Addresses()
        _settings.update { it.copy(lanIps = ips) }
        val portal = webFilePortal
        if (portal != null && portal.isRunning) {
            val ip = ips.firstOrNull() ?: "127.0.0.1"
            _webPortal.update {
                it.copy(url = "http://$ip:${portal.port}/?pin=${portal.pin}")
            }
        }
    }

    private fun startHostAndDiscovery() {
        val st = _settings.value
        val dm = appContext.resources.displayMetrics
        val srv = DeckLinkHostServer(
            deviceId = st.deviceId,
            deviceName = st.deviceName,
            passwordProvider = { _settings.value.activePassword },
            requestedPort = 7200,
            platform = "android",
            screenSizeProvider = { dm.widthPixels to dm.heightPixels },
            localFsProvider = { localFs },
            downloadDir = defaultDownloadDir(),
            sessionFactory = { peer -> makeCallbacks(peer, isInbound = true) },
            onSessionStarted = { sess ->
                activeSession = sess
                _connection.value = ActiveConnectionState(
                    connected = true,
                    connecting = false,
                    isInbound = true,
                    peerName = sess.peer.deviceName,
                    peerId = sess.peer.deviceId,
                    peerPlatform = sess.peer.platform,
                    remoteIp = sess.peer.remoteIp,
                    remoteWidth = sess.peer.screenWidth,
                    remoteHeight = sess.peer.screenHeight,
                )
                ScreenShareService.activeInstance?.requestKeyFrameAndResendConfig()
                emitToast("Đối tác đã kết nối: ${sess.peer.deviceName}")
            },
        )
        val actualPort = srv.start()
        hostServer = srv
        ScreenShareService.hostServerRef = srv
        _settings.update { it.copy(hostPort = actualPort, lanIps = localIpv4Addresses()) }

        val disc = DiscoveryManager(
            context = appContext,
            deviceId = st.deviceId,
            deviceName = st.deviceName,
            tcpPort = actualPort,
        )
        disc.start()
        discovery = disc
    }

    fun regenerateSessionPassword() {
        val next = "%06d".format(SecureRandom().nextInt(1_000_000))
        _settings.update { it.copy(sessionPassword = next) }
        emitToast("Đã tạo mật khẩu phiên mới: $next")
    }

    fun updateSettings(
        deviceName: String = _settings.value.deviceName,
        fixedPassword: String = _settings.value.fixedPassword,
        maxVideoDim: Int = _settings.value.maxVideoDim,
        bitrateMbps: Int = _settings.value.bitrateMbps,
        fps: Int = _settings.value.fps,
        shareInternalAudio: Boolean = _settings.value.shareInternalAudio,
    ) {
        val cleanName = deviceName.trim().ifEmpty { "Android PocketDeck" }
        val cleanFixed = fixedPassword.trim()
        appContext.getSharedPreferences("pocketdeck_prefs", Context.MODE_PRIVATE).edit()
            .putString("device_name", cleanName)
            .putString("fixed_password", cleanFixed)
            .putInt("max_dim", maxVideoDim)
            .putInt("bitrate_mbps", bitrateMbps)
            .putInt("fps", fps)
            .putBoolean("share_audio", shareInternalAudio)
            .apply()

        _settings.update {
            it.copy(
                deviceName = cleanName,
                fixedPassword = cleanFixed,
                maxVideoDim = maxVideoDim,
                bitrateMbps = bitrateMbps,
                fps = fps,
                shareInternalAudio = shareInternalAudio,
            )
        }
        hostServer?.deviceName = cleanName
        discovery?.deviceName = cleanName
        discovery?.refreshPeers()
        emitToast("Đã lưu cài đặt")
    }

    fun connectToPartner(target: String, password: String, onConnected: () -> Unit = {}) {
        val cleanTarget = target.trim()
        val cleanPass = password.trim()
        if (cleanTarget.isEmpty() || cleanPass.isEmpty()) {
            emitToast("Vui lòng nhập đủ ID/IP đối tác và Mật khẩu")
            return
        }
        _connection.update { it.copy(connecting = true) }

        scope.launch {
            try {
                disconnectActiveSession()
                val disc = discovery
                val (host, port) = disc?.resolvePartner(cleanTarget, defaultPort = 7200, timeoutMs = 2400L)
                    ?: (cleanTarget to 7200)
                val dummyPeer = PeerInfo(
                    deviceId = cleanTarget,
                    deviceName = cleanTarget,
                    platform = "windows",
                    remoteIp = host,
                )
                val sess = DeckLinkConnector.connect(
                    host = host,
                    port = port,
                    password = cleanPass,
                    clientId = _settings.value.deviceId,
                    clientName = _settings.value.deviceName,
                    platform = "android",
                    callbacks = makeCallbacks(dummyPeer, isInbound = false),
                    localFs = localFs,
                    downloadDir = defaultDownloadDir(),
                )
                activeSession = sess
                _connection.value = ActiveConnectionState(
                    connected = true,
                    connecting = false,
                    isInbound = false,
                    peerName = sess.peer.deviceName,
                    peerId = sess.peer.deviceId,
                    peerPlatform = sess.peer.platform,
                    remoteIp = sess.peer.remoteIp,
                    remoteWidth = sess.peer.screenWidth,
                    remoteHeight = sess.peer.screenHeight,
                )
                emitToast("Đã kết nối tới ${sess.peer.deviceName}")
                launch(Dispatchers.Main) { onConnected() }
            } catch (e: Exception) {
                _connection.value = ActiveConnectionState()
                emitToast("Lỗi kết nối: ${e.message ?: "Không phản hồi"}")
            }
        }
    }

    fun disconnectActiveSession() {
        val prev = activeSession
        activeSession = null
        prev?.close("user_disconnect")
        remotePcmPlayer.close()
        _connection.value = ActiveConnectionState()
    }

    private fun makeCallbacks(peerHint: PeerInfo, isInbound: Boolean): SessionCallbacks {
        return SessionCallbacks(
            onVideo = { v ->
                val isCfg = v.kind == DeckLinkFraming.VIDEO_KIND_CONFIG
                if (v.width > 0 && v.height > 0) {
                    _connection.update { it.copy(remoteWidth = v.width, remoteHeight = v.height) }
                }
                remoteVideoDecoder.submitAnnexB(v.data, isConfig = isCfg)
            },
            onAudio = { a ->
                if (a.codec == DeckLinkFraming.AUDIO_CODEC_PCM16) {
                    remotePcmPlayer.writePcm16Le(a.data)
                }
            },
            onControl = { ctrl ->
                if (isInbound) {
                    RemoteControlService.instance.value?.handleControlMessage(ctrl)
                }
            },
            onChat = { msg ->
                val sender = (msg["sender"] as? String) ?: peerHint.deviceName.ifBlank { "Đối tác" }
                val text = (msg["text"] as? String) ?: ""
                val id = (msg["id"] as? String) ?: System.nanoTime().toString()
                if (text.isNotBlank()) {
                    _chatMessages.update {
                        it + ChatEntry(id, sender, text, System.currentTimeMillis(), isLocal = false)
                    }
                    _unreadChat.update { it + 1 }
                }
            },
            onClipboard = { clipText ->
                if (clipText.isNotEmpty()) {
                    runCatching {
                        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("PocketDeck", clipText))
                    }
                }
            },
            onTransferProgress = { prog ->
                _transfers.update { current ->
                    val idx = current.indexOfFirst { it.xferId == prog.xferId }
                    if (idx >= 0) {
                        current.toMutableList().apply { set(idx, prog) }
                    } else {
                        listOf(prog) + current
                    }
                }
                if (prog.status == "completed") {
                    val verb = if (prog.direction == "send") "Đã gửi xong" else "Đã nhận file"
                    emitToast("$verb: ${prog.filename}")
                }
            },
            onDisconnected = {
                if (activeSession?.isClosed == true) {
                    activeSession = null
                    remotePcmPlayer.close()
                    _connection.value = ActiveConnectionState()
                    emitToast("Đã ngắt kết nối với đối tác")
                }
            },
        )
    }

    // ------------------------------------------------------------------ Chat & Remote Input
    fun clearUnreadChat() {
        _unreadChat.value = 0
    }

    fun sendChatMessage(text: String) {
        val clean = text.trim()
        val sess = activeSession
        if (clean.isEmpty() || sess == null || sess.isClosed) {
            emitToast("Chưa có phiên kết nối để gửi tin nhắn")
            return
        }
        val sender = _settings.value.deviceName
        scope.launch {
            runCatching {
                val msg = sess.sendChat(sender, clean)
                val id = (msg["id"] as? String) ?: System.nanoTime().toString()
                _chatMessages.update {
                    it + ChatEntry(id, sender, clean, System.currentTimeMillis(), isLocal = true)
                }
            }.onFailure {
                emitToast("Không gửi được tin nhắn: ${it.message}")
            }
        }
    }

    fun sendRemoteTouch(action: String, nx: Float, ny: Float) {
        val sess = activeSession ?: return
        if (sess.isClosed) return
        val st = _connection.value
        val sw = st.remoteWidth.coerceAtLeast(1)
        val sh = st.remoteHeight.coerceAtLeast(1)
        val px = (nx * sw).toInt().coerceIn(0, sw - 1)
        val py = (ny * sh).toInt().coerceIn(0, sh - 1)
        scope.launch {
            runCatching {
                sess.sendControl(
                    action,
                    mapOf(
                        "x" to px,
                        "y" to py,
                        "nx" to nx.toDouble(),
                        "ny" to ny.toDouble(),
                        "screen_w" to sw,
                        "screen_h" to sh,
                    ),
                )
            }
        }
    }

    fun sendRemoteNav(keyName: String) {
        val sess = activeSession ?: return
        if (sess.isClosed) return
        scope.launch {
            runCatching { sess.sendControl("key", mapOf("key" to keyName)) }
        }
    }

    fun sendRemoteText(text: String) {
        val sess = activeSession ?: return
        if (text.isEmpty() || sess.isClosed) return
        scope.launch {
            runCatching { sess.sendControl("text", mapOf("text" to text)) }
        }
    }

    // ------------------------------------------------------------------ File Transfer Helpers
    suspend fun listLocalRoots(): List<FsRoot> = localFs.listRoots()
    suspend fun listLocalDir(path: String): DirListing = localFs.listDir(path)

    suspend fun listRemoteRoots(): List<FsRoot> {
        val sess = activeSession ?: throw IllegalStateException("Chưa kết nối đối tác")
        return sess.files.remoteFs().listRoots()
    }

    suspend fun listRemoteDir(path: String): DirListing {
        val sess = activeSession ?: throw IllegalStateException("Chưa kết nối đối tác")
        return sess.files.remoteFs().listDir(path)
    }

    fun sendFilesToPartner(localPaths: List<String>, remoteDestDir: String, onDone: () -> Unit = {}) {
        val sess = activeSession
        if (sess == null || sess.isClosed) {
            emitToast("Chưa kết nối với thiết bị đối tác")
            return
        }
        val files = localPaths.map { File(it) }.filter { it.exists() }
        if (files.isEmpty()) return
        scope.launch {
            runCatching {
                sess.files.sendItems(files, remoteDestDir)
                launch(Dispatchers.Main) { onDone() }
            }.onFailure {
                emitToast("Lỗi gửi file: ${it.message}")
            }
        }
    }

    fun pullFilesFromPartner(remotePaths: List<String>, localDestDir: String) {
        val sess = activeSession
        if (sess == null || sess.isClosed) {
            emitToast("Chưa kết nối với thiết bị đối tác")
            return
        }
        if (remotePaths.isEmpty()) return
        val dst = if (localDestDir.isBlank()) defaultDownloadDir() else File(localDestDir)
        scope.launch {
            runCatching {
                sess.files.requestPull(remotePaths, dst)
                emitToast("Đang tải ${remotePaths.size} mục từ đối tác...")
            }.onFailure {
                emitToast("Lỗi tải file: ${it.message}")
            }
        }
    }

    fun pauseOrResumeTransfer(xferId: String, currentStatus: String) {
        val sess = activeSession ?: return
        if (currentStatus == "paused") {
            sess.files.resumeTransfer(xferId)
        } else if (currentStatus == "active") {
            sess.files.pauseTransfer(xferId)
        }
    }

    fun cancelTransfer(xferId: String) {
        val sess = activeSession ?: return
        scope.launch {
            runCatching { sess.files.cancelTransfer(xferId) }
        }
    }

    // ------------------------------------------------------------------ Web QR File Portal
    fun setWebPortalEnabled(enabled: Boolean) {
        if (!enabled) {
            webFilePortal?.stop()
            webFilePortal = null
            _webPortal.value = WebPortalState()
            emitToast("Đã tắt cổng truyền file Web QR")
            return
        }
        if (webFilePortal?.isRunning == true) return
        val dir = defaultDownloadDir()
        val portal = WebFilePortal(
            sharedDir = dir,
            requestedPort = 7280,
            onFileUploaded = { f ->
                _webPortal.update { it.copy(uploadedCount = it.uploadedCount + 1) }
                emitToast("Nhận file qua Web QR: ${f.name}")
            },
        )
        val p = portal.start()
        webFilePortal = portal
        val ip = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        _webPortal.value = WebPortalState(
            running = true,
            url = "http://$ip:$p/?pin=${portal.pin}",
            pin = portal.pin,
            port = p,
            sharedDirPath = dir.absolutePath,
        )
        emitToast("Đã bật Cổng Web QR trên cổng $p")
    }

    // ------------------------------------------------------------------ AirPlay Receiver
    fun setAirPlayEnabled(enabled: Boolean) {
        if (!enabled) {
            discovery?.stopAirPlayAdvertisement()
            airPlayReceiver?.stop()
            airPlayReceiver = null
            airPlayAudioPlayer.close()
            _airPlay.value = AirPlayState()
            emitToast("Đã tắt đầu nhận AirPlay")
            return
        }
        if (airPlayReceiver != null) return
        val st = _settings.value
        val macLike = st.deviceId.padEnd(12, '2').chunked(2).take(6).joinToString(":")
        val cfg = AirPlayConfig(
            receiverName = st.deviceName,
            deviceId = macLike,
            maxWidth = 1920,
            maxHeight = 1080,
            maxFps = st.fps,
        )
        val rx = AirPlayReceiver(
            identity = airPlayIdentity,
            config = cfg,
            events = object : AirPlayEvents {
                override fun onClientConnecting(clientName: String, model: String, osVersion: String, remoteIp: String) {
                    _airPlay.update { it.copy(streamingClientName = clientName, streamingModel = model) }
                    emitToast("Đang nhận phản chiếu AirPlay từ $clientName")
                }

                override fun onVideoFormat(width: Int, height: Int) {
                    _airPlay.update { it.copy(videoWidth = width, videoHeight = height) }
                }

                override fun onVideoAnnexB(annexB: ByteArray, isCodecConfig: Boolean) {
                    airPlayVideoDecoder.submitAnnexB(annexB, isConfig = isCodecConfig)
                }

                override fun onAudioPacket(codecType: Int, clearFrame: ByteArray) {
                    airPlayAudioPlayer.submitPacket(codecType, clearFrame)
                }

                override fun onAudioFlush() {
                    airPlayAudioPlayer.flush()
                }

                override fun onClientDisconnected() {
                    airPlayAudioPlayer.flush()
                    _airPlay.update { it.copy(streamingClientName = "", streamingModel = "") }
                    emitToast("Thiết bị AirPlay đã ngắt phản chiếu")
                }

                override fun onError(reason: String) {
                    emitToast("AirPlay: $reason")
                }
            },
        )
        val port = rx.start(0)
        airPlayReceiver = rx
        discovery?.startAirPlayAdvertisement(cfg, airPlayIdentity.publicKeyHex, port)
        _airPlay.value = AirPlayState(
            receiverEnabled = true,
            rtspPort = port,
        )
        emitToast("Đầu nhận AirPlay '${cfg.receiverName}' sẵn sàng (cổng $port)")
    }

    fun toggleAirPlayMute() {
        val next = !_airPlay.value.audioMuted
        airPlayAudioPlayer.muted = next
        _airPlay.update { it.copy(audioMuted = next) }
    }

    // ------------------------------------------------------------------ ScreenShare Service Helper
    fun startScreenShareService(resultCode: Int, resultData: Intent) {
        val st = _settings.value
        val svcIntent = Intent(appContext, ScreenShareService::class.java).apply {
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenShareService.EXTRA_MAX_DIM, st.maxVideoDim)
            putExtra(ScreenShareService.EXTRA_BITRATE_BPS, st.bitrateMbps * 1_000_000)
            putExtra(ScreenShareService.EXTRA_FPS, st.fps)
            putExtra(ScreenShareService.EXTRA_AUDIO, st.shareInternalAudio)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(svcIntent)
        } else {
            appContext.startService(svcIntent)
        }
        emitToast("Đã bắt đầu chia sẻ màn hình Android")
    }

    fun stopScreenShareService() {
        val stopIntent = Intent(appContext, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_STOP
        }
        appContext.startService(stopIntent)
        emitToast("Đã dừng chia sẻ màn hình")
    }

    fun emitToast(msg: String) {
        _toasts.tryEmit(msg)
    }
}
