package tr.ogur.kamerabekcisi

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max

class Alert(val zone: Zone, val det: Det, val trackId: Int, val dwellMs: Long)

/** Kişi takibi + alan kuralları (kamera başına bir tane). */
class CamRules(val channel: Int) {

    private class Track(val id: Int, var group: Int, var det: Det, var lastSeen: Long) {
        var hits = 1
        val enter = HashMap<String, Long>()
        val lastIn = HashMap<String, Long>()
        val alerted = HashSet<String>()
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val zoneLastAlert = HashMap<String, Long>()

    fun update(dets: List<Det>, zones: List<Zone>, now: Long, minuteOfDay: Int): List<Alert> {
        // 1) Eşleştirme
        val used = HashSet<Track>()
        val seen = ArrayList<Track>()
        for (d in dets.sortedByDescending { it.score }) {
            var best: Track? = null
            var bestScore = 0f
            for (t in tracks) {
                if (t in used || t.group != d.group) continue
                val iou = t.det.iou(d)
                val dist = abs(t.det.cx - d.cx) + abs(t.det.y2 - d.y2)
                val near = dist < max(t.det.w, t.det.h) * 0.8f
                val s = if (iou > 0.15f) iou + 1f else if (near) 0.5f else 0f
                if (s > bestScore) { bestScore = s; best = t }
            }
            val t = best
            if (t != null) {
                used.add(t)
                if (now - t.lastSeen < 2500) t.hits++ else t.hits = 1
                t.det = d
                t.lastSeen = now
                seen.add(t)
            } else {
                val nt = Track(nextId++, d.group, d, now)
                tracks.add(nt)
                used.add(nt)
                seen.add(nt)
            }
        }
        tracks.removeAll { now - it.lastSeen > 4000 }

        // 2) Alan kuralları
        val alerts = ArrayList<Alert>()
        val myZones = zones.filter { it.enabled && it.channel == channel && it.points.size >= 3 }
        for (t in seen) {
            val foot = PointF(t.det.cx, t.det.y2)
            for (z in myZones) {
                if (!z.allows(t.group) || !z.activeAt(minuteOfDay)) continue
                val inside = pointInPolygon(foot, z.points)
                if (inside) {
                    t.lastIn[z.id] = now
                    val start = t.enter.getOrPut(z.id) { now }
                    val dwell = now - start
                    val last = zoneLastAlert[z.id] ?: 0L
                    if (dwell >= z.minSeconds * 1000L && t.hits >= 2 &&
                        z.id !in t.alerted && now - last >= 20_000L
                    ) {
                        t.alerted.add(z.id)
                        zoneLastAlert[z.id] = now
                        alerts.add(Alert(z, t.det, t.id, dwell))
                    }
                } else {
                    val li = t.lastIn[z.id]
                    if (li != null) {
                        if (now - li > 2000) t.enter.remove(z.id)
                        if (now - li > 15_000) t.alerted.remove(z.id)
                    }
                }
            }
        }
        return alerts
    }

    companion object {
        fun pointInPolygon(p: PointF, poly: List<PointF>): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val a = poly[i]
                val b = poly[j]
                if ((a.y > p.y) != (b.y > p.y)) {
                    val xCross = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                    if (p.x < xCross) inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}
