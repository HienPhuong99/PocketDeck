package com.pocketdeck.android.airplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Byte-granular AES-128-CTR stream cipher that never buffers partial 16-byte blocks across
 * `update()` calls (unlike some JCE `AES/CTR/NoPadding` providers).
 */
class AesCtrStream(key16: ByteArray, iv16: ByteArray) {
    private val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"))
    }
    private val counter = iv16.copyOf(16)
    private var keystreamBlock = ByteArray(16)
    private var ksIndex = 16

    private fun incrementCounter() {
        for (i in 15 downTo 0) {
            val next = (counter[i].toInt() and 0xFF) + 1
            counter[i] = (next and 0xFF).toByte()
            if (next <= 0xFF) break
        }
    }

    fun update(input: ByteArray, offset: Int = 0, length: Int = input.size - offset): ByteArray {
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            if (ksIndex >= 16) {
                keystreamBlock = ecb.doFinal(counter)
                incrementCounter()
                ksIndex = 0
            }
            val take = minOf(16 - ksIndex, length - pos)
            for (k in 0 until take) {
                out[pos + k] = (input[offset + pos + k].toInt() xor keystreamBlock[ksIndex + k].toInt()).toByte()
            }
            ksIndex += take
            pos += take
        }
        return out
    }
}

object AirPlayConstants {
    const val MODEL = "AppleTV3,2"
    const val SRCVERS = "220.68"
    const val FEATURES: Long = 0x5A7FFEE6L
    const val FEATURES_STR = "0x5A7FFEE6,0x0"
    const val PI = "2e388006-13ba-4041-9a67-25dd4a43d536"
    const val SERVER_HEADER = "AirTunes/220.68"
    const val NTP_EPOCH = 2208988800L

    private val ANNEX_B_START = byteArrayOf(0, 0, 0, 1)

    fun airplayTxt(deviceId: String, pkHex: String): Map<String, String> = linkedMapOf(
        "deviceid" to deviceId,
        "features" to FEATURES_STR,
        "flags" to "0x4",
        "model" to MODEL,
        "pk" to pkHex,
        "pi" to PI,
        "srcvers" to SRCVERS,
        "vv" to "2",
        "pw" to "false",
    )

    fun raopTxt(pkHex: String): Map<String, String> = linkedMapOf(
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "vv" to "2",
        "ft" to FEATURES_STR,
        "am" to MODEL,
        "md" to "0,1,2",
        "rhd" to "5.6.0.0",
        "pw" to "false",
        "sf" to "0x4",
        "sr" to "44100",
        "ss" to "16",
        "sv" to "false",
        "tp" to "UDP",
        "txtvers" to "1",
        "vs" to SRCVERS,
        "vn" to "65537",
        "pk" to pkHex,
    )

    fun txtBytes(txt: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((k, v) in txt) {
            val item = "$k=$v".toByteArray(StandardCharsets.UTF_8)
            out.write(item.size and 0xFF)
            out.write(item)
        }
        return out.toByteArray()
    }

    /** Derives the AES-128-CTR decryptor for the AirPlay screen-mirroring TCP stream. */
    fun mirrorCipher(aesKey: ByteArray, streamConnectionId: Long): AesCtrStream {
        val sidUnsigned = java.lang.Long.toUnsignedString(streamConnectionId)
        val skey = "AirPlayStreamKey$sidUnsigned".toByteArray(StandardCharsets.UTF_8)
        val siv = "AirPlayStreamIV$sidUnsigned".toByteArray(StandardCharsets.UTF_8)
        val key = AirPlayPairingSession.sha512(skey + aesKey).copyOfRange(0, 16)
        val iv = AirPlayPairingSession.sha512(siv + aesKey).copyOfRange(0, 16)
        return AesCtrStream(key, iv)
    }

