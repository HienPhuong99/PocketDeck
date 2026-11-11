package com.pocketdeck.android.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val CHUNK_SIZE: Int = 256 * 1024 // 256 KiB matching Windows Python engine
const val PART_SUFFIX: String = ".pocketdeck.part"

data class FsRoot(
    val name: String,
    val path: String,
    val kind: String = "folder"
)

data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Double
)

data class DirListing(
    val path: String,
    val parent: String?,
    val entries: List<FsEntry>,
    val freeBytes: Long = 0L
)

data class TransferProgress(
    val xferId: String,
    val direction: String, // "send" or "receive"
    val filename: String,
    val relPath: String,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val resumedFrom: Long = 0L,
    val speedBps: Double = 0.0,
    val status: String = "queued", // queued, active, paused, completed, cancelled, error
    val error: String = "",
    val savedPath: String = ""
) {
    val percent: Double
        get() = if (totalBytes <= 0L) {
            if (status == "completed") 100.0 else 0.0
        } else {
            ((transferredBytes.toDouble() / totalBytes.toDouble()) * 100.0).coerceAtMost(100.0)
        }

    val etaSeconds: Double?
        get() {
            val rem = (totalBytes - transferredBytes).coerceAtLeast(0L)
            if (rem == 0L) return 0.0
            if (speedBps <= 1.0) return null
            return rem.toDouble() / speedBps
        }
}

interface FileSystemProvider {
    suspend fun listRoots(): List<FsRoot>
    suspend fun listDir(path: String): DirListing
    suspend fun mkdir(parentDir: String, name: String): String
    suspend fun delete(path: String)
    suspend fun rename(path: String, newName: String): String
}

internal fun Map<String, Any?>.strVal(key: String): String = (this[key] as? String) ?: ""
internal fun Map<String, Any?>.longVal(key: String): Long = (this[key] as? Number)?.toLong() ?: 0L
internal fun Map<String, Any?>.doubleVal(key: String): Double = (this[key] as? Number)?.toDouble() ?: 0.0
internal fun Map<String, Any?>.boolVal(key: String): Boolean = (this[key] as? Boolean) ?: false

object PathSafety {
    fun sanitizeRelativePath(relPath: String): String {
        val norm = relPath.replace('\\', '/').trim('/')
        require(norm.isNotEmpty()) { "Empty relative file path" }
        val parts = norm.split('/')
        for (p in parts) {
            require(p.isNotEmpty() && p != "." && p != ".." && !p.contains(':')) {
                "Unsafe relative path segment: $p"
            }
        }
        return parts.joinToString(File.separator)
    }

    fun safeResolveUnder(destDir: File, relPath: String): File {
        val base = destDir.canonicalFile
        val cleanRel = sanitizeRelativePath(relPath)
        val candidate = File(base, cleanRel).canonicalFile
        val basePrefix = if (base.path.endsWith(File.separator)) base.path else base.path + File.separator
        require(candidate.path == base.path || candidate.path.startsWith(basePrefix)) {
            "Path escapes destination directory: $relPath"
        }
        return candidate
    }
}

