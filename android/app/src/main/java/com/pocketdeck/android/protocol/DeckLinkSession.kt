package com.pocketdeck.android.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class PeerInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val remoteIp: String,
    val screenWidth: Int = 1080,
    val screenHeight: Int = 2400,
    val capabilities: List<String> = emptyList()
)

data class SessionCallbacks(
    val onVideo: ((DeckLinkFraming.VideoSubPayload) -> Unit)? = null,
    val onAudio: ((DeckLinkFraming.AudioSubPayload) -> Unit)? = null,
    val onControl: ((Map<String, Any?>) -> Unit)? = null,
    val onChat: ((Map<String, Any?>) -> Unit)? = null,
    val onClipboard: ((String) -> Unit)? = null,
    val onTransferProgress: ((TransferProgress) -> Unit)? = null,
    val onFileSaved: ((File) -> Unit)? = null,
    val onDisconnected: ((String) -> Unit)? = null
)

class DeckLinkSession(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: BufferedOutputStream,
    private val cipher: SessionCipher,
    sendSeqStart: Long,
    recvSeqStart: Long,
    val peer: PeerInfo,
    val callbacks: SessionCallbacks = SessionCallbacks(),
    localFs: FileSystemProvider = AndroidLocalFileSystem(),
    downloadDir: File
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private var sendSeq: Long = sendSeqStart and 0xFFFFFFFFL
    private var recvSeq: Long = recvSeqStart and 0xFFFFFFFFL
    private val closed = AtomicBoolean(false)
    private var readerJob: Job? = null

    val files = FileTransferEngine(
        scope = scope,
        sendCtrl = { obj -> sendJson(DeckLinkFraming.CH_FILE_CTRL, obj) },
        sendData = { raw -> sendRaw(DeckLinkFraming.CH_FILE_DATA, raw) },
        localFs = localFs,
        defaultDownloadDir = downloadDir,
        onProgress = callbacks.onTransferProgress,
        onFileSaved = callbacks.onFileSaved
    )

    val isClosed: Boolean
        get() = closed.get()

    fun startReader(): Job {
        readerJob?.let { return it }
        val job = scope.launch {
            var reason = "closed"
            try {
                while (isActive && !closed.get()) {
                    val frame = DeckLinkFraming.readFrame(input, expectedSeq = recvSeq, cipher = cipher)
                    recvSeq = (recvSeq + 1L) and 0xFFFFFFFFL
                    dispatch(frame.channel, frame.payload)
                }
            } catch (_: EOFException) {
                reason = "connection_closed"
            } catch (_: SocketException) {
                reason = "socket_closed"
            } catch (e: Exception) {
                reason = e.message ?: "session_error"
            } finally {
                close(reason)
            }
        }
        readerJob = job
        return job
    }

    suspend fun sendRaw(channel: Int, payload: ByteArray) {
        if (closed.get()) throw SocketException("DeckLink session is closed")
        writeMutex.withLock {
            val frame = DeckLinkFraming.packFrame(channel, sendSeq, payload, cipher = cipher)
            sendSeq = (sendSeq + 1L) and 0xFFFFFFFFL
            withContext(Dispatchers.IO) {
                output.write(frame)
                output.flush()
            }
        }
    }

    suspend fun sendJson(channel: Int, obj: Map<String, Any?>) {
        sendRaw(channel, DeckLinkFraming.packJson(obj))
    }

    suspend fun sendControl(action: String, params: Map<String, Any?> = emptyMap()) {
        val m = params.toMutableMap()
        m["action"] = action
        sendJson(DeckLinkFraming.CH_CONTROL, m)
    }

    suspend fun sendVideo(kind: Int, ptsUs: Long, width: Int, height: Int, h264AnnexB: ByteArray) {
        sendRaw(
            DeckLinkFraming.CH_VIDEO,
            DeckLinkFraming.packVideoPayload(kind, ptsUs, width, height, h264AnnexB)
        )
    }

    suspend fun sendAudio(codec: Int, sampleRate: Int, channels: Int, ptsUs: Long, audioBytes: ByteArray) {
        sendRaw(
            DeckLinkFraming.CH_AUDIO,
            DeckLinkFraming.packAudioPayload(codec, sampleRate, channels, ptsUs, audioBytes)
        )
    }

    suspend fun sendChat(sender: String, text: String): Map<String, Any?> {
        val msg = mapOf<String, Any?>(
            "id" to UUID.randomUUID().toString().replace("-", "").take(12),
            "sender" to sender,
            "text" to text,
            "ts" to (System.currentTimeMillis() / 1000.0)
        )
        sendJson(DeckLinkFraming.CH_CHAT, msg)
        return msg
    }

    suspend fun sendClipboard(text: String) {
        sendJson(DeckLinkFraming.CH_CLIPBOARD, mapOf("text" to text))
    }

    private suspend fun dispatch(channel: Int, body: ByteArray) {
        when (channel) {
            DeckLinkFraming.CH_VIDEO -> callbacks.onVideo?.invoke(DeckLinkFraming.unpackVideoPayload(body))
            DeckLinkFraming.CH_AUDIO -> callbacks.onAudio?.invoke(DeckLinkFraming.unpackAudioPayload(body))
            DeckLinkFraming.CH_CONTROL -> callbacks.onControl?.invoke(DeckLinkFraming.unpackJson(body))
            DeckLinkFraming.CH_FILE_CTRL -> files.handleControl(DeckLinkFraming.unpackJson(body))
            DeckLinkFraming.CH_FILE_DATA -> files.handleData(body)
            DeckLinkFraming.CH_CHAT -> callbacks.onChat?.invoke(DeckLinkFraming.unpackJson(body))
            DeckLinkFraming.CH_CLIPBOARD -> {
                val obj = DeckLinkFraming.unpackJson(body)
                callbacks.onClipboard?.invoke(obj.strVal("text"))
            }
        }
    }

    fun close(reason: String = "closed") {
        if (!closed.compareAndSet(false, true)) return
        files.close()
        runCatching { socket.close() }
        scope.cancel()
        runCatching { callbacks.onDisconnected?.invoke(reason) }
    }
}

