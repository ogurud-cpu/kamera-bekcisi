package tr.ogur.kamerabekcisi

import android.graphics.Bitmap
import android.net.Uri
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.ByteBuffer
import java.util.Locale

/** Ham kareyi Android Bitmap'e çevirir (RGBA). */
object Frames {
    private const val MAX_W = 960

    fun toBitmap(f: Frame): Bitmap? {
        val img = f.image ?: return null
        if (img.isEmpty()) return null
        val src = img[0] as? ByteBuffer ?: return null
        val w = f.imageWidth
        val h = f.imageHeight
        if (w <= 0 || h <= 0) return null
        var stride = f.imageStride
        if (stride < w * 4) stride *= 4          // bazı sürümler pikselde verir
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val tight = ByteBuffer.allocate(w * h * 4)
        val row = ByteArray(w * 4)
        val s = src.duplicate()
        for (y in 0 until h) {
            s.position(y * stride)
            s.get(row, 0, row.size)
            tight.put(row)
        }
        tight.rewind()
        bmp.copyPixelsFromBuffer(tight)
        if (w > MAX_W) {
            val nh = (h.toFloat() * MAX_W / w).toInt()
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_W, nh, true)
            bmp.recycle()
            return scaled
        }
        return bmp
    }
}

object Streams {
    /** Yaygın DVR/NVR RTSP adres biçimleri. Hepsi ALT AKIŞ (düşük çözünürlük) içindir. */
    val templates: List<Pair<String, String>> = listOf(
        "Dahua tipi" to "rtsp://{user}:{pass}@{ip}:{port}/cam/realmonitor?channel={ch}&subtype=1",
        "XMeye tipi" to "rtsp://{ip}:{port}/user={user}&password={pass}&channel={ch}&stream=1.sdp?real_stream",
        "Hikvision tipi" to "rtsp://{user}:{pass}@{ip}:{port}/Streaming/Channels/{ch}02",
        "H264 DVR tipi" to "rtsp://{user}:{pass}@{ip}:{port}/h264/ch{ch}/sub/av_stream",
        "Uniview tipi" to "rtsp://{user}:{pass}@{ip}:{port}/unicast/c{ch}/s1/live",
        "TVT tipi" to "rtsp://{user}:{pass}@{ip}:{port}/chID={ch}&streamType=sub",
        "Genel tip A" to "rtsp://{user}:{pass}@{ip}:{port}/live/ch{ch0}_1",
        "Genel tip B" to "rtsp://{user}:{pass}@{ip}:{port}/ch{ch2}/1",
        "Genel tip C" to "rtsp://{user}:{pass}@{ip}:{port}/{ch}/1",
        "Dahua tipi (ana akış)" to "rtsp://{user}:{pass}@{ip}:{port}/cam/realmonitor?channel={ch}&subtype=0",
        "XMeye tipi (ana akış)" to "rtsp://{ip}:{port}/user={user}&password={pass}&channel={ch}&stream=0.sdp?real_stream"
    )

    @Volatile private var logInit = false

    fun quietLogs() {
        if (logInit) return
        logInit = true
        try { avutil.av_log_set_level(avutil.AV_LOG_QUIET) } catch (_: Throwable) { }
    }

    fun fill(t: String, ip: String, port: Int, user: String, pass: String, ch: Int): String =
        t.replace("{ip}", ip.trim())
            .replace("{port}", port.toString())
            .replace("{user}", Uri.encode(user))
            .replace("{pass}", Uri.encode(pass))
            .replace("{ch2}", String.format(Locale.US, "%02d", ch))
            .replace("{ch0}", (ch - 1).toString())
            .replace("{ch}", ch.toString())

    fun newGrabber(url: String, transport: String, timeoutSec: Int): FFmpegFrameGrabber {
        quietLogs()
        val g = FFmpegFrameGrabber(url)
        g.format = "rtsp"
        g.setOption("rtsp_transport", transport)
        g.setOption("timeout", (timeoutSec * 1_000_000L).toString())
        g.setOption("analyzeduration", "2000000")
        g.setOption("probesize", "1000000")
        g.pixelFormat = avutil.AV_PIX_FMT_RGBA
        return g
    }

    /** Tek kare alır. Hata olursa exception fırlatır. */
    fun grabOne(url: String, transport: String = Store.transport, timeoutSec: Int = 7): Bitmap {
        val g = newGrabber(url, transport, timeoutSec)
        try {
            g.start()
            for (i in 0 until 40) {
                val f = g.grabImage() ?: break
                val b = Frames.toBitmap(f)
                if (b != null) return b
            }
            throw RuntimeException("Bağlandı ama görüntü gelmedi")
        } finally {
            try { g.stop() } catch (_: Throwable) { }
            try { g.release() } catch (_: Throwable) { }
        }
    }

    /** FFmpeg hata mesajını anlaşılır Türkçeye çevirir. */
    fun explain(e: Throwable): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("-825242872") || m.contains("401") -> "Kullanıcı adı/şifre reddedildi"
            m.contains("-875574520") || m.contains("404") -> "Bu adres biçimi cihazda yok"
            m.contains("-858797304") || m.contains("403") -> "Erişim reddedildi"
            m.contains("-111") || m.contains("refused", true) -> "Port kapalı (bağlantı reddedildi)"
            m.contains("-110") || m.contains("timed out", true) || m.contains("-138") -> "Zaman aşımı (cevap yok)"
            m.contains("-1094995529") -> "Geçersiz yanıt"
            m.contains("görüntü gelmedi") -> "Bağlandı ama görüntü gelmedi"
            else -> m.take(90)
        }
    }

    class ProbeResult(val label: String, val template: String, val transport: String, val bitmap: Bitmap)

    /**
     * Sırayla adres biçimlerini dener, ilk çalışanı döndürür.
     * preferred: cihazda açık bulunan portlara göre öne alınacak biçim adları.
     */
    fun probe(
        ip: String, port: Int, user: String, pass: String,
        custom: String?, preferred: List<String>,
        log: (String) -> Unit
    ): ProbeResult? {
        val list = ArrayList<Pair<String, String>>()
        if (!custom.isNullOrBlank()) list.add("Özel adres" to custom.trim())
        for (p in preferred) templates.firstOrNull { it.first == p }?.let { list.add(it) }
        for (t in templates) if (list.none { it.second == t.second }) list.add(t)

        var authHint = false
        for (transport in listOf("tcp", "udp")) {
            log(if (transport == "tcp") "— TCP ile deneniyor —" else "— UDP ile deneniyor —")
            for ((label, t) in list) {
                val url = fill(t, ip, port, user, pass, 1)
                try {
                    val b = grabOne(url, transport, 6)
                    log("✅ $label: görüntü alındı (${b.width}×${b.height})")
                    return ProbeResult(label, t, transport, b)
                } catch (e: Throwable) {
                    val why = explain(e)
                    if (why.startsWith("Kullanıcı")) authHint = true
                    log("✗ $label: $why")
                }
            }
            if (authHint) {
                log("⚠️ Cihaz şifreyi reddetti. Kullanıcı adı/şifreyi kontrol edin.")
                return null
            }
        }
        return null
    }
}
