package com.pocketdeck.android.protocol

import com.google.gson.Gson
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight embedded HTTP File Transfer Portal (QR + 4-digit PIN) for Android.
 * Lets iPhones (mirroring via AirPlay) or any browser on the same Wi-Fi upload & download files
 * directly to/from `sharedDir` at full Wi-Fi speed without installing a client app.
 */
class WebFilePortal(
    val sharedDir: File,
    private val bindHost: String = "0.0.0.0",
    private val requestedPort: Int = 7280,
    pinCode: String? = null,
    private val onFileUploaded: ((File) -> Unit)? = null
) {
    private val gson = Gson()
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private var serverSocket: ServerSocket? = null

    var pin: String = pinCode ?: "%04d".format(SecureRandom().nextInt(10_000))
    var port: Int = 0
        private set

    val isRunning: Boolean
        get() = running.get()

    init {
        sharedDir.mkdirs()
    }

    fun start(): Int {
        if (!running.compareAndSet(false, true)) return port
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

        pool.execute {
            while (running.get() && !srv.isClosed) {
                try {
                    val client = srv.accept()
                    pool.execute { handleClient(client) }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                }
            }
        }
        return port
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun readHeaderBlock(ins: InputStream): Pair<String, Map<String, String>>? {
        val sb = StringBuilder()
        var state = 0
        while (sb.length < 16384) {
            val b = ins.read()
            if (b < 0) return null
            val ch = b.toChar()
            sb.append(ch)
            state = when {
                state == 0 && ch == '\r' -> 1
                state == 1 && ch == '\n' -> 2
                state == 2 && ch == '\r' -> 3
                state == 3 && ch == '\n' -> 4
                else -> 0
            }
            if (state == 4) break
        }
        val lines = sb.toString().split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val reqLine = lines[0]
        val headers = mutableMapOf<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
        }
        return reqLine to headers
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) null
            else URLDecoder.decode(pair.substring(0, eq), "UTF-8") to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
        }.toMap()
    }

    private fun checkPin(headers: Map<String, String>, query: Map<String, String>): Boolean {
        val cand = headers["x-pocketdeck-pin"] ?: query["pin"] ?: ""
        return cand.trim() == pin
    }

    private fun handleClient(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30_000
            val ins = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

            val parsed = readHeaderBlock(ins) ?: return
            val (reqLine, headers) = parsed
            val parts = reqLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullTarget = parts[1]
            val qIdx = fullTarget.indexOf('?')
            val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
            val query = if (qIdx >= 0) parseQuery(fullTarget.substring(qIdx + 1)) else emptyMap()

            if (method == "GET" && (path == "/" || path == "/index.html")) {
                val html = buildPortalHtml().toByteArray(Charsets.UTF_8)
                writeResponse(out, 200, "OK", "text/html; charset=utf-8", html)
                return
            }

            if (!checkPin(headers, query)) {
                val err = gson.toJson(mapOf("error" to "Sai mã PIN 4 số")).toByteArray(Charsets.UTF_8)
                writeResponse(out, 401, "Unauthorized", "application/json; charset=utf-8", err)
                return
            }

            when {
                method == "GET" && path == "/api/files" -> {
                    val files = (sharedDir.listFiles() ?: emptyArray())
                        .filter { it.isFile && !it.name.endsWith(PART_SUFFIX) }
                        .sortedByDescending { it.lastModified() }
                        .map {
                            mapOf(
                                "name" to it.name,
                                "size" to it.length(),
                                "mtime" to (it.lastModified() / 1000L)
                            )
                        }
                    val body = gson.toJson(mapOf("files" to files)).toByteArray(Charsets.UTF_8)
                    writeResponse(out, 200, "OK", "application/json; charset=utf-8", body)
                }

                method == "POST" && path == "/api/upload" -> {
                    val rawName = query["name"] ?: headers["x-file-name"]?.let { URLDecoder.decode(it, "UTF-8") } ?: "upload.bin"
                    val cleanName = File(rawName.replace('\\', '/')).name.ifEmpty { "upload.bin" }
                    val contentLen = headers["content-length"]?.toLongOrNull() ?: 0L
                    val dest = PathSafety.safeResolveUnder(sharedDir, cleanName)
                    val tmp = File(dest.parentFile, dest.name + PART_SUFFIX)

                    FileOutputStream(tmp).use { fos ->
                        val buf = ByteArray(64 * 1024)
                        var remaining = contentLen
                        while (remaining > 0L) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n <= 0) break
                            fos.write(buf, 0, n)
                            remaining -= n
                        }
                        fos.fd.sync()
                    }
                    if (dest.exists()) dest.delete()
                    tmp.renameTo(dest)
                    onFileUploaded?.invoke(dest)
                    val ok = gson.toJson(mapOf("ok" to true, "name" to dest.name, "size" to dest.length()))
                        .toByteArray(Charsets.UTF_8)
                    writeResponse(out, 200, "OK", "application/json; charset=utf-8", ok)
                }

                method == "GET" && path == "/api/download" -> {
                    val name = query["name"] ?: ""
                    val target = runCatching { PathSafety.safeResolveUnder(sharedDir, name) }.getOrNull()
                    if (target == null || !target.isFile) {
                        writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
                        return
                    }
                    val hdr = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        append("Content-Length: ${target.length()}\r\n")
                        append("Content-Disposition: attachment; filename=\"${target.name.replace("\"", "_")}\"\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(hdr.toByteArray(Charsets.UTF_8))
                    FileInputStream(target).use { fis ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = fis.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                    out.flush()
                }

                else -> {
                    writeResponse(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray())
                }
            }
        }
    }

    private fun writeResponse(
        out: BufferedOutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray
    ) {
        val hdr = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(hdr.toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun buildPortalHtml(): String = """
<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>PocketDeck — Truyền File Nhanh Wi-Fi</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body {
    margin: 0; padding: 20px;
    background: #0f172a; color: #f8fafc;
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
    line-height: 1.5;
  }
  .wrap { max-width: 720px; margin: 0 auto; }
  h1 { font-size: 22px; margin: 0 0 4px 0; letter-spacing: -0.02em; }
  .sub { color: #94a3b8; font-size: 14px; margin-bottom: 18px; }
  .card {
    background: #1e293b; border: 1px solid #334155;
    border-radius: 12px; padding: 16px; margin-bottom: 16px;
  }
  .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
  input[type="password"], input[type="text"] {
    background: #0f172a; color: #f8fafc; border: 1px solid #475569;
    border-radius: 8px; padding: 9px 12px; font-size: 15px;
    font-variant-numeric: tabular-nums; width: 150px;
  }
  button, .btn {
    background: #2563eb; color: #fff; border: none; border-radius: 8px;
    padding: 9px 16px; font-size: 14px; font-weight: 600; cursor: pointer;
    text-decoration: none; display: inline-block;
  }
  button.sec { background: #334155; }
  .bar { height: 10px; background: #0f172a; border-radius: 6px; overflow: hidden; margin-top: 10px; display: none; }
  .bar > div { height: 100%; width: 0%; background: #2563eb; transition: width 0.1s linear; }
  table { width: 100%; border-collapse: collapse; margin-top: 10px; font-size: 14px; font-variant-numeric: tabular-nums; }
  th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #334155; }
  th { color: #94a3b8; font-weight: 600; }
  .msg { color: #93c5fd; font-size: 13px; margin-top: 8px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>PocketDeck Wi-Fi File Portal</h1>
  <div class="sub">Truyền file trực tiếp giữa iPhone / Trình duyệt và điện thoại Android</div>

  <div class="card">
    <div class="row">
      <label for="pin">Mã PIN 4 số:</label>
      <input id="pin" type="text" inputmode="numeric" maxlength="6" placeholder="Nhập PIN">
      <button onclick="loadFiles()">Mở danh sách tệp</button>
    </div>
    <div id="authMsg" class="msg"></div>
  </div>

  <div class="card">
    <strong>Gửi tệp / ảnh / video sang Android</strong>
    <div class="row" style="margin-top:10px;">
      <input id="picker" type="file" multiple>
      <button onclick="uploadSelected()">Gửi ngay →</button>
    </div>
    <div id="pbar" class="bar"><div id="pfill"></div></div>
    <div id="upMsg" class="msg"></div>
  </div>

  <div class="card">
    <div class="row" style="justify-content:space-between;">
      <strong>Tệp chia sẻ trên Android</strong>
      <button class="sec" onclick="loadFiles()">Làm mới</button>
    </div>
    <table>
      <thead><tr><th>Tên tệp</th><th>Dung lượng</th><th>Tải về</th></tr></thead>
      <tbody id="tbody"><tr><td colspan="3" style="color:#64748b;">Nhập mã PIN ở trên để xem tệp</td></tr></tbody>
    </table>
  </div>
</div>
<script>
const urlPin = new URLSearchParams(location.search).get('pin');
if (urlPin) { document.getElementById('pin').value = urlPin; setTimeout(loadFiles, 80); }
function fmt(b){ if(b<1024)return b+' B'; if(b<1048576)return (b/1024).toFixed(1)+' KB'; return (b/1048576).toFixed(1)+' MB'; }
function getPin(){ return document.getElementById('pin').value.trim(); }
async function loadFiles(){
  const pin = getPin();
  const r = await fetch('/api/files?pin='+encodeURIComponent(pin));
  if(!r.ok){ document.getElementById('authMsg').textContent='Sai mã PIN! Hãy kiểm tra trên màn hình PocketDeck.'; return; }
  document.getElementById('authMsg').textContent='Đã xác thực thành công.';
  const data = await r.json();
  const tb = document.getElementById('tbody');
  tb.innerHTML = '';
  if(!data.files.length){ tb.innerHTML='<tr><td colspan="3" style="color:#64748b;">Chưa có tệp nào trong thư mục PocketDeck</td></tr>'; return; }
  for(const f of data.files){
    const tr = document.createElement('tr');
    const dl = '/api/download?pin='+encodeURIComponent(pin)+'&name='+encodeURIComponent(f.name);
    tr.innerHTML = '<td></td><td>'+fmt(f.size)+'</td><td><a class="btn" href="'+dl+'">Tải về</a></td>';
    tr.children[0].textContent = f.name;
    tb.appendChild(tr);
  }
}
async function uploadSelected(){
  const files = document.getElementById('picker').files;
  const pin = getPin();
  if(!files.length) return;
  const bar = document.getElementById('pbar'), fill = document.getElementById('pfill'), msg = document.getElementById('upMsg');
  bar.style.display = 'block';
  for(let i=0;i<files.length;i++){
    const f = files[i];
    msg.textContent = 'Đang gửi ('+(i+1)+'/'+files.length+'): '+f.name;
    await new Promise((res, rej) => {
      const xhr = new XMLHttpRequest();
      xhr.open('POST', '/api/upload?pin='+encodeURIComponent(pin)+'&name='+encodeURIComponent(f.name));
      xhr.upload.onprogress = e => { if(e.lengthComputable) fill.style.width = ((e.loaded/e.total)*100).toFixed(1)+'%'; };
      xhr.onload = () => xhr.status === 200 ? res() : rej(new Error('HTTP '+xhr.status));
      xhr.onerror = () => rej(new Error('Network error'));
      xhr.send(f);
    }).catch(err => { msg.textContent = 'Lỗi: '+err.message; throw err; });
  }
  msg.textContent = 'Đã gửi thành công '+files.length+' tệp!';
  loadFiles();
}
</script>
</body>
</html>
""".trimIndent()
}
