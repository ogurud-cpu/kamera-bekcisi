package tr.ogur.kamerabekcisi

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

/** Kaydırılabilir dikey sayfa oluşturur. */
fun Activity.page(title: String): LinearLayout {
    this.title = title
    val sv = ScrollView(this)
    sv.isFillViewport = true
    val ll = LinearLayout(this)
    ll.orientation = LinearLayout.VERTICAL
    ll.setPadding(dp(16), dp(12), dp(16), dp(32))
    sv.addView(ll, ViewGroup.LayoutParams(MATCH, WRAP))
    setContentView(sv)
    return ll
}

fun LinearLayout.text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(33, 33, 33)): TextView {
    val t = TextView(context)
    t.text = s
    t.textSize = size
    t.setTextColor(color)
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = context.dp(6)
    addView(t, lp)
    return t
}

fun LinearLayout.header(s: String): TextView {
    val t = text(s, 17f, true, Color.rgb(183, 28, 28))
    (t.layoutParams as LinearLayout.LayoutParams).topMargin = context.dp(18)
    return t
}

fun LinearLayout.btn(s: String, weight: Float = 0f, onClick: () -> Unit): Button {
    val b = Button(context)
    b.text = s
    b.setAllCaps(false)
    b.setOnClickListener { onClick() }
    val lp = if (weight > 0f) LinearLayout.LayoutParams(0, WRAP, weight) else LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = context.dp(4)
    addView(b, lp)
    return b
}

fun LinearLayout.field(label: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
    text(label, 13f, false, Color.rgb(97, 97, 97))
    val e = EditText(context)
    e.setText(value)
    e.inputType = type
    e.setSingleLine(true)
    addView(e, LinearLayout.LayoutParams(MATCH, WRAP))
    return e
}

fun LinearLayout.check(s: String, checked: Boolean, weight: Float = 0f): CheckBox {
    val c = CheckBox(context)
    c.text = s
    c.isChecked = checked
    val lp = if (weight > 0f) LinearLayout.LayoutParams(0, WRAP, weight) else LinearLayout.LayoutParams(MATCH, WRAP)
    addView(c, lp)
    return c
}

fun LinearLayout.row(): LinearLayout {
    val r = LinearLayout(context)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    addView(r, LinearLayout.LayoutParams(MATCH, WRAP))
    return r
}

fun LinearLayout.card(): LinearLayout {
    val c = LinearLayout(context)
    c.orientation = LinearLayout.VERTICAL
    c.setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(10))
    val bg = GradientDrawable()
    bg.setColor(Color.rgb(245, 245, 245))
    bg.cornerRadius = context.dp(10).toFloat()
    c.background = bg
    val lp = LinearLayout.LayoutParams(MATCH, WRAP)
    lp.topMargin = context.dp(8)
    addView(c, lp)
    return c
}

/** Bitmap'i oranını koruyarak sığdıran dikdörtgen */
fun fitRect(bw: Int, bh: Int, vw: Float, vh: Float): RectF {
    val s = min(vw / bw, vh / bh)
    val w = bw * s
    val h = bh * s
    val l = (vw - w) / 2f
    val t = (vh - h) / 2f
    return RectF(l, t, l + w, t + h)
}

/** Ana ekrandaki kamera kutucuğu: canlı kare + alanlar + tespitler */
class CamTileView(ctx: Context, val channel: Int) : View(ctx) {
    var bitmap: Bitmap? = null
    var dets: List<Det> = emptyList()
    var title = ""
    var status = ""
    var ok = false
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(12).toFloat() }
    private val bar = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(20, 20, 20))
        val b = bitmap
        if (b != null && !b.isRecycled) {
            val r = fitRect(b.width, b.height, width.toFloat(), height.toFloat())
            c.drawBitmap(b, null, r, bmpPaint)
            Draw.zones(c, r, Store.zones.filter { it.channel == channel }, 0.7f)
            Draw.dets(c, r, dets, 0.8f)
        } else {
            c.drawText(if (status.isBlank()) "Bağlanıyor…" else status, context.dp(10).toFloat(), height / 2f, txt)
        }
        val bh = context.dp(20).toFloat()
        c.drawRect(0f, 0f, width.toFloat(), bh, bar)
        dot.color = if (ok) Color.rgb(52, 199, 89) else Color.rgb(255, 59, 48)
        c.drawCircle(context.dp(9).toFloat(), bh / 2, context.dp(4).toFloat(), dot)
        c.drawText(title, context.dp(18).toFloat(), bh - context.dp(6), txt)
    }
}

/** Alan çizme yüzeyi: dokunarak köşe ekleme */
class ZoneCanvasView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var others: List<Zone> = emptyList()
        set(v) { field = v; invalidate() }
    val points = ArrayList<PointF>()

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 229, 255); strokeWidth = ctx.dp(2).toFloat()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(70, 0, 229, 255) }
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = ctx.dp(14).toFloat() }
    private var downX = 0f
    private var downY = 0f

    private fun imgRect(): RectF? {
        val b = bitmap ?: return null
        return fitRect(b.width, b.height, width.toFloat(), height.toFloat())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(20, 20, 20))
        val b = bitmap
        val r = imgRect()
        if (b == null || r == null) {
            c.drawText("Görüntü yükleniyor…", context.dp(12).toFloat(), height / 2f, txt)
            return
        }
        c.drawBitmap(b, null, r, bmpPaint)
        Draw.zones(c, r, others, 0.8f)
        if (points.isNotEmpty()) {
            val path = android.graphics.Path()
            points.forEachIndexed { i, p ->
                val x = r.left + p.x * r.width(); val y = r.top + p.y * r.height()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (points.size >= 3) { path.close(); c.drawPath(path, fill) }
            c.drawPath(path, line)
            for (p in points) {
                c.drawCircle(r.left + p.x * r.width(), r.top + p.y * r.height(), context.dp(5).toFloat(), dotP)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = e.x; downY = e.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                val r = imgRect() ?: return true
                val tol = context.dp(25).toFloat()
                if (Math.abs(e.x - downX) > tol || Math.abs(e.y - downY) > tol) return true
                if (!r.contains(e.x, e.y)) return true
                points.add(PointF((e.x - r.left) / r.width(), (e.y - r.top) / r.height()))
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
