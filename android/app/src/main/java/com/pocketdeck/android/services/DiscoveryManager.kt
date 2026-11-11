package com.pocketdeck.android.services

import android.content.Context
import android.net.wifi.WifiManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pocketdeck.android.airplay.AirPlayConfig
import com.pocketdeck.android.airplay.AirPlayConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

data class DiscoveredPeer(
    val deviceId: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
    val lastSeenMs: Long = System.currentTimeMillis(),
) {
    val formattedId: String
        get() = formatPartnerId(deviceId)
}

fun normalizePartnerTarget(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val digitsOnly = trimmed.filter { it.isDigit() }
    val cleanSpaces = trimmed.replace(" ", "").replace("-", "")
    if (cleanSpaces.all { it.isDigit() } && digitsOnly.length == 9) {
        return digitsOnly
    }
    return trimmed
}

fun formatPartnerId(id: String): String {
    val d = id.filter { it.isDigit() }
    return if (d.length == 9) "${d.substring(0, 3)} ${d.substring(3, 6)} ${d.substring(6, 9)}" else id
}

fun parseHostPort(target: String, defaultPort: Int = 7200): Pair<String, Int>? {
    val norm = normalizePartnerTarget(target)
    if (norm.isEmpty()) return null
    if (norm.length == 9 && norm.all { it.isDigit() }) return null
    if (norm.startsWith("[") && "]" in norm) {
        val end = norm.indexOf(']')
        val host = norm.substring(1, end)
        val rest = norm.substring(end + 1)
        val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: defaultPort else defaultPort
        return host to port
    }
    val colon = norm.lastIndexOf(':')
    if (colon > 0) {
        val host = norm.substring(0, colon).trim()
        val p = norm.substring(colon + 1).toIntOrNull()
        if (p != null) return host to p
    }
    return norm to defaultPort
}

fun localIpv4Addresses(): List<String> {
    val out = LinkedHashSet<String>()
    try {
        val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces())
        for (nif in ifaces) {
            if (!nif.isUp || nif.isLoopback) continue
            for (addr in Collections.list(nif.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                    addr.hostAddress?.let { out.add(it) }
                }
            }
        }
    } catch (_: Exception) {
    }
    return out.sorted()
}

private fun broadcastTargets(): List<String> {
    val targets = LinkedHashSet<String>()
    targets.add("255.255.255.255")
    for (ip in localIpv4Addresses()) {
        val parts = ip.split('.')
        if (parts.size == 4) {
            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
        }
    }
    return targets.toList()
}

/**
 * Combined UDP Broadcast (port 7201) + JmDNS (`_pocketdeck._tcp.local.`, `_airplay._tcp.local.`,
 * `_raop._tcp.local.`) LAN discovery and Partner ID resolver.
 * Wire-compatible with Windows `pocketdeck/discovery.py`.
 */
