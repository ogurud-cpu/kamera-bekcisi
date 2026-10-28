package tr.ogur.kamerabekcisi

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Ev ağında kayıt cihazını bulur: ONVIF yayını + port taraması. */
object Discovery {

    class Found(val ip: String) {
        val ports: MutableSet<Int> = ConcurrentHashMap.newKeySet()
        @Volatile var onvif = false

        fun describe(): String {
            val tags = ArrayList<String>()
            if (554 in ports) tags += "RTSP"
            if (34567 in ports) tags += "XMeye"
            if (37777 in ports) tags += "Dahua"
            if (8000 in ports) tags += "Hikvision"
            if (onvif) tags += "ONVIF"
            return "$ip  (${tags.joinToString(", ")})"
        }

        /** Açık portlara göre önce denenecek adres biçimleri */
        fun preferred(): List<String> {
            val l = ArrayList<String>()
            if (34567 in ports) l += "XMeye tipi"
            if (37777 in ports) l += "Dahua tipi"
            if (8000 in ports) l += "Hikvision tipi"
            return l
        }
    }

    private val PORTS = intArrayOf(554, 34567, 37777, 8000)

    fun localIPv4(): String? {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in ifs) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                }
            }
        } catch (_: Exception) { }
        return null
    }

    fun scan(ctx: Context, log: (String) -> Unit): List<Found> {
        val found = ConcurrentHashMap<String, Found>()
        val me = localIPv4()
        if (me == null) {
            log("Telefon Wi-Fi ağına bağlı görünmüyor.")
            return emptyList()
        }
        log("Telefonun adresi: $me — ağ taranıyor…")

        // 1) ONVIF WS-Discovery
        try {
            onvifProbe(ctx, found)
            if (found.isNotEmpty()) log("ONVIF ile ${found.size} cihaz yanıt verdi.")
        } catch (e: Exception) {
            log("ONVIF araması yapılamadı: ${e.message}")
        }

        // 2) Port taraması (aynı /24 ağ)
        val prefix = me.substringBeforeLast('.') + "."
        val pool = Executors.newFixedThreadPool(48)
        for (host in 1..254) {
            val ip = prefix + host
            if (ip == me) continue
            pool.execute {
                for (p in PORTS) {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, p), 350)
                            found.getOrPut(ip) { Found(ip) }.ports.add(p)
                        }
                    } catch (_: Exception) { }
                }
            }
        }
        pool.shutdown()
        pool.awaitTermination(40, TimeUnit.SECONDS)

        val result = found.values
            .filter { it.ports.isNotEmpty() || it.onvif }
            .sortedByDescending { (if (554 in it.ports) 10 else 0) + it.ports.size + (if (it.onvif) 1 else 0) }
        log(if (result.isEmpty()) "Cihaz bulunamadı. IP adresini elle yazabilirsiniz." else "${result.size} olası cihaz bulundu.")
        return result
    }

    private fun onvifProbe(ctx: Context, found: ConcurrentHashMap<String, Found>) {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("kb-onvif")
        lock.setReferenceCounted(false)
        lock.acquire()
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 700
                val msg = probeXml().toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                s.send(DatagramPacket(msg, msg.size, group, 3702))
                val buf = ByteArray(16384)
                val end = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < end) {
                    try {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        val ip = p.address?.hostAddress ?: continue
                        found.getOrPut(ip) { Found(ip) }.onvif = true
                    } catch (_: SocketTimeoutException) { }
                }
            }
        } finally {
            lock.release()
        }
    }

    private fun probeXml(): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" " +
            "xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" " +
            "xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" " +
            "xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
            "<w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>" +
            "<w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>" +
            "</e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"
}
