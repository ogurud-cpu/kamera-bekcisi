package tr.ogur.kamerabekcisi

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.PointF
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.UUID

class ZoneActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var spinner: Spinner
    private lateinit var canvas: ZoneCanvasView
    private lateinit var nameE: EditText
    private lateinit var personC: CheckBox
    private lateinit var vehicleC: CheckBox
    private lateinit var animalC: CheckBox
    private lateinit var allDayC: CheckBox
    private lateinit var startE: EditText
    private lateinit var endE: EditText
    private lateinit var minSecE: EditText
    private lateinit var listBox: LinearLayout
    private lateinit var editInfo: TextView
    private var cams: List<CamConfig> = emptyList()
    private var channel = 1
    private var editingId: String? = null
    @Volatile private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = page("Alanlar ve Kurallar")
        cams = Store.cams.filter { it.enabled }.ifEmpty { Store.cams }
        if (cams.isEmpty()) {
            root.text("Önce 'DVR bağlantısı' ekranından kameraları ekleyin.")
            return
        }
        val wanted = intent.getIntExtra("channel", cams[0].channel)
        channel = if (cams.any { it.channel == wanted }) wanted else cams[0].channel

        root.text("Kamera", 13f, false, Color.DKGRAY)
        spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cams.map { "${it.name} (kanal ${it.channel})" })
        spinner.setSelection(cams.indexOfFirst { it.channel == channel }.coerceAtLeast(0))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val ch = cams[pos].channel
                if (ch != channel) {
                    channel = ch
                    newZone()
                    loadSnapshot()
                    refreshList()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(spinner, LinearLayout.LayoutParams(MATCH, WRAP))

        canvas = ZoneCanvasView(this)
        val lp = LinearLayout.LayoutParams(MATCH, dp(260))
        lp.topMargin = dp(8)
        root.addView(canvas, lp)
        root.text("Görüntüye dokunarak alanın köşelerini sırayla işaretleyin (en az 3 nokta). " +
            "Kişinin AYAĞININ bastığı yer alanın içindeyse uyarı verilir.", 12f, false, Color.DKGRAY)

        val r = root.row()
        r.btn("Son noktayı sil", 1f) { if (canvas.points.isNotEmpty()) { canvas.points.removeAt(canvas.points.size - 1); canvas.invalidate() } }
        r.btn("Temizle", 1f) { canvas.points.clear(); canvas.invalidate() }
        r.btn("Görüntüyü yenile", 1f) { loadSnapshot(force = true) }

        editInfo = root.header("Yeni alan")
        nameE = root.field("Alan adı", "")
        root.text("Hangi durumlarda uyarı verilsin?", 13f, false, Color.DKGRAY)
        val cr = root.row()
        personC = cr.check("İnsan", true, 1f)
        vehicleC = cr.check("Araç", false, 1f)
        animalC = cr.check("Hayvan", false, 1f)
        allDayC = root.check("Tüm gün aktif", true)
        val tr = root.row()
        val c1 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val c2 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tr.addView(c1, LinearLayout.LayoutParams(0, WRAP, 1f))
        tr.addView(c2, LinearLayout.LayoutParams(0, WRAP, 1f))
        startE = c1.field("Başlangıç (SS:DD)", "22:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        endE = c2.field("Bitiş (SS:DD)", "06:00", InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME)
        allDayC.setOnCheckedChangeListener { _, b -> startE.isEnabled = !b; endE.isEnabled = !b }
        startE.isEnabled = false; endE.isEnabled = false
        minSecE = root.field("Alarm için alanda en az kaç saniye kalmalı?", "2", InputType.TYPE_CLASS_NUMBER)

        val sr = root.row()
        sr.btn("Alanı kaydet", 2f) { saveZone() }
        sr.btn("Yeni alan", 1f) { newZone() }

        root.header("Bu kameradaki alanlar")
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listBox, LinearLayout.LayoutParams(MATCH, WRAP))

        newZone()
        loadSnapshot()
        refreshList()
    }

    private fun loadSnapshot(force: Boolean = false) {
        val live = Hub.cams[channel]?.bitmap
        if (live != null && !force) {
            canvas.bitmap = live
            return
        }
        if (Hub.running && live != null) {
            canvas.bitmap = live
            return
        }
        if (loading) return
        loading = true
        canvas.bitmap = null
        val ch = channel
        Thread {
            try {
                val b = Streams.grabOne(Store.url(ch))
                runOnUiThread { if (ch == channel) canvas.bitmap = b }
            } catch (e: Throwable) {
                runOnUiThread { toast("Görüntü alınamadı: ${Streams.explain(e)}") }
            } finally {
                loading = false
            }
        }.start()
    }

    private fun newZone() {
        editingId = null
        editInfo.text = "Yeni alan"
        canvas.points.clear()
        val n = Store.zones.count { it.channel == channel } + 1
        nameE.setText("${Store.camName(channel)} – Alan $n")
        personC.isChecked = true; vehicleC.isChecked = false; animalC.isChecked = false
        allDayC.isChecked = true
        startE.setText("22:00"); endE.setText("06:00")
        minSecE.setText("2")
        canvas.others = Store.zones.filter { it.channel == channel }
    }

    private fun editZone(z: Zone) {
        editingId = z.id
        editInfo.text = "Düzenleniyor: ${z.name}"
        canvas.points.clear()
        canvas.points.addAll(z.points.map { PointF(it.x, it.y) })
        nameE.setText(z.name)
        personC.isChecked = z.person; vehicleC.isChecked = z.vehicle; animalC.isChecked = z.animal
        allDayC.isChecked = z.allDay
        startE.setText(Fmt.hm(z.startMin)); endE.setText(Fmt.hm(z.endMin))
        minSecE.setText(z.minSeconds.toString())
        canvas.others = Store.zones.filter { it.channel == channel && it.id != z.id }
        canvas.invalidate()
    }

    private fun saveZone() {
        if (canvas.points.size < 3) { toast("Alan için en az 3 nokta işaretleyin."); return }
        if (!personC.isChecked && !vehicleC.isChecked && !animalC.isChecked) { toast("En az bir tür seçin (İnsan/Araç/Hayvan)."); return }
        val s = Fmt.parseHm(startE.text.toString())
        val e = Fmt.parseHm(endE.text.toString())
        if (!allDayC.isChecked && (s == null || e == null)) { toast("Saatleri SS:DD biçiminde yazın (ör. 22:00)."); return }
        val old = Store.zones.firstOrNull { it.id == editingId }
        val z = Zone(
            editingId ?: UUID.randomUUID().toString(),
            channel,
            nameE.text.toString().trim().ifBlank { "Alan" },
            canvas.points.map { PointF(it.x, it.y) },
            personC.isChecked, vehicleC.isChecked, animalC.isChecked,
            allDayC.isChecked, s ?: 22 * 60, e ?: 6 * 60,
            (minSecE.text.toString().toIntOrNull() ?: 2).coerceIn(0, 600),
            old?.enabled ?: true
        )
        Store.upsertZone(z)
        toast("Kaydedildi: ${z.name}")
        newZone()
        refreshList()
    }

    private fun refreshList() {
        listBox.removeAllViews()
        val zs = Store.zones.filter { it.channel == channel }
        canvas.others = if (editingId == null) zs else zs.filter { it.id != editingId }
        if (zs.isEmpty()) {
            listBox.text("Henüz alan yok.", 13f, false, Color.GRAY)
            return
        }
        for (z in zs) {
            val c = listBox.card()
            c.text(z.name, 15f, true)
            c.text("${z.classesText()} • ${z.scheduleText()} • en az ${z.minSeconds} sn", 13f, false, Color.DKGRAY)
            val r = c.row()
            val en = r.check("Aktif", z.enabled, 1f)
            en.setOnCheckedChangeListener { _, b ->
                z.enabled = b
                Store.upsertZone(z)
            }
            r.btn("Düzenle", 1f) { editZone(z) }
            r.btn("Sil", 1f) {
                AlertDialog.Builder(this)
                    .setMessage("'${z.name}' silinsin mi?")
                    .setPositiveButton("Sil") { _, _ -> Store.deleteZone(z.id); if (editingId == z.id) newZone(); refreshList() }
                    .setNegativeButton("Vazgeç", null)
                    .show()
            }
        }
    }
}
