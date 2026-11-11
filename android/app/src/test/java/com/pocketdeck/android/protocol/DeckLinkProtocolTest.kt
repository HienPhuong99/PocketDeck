package com.pocketdeck.android.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

class DeckLinkProtocolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun testFramingCipherAndPythonCrossPlatformVectors() {
        assertEquals("482 910 375", DeckLinkFraming.formatPartnerId("482910375"))
        assertEquals("482910375", DeckLinkFraming.normalizePartnerTarget("482 910 375"))
        assertEquals("192.168.1.25:7200", DeckLinkFraming.normalizePartnerTarget("192.168.1.25:7200"))

        val serverNonce = ByteArray(32) { 0x11 }
        val clientNonce = ByteArray(32) { 0x22 }

        // Exact byte-for-byte match with Python `pocketdeck.protocol.framing`
        val proof = DeckLinkFraming.computeAuthProof("123456", serverNonce, clientNonce, "client")
        assertEquals("ccbd9fc68029c6b6f2df2ea6aeb0abf7f2be91e351188e638541ec7b67ff89d4", proof)

        val key = DeckLinkFraming.deriveSessionKey("123456", serverNonce, clientNonce)
        assertEquals("97ad27b98b6d9c533c71020b042bd3a0f9659a7a393aed8d221c7c7f00c2f459", DeckLinkFraming.toHex(key))

        val c2s = SessionCipher(key, DeckLinkFraming.DIR_CLIENT_TO_SERVER, DeckLinkFraming.DIR_SERVER_TO_CLIENT)
        val s2c = SessionCipher(key, DeckLinkFraming.DIR_SERVER_TO_CLIENT, DeckLinkFraming.DIR_CLIENT_TO_SERVER)

        val msg = "Xin chào từ Android PocketDeck!".toByteArray(Charsets.UTF_8)
        val wire = DeckLinkFraming.packFrame(DeckLinkFraming.CH_CHAT, 7L, msg, cipher = c2s)
        val decoded = DeckLinkFraming.readFrame(DataInputStream(ByteArrayInputStream(wire)), expectedSeq = 7L, cipher = s2c)
        assertEquals(DeckLinkFraming.CH_CHAT, decoded.channel)
        assertArrayEquals(msg, decoded.payload)

        // Tampering channel byte in header must fail AES-GCM AAD verification
        val tampered = wire.copyOf()
        tampered[3] = DeckLinkFraming.CH_CONTROL.toByte()
        try {
            DeckLinkFraming.readFrame(DataInputStream(ByteArrayInputStream(tampered)), expectedSeq = 7L, cipher = s2c)
            fail("Expected ProtocolException on tampered AAD")
        } catch (_: ProtocolException) {
            // Expected
        }