class AndroidLocalFileSystem(
    private val customRoots: List<FsRoot>? = null,
    private val fallbackRootDir: File = File(System.getProperty("user.home") ?: ".")
) : FileSystemProvider {

    private fun computeDefaultRoots(): List<FsRoot> {
        if (customRoots != null) return customRoots
        val out = mutableListOf<FsRoot>()
        val sdcard = File("/storage/emulated/0")
        if (sdcard.exists() && sdcard.isDirectory) {
            out.add(FsRoot("Bộ nhớ trong", sdcard.absolutePath, "android"))
            for ((label, sub) in listOf(
                "Download" to "Download",
                "Ảnh & Video (DCIM)" to "DCIM",
                "Pictures" to "Pictures",
                "Documents" to "Documents",
                "Movies" to "Movies"
            )) {
                val f = File(sdcard, sub)
                if (f.exists()) out.add(FsRoot(label, f.absolutePath, "folder"))
            }
        }
        if (out.isEmpty()) {
            out.add(FsRoot("Thư mục PocketDeck", fallbackRootDir.absolutePath, "folder"))
        }
        return out
    }

    override suspend fun listRoots(): List<FsRoot> = withContext(Dispatchers.IO) {
        computeDefaultRoots()
    }

    override suspend fun listDir(path: String): DirListing = withContext(Dispatchers.IO) {
        val target = if (path.isBlank()) {
            val roots = computeDefaultRoots()
            File(roots.first().path)
        } else {
            File(path)
        }.canonicalFile

        if (!target.exists() || !target.isDirectory) {
            throw IllegalArgumentException("Thư mục không tồn tại: ${target.path}")
        }

        val children = target.listFiles() ?: emptyArray()
        val entries = children.mapNotNull { item ->
            try {
                val isD = item.isDirectory
                FsEntry(
                    name = item.name,
                    path = item.absolutePath,
                    isDir = isD,
                    size = if (isD) 0L else item.length(),
                    mtime = item.lastModified() / 1000.0
                )
            } catch (_: SecurityException) {
                null
            }
        }.sortedWith(compareBy<FsEntry> { !it.isDir }.thenBy { it.name.lowercase() })

        val parentPath = target.parentFile?.takeIf { it.canRead() }?.absolutePath
        DirListing(
            path = target.absolutePath,
            parent = parentPath,
            entries = entries,
            freeBytes = target.usableSpace
        )
    }

    override suspend fun mkdir(parentDir: String, name: String): String = withContext(Dispatchers.IO) {
        val clean = name.trim().replace('/', '_').replace('\\', '_')
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Tên thư mục không hợp lệ" }
        val dir = File(File(parentDir).canonicalFile, clean)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException("Không thể tạo thư mục: ${dir.path}")
        }
        dir.absolutePath
    }

    override suspend fun delete(path: String): Unit = withContext(Dispatchers.IO) {
        val target = File(path).canonicalFile
        require(target.parentFile != null && target.path != "/storage/emulated/0" && target.path != "/sdcard") {
            "Không được xoá thư mục gốc"
        }
        if (target.isDirectory && !Files.isSymbolicLink(target.toPath())) {
            target.deleteRecursively()
        } else if (target.exists()) {
            target.delete()
        }
        Unit
    }

    override suspend fun rename(path: String, newName: String): String = withContext(Dispatchers.IO) {
        val clean = newName.trim().replace('/', '_').replace('\\', '_')
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Tên mới không hợp lệ" }
        val src = File(path).canonicalFile
        val dst = File(src.parentFile, clean)
        if (!src.renameTo(dst)) {
            throw IllegalStateException("Không thể đổi tên sang $clean")
        }
        dst.absolutePath
    }
}

private data class LocalItemSpec(
    val file: File,
    val relPosix: String,
    val size: Long,
    val mtimeSec: Double
)

private fun collectLocalFiles(paths: List<File>): List<LocalItemSpec> {
    val out = mutableListOf<LocalItemSpec>()
    for (raw in paths) {
        val f = raw.canonicalFile
        if (!f.exists()) continue
        if (f.isFile) {
            out.add(LocalItemSpec(f, f.name, f.length(), f.lastModified() / 1000.0))
        } else if (f.isDirectory) {
            val baseParent = f.parentFile ?: f
            f.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { child ->
                val rel = child.relativeTo(baseParent).invariantSeparatorsPath
                out.add(LocalItemSpec(child, rel, child.length(), child.lastModified() / 1000.0))
            }
        }
    }
    return out
}

