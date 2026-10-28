package tr.ogur.kamerabekcisi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Annotated JPEG of a camera's latest frame (zones + detections drawn). */
object Snap {
    fun jpeg(ch: Int, quality: Int = 70): ByteArray? {
        val st = Hub.cams[ch] ?: return null
        val src = st.bitmap ?: return null
        val b = src.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val c = Canvas(b)
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val s = b.width / 640f
        Draw.zones(c, r, Store.zones.filter { it.channel == ch }, s)
        Draw.dets(c, r, st.dets, s)
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        b.recycle()
        return bos.toByteArray()
    }
}

/** Evdeki cihazda çalışan küçük web paneli: canlı (MJPEG) kameralar + olaylar. */
class WebPanel(private val ctx: Context, private val port: Int = 8080) : Thread("web-panel") {
    @Volatile private var running = true
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(16)

    override fun run() {
        try {
            val ss = ServerSocket(port)
            server = ss
            Hub.log("Canlı izleme paneli açık: port $port")
            while (running) {
                val s = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(s) }
            }
        } catch (e: Exception) {
            Hub.log("Panel açılamadı: ${e.message}")
        }
    }

    fun shutdown() {
        running = false
        try { server?.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 15000
            val rd = BufferedReader(InputStreamReader(s.getInputStream()))
            val req = rd.readLine() ?: return
            while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break }
            val target = req.split(" ").getOrNull(1) ?: "/"
            val path = target.substringBefore("?")
            val q = HashMap<String, String>()
            target.substringAfter("?", "").split("&").forEach {
                val k = it.substringBefore("=")
                if (k.isNotEmpty()) q[k] = URLDecoder.decode(it.substringAfter("=", ""), "UTF-8")
            }
            val out = s.getOutputStream()
            if (q["k"] != Store.webPin) {
                send(out, 403, "text/html; charset=utf-8", page403().toByteArray())
                return
            }
            s.soTimeout = 0
            when (path) {
                "/" -> send(out, 200, "text/html; charset=utf-8", pageMain().toByteArray())
                "/live" -> mjpeg(out, q["ch"]?.toIntOrNull() ?: 1)
                "/snap" -> {
                    val j = Snap.jpeg(q["ch"]?.toIntOrNull() ?: 1, 85)
                    if (j == null) send(out, 404, "text/plain", "yok".toByteArray()) else send(out, 200, "image/jpeg", j)
                }
                "/olay" -> {
                    val f = q["f"] ?: ""
                    val file = File(File(ctx.filesDir, "olaylar"), f)
                    if (!f.matches(Regex("olay_\\d+_\\d+\\.jpg")) || !file.exists()) send(out, 404, "text/plain", "yok".toByteArray())
                    else send(out, 200, "image/jpeg", file.readBytes())
                }
                else -> send(out, 404, "text/plain", "yok".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) { }
        }
    }

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; else -> "Not Found" }
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun mjpeg(out: OutputStream, ch: Int) {
        val b = "kbframe"
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=$b\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray())
        var last: Bitmap? = null
        var idle = 0
        while (running) {
            val cur = Hub.cams[ch]?.bitmap
            if (cur != null && cur !== last) {
                last = cur
                idle = 0
                val j = Snap.jpeg(ch, 65) ?: continue
                out.write("--$b\r\nContent-Type: image/jpeg\r\nContent-Length: ${j.size}\r\n\r\n".toByteArray())
                out.write(j)
                out.write("\r\n".toByteArray())
                out.flush()
            } else {
                idle++
                if (idle > 600) break   // ~60 sn görüntü yoksa kapat
            }
            sleep(100)
        }
    }

    private fun page403() = "<html><body style='font-family:sans-serif;padding:30px'><h3>Kamera Bekçisi</h3>" +
        "<p>Erişim kodu hatalı. Adresi uygulamadaki 'Uzaktan izleme' bölümünden kopyalayın.</p></body></html>"

    private fun pageMain(): String {
        val k = URLEncoder.encode(Store.webPin, "UTF-8")
        val cams = Store.cams.filter { it.enabled }
        val tiles = StringBuilder()
        for (c in cams) {
            val st = Hub.cams[c.channel]
            val dot = if (st?.connected == true) "#34c759" else "#ff3b30"
            tiles.append("<div class='t' onclick=\"big(${c.channel})\"><img src='/live?ch=${c.channel}&k=$k'>" +
                "<span><i style='background:$dot'></i>${esc(c.name)}</span></div>")
        }
        val ev = StringBuilder()
        for (e in EventLog.list(ctx, 30)) {
            val t = java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale("tr")).format(java.util.Date(e.time))
            ev.append("<a href='/olay?f=${e.file}&k=$k' target='_blank' class='e'><img src='/olay?f=${e.file}&k=$k' loading='lazy'>" +
                "<div><b>${Groups.name(e.group)} • ${esc(e.zone)}</b><br>${esc(e.cam)}<br><small>$t</small></div></a>")
        }
        if (ev.isEmpty()) ev.append("<p style='color:#999'>Henüz olay yok.</p>")
        val status = if (Hub.running) "🟢 İzleme açık" else "⚪ İzleme kapalı"
        return """<!DOCTYPE html><html lang="tr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Kamera Bekçisi</title>
<style>
body{margin:0;background:#111;color:#eee;font-family:-apple-system,Roboto,sans-serif}
header{padding:12px 14px;display:flex;justify-content:space-between;align-items:center;background:#1c1c1e}
h1{font-size:17px;margin:0}.g{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:6px;padding:6px}
.t{position:relative;background:#000;aspect-ratio:4/3;cursor:pointer}.t img{width:100%;height:100%;object-fit:contain}
.t span{position:absolute;left:0;top:0;right:0;background:rgba(0,0,0,.55);padding:4px 8px;font-size:13px}
.t i{display:inline-block;width:8px;height:8px;border-radius:4px;margin-right:6px}
h2{font-size:15px;padding:10px 12px 0;margin:0}.ev{padding:6px}
.e{display:flex;gap:10px;background:#1c1c1e;margin:6px 0;padding:6px;border-radius:8px;color:#eee;text-decoration:none;font-size:13px}
.e img{width:110px;height:80px;object-fit:cover;border-radius:4px}
#b{display:none;position:fixed;inset:0;background:#000;z-index:9}#b img{width:100%;height:100%;object-fit:contain}
#b button{position:absolute;top:10px;right:10px;font-size:18px;padding:6px 14px;border:0;border-radius:6px}
</style></head><body>
<header><h1>📹 Kamera Bekçisi</h1><span style="font-size:13px">$status</span></header>
<div class="g">$tiles</div>
<h2>Son olaylar</h2><div class="ev">$ev</div>
<div id="b"><img id="bi"><button onclick="cl()">✕</button></div>
<script>
function big(c){document.getElementById('bi').src='/live?ch='+c+'&k=$k';document.getElementById('b').style.display='block'}
function cl(){document.getElementById('bi').src='';document.getElementById('b').style.display='none'}
</script></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

/** Telegram komutları: foto, foto 2, durum */
class TelegramCommands(private val ctx: Context) : Thread("tg-komut") {
    @Volatile private var running = true
    private var offset = 0L

    fun shutdown() { running = false; interrupt() }

    override fun run() {
        // Eski mesajları atla
        try { fetch(-1, 0)?.let { arr -> if (arr.length() > 0) offset = arr.getJSONObject(arr.length() - 1).getLong("update_id") + 1 } } catch (_: Exception) { }
        while (running) {
            if (!Telegram.configured()) { try { sleep(10_000) } catch (_: InterruptedException) { break }; continue }
            try {
                val arr = fetch(offset, 25) ?: continue
                for (i in 0 until arr.length()) {
                    val u = arr.getJSONObject(i)
                    offset = u.getLong("update_id") + 1
                    val m = u.optJSONObject("message") ?: continue
                    val chat = m.optJSONObject("chat")?.opt("id")?.toString() ?: continue
                    if (chat != Store.tgChat) continue
                    handle(m.optString("text", "").trim().lowercase(java.util.Locale("tr")))
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {
                try { sleep(5000) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun fetch(off: Long, timeout: Int): org.json.JSONArray? {
        val c = URL("https://api.telegram.org/bot${Store.tgToken.trim()}/getUpdates?offset=$off&timeout=$timeout")
            .openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = (timeout + 15) * 1000
        val txt = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return JSONObject(txt).optJSONArray("result")
    }

    private fun handle(t: String) {
        val cmd = t.removePrefix("/").substringBefore("@")
        val word = cmd.split(" ").firstOrNull() ?: return
        val arg = cmd.split(" ").getOrNull(1)?.toIntOrNull()
        when (word) {
            "foto", "fotograf", "fotoğraf", "kamera", "kameralar", "bak" -> {
                val cams = Store.cams.filter { it.enabled }
                val sel = if (arg != null) cams.filterIndexed { i, c -> c.channel == arg || i + 1 == arg }.take(1) else cams
                if (sel.isEmpty()) { Telegram.sendText("Kamera bulunamadı."); return }
                for (c in sel) {
                    val j = Snap.jpeg(c.channel, 85)
                    if (j == null) { Telegram.sendText("${c.name}: görüntü yok"); continue }
                    val f = File(ctx.cacheDir, "anlik_${c.channel}.jpg")
                    f.writeBytes(j)
                    val n = Hub.cams[c.channel]?.dets?.size ?: 0
                    Telegram.sendPhoto(f, "📷 ${c.name}" + if (n > 0) " • $n nesne" else "")
                }
            }
            "durum" -> {
                val cams = Store.cams.filter { it.enabled }
                val up = cams.count { Hub.cams[it.channel]?.connected == true }
                val z = Store.zones.count { it.enabled }
                Telegram.sendText("${if (Hub.running) "🟢 İzleme açık" else "⚪ İzleme kapalı"}\n$up/${cams.size} kamera bağlı\n$z alan izleniyor")
            }
            "start", "yardim", "yardım", "help" -> Telegram.sendText(
                "Komutlar:\nfoto — tüm kameraların anlık görüntüsü\nfoto 2 — yalnızca 2. kamera\ndurum — sistem durumu")
        }
    }
}