object DeckLinkConnector {
    suspend fun connect(
        host: String,
        port: Int,
        password: String,
        clientId: String,
        clientName: String,
        platform: String = "android",
        callbacks: SessionCallbacks = SessionCallbacks(),
        localFs: FileSystemProvider = AndroidLocalFileSystem(),
        downloadDir: File,
        timeoutMs: Int = 10_000
    ): DeckLinkSession = withContext(Dispatchers.IO) {
        val sock = Socket()
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)

            // 1. Read server hello (seq 0)
            val helloFrame = DeckLinkFraming.readFrame(input, expectedSeq = 0L)
            if (helloFrame.channel != DeckLinkFraming.CH_AUTH) {
                throw ProtocolException("Expected CH_AUTH hello from server")
            }
            val hello = DeckLinkFraming.unpackJson(helloFrame.payload)
            if (hello.strVal("op") != "hello" || hello.longVal("version").toInt() != DeckLinkFraming.PROTOCOL_VERSION) {
                throw ProtocolException("Incompatible DeckLink server hello")
            }

            val serverNonce = DeckLinkFraming.fromHex(hello.strVal("server_nonce"))
            val clientNonce = DeckLinkFraming.randomNonce()
            val proof = DeckLinkFraming.computeAuthProof(password, serverNonce, clientNonce, "client")

            // 2. Send client auth (seq 0)
            val authReq = mapOf<String, Any?>(
                "op" to "auth",
                "client_id" to clientId,
                "client_name" to clientName,
                "platform" to platform,
                "client_nonce" to DeckLinkFraming.toHex(clientNonce),
                "proof" to proof,
                "requested" to listOf("video", "audio", "control", "file_transfer", "chat", "clipboard")
            )
            output.write(DeckLinkFraming.packFrame(DeckLinkFraming.CH_AUTH, 0L, DeckLinkFraming.packJson(authReq)))
            output.flush()

            // 3. Read server auth_ok / auth_fail (seq 1)
            val resFrame = DeckLinkFraming.readFrame(input, expectedSeq = 1L)
            if (resFrame.channel != DeckLinkFraming.CH_AUTH) {
                throw ProtocolException("Expected CH_AUTH response")
            }
            val res = DeckLinkFraming.unpackJson(resFrame.payload)
            if (res.strVal("op") != "auth_ok") {
                throw AuthenticationException(res.strVal("error").ifEmpty { "Sai mật khẩu kết nối" })
            }