        // Path traversal must be rejected
        val baseDir = tmp.newFolder("safe_dl")
        try {
            PathSafety.safeResolveUnder(baseDir, "../outside.txt")
            fail("Expected traversal rejection")
        } catch (_: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun testFullDeckLinkSessionFileTransferResumeAndChat() = runBlocking {
        val serverStorage = tmp.newFolder("android_host")
        val serverDl = File(serverStorage, "Download").apply { mkdirs() }
        val clientDl = tmp.newFolder("client_dl")

        val serverFs = AndroidLocalFileSystem(
            customRoots = listOf(FsRoot("Bộ nhớ trong", serverStorage.absolutePath, "android"))
        )
        val chatReceived = CompletableDeferred<String>()
        val pulledFileReady = CompletableDeferred<File>()

        val host = DeckLinkHostServer(
            deviceId = "482910375",
            deviceName = "Pixel 9 Android",
            passwordProvider = { "654321" },
            bindHost = "127.0.0.1",
            requestedPort = 0,
            localFsProvider = { serverFs },
            downloadDir = serverDl,
            sessionFactory = {
                SessionCallbacks(
                    onChat = { m -> chatReceived.complete((m["text"] as? String) ?: "") }
                )
            }
        )
        val port = host.start()

        try {
            // 1. Wrong password must fail
            try {
                DeckLinkConnector.connect(
                    host = "127.0.0.1",
                    port = port,
                    password = "000000",
                    clientId = "111222333",
                    clientName = "Peer",
                    downloadDir = clientDl
                )
                fail("Wrong password should throw AuthenticationException")
            } catch (_: AuthenticationException) {
                // Expected
            }

            // 2. Valid connection
            val client = DeckLinkConnector.connect(
                host = "127.0.0.1",
                port = port,
                password = "654321",
                clientId = "111222333",
                clientName = "Windows Client",
                callbacks = SessionCallbacks(
                    onFileSaved = { f -> if (!pulledFileReady.isCompleted) pulledFileReady.complete(f) }
                ),
                downloadDir = clientDl
            )

            // 3. Remote filesystem RPCs (UltraViewer dual-pane browse + mkdir + rename)
            val roots = client.files.remoteFs().listRoots()
            assertEquals(1, roots.size)
            val createdDir = client.files.remoteFs().mkdir(serverStorage.absolutePath, "Thư mục thử nghiệm")
            assertTrue(File(createdDir).isDirectory)

            // 4. Chunked file send WITH automatic .pocketdeck.part resume (100 KB pre-seeded out of 600 KB)
            val srcBytes = Random(42).nextBytes(600 * 1024)
            val srcFile = File(tmp.root, "du-lieu-lon.bin").apply { writeBytes(srcBytes) }
            val prePart = File(serverDl, "du-lieu-lon.bin$PART_SUFFIX")
            prePart.writeBytes(srcBytes.copyOfRange(0, 100 * 1024))

            val sendResults = client.files.sendItems(listOf(srcFile), serverDl.absolutePath)
            assertEquals(1, sendResults.size)
            val prog = sendResults.first()
            assertEquals("completed", prog.status)
            assertEquals(100 * 1024L, prog.resumedFrom)
            assertFalse(prePart.exists())
            assertArrayEquals(srcBytes, File(serverDl, "du-lieu-lon.bin").readBytes())

            // 5. UltraViewer Pull (`requestPull`) from Android host -> Client
            val hostSample = File(serverStorage, "anh-chup.jpg").apply {
                writeText("JPG_PAYLOAD_VIETNAMESE_12345", Charsets.UTF_8)
            }
            client.files.requestPull(listOf(hostSample.absolutePath), clientDl)
            val pulled = withTimeout(5_000L) { pulledFileReady.await() }
            assertEquals("JPG_PAYLOAD_VIETNAMESE_12345", pulled.readText(Charsets.UTF_8))

            // 6. Live Chat
            client.sendChat("Windows Client", "Đã nhận đủ file nhé!")
            assertEquals("Đã nhận đủ file nhé!", withTimeout(5_000L) { chatReceived.await() })

            client.close()
        } finally {
            host.stop()
        }
    }

    @Test
    fun testWebFilePortalPinUploadAndDownload() {
        val shared = tmp.newFolder("portal_shared")
        val portal = WebFilePortal(sharedDir = shared, bindHost = "127.0.0.1", requestedPort = 0, pinCode = "8426")
        val port = portal.start()
        try {
            // Wrong PIN -> 401
            val badConn = URL("http://127.0.0.1:$port/api/files?pin=0000").openConnection() as HttpURLConnection
            assertEquals(401, badConn.responseCode)

            // Upload Vietnamese filename via POST /api/upload
            val payload = "Tài liệu gửi từ Safari trên iPhone qua cổng Web QR!".toByteArray(Charsets.UTF_8)
            val encodedName = URLEncoder.encode("tai-lieu-iphone.txt", "UTF-8")
            val upConn = (URL("http://127.0.0.1:$port/api/upload?pin=8426&name=$encodedName").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
            }
            upConn.outputStream.use { it.write(payload) }
            assertEquals(200, upConn.responseCode)
            assertTrue(File(shared, "tai-lieu-iphone.txt").isFile)

            // Download back via GET /api/download
            val dlConn = URL("http://127.0.0.1:$port/api/download?pin=8426&name=$encodedName").openConnection() as HttpURLConnection
            assertEquals(200, dlConn.responseCode)
            val downloaded = dlConn.inputStream.use { it.readBytes() }
            assertArrayEquals(payload, downloaded)
        } finally {
            portal.stop()
        }
    }
}