class DiscoveryManager(
    private val context: Context?,
    val deviceId: String,
    @Volatile var deviceName: String,
    @Volatile var tcpPort: Int,
    private val udpPort: Int = 7201,
    private val enableMdns: Boolean = true,
) {
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, Any?>>() {}.type
    private val peersMap = ConcurrentHashMap<String, DiscoveredPeer>()
    private val _peersFlow = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peersFlow: StateFlow<List<DiscoveredPeer>> = _peersFlow.asStateFlow()

    private var scope: CoroutineScope? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var jmdns: JmDNS? = null
    private var pocketDeckService: ServiceInfo? = null
    private val airplayServices = ArrayList<ServiceInfo>()

    fun start() {
        stop()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        acquireMulticastLock()
        startUdp(s)
        if (enableMdns) {
            s.launch { startJmdns() }
        }
    }

    fun stop() {
        stopAirPlayAdvertisement()
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Exception) {
        }
        jmdns = null
        pocketDeckService = null
        try {
            udpSocket?.close()
        } catch (_: Exception) {
        }
        udpSocket = null
        releaseMulticastLock()
        scope?.cancel()
        scope = null
    }

    fun refreshPeers() {
        val pkt = gson.toJson(mapOf("op" to "discover", "from" to normalizePartnerTarget(deviceId)))
            .toByteArray(StandardCharsets.UTF_8)
        scope?.launch { sendUdpBroadcast(pkt) }
    }

    fun startAirPlayAdvertisement(config: AirPlayConfig, pkHex: String, rtspPort: Int) {
        if (!enableMdns || rtspPort <= 0) return
        scope?.launch {
            val mdns = ensureJmdns() ?: return@launch
            stopAirPlayAdvertisementInternal(mdns)
            try {
                val airplayInfo = ServiceInfo.create(
                    "_airplay._tcp.local.",
                    config.receiverName,
                    rtspPort,
                    0,
                    0,
                    AirPlayConstants.airplayTxt(config.deviceId, pkHex),
                )
                val raopInfo = ServiceInfo.create(
                    "_raop._tcp.local.",
                    "${config.raopPrefix}@${config.receiverName}",
                    rtspPort,
                    0,
                    0,
                    AirPlayConstants.raopTxt(pkHex),
                )
                mdns.registerService(airplayInfo)
                mdns.registerService(raopInfo)
                synchronized(airplayServices) {
                    airplayServices.add(airplayInfo)
                    airplayServices.add(raopInfo)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stopAirPlayAdvertisement() {
        val mdns = jmdns ?: return
        scope?.launch { stopAirPlayAdvertisementInternal(mdns) } ?: stopAirPlayAdvertisementInternal(mdns)
    }

    private fun stopAirPlayAdvertisementInternal(mdns: JmDNS) {
        val copy = synchronized(airplayServices) {
            val l = airplayServices.toList()
            airplayServices.clear()
            l
        }
        for (svc in copy) {
            runCatching { mdns.unregisterService(svc) }
        }
    }

    suspend fun resolvePartner(
        target: String,
        defaultPort: Int = 7200,
        timeoutMs: Long = 2200L,
    ): Pair<String, Int> = withContext(Dispatchers.IO) {
        parseHostPort(target, defaultPort)?.let { return@withContext it }

        val wantedId = normalizePartnerTarget(target)
        val now = System.currentTimeMillis()
        peersMap[wantedId]?.takeIf { now - it.lastSeenMs < 60_000L }?.let {
            return@withContext it.host to it.port
        }

        val query = gson.toJson(mapOf("op" to "resolve", "id" to wantedId)).toByteArray(StandardCharsets.UTF_8)
        val dstPort = if (udpPort > 0) udpPort else 7201
        val targets = broadcastTargets() + "127.0.0.1"

        DatagramSocket().use { qsock ->
            qsock.broadcast = true
            qsock.soTimeout = 250
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(300L)
            val rxBuf = ByteArray(2048)

            while (System.currentTimeMillis() < deadline) {
                for (dst in targets) {
                    runCatching {
                        val addr = InetAddress.getByName(dst)
                        qsock.send(DatagramPacket(query, query.size, addr, dstPort))
                    }
                }
                try {
                    val pkt = DatagramPacket(rxBuf, rxBuf.size)
                    qsock.receive(pkt)
                    val msg: Map<String, Any?> = gson.fromJson(
                        String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8),
                        mapType,
                    )
                    val op = msg["op"]?.toString()
                    val rId = normalizePartnerTarget(msg["id"]?.toString() ?: "")
                    if (op == "here" && rId == wantedId) {
                        val rPort = (msg["port"] as? Number)?.toInt() ?: defaultPort
                        val hostIp = pkt.address.hostAddress ?: "127.0.0.1"
                        recordPeer(
                            rId,
                            msg["name"]?.toString() ?: rId,
                            msg["platform"]?.toString() ?: "windows",
                            hostIp,
                            rPort,
                        )
                        return@withContext hostIp to rPort
                    }
                } catch (_: SocketTimeoutException) {
                } catch (_: Exception) {
                }

                peersMap[wantedId]?.let { return@withContext it.host to it.port }
            }
        }
        throw IllegalStateException("Không tìm thấy thiết bị có ID ${formatPartnerId(wantedId)} trong mạng Wi-Fi")
    }

    private fun startUdp(s: CoroutineScope) {
        if (udpPort <= 0) return
        val sock = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = 600
                bind(InetSocketAddress("0.0.0.0", udpPort))
            }
        } catch (_: Exception) {
            return
        }
        udpSocket = sock

        s.launch {
            sendUdpBroadcast(myPayload("here"))
            var lastAnnounce = System.currentTimeMillis()
            val buf = ByteArray(2048)

            while (isActive && !sock.isClosed) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    val now = System.currentTimeMillis()
                    if (now - lastAnnounce >= 12_000L) {
                        sendUdpBroadcast(myPayload("here"))
                        lastAnnounce = now
                    }
                    continue
                } catch (_: Exception) {
                    break
                }

                try {
                    val msg: Map<String, Any?> = gson.fromJson(
                        String(pkt.data, pkt.offset, pkt.length, StandardCharsets.UTF_8),
                        mapType,
                    )
                    val op = msg["op"]?.toString() ?: continue
                    val senderIp = pkt.address.hostAddress ?: continue
                    val myNorm = normalizePartnerTarget(deviceId)

                    when (op) {
                        "here" -> {
                            val id = msg["id"]?.toString() ?: ""
                            val name = msg["name"]?.toString() ?: ""
                            val plat = msg["platform"]?.toString() ?: ""
                            val p = (msg["port"] as? Number)?.toInt() ?: 0
                            recordPeer(id, name, plat, senderIp, p)
                        }
                        "discover" -> {
                            val from = normalizePartnerTarget(msg["from"]?.toString() ?: "")
                            if (from != myNorm && tcpPort > 0) {
                                val reply = myPayload("here")
                                runCatching { sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port)) }
                            }
                        }
                        "resolve" -> {
                            val wanted = normalizePartnerTarget(msg["id"]?.toString() ?: "")
                            if (wanted == myNorm && tcpPort > 0) {
                                val reply = myPayload("here")
                                runCatching { sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port)) }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun myPayload(op: String): ByteArray = gson.toJson(
        linkedMapOf(
            "op" to op,
            "id" to normalizePartnerTarget(deviceId),
            "name" to deviceName,
            "platform" to "android",
            "port" to tcpPort,
            "ver" to 1,
        )
    ).toByteArray(StandardCharsets.UTF_8)

    private fun sendUdpBroadcast(pkt: ByteArray) {
        val sock = udpSocket ?: return
        val dstPort = if (udpPort > 0) udpPort else 7201
        for (bcast in broadcastTargets()) {
            runCatching {
                sock.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(bcast), dstPort))
            }
        }
    }

    @Synchronized
    private fun ensureJmdns(): JmDNS? {
        jmdns?.let { return it }
        val ip = localIpv4Addresses().firstOrNull() ?: return null
        return try {
            JmDNS.create(InetAddress.getByName(ip), "PocketDeck-${normalizePartnerTarget(deviceId)}").also {
                jmdns = it
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun startJmdns() {
        val mdns = ensureJmdns() ?: return
        try {
            if (tcpPort > 0) {
                val props = mapOf(
                    "id" to normalizePartnerTarget(deviceId),
                    "name" to deviceName,
                    "platform" to "android",
                    "ver" to "1",
                )
                val svc = ServiceInfo.create(
                    "_pocketdeck._tcp.local.",
                    "PocketDeck-${normalizePartnerTarget(deviceId)}",
                    tcpPort,
                    0,
                    0,
                    props,
                )
                mdns.registerService(svc)
                pocketDeckService = svc
            }

            mdns.addServiceListener(
                "_pocketdeck._tcp.local.",
                object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        mdns.requestServiceInfo(event.type, event.name, 1500L)
                    }

                    override fun serviceRemoved(event: ServiceEvent) {}

                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info ?: return
                        val host = info.inet4Addresses?.firstOrNull()?.hostAddress ?: return
                        val id = info.getPropertyString("id") ?: return
                        val name = info.getPropertyString("name") ?: id
                        val plat = info.getPropertyString("platform") ?: "windows"
                        recordPeer(id, name, plat, host, info.port)
                    }
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun recordPeer(rawId: String, name: String, platform: String, host: String, port: Int) {
        val norm = normalizePartnerTarget(rawId)
        val myNorm = normalizePartnerTarget(deviceId)
        if (norm.isEmpty() || norm == myNorm || port <= 0) return
        peersMap[norm] = DiscoveredPeer(
            deviceId = norm,
            name = name.ifBlank { norm },
            platform = platform.ifBlank { "windows" },
            host = host,
            port = port,
            lastSeenMs = System.currentTimeMillis(),
        )
        val cutoff = System.currentTimeMillis() - 45_000L
        peersMap.entries.removeIf { it.value.lastSeenMs < cutoff }
        _peersFlow.value = peersMap.values.sortedBy { it.name.lowercase() }
    }

    private fun acquireMulticastLock() {
        val ctx = context?.applicationContext ?: return
        try {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("PocketDeckMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
        } catch (_: Exception) {
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
