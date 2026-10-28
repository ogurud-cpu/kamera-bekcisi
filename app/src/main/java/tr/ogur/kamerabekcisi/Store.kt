package tr.ogur.kamerabekcisi

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import org.json.JSONArray
import org.json.JSONObject

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
    }
}

class CamConfig(var channel: Int, var name: String, var enabled: Boolean)

class Zone(
    var id: String,
    var channel: Int,
    var name: String,
    var points: List<PointF>,          // 0..1 aralığında, görüntüye göre oransal
    var person: Boolean,
    var vehicle: Boolean,
    var animal: Boolean,
    var allDay: Boolean,
    var startMin: Int,                 // gün içindeki dakika (22:00 -> 1320)
    var endMin: Int,
    var minSeconds: Int,
    var enabled: Boolean
) {
    fun allows(group: Int): Boolean = when (group) {
        Groups.PERSON -> person
        Groups.VEHICLE -> vehicle
        Groups.ANIMAL -> animal
        else -> false
    }

    fun activeAt(minuteOfDay: Int): Boolean {
        if (allDay) return true
        return if (startMin <= endMin) minuteOfDay in startMin until endMin
        else minuteOfDay >= startMin || minuteOfDay < endMin
    }

    fun scheduleText(): String =
        if (allDay) "Tüm gün" else "${Fmt.hm(startMin)}–${Fmt.hm(endMin)}"

    fun classesText(): String {
        val l = mutableListOf<String>()
        if (person) l += "İnsan"
        if (vehicle) l += "Araç"
        if (animal) l += "Hayvan"
        return if (l.isEmpty()) "—" else l.joinToString(", ")
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id); o.put("channel", channel); o.put("name", name)
        val pts = JSONArray()
        for (p in points) pts.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble()))
        o.put("points", pts)
        o.put("person", person); o.put("vehicle", vehicle); o.put("animal", animal)
        o.put("allDay", allDay); o.put("startMin", startMin); o.put("endMin", endMin)
        o.put("minSeconds", minSeconds); o.put("enabled", enabled)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Zone {
            val arr = o.optJSONArray("points") ?: JSONArray()
            val pts = ArrayList<PointF>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONArray(i)
                pts.add(PointF(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()))
            }
            return Zone(
                o.optString("id"), o.optInt("channel", 1), o.optString("name", "Alan"), pts,
                o.optBoolean("person", true), o.optBoolean("vehicle", false), o.optBoolean("animal", false),
                o.optBoolean("allDay", true), o.optInt("startMin", 22 * 60), o.optInt("endMin", 6 * 60),
                o.optInt("minSeconds", 2), o.optBoolean("enabled", true)
            )
        }
    }
}

object Fmt {
    fun hm(min: Int): String = String.format(java.util.Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)

    /** "22:30" -> 1350 ; hatalıysa null */
    fun parseHm(s: String): Int? {
        val p = s.trim().split(":", ".")
        if (p.isEmpty() || p.size > 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        var m = 0
        if (p.size == 2) {
            m = p[1].trim().toIntOrNull() ?: return null
        }
        if (h !in 0..24 || m !in 0..59) return null
        return (h % 24) * 60 + m
    }
}

object Store {
    private lateinit var sp: SharedPreferences

    @Volatile var cams: List<CamConfig> = emptyList()
        private set
    @Volatile var zones: List<Zone> = emptyList()
        private set

    fun init(ctx: Context) {
        if (::sp.isInitialized) return
        sp = ctx.applicationContext.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)
        loadCams()
        loadZones()
    }

    private fun str(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun putStr(k: String, v: String) { sp.edit().putString(k, v).apply() }

    var ip: String
        get() = str("ip")
        set(v) = putStr("ip", v)
    var port: Int
        get() = sp.getInt("port", 554)
        set(v) { sp.edit().putInt("port", v).apply() }
    var user: String
        get() = str("user", "admin")
        set(v) = putStr("user", v)
    var pass: String
        get() = str("pass")
        set(v) = putStr("pass", v)
    var template: String
        get() = str("template")
        set(v) = putStr("template", v)
    var templateLabel: String
        get() = str("templateLabel")
        set(v) = putStr("templateLabel", v)
    var transport: String
        get() = str("transport", "tcp")
        set(v) = putStr("transport", v)
    var tgToken: String
        get() = str("tgToken")
        set(v) = putStr("tgToken", v)
    var tgChat: String
        get() = str("tgChat")
        set(v) = putStr("tgChat", v)
    /** 0 = düşük, 1 = orta, 2 = yüksek hassasiyet */
    var sensitivity: Int
        get() = sp.getInt("sensitivity", 1)
        set(v) { sp.edit().putInt("sensitivity", v).apply() }
    /** Uzaktan izleme paneli erişim kodu */
    val webPin: String
        get() {
            var p = str("webPin")
            if (p.isBlank()) {
                p = (100000 + java.security.SecureRandom().nextInt(900000)).toString()
                putStr("webPin", p)
            }
            return p
        }
    var monitoring: Boolean
        get() = sp.getBoolean("monitoring", false)
        set(v) { sp.edit().putBoolean("monitoring", v).apply() }

    fun confidence(): Float = when (sensitivity) {
        0 -> 0.60f
        2 -> 0.35f
        else -> 0.45f
    }

    fun isConfigured(): Boolean = ip.isNotBlank() && template.isNotBlank()

    fun url(channel: Int): String = Streams.fill(template, ip, port, user, pass, channel)

    fun camName(channel: Int): String = cams.firstOrNull { it.channel == channel }?.name ?: "Kamera $channel"

    // ---- kameralar ----
    private fun loadCams() {
        val list = ArrayList<CamConfig>()
        try {
            val a = JSONArray(str("cams", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(CamConfig(o.getInt("channel"), o.optString("name", "Kamera"), o.optBoolean("enabled", true)))
            }
        } catch (_: Exception) { }
        cams = list
    }

    fun saveCams(list: List<CamConfig>) {
        val a = JSONArray()
        for (c in list) a.put(JSONObject().put("channel", c.channel).put("name", c.name).put("enabled", c.enabled))
        putStr("cams", a.toString())
        cams = list.toList()
    }

    // ---- alanlar ----
    private fun loadZones() {
        val list = ArrayList<Zone>()
        try {
            val a = JSONArray(str("zones", "[]"))
            for (i in 0 until a.length()) list.add(Zone.fromJson(a.getJSONObject(i)))
        } catch (_: Exception) { }
        zones = list
    }

    fun saveZones(list: List<Zone>) {
        val a = JSONArray()
        for (z in list) a.put(z.toJson())
        putStr("zones", a.toString())
        zones = list.toList()
    }

    fun upsertZone(z: Zone) {
        val l = zones.filter { it.id != z.id }.toMutableList()
        l.add(z)
        saveZones(l)
    }

    fun deleteZone(id: String) = saveZones(zones.filter { it.id != id })
}
