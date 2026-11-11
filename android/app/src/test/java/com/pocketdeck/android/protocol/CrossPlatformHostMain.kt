package com.pocketdeck.android.protocol

import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Standalone JVM harness for live cross-platform socket interoperability testing
 * between PocketDeck for Windows (Python) and PocketDeck for Android (Kotlin).
 *
 * Usage:
 *   java -cp <unitTestClasspath> com.pocketdeck.android.protocol.CrossPlatformHostMainKt <storageDir> <deviceId> <password>
 */
fun main(args: Array<String>) {
    require(args.size >= 3) { "Expected <storageDir> <deviceId> <password>" }
    val storageRoot = File(args[0]).canonicalFile
    val deviceId = args[1]
    val password = args[2]

    val downloadDir = File(storageRoot, "Download").apply { mkdirs() }
    val dcimDir = File(storageRoot, "DCIM").apply { mkdirs() }

    val customFs = AndroidLocalFileSystem(
        customRoots = listOf(
            FsRoot("Bộ nhớ trong", storageRoot.absolutePath, "android"),
            FsRoot("Download", downloadDir.absolutePath, "folder"),
            FsRoot("DCIM", dcimDir.absolutePath, "folder"),
        ),
        fallbackRootDir = storageRoot,
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val gson = Gson()
    var activeSession: DeckLinkSession? = null

    val server = DeckLinkHostServer(
        deviceId = deviceId,
        deviceName = "Kotlin Android Bridge Host",
        passwordProvider = { password },
        bindHost = "127.0.0.1",
        requestedPort = 0,
        platform = "android",
        screenSizeProvider = { 1080 to 2400 },
        localFsProvider = { customFs },
        downloadDir = downloadDir,
        sessionFactory = {
            SessionCallbacks(
                onControl = { ctrl ->
                    File(storageRoot, "last_control.json").writeText(gson.toJson(ctrl), Charsets.UTF_8)
                },
                onChat = { chat ->
                    val txt = (chat["text"] as? String).orEmpty()
                    File(storageRoot, "last_chat.txt").writeText(txt, Charsets.UTF_8)
                    val sess = activeSession
                    if (sess != null) {
                        scope.launch {
                            runCatching {
                                sess.sendChat("Android Host", "Phản hồi từ Kotlin: $txt")
                            }
                        }
                    }
                },
                onClipboard = { clip ->
                    File(storageRoot, "last_clipboard.txt").writeText(clip, Charsets.UTF_8)
                },
            )
        },
        onSessionStarted = { sess ->
            activeSession = sess
        },
    )

    val boundPort = server.start()
    println("READY PORT=$boundPort")
    System.out.flush()

    val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
    while (true) {
        val line = reader.readLine() ?: break
        if (line.trim().equals("QUIT", ignoreCase = true)) {
            break
        }
    }

    server.stop()
    scope.cancel()
}
