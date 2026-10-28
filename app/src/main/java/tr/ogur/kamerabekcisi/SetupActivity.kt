package tr.ogur.kamerabekcisi

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var ipE: EditText
    private lateinit var portE: EditText
    private lateinit var userE: EditText
    private lateinit var passE: EditText
    private lateinit var customE: EditText
    private lateinit var foundBox: LinearLayout
    private lateinit var logT: TextView
    private lateinit var chBox: LinearLayout
    private lateinit var testBtn: Button
    private lateinit var saveBtn: Button
    @Volatile private var busy = false
    private var preferred: List<String> = emptyList()
    private val logLines = ArrayList<String>()

    private class ChRow(val channel: Int, val check: CheckBox, val name: EditText)
    private val chRows = ArrayList<ChRow>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = page("DVR Bağlantısı")

        root.header("1) Kayıt cihazını bulun")
        root.text("Bu cihaz, kayıt cihazıyla aynı Wi-Fi ağında olmalı.", 13f, false, Color.DKGRAY)
        root.btn("Ağda kayıt cihazını ara") { scan() }
        foundBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(foundBox, LinearLayout.LayoutParams(MATCH, WRAP))

        root.header("2) Bağlantı bilgileri")
        ipE = root.field("Kayıt cihazının IP adresi", Store.ip, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        portE = root.field("RTSP portu", Store.port.toString(), InputType.TYPE_CLASS_NUMBER)
        userE = root.field("Kullanıcı adı (ttec uygulamasındaki)", Store.user)
        passE = root.field("Şifre", Store.pass, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        customE = root.field("Özel RTSP adresi (isteğe bağlı, ileri düzey). {ip} {port} {user} {pass} {ch} kullanılabilir", "")

        root.header("3) Test")
        testBtn = root.btn("Bağlantıyı test et ve kameraları bul") { test() }
        logT = root.text("", 12f, false, Color.DKGRAY)
        if (Store.templateLabel.isNotBlank()) {
            logT.text = "Kayıtlı bağlantı: ${Store.templateLabel} (${Store.transport.uppercase()})"
        }

        root.header("4) Kameralar")
        chBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(chBox, LinearLayout.LayoutParams(MATCH, WRAP))
        saveBtn = root.btn("Kaydet") { save() }
        showSavedCams()
    }

    private fun log(s: String) = runOnUiThread {
        logLines.add(s)
        while (logLines.size > 30) logLines.removeAt(0)
        logT.text = logLines.joinToString("\n")
    }

    private fun stopMonitoringIfRunning() {
        if (Hub.running) {
            MonitorService.stop(this)
            log("İzleme test için durduruldu (kaydettikten sonra yeniden başlatın).")
            try { Thread.sleep(1500) } catch (_: InterruptedException) { }
        }
    }

    private fun scan() {
        if (busy) return
        busy = true
        foundBox.removeAllViews()
        logLines.clear()
        log("Ağ taranıyor, 10–20 sn sürebilir…")
        Thread {
            val list = try { Discovery.scan(this) { log(it) } } catch (e: Exception) { log("Hata: ${e.message}"); emptyList() }
            runOnUiThread {
                busy = false
                for (f in list) {
                    foundBox.btn("📹 " + f.describe()) {
                        ipE.setText(f.ip)
                        preferred = f.preferred()
                        if (34567 in f.ports && 554 !in f.ports) {
                            log("Not: ${f.ip} cihazında RTSP portu (554) kapalı görünüyor. Yine de deneyebilirsiniz.")
                        }
                        toast("${f.ip} seçildi. Kullanıcı adı ve şifreyi girip test edin.")
                    }
                }
                if (list.size == 1) {
                    ipE.setText(list[0].ip)
                    preferred = list[0].preferred()
                }
            }
        }.start()
    }

    private fun test() {
        if (busy) return
        val ip = ipE.text.toString().trim()
        val port = portE.text.toString().trim().toIntOrNull() ?: 554
        val user = userE.text.toString().trim()
        val pass = passE.text.toString()
        val custom = customE.text.toString().trim()
        if (ip.isEmpty()) { toast("IP adresini girin ya da ağda arayın."); return }
        busy = true
        testBtn.isEnabled = false
        logLines.clear()
        chBox.removeAllViews()
        chRows.clear()
        Thread {
            stopMonitoringIfRunning()
            log("$ip:$port deneniyor…")
            val res = Streams.probe(ip, port, user, pass, custom.ifBlank { null }, preferred) { log(it) }
            if (res == null) {
                log("\n❌ Görüntü alınamadı.\nKontrol edin: IP doğru mu, kullanıcı adı/şifre doğru mu? " +
                    "Cihaz menüsünde RTSP kapalı olabilir (Ağ > RTSP / Port ayarları).")
                runOnUiThread { busy = false; testBtn.isEnabled = true }
                return@Thread
            }
            Store.ip = ip; Store.port = port; Store.user = user; Store.pass = pass
            Store.template = res.template
            Store.templateLabel = res.label
            Store.transport = res.transport
            log("\n✔ Bağlantı biçimi bulundu: ${res.label}. Kanallar taranıyor (1–8)…")

            for (ch in 1..8) {
                var bmp: Bitmap? = null
                var err: String? = null
                if (ch == 1) bmp = res.bitmap else {
                    try { bmp = Streams.grabOne(Store.url(ch), res.transport, 6) } catch (e: Throwable) { err = Streams.explain(e) }
                }
                log(if (bmp != null) "Kanal $ch: görüntü var" else "Kanal $ch: yok (${err ?: "-"})")
                val b = bmp
                runOnUiThread { addChannelRow(ch, b) }
            }
            log("\nGörüntü gelen kanallar işaretlendi. İsim verip 'Kaydet'e basın.")
            runOnUiThread { busy = false; testBtn.isEnabled = true }
        }.start()
    }

    private fun addChannelRow(ch: Int, bmp: Bitmap?) {
        val existing = Store.cams.firstOrNull { it.channel == ch }
        val card = chBox.card()
        val row = card.row()
        val iv = ImageView(this)
        iv.setBackgroundColor(Color.rgb(30, 30, 30))
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        if (bmp != null) iv.setImageBitmap(bmp)
        row.addView(iv, LinearLayout.LayoutParams(dp(120), dp(84)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val cb = col.check(if (bmp != null) "Kanal $ch — izle" else "Kanal $ch — görüntü yok", bmp != null)
        val name = EditText(this)
        name.setText(existing?.name ?: "Kamera $ch")
        name.setSingleLine(true)
        col.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
        chRows.add(ChRow(ch, cb, name))
    }

    private fun showSavedCams() {
        if (Store.cams.isEmpty()) return
        for (c in Store.cams.sortedBy { it.channel }) {
            val card = chBox.card()
            val cb = card.check("Kanal ${c.channel} — izle", c.enabled)
            val name = EditText(this)
            name.setText(c.name)
            name.setSingleLine(true)
            card.addView(name, LinearLayout.LayoutParams(MATCH, WRAP))
            chRows.add(ChRow(c.channel, cb, name))
        }
    }

    private fun save() {
        if (busy) { toast("Test bitene kadar bekleyin."); return }
        if (!Store.isConfigured()) { toast("Önce bağlantıyı test edin."); return }
        Store.user = userE.text.toString().trim()
        Store.pass = passE.text.toString()
        Store.port = portE.text.toString().trim().toIntOrNull() ?: 554
        val list = chRows.map {
            CamConfig(it.channel, it.name.text.toString().trim().ifBlank { "Kamera ${it.channel}" }, it.check.isChecked)
        }
        Store.saveCams(list)
        toast("Kaydedildi: ${list.count { it.enabled }} kamera izlenecek.")
        finish()
    }
}
