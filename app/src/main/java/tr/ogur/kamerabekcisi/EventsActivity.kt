package tr.ogur.kamerabekcisi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var listBox: LinearLayout
    private val df = SimpleDateFormat("d MMMM EEEE, HH:mm:ss", Locale("tr"))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = page("Olaylar")
        val r = root.row()
        r.btn("Yenile", 1f) { load() }
        r.btn("Tümünü sil", 1f) {
            AlertDialog.Builder(this)
                .setMessage("Tüm olay kayıtları silinsin mi?")
                .setPositiveButton("Sil") { _, _ -> EventLog.clear(this); load() }
                .setNegativeButton("Vazgeç", null)
                .show()
        }
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(MATCH, WRAP))
        load()
    }

    private fun load() {
        listBox.removeAllViews()
        val events = EventLog.list(this, 150)
        if (events.isEmpty()) {
            listBox.text("Henüz olay yok.", 14f, false, Color.GRAY)
            return
        }
        for (e in events) {
            val c = listBox.card()
            val row = c.row()
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val f = EventLog.file(this, e)
            if (f.exists()) {
                val o = BitmapFactory.Options().apply { inSampleSize = 4 }
                iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, o))
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(110), dp(80)))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            col.text("${Groups.name(e.group)} • ${e.zone}", 14f, true)
            col.text(e.cam, 13f, false, Color.DKGRAY)
            col.text(df.format(Date(e.time)), 12f, false, Color.GRAY)
            c.setOnClickListener { showBig(e) }
        }
    }

    private fun showBig(e: EventLog.Event) {
        val f = EventLog.file(this, e)
        if (!f.exists()) return
        val iv = ImageView(this)
        iv.adjustViewBounds = true
        iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
        AlertDialog.Builder(this)
            .setTitle("${e.zone} — ${e.cam}")
            .setView(iv)
            .setPositiveButton("Kapat", null)
            .show()
    }
}
