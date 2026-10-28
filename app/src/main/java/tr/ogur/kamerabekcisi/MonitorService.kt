package tr.ogur.kamerabekcisi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

/** Tek bir kanalın RTSP akışını sürekli okur, son kareyi tutar, koparsa yeniden bağlanır. */
class CameraWorker(val channel: Int, private val url: String, private val transport: String) : Thread("kamera-$channel") {
    @Volatile var running = true
    @Volatile var latest: Bitmap? = null
    @Volatile var latestTime = 0L
    @Volatile var connected = false
    @Volatile var lastOk = SystemClock.elapsedRealtime()
    @Volatile var lastError: String? = null
    private val copyEveryMs = 300L

    override fun run() {
        var backoff = 2000L
        while (running) {
            val g = try { Streams.newGrabber(url, transport, 8) } catch (e: Throwable) {
                lastError = Streams.explain(e); null
            }
            if (g != null) {
                try {
                    g.start()
                    connected = true
                    lastError = null
                    backoff = 2000L
                    var lastCopy = 0L
                    while (running) {
                        val f = g.grabImage() ?: throw RuntimeException("Akış sona erdi")
                        val now = SystemClock.elapsedRealtime()
                        lastOk = now
                        if (now - lastCopy >= copyEveryMs) {
                            val b = Frames.toBitmap(f)
                            if (b != null) {
                                latest = b
                                latestTime = now
                                lastCopy = now
                            }
                        }
                    }
                } catch (e: Throwable) {
                    lastError = Streams.explain(e)
                } finally {
                    connected = false
                    try { g.stop() } catch (_: Throwable) { }
                    try { g.release() } catch (_: Throwable) { }
                }
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
            backoff = min(backoff * 2, 30_000L)
        }
        connected = false
    }

    fun shutdown() {
        running = false
        interrupt()
    }
}

class MonitorService : Service() {

    companion object {
        private const val CH_RUN = "izleme"
        private const val CH_ALARM = "alarm"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "tr.ogur.kamerabekcisi.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MonitorService::class.java))
        }