    /** Converts 4-byte big-endian length-prefixed AVCC NALs into Annex-B (`00 00 00 01`) NALs. */
    fun avccToAnnexB(data: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(data.size + 16)
        var pos = 0
        val n = data.size
        while (pos < n) {
            if (pos + 4 > n) return null
            val size = ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size <= 0 || pos + 4 + size > n || (data[pos + 4].toInt() and 0x80) != 0) {
                return null
            }
            out.write(ANNEX_B_START)
            out.write(data, pos + 4, size)
            pos += 4 + size
        }
        return out.toByteArray()
    }
}

data class AirPlayConfig(
    val receiverName: String = "PocketDeck Android",
    val deviceId: String = "AA:BB:CC:11:22:33",
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxFps: Int = 60,
) {
    val raopPrefix: String
        get() = deviceId.replace(":", "").uppercase()
}

interface AirPlayEvents {
    fun onClientConnecting(clientName: String, model: String, osVersion: String, remoteIp: String) {}
    fun onVideoFormat(width: Int, height: Int) {}
    fun onVideoAnnexB(annexB: ByteArray, isCodecConfig: Boolean) {}
    fun onAudioPacket(codecType: Int, clearFrame: ByteArray) {}
    fun onAudioFlush() {}
    fun onClientDisconnected() {}
    fun onError(reason: String) {}
}

/**
 * Full AirPlay Screen-Mirroring + RAOP RTSP Receiver on Android.
 * Accepts iPhone / iPad "Phản chiếu màn hình" connections over Wi-Fi LAN.
 */
class AirPlayReceiver(
    val identity: AirPlayServerIdentity,
    var config: AirPlayConfig,
    private val events: AirPlayEvents,
) {
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeConnection: RtspConnection? = null
    @Volatile
    var volumeDb: Double = 0.0
        private set

    val rtspPort: Int
        get() = serverSocket?.localPort ?: 0

    fun start(bindPort: Int = 0): Int {
        stop()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val srv = ServerSocket()
        srv.reuseAddress = true
        srv.bind(InetSocketAddress("0.0.0.0", bindPort))
        serverSocket = srv

        s.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                client.tcpNoDelay = true
                s.launch {
                    RtspConnection(this@AirPlayReceiver, client, s).serve()
                }
            }
        }
        return srv.localPort
    }

    fun stop() {
        activeConnection?.cleanup()
        activeConnection = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    internal fun claimMirror(conn: RtspConnection) {
        val prev = activeConnection
        if (prev != null && prev !== conn) {
            prev.stopMirrorAndAudio()
        }
        activeConnection = conn
    }

    internal fun onVolumeChanged(db: Double) {
        volumeDb = db
    }

    internal val eventsSink: AirPlayEvents get() = events
}