class FileTransferEngine(
    private val scope: CoroutineScope,
    private val sendCtrl: suspend (Map<String, Any?>) -> Unit,
    private val sendData: suspend (ByteArray) -> Unit,
    val localFs: FileSystemProvider = AndroidLocalFileSystem(),
    val defaultDownloadDir: File,
    private val onProgress: ((TransferProgress) -> Unit)? = null,
    private val onFileSaved: ((File) -> Unit)? = null
) {
    private class IncomingState(
        var progress: TransferProgress,
        val finalFile: File,
        val partFile: File,
        val raf: RandomAccessFile,
        val digest: MessageDigest,
        var expectedOffset: Long,
        val mtimeSec: Double,
        var lastTickNs: Long = System.nanoTime(),
        var lastTickBytes: Long = expectedOffset
    )

    private class OutgoingControl(var progress: TransferProgress) {
        val accepted = CompletableDeferred<Long>()
        val done = CompletableDeferred<String>()
        @Volatile var paused: Boolean = false
        @Volatile var cancelled: Boolean = false
        @Volatile var error: String = ""
        val pauseSignal = Object()
    }

    private val rpcWaiters = ConcurrentHashMap<String, CompletableDeferred<Map<String, Any?>>>()
    private val incoming = ConcurrentHashMap<String, IncomingState>() // keyed by 32-char lowercase hex UUID
    private val outgoing = ConcurrentHashMap<String, OutgoingControl>()
    private val transfers = ConcurrentHashMap<String, TransferProgress>()

    init {
        defaultDownloadDir.mkdirs()
    }

    fun allTransfers(): List<TransferProgress> = transfers.values.toList()

    private fun notifyProgress(p: TransferProgress) {
        transfers[p.xferId] = p
        onProgress?.invoke(p)
    }

    suspend fun rpcCall(payload: Map<String, Any?>, timeoutMs: Long = 15_000L): Map<String, Any?> {
        val reqId = UUID.randomUUID().toString().replace("-", "").take(12)
        val msg = payload.toMutableMap()
        msg["req_id"] = reqId
        val deferred = CompletableDeferred<Map<String, Any?>>()
        rpcWaiters[reqId] = deferred
        try {
            sendCtrl(msg)
            val resp = withTimeout(timeoutMs) { deferred.await() }
            val err = resp.strVal("error")
            if (err.isNotEmpty()) throw RuntimeException(err)
            return resp
        } finally {
            rpcWaiters.remove(reqId)
        }
    }

    fun remoteFs(): FileSystemProvider = RemoteDeckLinkFileSystem(this)

    fun pauseTransfer(xferId: String) {
        val ctrl = outgoing[xferId] ?: return
        if (ctrl.progress.status == "active") {
            ctrl.paused = true
            ctrl.progress = ctrl.progress.copy(status = "paused", speedBps = 0.0)
            notifyProgress(ctrl.progress)
        }
    }

    fun resumeTransfer(xferId: String) {
        val ctrl = outgoing[xferId] ?: return
        if (ctrl.progress.status == "paused") {
            ctrl.paused = false
            synchronized(ctrl.pauseSignal) { ctrl.pauseSignal.notifyAll() }
            ctrl.progress = ctrl.progress.copy(status = "active")
            notifyProgress(ctrl.progress)
        }
    }

    suspend fun cancelTransfer(xferId: String) {
        val out = outgoing[xferId]
        if (out != null) {
            out.cancelled = true
            out.paused = false
            synchronized(out.pauseSignal) { out.pauseSignal.notifyAll() }
            if (!out.accepted.isCompleted) out.accepted.complete(0L)
            if (!out.done.isCompleted) out.done.complete("")
            out.progress = out.progress.copy(status = "cancelled")
            notifyProgress(out.progress)
            sendCtrl(mapOf("op" to "xfer_cancel", "xfer_id" to xferId))
            return
        }
        val inc = incoming.remove(xferId.lowercase())
        if (inc != null) {
            runCatching { inc.raf.close() }
            inc.progress = inc.progress.copy(status = "cancelled")
            notifyProgress(inc.progress)
            sendCtrl(mapOf("op" to "xfer_cancel", "xfer_id" to xferId))
        }
    }

    suspend fun sendItems(localFiles: List<File>, remoteDestDir: String = ""): List<TransferProgress> {
        val specs = withContext(Dispatchers.IO) { collectLocalFiles(localFiles) }
        val results = mutableListOf<TransferProgress>()
        for (spec in specs) {
            results.add(sendSingleFile(spec, remoteDestDir))
        }
        return results
    }

    suspend fun requestPull(remotePaths: List<String>, localDestDir: File) {
        sendCtrl(
            mapOf(
                "op" to "xfer_pull",
                "paths" to remotePaths,
                "dest_dir" to localDestDir.canonicalPath
            )
        )
    }

    private suspend fun sendSingleFile(spec: LocalItemSpec, remoteDestDir: String): TransferProgress {
        val uuid = UUID.randomUUID()
        val xferId = DeckLinkFraming.toHex(DeckLinkFraming.uuidToBytes(uuid))
        val uuidBytes = DeckLinkFraming.uuidToBytes(uuid)

        var prog = TransferProgress(
            xferId = xferId,
            direction = "send",
            filename = spec.file.name,
            relPath = spec.relPosix,
            totalBytes = spec.size,
            status = "queued"
        )
        val ctrl = OutgoingControl(prog)
        outgoing[xferId] = ctrl
        notifyProgress(prog)

        try {
            sendCtrl(
                mapOf(
                    "op" to "xfer_offer",
                    "xfer_id" to xferId,
                    "dest_dir" to remoteDestDir,
                    "rel_path" to spec.relPosix,
                    "size" to spec.size,
                    "mtime" to spec.mtimeSec
                )
            )
            val resumeOffset = withTimeout(20_000L) { ctrl.accepted.await() }
                .coerceIn(0L, spec.size)

            if (ctrl.cancelled) {
                prog = prog.copy(status = "cancelled")
                notifyProgress(prog)
                return prog
            }
            if (ctrl.error.isNotEmpty()) {
                prog = prog.copy(status = "error", error = ctrl.error)
                notifyProgress(prog)
                return prog
            }

            prog = prog.copy(
                resumedFrom = resumeOffset,
                transferredBytes = resumeOffset,
                status = "active"
            )
            ctrl.progress = prog
            notifyProgress(prog)

            val digest = MessageDigest.getInstance("SHA-256")
            var lastTickNs = System.nanoTime()
            var lastTickB = resumeOffset

            withContext(Dispatchers.IO) {
                RandomAccessFile(spec.file, "r").use { raf ->
                    val buf = ByteArray(CHUNK_SIZE)
                    var prefixLeft = resumeOffset
                    while (prefixLeft > 0L) {
                        val toRead = minOf(CHUNK_SIZE.toLong(), prefixLeft).toInt()
                        val n = raf.read(buf, 0, toRead)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        prefixLeft -= n
                    }

                    var cur = resumeOffset
                    while (cur < spec.size) {
                        while (ctrl.paused && !ctrl.cancelled) {
                            synchronized(ctrl.pauseSignal) { ctrl.pauseSignal.wait(150L) }
                        }
                        if (ctrl.cancelled || ctrl.error.isNotEmpty()) break

                        val n = raf.read(buf)
                        if (n <= 0) break
                        digest.update(buf, 0, n)
                        val slice = if (n == buf.size) buf else buf.copyOfRange(0, n)
                        sendData(DeckLinkFraming.packFileChunk(uuidBytes, cur, slice))
                        cur += n
                        prog = ctrl.progress.copy(transferredBytes = cur)
                        ctrl.progress = prog

                        val nowNs = System.nanoTime()
                        val dtSec = (nowNs - lastTickNs) / 1_000_000_000.0
                        if (dtSec >= 0.12 || cur >= spec.size) {
                            val spd = (cur - lastTickB) / dtSec.coerceAtLeast(1e-3)
                            prog = prog.copy(speedBps = spd)
                            ctrl.progress = prog
                            lastTickNs = nowNs
                            lastTickB = cur
                            notifyProgress(prog)
                        }
                    }
                }
            }

            if (ctrl.cancelled) {
                prog = prog.copy(status = "cancelled")
                notifyProgress(prog)
                return prog
            }
            if (ctrl.error.isNotEmpty()) {
                prog = prog.copy(status = "error", error = ctrl.error)
                notifyProgress(prog)
                return prog
            }

            val shaHex = DeckLinkFraming.toHex(digest.digest())
            sendCtrl(mapOf("op" to "xfer_eof", "xfer_id" to xferId, "sha256" to shaHex))
            val savedPath = withTimeout(30_000L) { ctrl.done.await() }
            prog = when {
                ctrl.error.isNotEmpty() -> prog.copy(status = "error", error = ctrl.error)
                ctrl.cancelled -> prog.copy(status = "cancelled")
                else -> prog.copy(status = "completed", savedPath = savedPath)
            }
            notifyProgress(prog)
            return prog
        } catch (e: Exception) {
            prog = prog.copy(status = "error", error = e.message ?: "Transfer error")
            notifyProgress(prog)
            return prog
        } finally {
            outgoing.remove(xferId)
        }
    }

    // ------------------------------------------------------------------ Incoming Frame Handlers
    suspend fun handleControl(msg: Map<String, Any?>) {
        val op = msg.strVal("op")
        val reqId = msg.strVal("req_id")

        if (op == "rpc_resp" && reqId.isNotEmpty()) {
            rpcWaiters[reqId]?.complete(msg)
            return
        }
        if (op in setOf("fs_roots", "fs_list", "fs_mkdir", "fs_delete", "fs_rename")) {
            handleFsRpc(op, reqId, msg)
            return
        }

        when (op) {
            "xfer_offer" -> handleOffer(msg)
            "xfer_accept" -> {
                val ctrl = outgoing[msg.strVal("xfer_id")]
                if (ctrl != null && !ctrl.accepted.isCompleted) {
                    ctrl.accepted.complete(msg.longVal("resume_offset"))
                }
            }
            "xfer_eof" -> handleEof(msg)
            "xfer_done" -> {
                val ctrl = outgoing[msg.strVal("xfer_id")]
                if (ctrl != null && !ctrl.done.isCompleted) {
                    ctrl.done.complete(msg.strVal("saved_path"))
                }
            }
            "xfer_error" -> {
                val xferId = msg.strVal("xfer_id")
                val err = msg.strVal("error").ifEmpty { "Transfer failed" }
                outgoing[xferId]?.let { ctrl ->
                    ctrl.error = err
                    if (!ctrl.accepted.isCompleted) ctrl.accepted.complete(0L)
                    if (!ctrl.done.isCompleted) ctrl.done.complete("")
                }
                incoming.remove(xferId.lowercase())?.let { inc ->
                    runCatching { inc.raf.close() }
                    inc.progress = inc.progress.copy(status = "error", error = err)
                    notifyProgress(inc.progress)
                }
            }
            "xfer_cancel" -> {
                val xferId = msg.strVal("xfer_id")
                outgoing[xferId]?.let { ctrl ->
                    ctrl.cancelled = true
                    ctrl.paused = false
                    synchronized(ctrl.pauseSignal) { ctrl.pauseSignal.notifyAll() }
                    if (!ctrl.accepted.isCompleted) ctrl.accepted.complete(0L)
                    if (!ctrl.done.isCompleted) ctrl.done.complete("")
                }
                incoming.remove(xferId.lowercase())?.let { inc ->
                    runCatching { inc.raf.close() }
                    inc.progress = inc.progress.copy(status = "cancelled")
                    notifyProgress(inc.progress)
                }
            }
            "xfer_pull" -> {
                val rawPaths = (msg["paths"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                val destDir = msg.strVal("dest_dir")
                scope.launch {
                    sendItems(rawPaths.map { File(it) }, destDir)
                }
            }
        }
    }

    private suspend fun handleFsRpc(op: String, reqId: String, msg: Map<String, Any?>) {
        val resp = mutableMapOf<String, Any?>("op" to "rpc_resp", "req_id" to reqId)
        try {
            when (op) {
                "fs_roots" -> {
                    val roots = localFs.listRoots()
                    resp["roots"] = roots.map { mapOf("name" to it.name, "path" to it.path, "kind" to it.kind) }
                }
                "fs_list" -> {
                    val listing = localFs.listDir(msg.strVal("path"))
                    resp["path"] = listing.path
                    resp["parent"] = listing.parent
                    resp["free_bytes"] = listing.freeBytes
                    resp["entries"] = listing.entries.map {
                        mapOf(
                            "name" to it.name,
                            "path" to it.path,
                            "is_dir" to it.isDir,
                            "size" to it.size,
                            "mtime" to it.mtime
                        )
                    }
                }
                "fs_mkdir" -> {
                    resp["created"] = localFs.mkdir(msg.strVal("parent_dir"), msg.strVal("name"))
                }
                "fs_delete" -> {
                    localFs.delete(msg.strVal("path"))
                    resp["ok"] = true
                }
                "fs_rename" -> {
                    resp["renamed"] = localFs.rename(msg.strVal("path"), msg.strVal("new_name"))
                }
            }
        } catch (e: Exception) {
            resp["error"] = e.message ?: "Filesystem error"
        }
        sendCtrl(resp)
    }

    private suspend fun handleOffer(msg: Map<String, Any?>) {
        val xferId = msg.strVal("xfer_id").lowercase()
        if (xferId.length != 32) return
        val destRaw = msg.strVal("dest_dir").trim()
        val destBase = if (destRaw.isNotEmpty()) File(destRaw) else defaultDownloadDir
        val relPath = msg.strVal("rel_path")
        val totalSize = msg.longVal("size")
        val mtimeSec = msg.doubleVal("mtime")

        try {
            val state = withContext(Dispatchers.IO) {
                destBase.mkdirs()
                val finalFile = PathSafety.safeResolveUnder(destBase, relPath)
                finalFile.parentFile?.mkdirs()
                val partFile = File(finalFile.parentFile, finalFile.name + PART_SUFFIX)

                var resumeOffset = 0L
                val digest = MessageDigest.getInstance("SHA-256")
                val raf = RandomAccessFile(partFile, "rw")
                if (partFile.exists()) {
                    val existing = raf.length()
                    if (existing in 1..totalSize) {
                        resumeOffset = existing
                        raf.seek(0L)
                        val buf = ByteArray(CHUNK_SIZE)
                        var rem = existing
                        while (rem > 0L) {
                            val n = raf.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (n <= 0) break
                            digest.update(buf, 0, n)
                            rem -= n
                        }
                        raf.seek(existing)
                    } else {
                        raf.setLength(0L)
                    }
                }

                val prog = TransferProgress(
                    xferId = xferId,
                    direction = "receive",
                    filename = finalFile.name,
                    relPath = relPath,
                    totalBytes = totalSize,
                    transferredBytes = resumeOffset,
                    resumedFrom = resumeOffset,
                    status = "active"
                )
                IncomingState(
                    progress = prog,
                    finalFile = finalFile,
                    partFile = partFile,
                    raf = raf,
                    digest = digest,
                    expectedOffset = resumeOffset,
                    mtimeSec = mtimeSec
                )
            }
            incoming[xferId] = state
            notifyProgress(state.progress)
            sendCtrl(mapOf("op" to "xfer_accept", "xfer_id" to xferId, "resume_offset" to state.expectedOffset))
        } catch (e: Exception) {
            sendCtrl(mapOf("op" to "xfer_error", "xfer_id" to xferId, "error" to (e.message ?: "Offer rejected")))
        }
    }

    suspend fun handleData(payload: ByteArray) {
        val parsed = DeckLinkFraming.unpackFileChunk(payload)
        val xferId = DeckLinkFraming.toHex(parsed.uuidBytes)
        val inc = incoming[xferId] ?: return

        if (parsed.offset != inc.expectedOffset) {
            incoming.remove(xferId)
            runCatching { inc.raf.close() }
            val err = "Offset mismatch: got ${parsed.offset}, expected ${inc.expectedOffset}"
            inc.progress = inc.progress.copy(status = "error", error = err)
            notifyProgress(inc.progress)
            sendCtrl(mapOf("op" to "xfer_error", "xfer_id" to xferId, "error" to err))
            return
        }

        withContext(Dispatchers.IO) {
            inc.raf.write(parsed.chunk)
        }
        inc.digest.update(parsed.chunk)
        inc.expectedOffset += parsed.chunk.size
        inc.progress = inc.progress.copy(transferredBytes = inc.expectedOffset)

        val nowNs = System.nanoTime()
        val dtSec = (nowNs - inc.lastTickNs) / 1_000_000_000.0
        if (dtSec >= 0.12 || inc.expectedOffset >= inc.progress.totalBytes) {
            val spd = (inc.expectedOffset - inc.lastTickBytes) / dtSec.coerceAtLeast(1e-3)
            inc.progress = inc.progress.copy(speedBps = spd)
            inc.lastTickNs = nowNs
            inc.lastTickBytes = inc.expectedOffset
            notifyProgress(inc.progress)
        }
    }

    private suspend fun handleEof(msg: Map<String, Any?>) {
        val xferId = msg.strVal("xfer_id").lowercase()
        val inc = incoming.remove(xferId) ?: return
        try {
            withContext(Dispatchers.IO) {
                inc.raf.fd.sync()
                inc.raf.close()
                if (inc.expectedOffset != inc.progress.totalBytes) {
                    throw IllegalStateException("Incomplete transfer (${inc.expectedOffset}/${inc.progress.totalBytes})")
                }
                val expectedSha = msg.strVal("sha256").lowercase()
                val actualSha = DeckLinkFraming.toHex(inc.digest.digest()).lowercase()
                if (expectedSha.isNotEmpty() && expectedSha != actualSha) {
                    inc.partFile.delete()
                    throw IllegalStateException("Mã kiểm tra toàn vẹn SHA-256 không khớp!")
                }
                if (inc.finalFile.exists()) {
                    inc.finalFile.delete()
                }
                if (!inc.partFile.renameTo(inc.finalFile)) {
                    inc.partFile.copyTo(inc.finalFile, overwrite = true)
                    inc.partFile.delete()
                }
                if (inc.mtimeSec > 0) {
                    inc.finalFile.setLastModified((inc.mtimeSec * 1000.0).toLong())
                }
            }
            inc.progress = inc.progress.copy(status = "completed", savedPath = inc.finalFile.absolutePath)
            notifyProgress(inc.progress)
            onFileSaved?.invoke(inc.finalFile)
            sendCtrl(mapOf("op" to "xfer_done", "xfer_id" to xferId, "saved_path" to inc.finalFile.absolutePath))
        } catch (e: Exception) {
            runCatching { inc.raf.close() }
            inc.progress = inc.progress.copy(status = "error", error = e.message ?: "EOF verify failed")
            notifyProgress(inc.progress)
            sendCtrl(mapOf("op" to "xfer_error", "xfer_id" to xferId, "error" to (e.message ?: "EOF error")))
        }
    }

    fun close() {
        for (inc in incoming.values) {
            runCatching { inc.raf.close() }
        }
        incoming.clear()
        for (def in rpcWaiters.values) {
            def.cancel()
        }
        rpcWaiters.clear()
    }
}

class RemoteDeckLinkFileSystem(private val engine: FileTransferEngine) : FileSystemProvider {
    override suspend fun listRoots(): List<FsRoot> {
        val resp = engine.rpcCall(mapOf("op" to "fs_roots"))
        val raw = resp["roots"] as? List<*> ?: emptyList<Any>()
        return raw.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            FsRoot(
                name = m["name"]?.toString() ?: "",
                path = m["path"]?.toString() ?: "",
                kind = m["kind"]?.toString() ?: "folder"
            )
        }
    }

    override suspend fun listDir(path: String): DirListing {
        val resp = engine.rpcCall(mapOf("op" to "fs_list", "path" to path))
        val rawEntries = resp["entries"] as? List<*> ?: emptyList<Any>()
        val entries = rawEntries.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            FsEntry(
                name = m["name"]?.toString() ?: "",
                path = m["path"]?.toString() ?: "",
                isDir = (m["is_dir"] as? Boolean) ?: false,
                size = (m["size"] as? Number)?.toLong() ?: 0L,
                mtime = (m["mtime"] as? Number)?.toDouble() ?: 0.0
            )
        }
        return DirListing(
            path = resp.strVal("path").ifEmpty { path },
            parent = resp["parent"]?.toString()?.takeIf { it.isNotEmpty() },
            entries = entries,
            freeBytes = resp.longVal("free_bytes")
        )
    }

    override suspend fun mkdir(parentDir: String, name: String): String {
        val resp = engine.rpcCall(mapOf("op" to "fs_mkdir", "parent_dir" to parentDir, "name" to name))
        return resp.strVal("created")
    }

    override suspend fun delete(path: String) {
        engine.rpcCall(mapOf("op" to "fs_delete", "path" to path))
    }

    override suspend fun rename(path: String, newName: String): String {
        val resp = engine.rpcCall(mapOf("op" to "fs_rename", "path" to path, "new_name" to newName))
        return resp.strVal("renamed")
    }
}