        @Suppress("DEPRECATION")
        fun builder(ctx: Context, ch: String): Notification.Builder {
            return if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, ch)
            else Notification.Builder(ctx)
        }
    }

    private val workers = ArrayList<CameraWorker>()
    private var analyzer: Thread? = null
    @Volatile private var active = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val rules = HashMap<Int, CamRules>()
    private val downNotified = HashSet<Int>()
    private val tf = SimpleDateFormat("HH:mm:ss", Locale("tr"))
    private var alarmNotifId = 100
    private var web: WebPanel? = null
    private var tgCmd: TelegramCommands? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH_RUN, "İzleme durumu", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Alarmlar", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Store.monitoring = false
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground("Başlatılıyor…")
        if (!active) begin()
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val n = builder(this, CH_RUN)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Kamera Bekçisi")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "Durdur", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String) {
        try { goForeground(text) } catch (_: Throwable) { }
    }

    private fun begin() {
        active = true
        Hub.running = true
        Store.monitoring = true

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kamerabekcisi:izleme").apply {
            setReferenceCounted(false); acquire()
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kamerabekcisi:wifi").apply {
            setReferenceCounted(false); acquire()
        }

        if (!Store.isConfigured()) {
            Hub.log("DVR bağlantısı ayarlanmamış. Önce 'DVR Bağlantısı' ekranını tamamlayın.")
            updateNotif("DVR bağlantısı ayarlanmamış")
            return
        }

        val cams = Store.cams.filter { it.enabled }
        for (c in cams) {
            val w = CameraWorker(c.channel, Store.url(c.channel), Store.transport)
            workers.add(w)
            rules[c.channel] = CamRules(c.channel)
            w.start()
        }
        Hub.log("İzleme başladı: ${cams.size} kamera")
        Telegram.sendText("🟢 Kamera Bekçisi çalışıyor (${cams.size} kamera izleniyor).\nAnlık görüntü için: foto")

        analyzer = Thread({ analyzeLoop() }, "analiz").also { it.start() }
        web = WebPanel(this).also { it.start() }
        tgCmd = TelegramCommands(this).also { it.start() }
    }

    private fun analyzeLoop() {
        var detector: Detector? = null
        try {
            detector = Detector(this)
            Hub.detectorError = null
            Hub.log("Yapay zeka modeli yüklendi (${detector.size}px)")
        } catch (e: Throwable) {
            Hub.detectorError = "Yapay zeka modeli yüklenemedi: ${e.message}"
            Hub.log(Hub.detectorError!!)
        }

        val lastAnalyzed = HashMap<Int, Long>()
        val lastRun = HashMap<Int, Long>()
        var lastHealth = 0L
        val perCamMs = 500L

        while (active) {
            var did = false
            val now0 = SystemClock.elapsedRealtime()
            for (w in workers) {
                if (!active) break
                val st = Hub.cam(w.channel)
                st.connected = w.connected
                st.error = w.lastError
                val bmp = w.latest ?: continue
                val t = w.latestTime
                if (lastAnalyzed[w.channel] == t) continue
                if (now0 - (lastRun[w.channel] ?: 0L) < perCamMs) continue
                lastAnalyzed[w.channel] = t
                lastRun[w.channel] = now0

                var dets: List<Det> = emptyList()
                val d = detector
                if (d != null) {
                    try {
                        val s = SystemClock.elapsedRealtime()
                        dets = d.detect(bmp, Store.confidence())
                        val ms = SystemClock.elapsedRealtime() - s
                        Hub.inferenceMs = (Hub.inferenceMs * 4 + ms) / 5
                    } catch (e: Throwable) {
                        Hub.log("Analiz hatası: ${e.message?.take(80)}")
                    }
                }
                st.bitmap = bmp
                st.dets = dets

                val now = SystemClock.elapsedRealtime()
                val cal = Calendar.getInstance()
                val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                val r = rules.getOrPut(w.channel) { CamRules(w.channel) }
                val alerts = r.update(dets, Store.zones, now, minute)
                for (a in alerts) handleAlert(w.channel, bmp, dets, a)
                did = true
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastHealth > 10_000) {
                lastHealth = now
                checkHealth(now)
            }
            if (!did) {
                try { Thread.sleep(40) } catch (_: InterruptedException) { break }
            }
        }
        try { detector?.close() } catch (_: Throwable) { }
    }

    private fun checkHealth(now: Long) {
        val up = workers.count { it.connected }
        updateNotif("$up/${workers.size} kamera bağlı • ${Store.zones.count { it.enabled }} alan izleniyor")

        val newlyDown = ArrayList<String>()
        val recovered = ArrayList<String>()
        for (w in workers) {
            if (!w.connected && now - w.lastOk > 120_000 && w.channel !in downNotified) {
                downNotified.add(w.channel)
                newlyDown.add(Store.camName(w.channel))
            }
            if (w.connected && w.channel in downNotified) {
                downNotified.remove(w.channel)
                recovered.add(Store.camName(w.channel))
            }
        }
        if (newlyDown.isNotEmpty()) {
            val msg = if (newlyDown.size == workers.size && workers.size > 1)
                "⚠️ Kayıt cihazına ulaşılamıyor — hiçbir kameradan görüntü gelmiyor (2 dk+)."
            else "⚠️ Görüntü gelmiyor (2 dk+): ${newlyDown.joinToString(", ")}"
            Hub.log(msg)
            Telegram.sendText(msg)
        }
        if (recovered.isNotEmpty()) {
            val msg = "✅ Görüntü geri geldi: ${recovered.joinToString(", ")}"
            Hub.log(msg)
            Telegram.sendText(msg)
        }
    }

    private fun handleAlert(channel: Int, bmp: Bitmap, dets: List<Det>, a: Alert) {
        val cam = Store.camName(channel)
        val what = Groups.name(a.det.group)
        val time = tf.format(Date())
        val caption = "🚨 ${a.zone.name} — $cam\n$what algılandı • $time\n(güven %${(a.det.score * 100).toInt()}, alanda ${a.dwellMs / 1000} sn)"
        Hub.log("ALARM: ${a.zone.name} / $cam / $what")
        try {
            val img = Draw.annotate(bmp, a.zone, dets, a.det)
            val f = EventLog.add(this, img, channel, cam, a.zone.name, a.det.group, a.det.score)
            img.recycle()
            Telegram.sendPhoto(f, caption)
        } catch (e: Throwable) {
            Hub.log("Olay kaydedilemedi: ${e.message}")
            Telegram.sendText(caption)
        }
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val open = PendingIntent.getActivity(this, 2, Intent(this, EventsActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            @Suppress("DEPRECATION")
            val n = builder(this, CH_ALARM)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("🚨 ${a.zone.name} — $cam")
                .setContentText("$what algılandı • $time")
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(alarmNotifId++, n)
            if (alarmNotifId > 150) alarmNotifId = 100
        } catch (_: Throwable) { }
    }

    override fun onDestroy() {
        active = false
        Hub.running = false
        for (w in workers) w.shutdown()
        workers.clear()
        analyzer?.interrupt()
        analyzer = null
        web?.shutdown(); web = null
        tgCmd?.shutdown(); tgCmd = null
        try { wakeLock?.release() } catch (_: Throwable) { }
        try { wifiLock?.release() } catch (_: Throwable) { }
        for (s in Hub.cams.values) { s.connected = false }
        Hub.log("İzleme durduruldu")
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Store.init(context)
        if (Store.monitoring && Store.isConfigured()) {
            try { MonitorService.start(context) } catch (_: Throwable) { }
        }
    }
}