internal class RtspConnection(
    private val rx: AirPlayReceiver,
    private val socket: Socket,
    private val parentScope: CoroutineScope,
) {
    private val remoteIp: String = socket.inetAddress?.hostAddress ?: "0.0.0.0"
    private val pairing = AirPlayPairingSession(rx.identity)
    private val fairPlay = FairPlay()

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null
    private var timing: TimingSender? = null
    private var mirror: MirrorTcpServer? = null
    private var audio: AudioUdpReceiver? = null
    private var cleanedUp = false

    fun serve() {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 65536)
            val output = socket.getOutputStream()
            while (!socket.isClosed) {
                val req = readRtspRequest(input) ?: break
                val resp = dispatch(req)
                writeRtspResponse(output, req, resp)
                if (resp.closeConnection) break
            }
        } catch (_: EOFException) {
        } catch (_: Exception) {
        } finally {
            cleanup()
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun dispatch(req: RtspRequest): RtspResponse {
        val path = req.url.substringBefore('?')
        val resp = RtspResponse()
        try {
            when {
                req.method == "GET" && path == "/info" -> onInfo(req, resp)
                req.method == "POST" && path == "/pair-setup" ->
                    resp.setBody(pairing.pairSetup(req.body), "application/octet-stream")
                req.method == "POST" && path == "/pair-verify" ->
                    resp.setBody(pairing.pairVerify(req.body), "application/octet-stream")
                req.method == "POST" && path == "/fp-setup" -> onFpSetup(req, resp)
                req.method == "OPTIONS" ->
                    resp.headers.add("Public" to "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER")
                req.method == "SETUP" -> onSetup(req, resp)
                req.method == "GET_PARAMETER" -> {
                    val bodyStr = String(req.body, StandardCharsets.UTF_8)
                    if (bodyStr.startsWith("volume")) {
                        val line = String.format(java.util.Locale.US, "volume: %9.6f\r\n", rx.volumeDb)
                        resp.setBody(line.toByteArray(StandardCharsets.UTF_8), "text/parameters")
                    }
                }
                req.method == "SET_PARAMETER" -> {
                    val ct = req.header("content-type") ?: ""
                    val bodyStr = String(req.body, StandardCharsets.UTF_8)
                    if (ct == "text/parameters" && bodyStr.startsWith("volume:")) {
                        bodyStr.substringAfter(':').trim().toDoubleOrNull()?.let { rx.onVolumeChanged(it) }
                    }
                }
                req.method == "RECORD" -> {
                    resp.headers.add("Audio-Latency" to "11025")
                    resp.headers.add("Audio-Jack-Status" to "connected; type=analog")
                }
                req.method == "FLUSH" -> rx.eventsSink.onAudioFlush()
                req.method == "TEARDOWN" -> onTeardown(req, resp)
                req.method == "POST" && (path == "/feedback" || path == "/audioMode") -> Unit
            }
        } catch (e: IllegalArgumentException) {
            return RtspResponse(400, "Bad Request")
        } catch (e: Exception) {
            rx.eventsSink.onError(e.message ?: "AirPlay RTSP error")
            return RtspResponse(500, "Internal Server Error")
        }
        return resp
    }

    private fun onInfo(req: RtspRequest, resp: RtspResponse) {
        val ct = req.header("content-type") ?: ""
        val cfg = rx.config
        val res = LinkedHashMap<String, Any?>()

        if ("application/x-apple-binary-plist" in ct && req.body.isNotEmpty()) {
            val parsed = runCatching { BinaryPlist.parseDict(req.body) }.getOrDefault(emptyMap())
            val qualifiers = (parsed["qualifier"] as? List<*>)?.mapNotNull { it?.toString() }.orEmpty()
            if ("txtAirPlay" in qualifiers) {
                res["txtAirPlay"] = AirPlayConstants.txtBytes(
                    AirPlayConstants.airplayTxt(cfg.deviceId, rx.identity.publicKeyHex)
                )
            }
            if ("txtRAOP" in qualifiers) {
                res["txtRAOP"] = AirPlayConstants.txtBytes(
                    AirPlayConstants.raopTxt(rx.identity.publicKeyHex)
                )
            }
        }

        if (ct.isEmpty()) {
            res["deviceID"] = cfg.deviceId
            res["macAddress"] = cfg.deviceId
            res["pk"] = rx.identity.publicKeyBytes
            res["features"] = AirPlayConstants.FEATURES
            res["name"] = cfg.receiverName
            res["pi"] = AirPlayConstants.PI
            res["vv"] = 2L
            res["statusFlags"] = 68L
            res["keepAliveLowPower"] = true
            res["sourceVersion"] = AirPlayConstants.SRCVERS
            res["keepAliveSendStatsAsBody"] = true
            res["model"] = AirPlayConstants.MODEL

            if (req.header("cseq") != null) {
                res["initialVolume"] = 0.0
                res["audioLatencies"] = listOf(
                    linkedMapOf("type" to 100L, "inputLatencyMicros" to 0L, "audioType" to "default", "outputLatencyMicros" to false),
                    linkedMapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to false),
                )
                res["audioFormats"] = listOf(
                    linkedMapOf("audioOutputFormats" to 0x3FFFFFCL, "type" to 100L, "audioInputFormats" to 0x3FFFFFCL),
                    linkedMapOf("audioOutputFormats" to 0x3FFFFFCL, "type" to 101L, "audioInputFormats" to 0x3FFFFFCL),
                )
                res["displays"] = listOf(
                    linkedMapOf(
                        "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                        "widthPhysical" to 0L,
                        "heightPhysical" to 0L,
                        "width" to cfg.maxWidth.toLong(),
                        "height" to cfg.maxHeight.toLong(),
                        "widthPixels" to cfg.maxWidth.toLong(),
                        "heightPixels" to cfg.maxHeight.toLong(),
                        "rotation" to false,
                        "refreshRate" to (1.0 / cfg.maxFps.toDouble()),
                        "maxFPS" to cfg.maxFps.toLong(),
                        "overscanned" to false,
                        "features" to 14L,
                    )
                )
            }
        }
        resp.setBody(BinaryPlist.encode(res), "application/x-apple-binary-plist")
    }

    private fun onFpSetup(req: RtspRequest, resp: RtspResponse) {
        when (req.body.size) {
            16 -> {
                if ((req.body[4].toInt() and 0xFF) != 0x03) {
                    resp.status = 501
                    resp.reason = "Not Implemented"
                    return
                }
                resp.setBody(fairPlay.setup(req.body), "application/octet-stream")
            }
            164 -> resp.setBody(fairPlay.handshake(req.body), "application/octet-stream")
            else -> throw IllegalArgumentException("Invalid fp-setup length ${req.body.size}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onSetup(req: RtspRequest, resp: RtspResponse) {
        val plist = if (req.body.isNotEmpty()) BinaryPlist.parseDict(req.body) else emptyMap()
        val out = LinkedHashMap<String, Any?>()

        val ekey = plist["ekey"] as? ByteArray
        val eiv = plist["eiv"] as? ByteArray
        if (ekey != null && eiv != null) {
            val clientName = plist["name"]?.toString() ?: "iPhone"
            val model = plist["model"]?.toString() ?: ""
            val osVer = plist["osVersion"]?.toString() ?: ""
            rx.eventsSink.onClientConnecting(clientName, model, osVer, remoteIp)

            var rawAes = fairPlay.decrypt(ekey)
            val shared = pairing.ecdhSecret
            if (shared != null) {
                rawAes = AirPlayPairingSession.sha512(rawAes + shared).copyOfRange(0, 16)
            }
            aesKey = rawAes
            aesIv = eiv

            timing?.stop()
            val remoteTimingPort = (plist["timingPort"] as? Number)?.toInt() ?: 0
            val ts = TimingSender(parentScope)
            val localTimingPort = ts.start(remoteIp, remoteTimingPort)
            timing = ts
            out["timingPort"] = localTimingPort.toLong()
            out["eventPort"] = 0L
        }

        val streams = plist["streams"] as? List<Map<String, Any?>>
        if (!streams.isNullOrEmpty()) {
            val key = aesKey ?: throw IllegalArgumentException("Stream SETUP before FairPlay key exchange")
            val iv = aesIv ?: ByteArray(16)
            val resStreams = ArrayList<Map<String, Any?>>()
            for (s in streams) {
                val stype = (s["type"] as? Number)?.toInt() ?: -1
                when (stype) {
                    110 -> {
                        val sid = (s["streamConnectionID"] as? Number)?.toLong() ?: 0L
                        rx.claimMirror(this)
                        mirror?.stop()
                        val m = MirrorTcpServer(key, sid, parentScope, rx.eventsSink)
                        val port = m.start()
                        mirror = m
                        resStreams.add(linkedMapOf("dataPort" to port.toLong(), "type" to 110L))
                    }
                    96 -> {
                        audio?.stop()
                        val ct = (s["ct"] as? Number)?.toInt() ?: 8
                        val a = AudioUdpReceiver(key, iv, ct, parentScope, rx.eventsSink)
                        val (dport, cport) = a.start()
                        audio = a
                        resStreams.add(
                            linkedMapOf(
                                "dataPort" to dport.toLong(),
                                "controlPort" to cport.toLong(),
                                "type" to 96L,
                            )
                        )
                    }
                }
            }
            out["streams"] = resStreams
        }

        resp.setBody(BinaryPlist.encode(out), "application/x-apple-binary-plist")
    }

    @Suppress("UNCHECKED_CAST")
    private fun onTeardown(req: RtspRequest, resp: RtspResponse) {
        val types = HashSet<Int>()
        if (req.body.isNotEmpty()) {
            val parsed = runCatching { BinaryPlist.parseDict(req.body) }.getOrDefault(emptyMap())
            val streams = parsed["streams"] as? List<Map<String, Any?>>
            streams?.forEach { s ->
                (s["type"] as? Number)?.toInt()?.let { types.add(it) }
            }
        }
        resp.headers.add("Connection" to "close")
        if (96 in types) {
            audio?.stop()
            audio = null
        }
        if (110 in types) {
            mirror?.stop()
            mirror = null
        }
        if (types.isEmpty()) {
            resp.closeConnection = true
            cleanup()
        }
    }

    @Synchronized
    fun stopMirrorAndAudio() {
        mirror?.stop()
        mirror = null
        audio?.stop()
        audio = null
    }

    @Synchronized
    fun cleanup() {
        if (cleanedUp) return
        cleanedUp = true
        val hadMirror = mirror != null
        timing?.stop()
        timing = null
        stopMirrorAndAudio()
        if (hadMirror) {
            rx.eventsSink.onClientDisconnected()
        }
    }
}

private class TimingSender(private val scope: CoroutineScope) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null

    fun start(remoteIp: String, remotePort: Int): Int {
        val ds = DatagramSocket(0)
        socket = ds
        if (remotePort > 0) {
            val addr = InetAddress.getByName(remoteIp)
            job = scope.launch {
                while (isActive && !ds.isClosed) {
                    try {
                        val nowSecDouble = System.currentTimeMillis() / 1000.0 + AirPlayConstants.NTP_EPOCH
                        val sec = nowSecDouble.toLong()
                        val frac = ((nowSecDouble - sec) * 4294967296.0).toLong() and 0xFFFFFFFFL
                        val pkt = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
                        pkt.put(0x80.toByte()).put(0xD2.toByte()).put(0x00).put(0x07)
                        pkt.position(24)
                        pkt.putInt(sec.toInt())
                        pkt.putInt(frac.toInt())
                        val arr = pkt.array()
                        ds.send(DatagramPacket(arr, arr.size, addr, remotePort))
                    } catch (_: Exception) {
                    }
                    delay(3000)
                }
            }
        }
        return ds.localPort
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}

private class MirrorTcpServer(
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val scope: CoroutineScope,
    private val events: AirPlayEvents,
) {
    private val decryptor = AirPlayConstants.mirrorCipher(aesKey, streamConnectionId)
    private var server: ServerSocket? = null
    private var client: Socket? = null
    private var job: Job? = null
    private var pendingSpsPps: ByteArray? = null
    private var checkedFirstPacket = false

    fun start(): Int {
        val srv = ServerSocket(0)
        server = srv
        job = scope.launch {
            val sock = try {
                srv.accept()
            } catch (_: Exception) {
                return@launch
            }
            client = sock
            try {
                sock.receiveBufferSize = 4 * 1024 * 1024
            } catch (_: Exception) {
            }
            try {
                val dis = DataInputStream(BufferedInputStream(sock.getInputStream(), 256 * 1024))
                val hdr = ByteArray(128)
                while (isActive && !sock.isClosed) {
                    dis.readFully(hdr)
                    val size = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (size < 0 || size > 16 * 1024 * 1024) break
                    val payload = ByteArray(size)
                    if (size > 0) dis.readFully(payload)
                    handlePacket(hdr, payload)
                }
            } catch (_: EOFException) {
            } catch (_: Exception) {
            } finally {
                try {
                    sock.close()
                } catch (_: Exception) {
                }
                events.onClientDisconnected()
            }
        }
        return srv.localPort
    }

    private fun handlePacket(hdr: ByteArray, payload: ByteArray) {
        val ptype = hdr[4].toInt() and 0xFF
        when (ptype) {
            0 -> {
                val dec = decryptor.update(payload)
                var annexB = AirPlayConstants.avccToAnnexB(dec)
                if (annexB == null) {
                    if (!checkedFirstPacket) {
                        checkedFirstPacket = true
                        events.onError("Giải mã luồng video AirPlay thất bại")
                    }
                    return
                }
                checkedFirstPacket = true
                val cfg = pendingSpsPps
                if (cfg != null) {
                    annexB = cfg + annexB
                    pendingSpsPps = null
                }
                events.onVideoAnnexB(annexB, isCodecConfig = false)
            }
            1 -> {
                if (payload.size >= 8 && String(payload, 4, 4, StandardCharsets.US_ASCII) == "hvc1") {
                    events.onError("Thiết bị nguồn gửi H.265 chưa hỗ trợ")
                    return
                }
                if (payload.size < 12) return
                val spsSize = ByteBuffer.wrap(payload, 6, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                if (8 + spsSize + 3 > payload.size) return
                val sps = payload.copyOfRange(8, 8 + spsSize)
                val ppsSize = ByteBuffer.wrap(payload, 8 + spsSize + 1, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
                val ppsStart = 8 + spsSize + 3
                if (ppsStart + ppsSize > payload.size) return
                val pps = payload.copyOfRange(ppsStart, ppsStart + ppsSize)

                val startCode = byteArrayOf(0, 0, 0, 1)
                val configNal = startCode + sps + startCode + pps
                pendingSpsPps = configNal
                events.onVideoAnnexB(configNal, isCodecConfig = true)

                val bb = ByteBuffer.wrap(hdr, 56, 8).order(ByteOrder.LITTLE_ENDIAN)
                val w = bb.float.toInt()
                val h = bb.float.toInt()
                if (w > 0 && h > 0) {
                    events.onVideoFormat(w, h)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        try {
            client?.close()
        } catch (_: Exception) {
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
    }
}

private class AudioUdpReceiver(
    private val aesKey: ByteArray,
    private val aesIv: ByteArray,
    private val codecType: Int,
    private val scope: CoroutineScope,
    private val events: AirPlayEvents,
) {
    private var dataSock: DatagramSocket? = null
    private var ctrlSock: DatagramSocket? = null
    private var dataJob: Job? = null
    private var ctrlJob: Job? = null
    @Volatile
    private var lastSeq: Int = -1

    fun start(): Pair<Int, Int> {
        val ds = DatagramSocket(0)
        val cs = DatagramSocket(0)
        dataSock = ds
        ctrlSock = cs

        dataJob = scope.launch {
            val buf = ByteArray(4096)
            while (isActive && !ds.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    ds.receive(pkt)
                    handleRtp(pkt.data, pkt.offset, pkt.length)
                } catch (_: Exception) {
                    break
                }
            }
        }

        ctrlJob = scope.launch {
            val buf = ByteArray(4096)
            while (isActive && !cs.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    cs.receive(pkt)
                    if (pkt.length > 16 && (pkt.data[pkt.offset + 1].toInt() and 0x7F) == 0x56) {
                        handleRtp(pkt.data, pkt.offset + 4, pkt.length - 4)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
        return ds.localPort to cs.localPort
    }

    @Synchronized
    private fun handleRtp(data: ByteArray, offset: Int, length: Int) {
        if (length < 12) return
        val seq = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
        if (lastSeq >= 0) {
            val diff = (seq - lastSeq) and 0xFFFF
            if (diff == 0 || diff > 0x8000) return
        }
        lastSeq = seq

        val payloadLen = length - 12
        val payloadOff = offset + 12
        val blockBytes = (payloadLen / 16) * 16
        val clear = ByteArray(payloadLen)
        if (blockBytes > 0) {
            val cbc = Cipher.getInstance("AES/CBC/NoPadding")
            cbc.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val dec = cbc.doFinal(data, payloadOff, blockBytes)
            System.arraycopy(dec, 0, clear, 0, blockBytes)
        }
        if (payloadLen > blockBytes) {
            System.arraycopy(data, payloadOff + blockBytes, clear, blockBytes, payloadLen - blockBytes)
        }
        events.onAudioPacket(codecType, clear)
    }

    fun stop() {
        dataJob?.cancel()
        ctrlJob?.cancel()
        try {
            dataSock?.close()
        } catch (_: Exception) {
        }
        try {
            ctrlSock?.close()
        } catch (_: Exception) {
        }
    }
}

private class RtspRequest(
    val method: String,
    val url: String,
    val protocol: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

private class RtspResponse(
    var status: Int = 200,
    var reason: String = "OK",
) {
    val headers = ArrayList<Pair<String, String>>()
    var body: ByteArray = ByteArray(0)
    var closeConnection: Boolean = false

    fun setBody(bytes: ByteArray, contentType: String) {
        body = bytes
        headers.add("Content-Type" to contentType)
    }
}

private fun readRtspRequest(input: InputStream): RtspRequest? {
    val headBuf = ByteArrayOutputStream(512)
    var state = 0 // matching \r\n\r\n
    while (true) {
        val b = input.read()
        if (b < 0) {
            if (headBuf.size() == 0) return null
            throw EOFException("Incomplete RTSP header")
        }
        headBuf.write(b)
        state = when {
            state == 0 && b == '\r'.code -> 1
            state == 1 && b == '\n'.code -> 2
            state == 2 && b == '\r'.code -> 3
            state == 3 && b == '\n'.code -> 4
            b == '\r'.code -> 1
            else -> 0
        }
        if (state == 4) break
        if (headBuf.size() > 65536) throw IllegalArgumentException("RTSP header too large")
    }

    val rawHead = String(headBuf.toByteArray(), StandardCharsets.UTF_8)
    val lines = rawHead.split("\r\n")
    val firstParts = lines.firstOrNull()?.split(' ') ?: return null
    if (firstParts.size < 3) return null
    val method = firstParts[0]
    val url = firstParts[1]
    val protocol = firstParts[2]

    val headers = LinkedHashMap<String, String>()
    for (i in 1 until lines.size) {
        val line = lines[i]
        val colon = line.indexOf(':')
        if (colon > 0) {
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    val contentLen = headers["content-length"]?.toIntOrNull() ?: 0
    if (contentLen < 0 || contentLen > 4 * 1024 * 1024) {
        throw IllegalArgumentException("Invalid RTSP Content-Length $contentLen")
    }
    val body = ByteArray(contentLen)
    var readTotal = 0
    while (readTotal < contentLen) {
        val r = input.read(body, readTotal, contentLen - readTotal)
        if (r < 0) throw EOFException("Incomplete RTSP body")
        readTotal += r
    }
    return RtspRequest(method, url, protocol, headers, body)
}

private fun writeRtspResponse(output: OutputStream, req: RtspRequest, resp: RtspResponse) {
    val sb = StringBuilder(256)
    sb.append(req.protocol).append(' ').append(resp.status).append(' ').append(resp.reason).append("\r\n")
    req.header("cseq")?.let { sb.append("CSeq: ").append(it).append("\r\n") }
    sb.append("Server: ").append(AirPlayConstants.SERVER_HEADER).append("\r\n")
    for ((k, v) in resp.headers) {
        sb.append(k).append(": ").append(v).append("\r\n")
    }
    sb.append("Content-Length: ").append(resp.body.size).append("\r\n\r\n")
    output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))
    if (resp.body.isNotEmpty()) {
        output.write(resp.body)
    }
    output.flush()
}