            val expectedSrv = DeckLinkFraming.computeAuthProof(password, serverNonce, clientNonce, "server")
            if (!DeckLinkFraming.verifyAuthProof(expectedSrv, res.strVal("server_proof"))) {
                throw AuthenticationException("Xác thực hai chiều với thiết bị đối tác thất bại")
            }

            // Clear read timeout once authenticated so idle sessions stay open
            sock.soTimeout = 0
            val sessionKey = DeckLinkFraming.deriveSessionKey(password, serverNonce, clientNonce)
            val cipher = SessionCipher(
                sessionKey,
                sendDir = DeckLinkFraming.DIR_CLIENT_TO_SERVER,
                recvDir = DeckLinkFraming.DIR_SERVER_TO_CLIENT
            )
            val caps = (hello["capabilities"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val peer = PeerInfo(
                deviceId = hello.strVal("device_id"),
                deviceName = hello.strVal("device_name").ifEmpty { host },
                platform = hello.strVal("platform").ifEmpty { "windows" },
                remoteIp = host,
                screenWidth = res.longVal("screen_width").toInt().takeIf { it > 0 } ?: 1080,
                screenHeight = res.longVal("screen_height").toInt().takeIf { it > 0 } ?: 2400,
                capabilities = caps
            )
            val session = DeckLinkSession(
                socket = sock,
                input = input,
                output = output,
                cipher = cipher,
                sendSeqStart = 1L,
                recvSeqStart = 2L,
                peer = peer,
                callbacks = callbacks,
                localFs = localFs,
                downloadDir = downloadDir
            )
            session.startReader()
            session
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }
}

class DeckLinkHostServer(
    val deviceId: String,
    var deviceName: String,
    private val passwordProvider: () -> String,
    private val bindHost: String = "0.0.0.0",
    private val requestedPort: Int = 7200,
    private val platform: String = "android",
    private val screenSizeProvider: () -> Pair<Int, Int> = { 1080 to 2400 },
    private val localFsProvider: () -> FileSystemProvider = { AndroidLocalFileSystem() },
    private val downloadDir: File,
    private val sessionFactory: ((PeerInfo) -> SessionCallbacks)? = null,
    private val onSessionStarted: ((DeckLinkSession) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateLimiter = AuthRateLimiter()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    val sessions: MutableSet<DeckLinkSession> = Collections.synchronizedSet(mutableSetOf())

    var port: Int = 0
        private set

    fun start(): Int {
        val srv = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost, requestedPort))
            }
        } catch (_: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindHost, 0))
            }
        }
        serverSocket = srv
        port = srv.localPort

        acceptJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    continue
                }
                launch { handleClient(client) }
            }
        }
        return port
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { serverSocket?.close() }
        serverSocket = null
        val snapshot = synchronized(sessions) { sessions.toList() }
        sessions.clear()
        snapshot.forEach { it.close("server_stopped") }
        scope.cancel()
    }

    suspend fun broadcastVideo(kind: Int, ptsUs: Long, width: Int, height: Int, h264: ByteArray) {
        val snapshot = synchronized(sessions) { sessions.toList() }
        for (sess in snapshot) {
            runCatching { sess.sendVideo(kind, ptsUs, width, height, h264) }
        }
    }

    suspend fun broadcastAudio(codec: Int, sampleRate: Int, channels: Int, ptsUs: Long, pcmOrCoded: ByteArray) {
        val snapshot = synchronized(sessions) { sessions.toList() }
        for (sess in snapshot) {
            runCatching { sess.sendAudio(codec, sampleRate, channels, ptsUs, pcmOrCoded) }
        }
    }

    private fun handleClient(sock: Socket) {
        val remoteIp = sock.inetAddress?.hostAddress ?: "0.0.0.0"
        try {
            sock.tcpNoDelay = true
            sock.soTimeout = 15_000
            val input = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
            val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)

            if (rateLimiter.isLocked(remoteIp)) {
                val err = mapOf("op" to "auth_fail", "error" to "Quá nhiều lần nhập sai mật khẩu. Vui lòng thử lại sau 60 giây.")
                output.write(DeckLinkFraming.packFrame(DeckLinkFraming.CH_AUTH, 0L, DeckLinkFraming.packJson(err)))
                output.flush()
                sock.close()
                return
            }

            val serverNonce = DeckLinkFraming.randomNonce()
            val hello = mapOf<String, Any?>(
                "op" to "hello",
                "version" to DeckLinkFraming.PROTOCOL_VERSION,
                "device_id" to deviceId,
                "device_name" to deviceName,
                "platform" to platform,
                "server_nonce" to DeckLinkFraming.toHex(serverNonce),
                "capabilities" to listOf("video", "audio", "control", "file_transfer", "chat", "clipboard")
            )
            output.write(DeckLinkFraming.packFrame(DeckLinkFraming.CH_AUTH, 0L, DeckLinkFraming.packJson(hello)))
            output.flush()

            val authFrame = DeckLinkFraming.readFrame(input, expectedSeq = 0L)
            if (authFrame.channel != DeckLinkFraming.CH_AUTH) throw ProtocolException("Expected CH_AUTH")
            val auth = DeckLinkFraming.unpackJson(authFrame.payload)
            if (auth.strVal("op") != "auth") throw ProtocolException("Invalid auth op")

            val clientNonce = DeckLinkFraming.fromHex(auth.strVal("client_nonce"))
            val password = passwordProvider()
            val expectedClientProof = DeckLinkFraming.computeAuthProof(password, serverNonce, clientNonce, "client")

            if (!DeckLinkFraming.verifyAuthProof(expectedClientProof, auth.strVal("proof"))) {
                val locked = rateLimiter.recordFailure(remoteIp)
                val msg = if (locked) "Quá nhiều lần thử sai. Tạm khoá 60 giây." else "Sai mật khẩu phiên (6 chữ số)."
                output.write(DeckLinkFraming.packFrame(DeckLinkFraming.CH_AUTH, 1L, DeckLinkFraming.packJson(mapOf("op" to "auth_fail", "error" to msg))))
                output.flush()
                sock.close()
                return
            }

            rateLimiter.recordSuccess(remoteIp)
            val (sw, sh) = screenSizeProvider()
            val srvProof = DeckLinkFraming.computeAuthProof(password, serverNonce, clientNonce, "server")
            val okResp = mapOf<String, Any?>(
                "op" to "auth_ok",
                "server_proof" to srvProof,
                "screen_width" to sw,
                "screen_height" to sh
            )
            output.write(DeckLinkFraming.packFrame(DeckLinkFraming.CH_AUTH, 1L, DeckLinkFraming.packJson(okResp)))
            output.flush()

            sock.soTimeout = 0
            val sessionKey = DeckLinkFraming.deriveSessionKey(password, serverNonce, clientNonce)
            val cipher = SessionCipher(
                sessionKey,
                sendDir = DeckLinkFraming.DIR_SERVER_TO_CLIENT,
                recvDir = DeckLinkFraming.DIR_CLIENT_TO_SERVER
            )
            val reqCaps = (auth["requested"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
            val peer = PeerInfo(
                deviceId = auth.strVal("client_id"),
                deviceName = auth.strVal("client_name").ifEmpty { remoteIp },
                platform = auth.strVal("platform").ifEmpty { "unknown" },
                remoteIp = remoteIp,
                capabilities = reqCaps
            )

            val baseCbs = sessionFactory?.invoke(peer) ?: SessionCallbacks()
            lateinit var sessionRef: DeckLinkSession
            val wrappedCbs = baseCbs.copy(
                onDisconnected = { reason ->
                    sessions.remove(sessionRef)
                    baseCbs.onDisconnected?.invoke(reason)
                }
            )
            sessionRef = DeckLinkSession(
                socket = sock,
                input = input,
                output = output,
                cipher = cipher,
                sendSeqStart = 2L,
                recvSeqStart = 1L,
                peer = peer,
                callbacks = wrappedCbs,
                localFs = localFsProvider(),
                downloadDir = downloadDir
            )
            sessions.add(sessionRef)
            onSessionStarted?.invoke(sessionRef)
            sessionRef.startReader()
        } catch (_: Exception) {
            runCatching { sock.close() }
        }
    }
}
