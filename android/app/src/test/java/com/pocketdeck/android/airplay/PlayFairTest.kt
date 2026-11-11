package com.pocketdeck.android.airplay

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.pocketdeck.android.services.DiscoveryManager
import com.pocketdeck.android.services.parseHostPort
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

class PlayFairTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `decrypts all 21 real iPhone FairPlay capture vectors`() {
        val stream = javaClass.classLoader!!.getResourceAsStream("fairplay_vectors.json")
        assertNotNull("fairplay_vectors.json resource missing", stream)
        val type = object : TypeToken<List<List<String>>>() {}.type
        val vectors: List<List<String>> = InputStreamReader(stream!!, StandardCharsets.UTF_8).use {
            Gson().fromJson(it, type)
        }
        assertEquals(21, vectors.size)

        for ((idx, vec) in vectors.withIndex()) {
            val msg3 = hexToBytes(vec[0])
            val ekey = hexToBytes(vec[1])
            val expectedKeyHex = vec[2]

            val fp = FairPlay()
            val setupReq = ByteArray(16).also {
                it[4] = 0x03
                it[14] = msg3[12]
            }
            val setupReply = fp.setup(setupReq)
            assertEquals(142, setupReply.size)

            val hsReply = fp.handshake(msg3)
            assertEquals(32, hsReply.size)

            val recovered = fp.decrypt(ekey)
            assertEquals("FairPlay vector #$idx mismatch", expectedKeyHex, bytesToHex(recovered))
        }
    }

    @Test
    fun `BinaryPlist encodes and parses nested AirPlay dictionaries, UTF-16, arrays and binary data`() {
        val original = linkedMapOf<String, Any?>(
            "name" to "Điện thoại Việt Nam",
            "model" to "AppleTV3,2",
            "features" to 0x5A7FFEE6L,
            "vv" to 2L,
            "keepAliveLowPower" to true,
            "overscanned" to false,
            "refreshRate" to (1.0 / 60.0),
            "pk" to ByteArray(32) { (it * 7).toByte() },
            "streams" to listOf(
                linkedMapOf("type" to 110L, "dataPort" to 51234L),
                linkedMapOf("type" to 96L, "dataPort" to 51235L, "controlPort" to 51236L),
            ),
        )

        val encoded = BinaryPlist.encode(original)
        assertEquals("bplist00", String(encoded, 0, 8, StandardCharsets.US_ASCII))

        val parsed = BinaryPlist.parseDict(encoded)
        assertEquals("Điện thoại Việt Nam", parsed["name"])
        assertEquals("AppleTV3,2", parsed["model"])
        assertEquals(0x5A7FFEE6L, parsed["features"])
        assertEquals(true, parsed["keepAliveLowPower"])
        assertEquals(false, parsed["overscanned"])
        assertArrayEquals(ByteArray(32) { (it * 7).toByte() }, parsed["pk"] as ByteArray)

        @Suppress("UNCHECKED_CAST")
        val streams = parsed["streams"] as List<Map<String, Any?>>
        assertEquals(2, streams.size)
        assertEquals(110L, streams[0]["type"])
        assertEquals(51234L, streams[0]["dataPort"])
    }

    @Test
    fun `AirPlayPairingSession completes full Ed25519 and X25519 pair-setup and pair-verify`() {
        val (serverId, _) = AirPlayServerIdentity.fromHexOrGenerate("11".repeat(32))
        val session = AirPlayPairingSession(serverId)

        // Step 0: pair-setup
        val clientEdPriv = Ed25519PrivateKeyParameters(SecureRandom())
        val clientEdPub = clientEdPriv.generatePublicKey().encoded
        val srvEdPub = session.pairSetup(clientEdPub)
        assertArrayEquals(serverId.publicKeyBytes, srvEdPub)

        // Step 1: client sends 0x01000000 + clientX25519Pub + clientEd25519Pub
        val clientXPriv = X25519PrivateKeyParameters(SecureRandom())
        val clientXPub = clientXPriv.generatePublicKey().encoded
        val step1Req = ByteArray(68)
        step1Req[0] = 1
        System.arraycopy(clientXPub, 0, step1Req, 4, 32)
        System.arraycopy(clientEdPub, 0, step1Req, 36, 32)

        val step1Resp = session.pairVerify(step1Req)
        assertEquals(96, step1Resp.size)
        val srvXPub = step1Resp.copyOfRange(0, 32)
        val encSrvSig = step1Resp.copyOfRange(32, 96)

        val shared = ByteArray(32)
        X25519Agreement().apply {
            init(clientXPriv)
            calculateAgreement(X25519PublicKeyParameters(srvXPub, 0), shared, 0)
        }
        val key = AirPlayPairingSession.sha512("Pair-Verify-AES-Key".encodeToByteArray() + shared).copyOfRange(0, 16)
        val iv = AirPlayPairingSession.sha512("Pair-Verify-AES-IV".encodeToByteArray() + shared).copyOfRange(0, 16)

        val ctr = AesCtrStream(key, iv)
        val srvSig = ctr.update(encSrvSig)
        val srvVerifier = Ed25519Signer().apply {
            init(false, Ed25519PublicKeyParameters(srvEdPub, 0))
            val msg = srvXPub + clientXPub
            update(msg, 0, msg.size)
        }
        assertTrue(srvVerifier.verifySignature(srvSig))

        // Step 2: client signs (clientXPub + srvXPub) encrypted with continuing CTR keystream
        val clientSigner = Ed25519Signer().apply {
            init(true, clientEdPriv)
            val msg = clientXPub + srvXPub
            update(msg, 0, msg.size)
        }
        val encClientSig = ctr.update(clientSigner.generateSignature())
        val step2Req = ByteArray(68)
        step2Req[0] = 0
        System.arraycopy(encClientSig, 0, step2Req, 4, 64)

        val step2Resp = session.pairVerify(step2Req)
        assertEquals(0, step2Resp.size)
        assertTrue(session.verified)
    }

    @Test
    fun `AesCtrStream and avccToAnnexB handle unaligned chunks and unsigned stream IDs`() {
        val aesKey = ByteArray(16) { (it + 3).toByte() }
        val highBitSid = -6148914691236517205L // top bit set (> Long.MAX_VALUE as uint64)
        val enc = AirPlayConstants.mirrorCipher(aesKey, highBitSid)
        val dec = AirPlayConstants.mirrorCipher(aesKey, highBitSid)

        // Create two AVCC NAL units (lengths 7 and 19 -> total 34 bytes, intentionally not multiple of 16)
        val nal1 = byteArrayOf(0x65, 1, 2, 3, 4, 5, 6)
        val nal2 = ByteArray(19) { if (it == 0) 0x41 else it.toByte() }
        val avcc = ByteBuffer.allocate(4 + nal1.size + 4 + nal2.size).order(ByteOrder.BIG_ENDIAN)
            .putInt(nal1.size).put(nal1)
            .putInt(nal2.size).put(nal2)
            .array()

        val c1 = enc.update(avcc, 0, 11)
        val c2 = enc.update(avcc, 11, avcc.size - 11)
        assertEquals(11, c1.size)
        assertEquals(avcc.size - 11, c2.size)

        val p1 = dec.update(c1)
        val p2 = dec.update(c2)
        val annexB = AirPlayConstants.avccToAnnexB(p1 + p2)
        assertNotNull(annexB)
        assertEquals(4 + nal1.size + 4 + nal2.size, annexB!!.size)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1), annexB.copyOfRange(0, 4))
    }

    @Test
    fun `DiscoveryManager resolves 9-digit Partner ID over UDP loopback`() = runBlocking {
        assertEquals("192.168.1.50" to 7200, parseHostPort("192.168.1.50"))
        assertEquals("192.168.1.50" to 7999, parseHostPort("192.168.1.50:7999"))
        assertEquals(null, parseHostPort("123 456 789"))

        val udpPort = 17421
        val hostDisc = DiscoveryManager(
            context = null,
            deviceId = "987654321",
            deviceName = "Pixel Test",
            tcpPort = 17200,
            udpPort = udpPort,
            enableMdns = false,
        )
        val clientDisc = DiscoveryManager(
            context = null,
            deviceId = "111222333",
            deviceName = "Client Test",
            tcpPort = 0,
            udpPort = 0,
            enableMdns = false,
        )
        // Use a dedicated resolver targeting hostDisc's UDP port
        val resolver = DiscoveryManager(
            context = null,
            deviceId = "111222333",
            deviceName = "Client Test",
            tcpPort = 0,
            udpPort = udpPort,
            enableMdns = false,
        )
        hostDisc.start()
        try {
            val (_, resolvedPort) = resolver.resolvePartner("987 654 321", timeoutMs = 1500L)
            assertEquals(17200, resolvedPort)
        } finally {
            clientDisc.stop()
            hostDisc.stop()
        }
    }
}
