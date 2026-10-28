package tr.ogur.kamerabekcisi

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

object Groups {
    const val PERSON = 0
    const val VEHICLE = 1
    const val ANIMAL = 2

    fun name(g: Int): String = when (g) {
        PERSON -> "İnsan"
        VEHICLE -> "Araç"
        ANIMAL -> "Hayvan"
        else -> "?"
    }

    fun color(g: Int): Int = when (g) {
        PERSON -> Color.rgb(255, 59, 48)
        VEHICLE -> Color.rgb(0, 122, 255)
        else -> Color.rgb(52, 199, 89)
    }
}

/** Bir tespit. Koordinatlar 0..1 aralığında (görüntüye oransal). */
class Det(val group: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val cx get() = (x1 + x2) / 2f
    val w get() = x2 - x1
    val h get() = y2 - y1

    fun iou(o: Det): Float = iou(x1, y1, x2, y2, o.x1, o.y1, o.x2, o.y2)

    companion object {
        fun iou(ax1: Float, ay1: Float, ax2: Float, ay2: Float, bx1: Float, by1: Float, bx2: Float, by2: Float): Float {
            val ix = max(0f, min(ax2, bx2) - max(ax1, bx1))
            val iy = max(0f, min(ay2, by2) - max(ay1, by1))
            val inter = ix * iy
            val u = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
            return if (u <= 0f) 0f else inter / u
        }
    }
}

/** YOLO (COCO) nesne algılayıcı — ONNX Runtime ile telefonda çalışır. */
class Detector(ctx: Context) : Closeable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputName: String
    val size: Int
    private val input: FloatBuffer
    private val planes: FloatArray
    private val pixels: IntArray
    private val board: Bitmap
    private val canvas: Canvas
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // COCO sınıfı -> grup
    private val clsIds = intArrayOf(0, 1, 2, 3, 5, 7, 15, 16, 17, 18, 19)
    private val clsGrp = intArrayOf(
        Groups.PERSON,
        Groups.VEHICLE, Groups.VEHICLE, Groups.VEHICLE, Groups.VEHICLE, Groups.VEHICLE,
        Groups.ANIMAL, Groups.ANIMAL, Groups.ANIMAL, Groups.ANIMAL, Groups.ANIMAL
    )

    init {
        val bytes = ctx.assets.open("yolo.onnx").use { it.readBytes() }
        val opts = OrtSession.SessionOptions()
        var xnn = false
        try {
            opts.addXnnpack(mapOf("intra_op_num_threads" to "4"))
            xnn = true
        } catch (_: Throwable) { }
        opts.setIntraOpNumThreads(if (xnn) 1 else 4)
        session = env.createSession(bytes, opts)
        inputName = session.inputNames.iterator().next()
        val shape = (session.inputInfo[inputName]!!.info as TensorInfo).shape
        size = if (shape.size == 4 && shape[2] > 0) shape[2].toInt() else 480
        input = FloatBuffer.allocate(3 * size * size)
        planes = FloatArray(3 * size * size)
        pixels = IntArray(size * size)
        board = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        canvas = Canvas(board)
    }

    @Synchronized
    fun detect(src: Bitmap, conf: Float): List<Det> {
        val w = src.width
        val h = src.height
        val r = min(size.toFloat() / w, size.toFloat() / h)
        val nw = w * r
        val nh = h * r
        val px = (size - nw) / 2f
        val py = (size - nh) / 2f

        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(src, null, RectF(px, py, px + nw, py + nh), paint)
        board.getPixels(pixels, 0, size, 0, 0, size, size)
        val area = size * size
        for (i in 0 until area) {
            val p = pixels[i]
            planes[i] = ((p shr 16) and 0xFF) / 255f
            planes[area + i] = ((p shr 8) and 0xFF) / 255f
            planes[2 * area + i] = (p and 0xFF) / 255f
        }
        input.clear()
        input.put(planes)
        input.rewind()

        val raw = ArrayList<Det>()
        OnnxTensor.createTensor(env, input, longArrayOf(1, 3, size.toLong(), size.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val out = res.get(0) as OnnxTensor
                val shp = out.info.shape
                val fb = out.floatBuffer
                val transposed = shp[1] > shp[2]
                val c = (if (transposed) shp[2] else shp[1]).toInt()
                val n = (if (transposed) shp[1] else shp[2]).toInt()
                fun v(ch: Int, i: Int): Float = if (!transposed) fb.get(ch * n + i) else fb.get(i * c + ch)

                for (i in 0 until n) {
                    var bs = 0f
                    var bg = -1
                    for (k in clsIds.indices) {
                        val ci = 4 + clsIds[k]
                        if (ci >= c) continue
                        val s = v(ci, i)
                        if (s > bs) { bs = s; bg = clsGrp[k] }
                    }
                    if (bg < 0 || bs < conf) continue
                    val cx = v(0, i); val cy = v(1, i); val bw = v(2, i); val bh = v(3, i)
                    val x1 = ((cx - bw / 2f - px) / r / w).coerceIn(0f, 1f)
                    val y1 = ((cy - bh / 2f - py) / r / h).coerceIn(0f, 1f)
                    val x2 = ((cx + bw / 2f - px) / r / w).coerceIn(0f, 1f)
                    val y2 = ((cy + bh / 2f - py) / r / h).coerceIn(0f, 1f)
                    if (y2 - y1 < 0.025f || x2 - x1 < 0.008f) continue
                    raw.add(Det(bg, bs, x1, y1, x2, y2))
                }
            }
        }
        return nms(raw, 0.45f)
    }

    private fun nms(list: List<Det>, thr: Float): List<Det> {
        val sorted = list.sortedByDescending { it.score }
        val keep = ArrayList<Det>()
        for (d in sorted) {
            if (keep.none { it.group == d.group && it.iou(d) > thr }) keep.add(d)
            if (keep.size >= 60) break
        }
        return keep
    }

    override fun close() {
        try { session.close() } catch (_: Throwable) { }
    }
}
