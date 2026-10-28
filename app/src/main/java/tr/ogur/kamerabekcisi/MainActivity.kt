package tr.ogur.kamerabekcisi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import android.view.View

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var startBtn: Button
    private lateinit var grid: LinearLayout
    private lateinit var logView: TextView
    private lateinit var remoteT: TextView

    private fun panelUrl(): String {
        val ip = Discovery.localIPv4() ?: "CIHAZ-IP"
        return "http://$ip:8080/?k=${Store.webPin}"
    }
    private val tiles = ArrayList<CamTileView>()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        root = page("Kamera Bekçisi")

        status = root.text("", 15f, true)
        startBtn = root.btn("İzlemeyi başlat") { toggle() }

        grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        root.addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
        root.text("Kameraya dokunarak o kameranın alanlarını düzenleyebilirsiniz.", 12f, false, Color.GRAY)

        root.header("Ayarlar")
        val r1 = root.row()
        r1.btn("DVR bağlantısı", 1f) { startActivity(Intent(this, SetupActivity::class.java)) }
        r1.btn("Alanlar", 1f) { startActivity(Intent(this, ZoneActivity::class.java)) }
        val r2 = root.row()
        r2.btn("Telegram", 1f) { startActivity(Intent(this, TelegramActivity::class.java)) }
        r2.btn("Olaylar", 1f) { startActivity(Intent(this, EventsActivity::class.java)) }

        root.text("Hassasiyet", 13f, false, Color.DKGRAY)
        val sp = Spinner(this)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Düşük (daha az yanlış alarm)", "Orta (önerilen)", "Yüksek (uzaktakileri de yakalar)"))
        sp.setSelection(Store.sensitivity)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { Store.sensitivity = pos }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        root.addView(sp, LinearLayout.LayoutParams(MATCH, WRAP))

        root.btn("Pil kısıtlamasını kaldır (sürekli çalışma için)") { askBattery() }

        root.header("Uzaktan izleme")
        remoteT = root.text("", 13f, false, Color.DKGRAY)
        root.btn("Panel adresini kopyala") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("panel", panelUrl()))
            toast("Kopyalandı")
        }

        root.header("Kayıt")
        logView = root.text("", 12f, false, Color.DKGRAY)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        buildGrid()
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    private fun buildGrid() {
        grid.removeAllViews()
        tiles.clear()
        val cams = Store.cams.filter { it.enabled }
        if (!Store.isConfigured() || cams.isEmpty()) {
            grid.card().text("Henüz DVR bağlantısı yapılmadı.\n\n1) 'DVR bağlantısı' ile kayıt cihazını bulun ve test edin.\n" +
                "2) 'Alanlar' ile her kamerada uyarı alanı çizin.\n3) 'Telegram' ile bildirim kanalını bağlayın.\n4) İzlemeyi başlatın.")
            return
        }
        var row: LinearLayout? = null
        cams.forEachIndexed { i, c ->
            if (i % 2 == 0) {
                row = LinearLayout(this).also {
                    it.orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(MATCH, dp(130))
                    lp.topMargin = dp(6)
                    grid.addView(it, lp)
                }
            }
            val t = CamTileView(this, c.channel)
            t.title = c.name
            t.setOnClickListener {
                startActivity(Intent(this, ZoneActivity::class.java).putExtra("channel", c.channel))
            }
            val lp = LinearLayout.LayoutParams(0, MATCH, 1f)
            lp.marginStart = if (i % 2 == 1) dp(6) else 0
            row?.addView(t, lp)
            tiles.add(t)
        }
        if (cams.size % 2 == 1) {
            row?.addView(View(this), LinearLayout.LayoutParams(0, MATCH, 1f).apply { marginStart = dp(6) })
        }
    }

    @SuppressLint("SetTextI18n")
    private fun refresh() {
        val running = Hub.running
        startBtn.text = if (running) "İzlemeyi durdur" else "İzlemeyi başlat"
        if (running) {
            val total = tiles.size
            val up = tiles.count { Hub.cams[it.channel]?.connected == true }
            val ai = Hub.detectorError ?: (if (Hub.inferenceMs > 0) "yapay zeka ${Hub.inferenceMs} ms/kare" else "yapay zeka hazırlanıyor")
            status.text = "🟢 İzleme açık • $up/$total kamera bağlı • $ai"
            status.setTextColor(Color.rgb(27, 94, 32))
        } else {
            status.text = "⚪ İzleme kapalı"
            status.setTextColor(Color.DKGRAY)
        }
        for (t in tiles) {
            val s = Hub.cams[t.channel]
            t.bitmap = s?.bitmap
            t.dets = s?.dets ?: emptyList()
            t.ok = s?.connected == true
            val err = s?.error
            t.status = when {
                !running -> "İzleme kapalı"
                err != null -> err
                else -> "Bağlanıyor…"
            }
            t.invalidate()
        }
        logView.text = Hub.lastLogs(8).asReversed().joinToString("\n")
        val tsIp = tailscaleIp()
        remoteT.text = (if (Hub.running) "" else "(İzleme açıkken çalışır)\n") +
            "• Telegram'dan bota \"foto\" yazın → anlık görüntüler gelir.\n" +
            "• Canlı panel (ev Wi-Fi'ında): ${panelUrl()}\n" +
            (if (tsIp != null) "• Dışarıdan (Tailscale): http://$tsIp:8080/?k=${Store.webPin}"
             else "• Dışarıdan izlemek için bu cihaza ve kendi telefonunuza Tailscale kurup aynı hesapla giriş yapın; adres burada görünecek.")
    }

    private fun tailscaleIp(): String? {
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifs) {
                for (a in ni.inetAddresses) {
                    val h = a.hostAddress ?: continue
                    if (a is java.net.Inet4Address && h.startsWith("100.")) {
                        val second = h.split(".")[1].toIntOrNull() ?: continue
                        if (second in 64..127) return h
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun toggle() {
        if (Hub.running) {
            Store.monitoring = false
            MonitorService.stop(this)
        } else {
            if (!Store.isConfigured()) {
                toast("Önce 'DVR bağlantısı' ekranından kayıt cihazını bağlayın.")
                return
            }
            if (Store.cams.none { it.enabled }) {
                toast("İzlenecek kamera seçilmemiş.")
                return
            }
            if (!Telegram.configured()) toast("Not: Telegram bağlı değil, bildirimler yalnızca bu cihazda görünür.")
            Store.monitoring = true
            MonitorService.start(this)
        }
        handler.postDelayed({ refresh() }, 300)
    }

    @SuppressLint("BatteryLife")
    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Pil kısıtlaması zaten kaldırılmış.")
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
