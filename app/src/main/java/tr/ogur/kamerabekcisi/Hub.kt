package tr.ogur.kamerabekcisi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Servis ile ekranlar arasında canlı durum paylaşımı (aynı süreç içinde). */
class CamState {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var dets: List<Det> = emptyList()
    @Volatile var connected = false
    @Volatile var error: String? = null
}

object Hub {
    val cams = ConcurrentHashMap<Int, CamState>()
    @Volatile var running = false
    @Volatile var inferenceMs = 0L
    @Volatile var detectorError: String? = null
    private val logs = ArrayDeque<String>()
    private val tf = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun cam(ch: Int): CamState = cams.getOrPut(ch) { CamState() }

    fun log(s: String) {
        synchronized(logs) {
            logs.addLast(tf.format(Date()) + "  " + s)
            while (logs.size > 80) logs.removeFirst()
        }
    }

    fun lastLogs(n: Int): List<String> = synchronized(logs) { logs.toList().takeLast(n) }
}

/** Ortak çizim: alanlar ve tespit kutuları. */
object Draw {
    private val zoneStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(255, 204, 0); strokeWidth = 4f
    }
    private val zoneFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = Color.argb(50, 255, 204, 0)
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f }
    private val labelBg = Paint().apply { style = Paint.Style.FILL }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    fun zones(c: Canvas, r: RectF, zones: List<Zone>, scale: Float = 1f) {
        zoneStroke.strokeWidth = 3f * scale
        for (z in zones) {
            if (z.points.size < 2) continue
            val path = Path()
            z.points.forEachIndexed { i, p ->
                val x = r.left + p.x * r.width()
                val y = r.top + p.y * r.height()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            if (z.enabled) c.drawPath(path, zoneFill)
            zoneStroke.alpha = if (z.enabled) 255 else 90
            c.drawPath(path, zoneStroke)
        }
    }

    fun dets(c: Canvas, r: RectF, dets: List<Det>, scale: Float = 1f) {
        boxPaint.strokeWidth = 3f * scale
        labelText.textSize = 13f * scale
        for (d in dets) {
            val col = Groups.color(d.group)
            boxPaint.color = col
            val x1 = r.left + d.x1 * r.width(); val y1 = r.top + d.y1 * r.height()
            val x2 = r.left + d.x2 * r.width(); val y2 = r.top + d.y2 * r.height()
            c.drawRect(x1, y1, x2, y2, boxPaint)
            val label = "${Groups.name(d.group)} %${(d.score * 100).toInt()}"
            val tw = labelText.measureText(label)
            labelBg.color = col
            val ty = if (y1 - labelText.textSize - 4 * scale < r.top) y1 else y1 - labelText.textSize - 4 * scale
            c.drawRect(x1, ty, x1 + tw + 8 * scale, ty + labelText.textSize + 4 * scale, labelBg)
            c.drawText(label, x1 + 4 * scale, ty + labelText.textSize, labelText)
        }
    }

    /** Alarm fotoğrafı: alan + tespit çizilmiş kopya */
    fun annotate(src: Bitmap, zone: Zone, dets: List<Det>, hit: Det): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(b)
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        val s = b.width / 640f
        zones(c, r, listOf(zone), s)
        dets(c, r, dets.filter { it !== hit }, s * 0.8f)
        dets(c, r, listOf(hit), s * 1.4f)
        return b
    }
}

/** Olay arşivi: fotoğraflar + JSON satırları */
object EventLog {
    class Event(val time: Long, val channel: Int, val cam: String, val zone: String, val group: Int, val score: Float, val file: String)

    private fun dir(ctx: Context): File = File(ctx.filesDir, "olaylar").apply { mkdirs() }
    private fun index(ctx: Context): File = File(dir(ctx), "olaylar.jsonl")

    @Synchronized
    fun add(ctx: Context, bmp: Bitmap, channel: Int, cam: String, zone: String, group: Int, score: Float): File {
        val t = System.currentTimeMillis()
        val f = File(dir(ctx), "olay_${t}_$channel.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        val o = JSONObject().put("t", t).put("ch", channel).put("cam", cam).put("zone", zone)
            .put("g", group).put("s", score.toDouble()).put("f", f.name)
        index(ctx).appendText(o.toString() + "\n")
        prune(ctx)
        return f
    }

    @Synchronized
    fun list(ctx: Context, limit: Int = 200): List<Event> {
        val idx = index(ctx)
        if (!idx.exists()) return emptyList()
        val out = ArrayList<Event>()
        for (line in idx.readLines().asReversed()) {
            if (line.isBlank()) continue
            try {
                val o = JSONObject(line)
                out.add(Event(o.getLong("t"), o.getInt("ch"), o.optString("cam"), o.optString("zone"),
                    o.optInt("g"), o.optDouble("s", 0.0).toFloat(), o.optString("f")))
            } catch (_: Exception) { }
            if (out.size >= limit) break
        }
        return out
    }

    fun file(ctx: Context, e: Event): File = File(dir(ctx), e.file)

    @Synchronized
    fun clear(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun prune(ctx: Context) {
        val imgs = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: return
        if (imgs.size <= 400) return
        imgs.take(imgs.size - 300).forEach { it.delete() }
        val keep = dir(ctx).listFiles { f -> f.name.endsWith(".jpg") }?.map { it.name }?.toHashSet() ?: return
        val lines = index(ctx).readLines().filter { l ->
            try { JSONObject(l).optString("f") in keep } catch (_: Exception) { false }
        }
        index(ctx).writeText(lines.joinToString("\n", postfix = "\n"))
    }
}
