package uy.paysandu.omnibus.domain

import uy.paysandu.omnibus.data.Bus
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.data.StaticData
import uy.paysandu.omnibus.data.Stop
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.round

/** Distancia aproximada en km (misma fórmula que la web, calibrada para Paysandú). */
fun km(a: Pt, b: Pt) = hypot((a.lat - b.lat) * 111.2, (a.lon - b.lon) * 94.2)

enum class RegBand { OK, LATE, VLATE, EARLY }

fun regBand(r: Int) = when {
    r >= 8 -> RegBand.VLATE
    r >= 3 -> RegBand.LATE
    r <= -2 -> RegBand.EARLY
    else -> RegBand.OK
}

fun regText(r: Int) = when (regBand(r)) {
    RegBand.OK -> "En hora"
    RegBand.EARLY -> "${-r} min adelantado"
    else -> "$r min atrasado"
}

fun fmtMin(m: Double) = if (m < 1) "Llegando" else "${round(m).toInt()} min"

fun hhmm(m: Double): String {
    val t = ((round(m).toInt() % 1440) + 1440) % 1440
    return "%02d:%02d".format(t / 60, t % 60)
}

data class Eta(val min: Double, val km: Double, val how: String) // how: "horario" | "distancia"

sealed class Arrival {
    abstract val lt: String
    abstract val idx: Int
    abstract val min: Double

    data class Live(override val lt: String, override val idx: Int, override val min: Double,
                    val bus: Bus, val eta: Eta, val more: List<Pair<Bus, Eta>>) : Arrival()

    data class Scheduled(override val lt: String, override val idx: Int, override val min: Double,
                         val at: Double) : Arrival()
}

data class NearStop(val stop: Stop, val distM: Double, val arrivals: List<Arrival>)

/**
 * Port del motor de la web de COPAY (copay.com.uy/gps).
 * Sin estado de UI: recibe los coches y la hora actual como parámetros.
 */
class EtaEngine(val d: StaticData) {

    companion object {
        const val BUS_KMH = 18.0
        const val BUS_MAX_KMH = 35.0
        const val MAX_MIN = 90.0
        const val NEAR_M = 600.0
        const val STALE_MIN = 3L
    }

    /** distancias acumuladas por sublínea */
    val cum: Map<String, DoubleArray> = d.subs.mapValues { (_, sub) ->
        val s = sub.stops
        DoubleArray(s.size).also { c -> for (i in 1 until s.size) c[i] = c[i - 1] + km(s[i - 1].pt, s[i].pt) }
    }

    /** parada -> [(sublínea, índice)]; la última parada de cada sublínea no cuenta (ahí no se sube) */
    val stopIx: Map<Int, List<Pair<String, Int>>> = buildMap<Int, MutableList<Pair<String, Int>>> {
        for ((lt, sub) in d.subs) sub.stops.forEachIndexed { i, s ->
            if (i == sub.stops.lastIndex) return@forEachIndexed
            getOrPut(s.code) { mutableListOf() }.add(lt to i)
        }
    }

    val stopInfo: Map<Int, Stop> = buildMap {
        for (sub in d.subs.values) for (s in sub.stops) if (!containsKey(s.code)) put(s.code, s)
    }

    // ---------- día / programación ----------
    var idt = "2"; private set
    private var srv: Map<Int, uy.paysandu.omnibus.data.SchedRow> = emptyMap()

    /** dayOfWeek: 0 = domingo … 6 = sábado (igual que JS Date.getDay()). */
    fun setDay(dayOfWeek: Int) {
        idt = d.idtempPorDia[dayOfWeek] ?: "2"
        val m = HashMap<Int, uy.paysandu.omnibus.data.SchedRow>()
        for ((k, rows) in d.sched) for (r in rows) if (k == idt || !m.containsKey(r.srv)) m[r.srv] = r
        srv = m
    }

    fun isPermanent(lt: String) =
        lt.substringBefore('/') in d.permanentes && !Regex("predio", RegexOption.IGNORE_CASE).containsMatchIn(d.subs[lt]?.name ?: "")

    /** Sublínea visible: permanentes siempre; eventuales solo si hay un coche haciéndola. */
    fun isOpen(lt: String, buses: List<Bus>) = isPermanent(lt) || buses.any { it.lt == lt }

    /** Coches que corresponden a sublíneas conocidas. */
    fun prep(list: List<Bus>) = list.filter { d.subs.containsKey(it.lt) }

    // ---------- tiempos ----------
    fun offsetAt(lt: String, pat: Int, idx: Int): Double {
        val p = d.pat[pat]
        val c = cum.getValue(lt)
        for (k in 0 until p.size - 1) {
            val (i0, m0) = p[k]
            val (i1, m1) = p[k + 1]
            if (idx in i0..i1) {
                val dd = c[i1] - c[i0]
                return if (dd > 0) m0 + (m1 - m0) * (c[idx] - c[i0]) / dd else m0
            }
        }
        val (il, ml) = p.last()
        return if (idx < p.first().first) 0.0 else ml + (c[idx] - c[il]) / BUS_KMH * 60
    }

    /** Índice del coche en su sublínea: próxima parada (p2c); si se repite, la más cercana. */
    fun busIndex(b: Bus): Int {
        val s = d.subs[b.lt]?.stops ?: return -1
        val me = Pt(b.lat, b.lon)
        var best = -1
        var bd = 1e9
        s.forEachIndexed { i, x -> if (x.code == b.p2c) { val dd = km(me, x.pt); if (dd < bd) { bd = dd; best = i } } }
        if (best < 0) s.forEachIndexed { i, x -> val dd = km(me, x.pt); if (dd < bd) { bd = dd; best = i } }
        return best
    }

    /** Minutos hasta que el coche b llegue a la parada idx de su sublínea; null si ya pasó. */
    fun busEta(b: Bus, idx: Int, nowMin: Double): Eta? {
        val bi = busIndex(b)
        if (bi < 0 || bi > idx) return null
        val c = cum.getValue(b.lt)
        val distKm = max(0.0, c[idx] - c[bi] + km(Pt(b.lat, b.lon), d.subs.getValue(b.lt).stops[bi].pt))
        val floor = distKm / BUS_MAX_KMH * 60
        val sch = srv[b.srv]
        if (sch != null && sch.lt == b.lt) {
            var t = sch.dep + offsetAt(b.lt, sch.pat, idx) + b.reg - nowMin
            if (t < -720) t += 1440
            if (t > 720) t -= 1440
            return Eta(max(t, floor), distKm, "horario")
        }
        return Eta(max(distKm / BUS_KMH * 60, floor), distKm, "distancia")
    }

    /** Próxima salida programada (sin coche en la calle) que pasa por la parada idx. */
    fun nextScheduled(lt: String, idx: Int, exclude: Set<Int>, nowMin: Double): Arrival.Scheduled? {
        var best: Arrival.Scheduled? = null
        for (r in d.sched[idt].orEmpty()) {
            if (r.lt != lt || r.srv in exclude) continue
            val t = r.dep + offsetAt(lt, r.pat, idx)
            var dd = t - nowMin
            if (dd < -720) dd += 1440
            if (dd > 0.5 && (best == null || dd < best.min)) best = Arrival.Scheduled(lt, idx, dd, t)
        }
        return best
    }

    /** Todas las llegadas a una parada: la mejor en vivo por sublínea, o la programada. */
    fun arrivalsAt(code: Int, buses: List<Bus>, nowMin: Double): List<Arrival> {
        val out = mutableListOf<Arrival>()
        for ((lt, idx) in stopIx[code].orEmpty()) {
            if (!isOpen(lt, buses)) continue
            val mine = buses.filter { it.lt == lt }
            val live = mine.mapNotNull { b -> busEta(b, idx, nowMin)?.let { b to it } }
                .filter { it.second.min <= MAX_MIN }.sortedBy { it.second.min }
            if (live.isNotEmpty()) {
                out += Arrival.Live(lt, idx, live[0].second.min, live[0].first, live[0].second, live.drop(1))
            } else if (isPermanent(lt)) {
                nextScheduled(lt, idx, mine.map { it.srv }.toSet(), nowMin)
                    ?.takeIf { it.min <= MAX_MIN }?.let { out += it }
            }
        }
        return out.sortedBy { it.min }
    }

    /** Paradas cerca de un punto; cada sublínea aparece solo en la parada más cercana. */
    fun nearStops(me: Pt, buses: List<Bus>, nowMin: Double, radiusM: Double = NEAR_M): List<NearStop> {
        val seen = stopIx.keys.mapNotNull { code ->
            if (stopIx.getValue(code).none { isOpen(it.first, buses) }) return@mapNotNull null
            val s = stopInfo.getValue(code)
            val dm = km(me, s.pt) * 1000
            if (dm <= radiusM) s to dm else null
        }.sortedBy { it.second }
        val used = HashSet<String>()
        val list = seen.mapNotNull { (s, dm) ->
            val arr = arrivalsAt(s.code, buses, nowMin).filter { used.add(it.lt) }
            if (arr.isEmpty()) null else NearStop(s, dm, arr)
        }.take(4)
        // sin llegadas próximas: igual mostramos las 3 paradas más cercanas
        return list.ifEmpty { seen.take(3).map { (s, dm) -> NearStop(s, dm, emptyList()) } }
    }

    // ---------- nombres ----------
    fun stopName(code: Int): String =
        d.names[code]?.first?.takeIf { it.isNotBlank() } ?: nice(stopInfo[code]?.raw.orEmpty())

    fun stopRef(code: Int): String =
        d.names[code]?.let { (n, r) -> if (r.isNotBlank() && r != n) r else "" }.orEmpty()

    fun lineColor(line: String) = d.lines[line]?.color ?: StaticData.OTHER_COLOR

    /** Destino de una sublínea ("101/1" -> "Aduana"). */
    fun destOf(lt: String): String {
        val parts = d.subs.getValue(lt).name.split(Regex("\\s*-\\s*"))
        return nice(parts.last().ifBlank { parts.first() })
    }

    fun nice(s: String): String {
        val lower = s.lowercase()
        val sb = StringBuilder(lower.length)
        for (i in lower.indices) {
            val ch = lower[i]
            val prev = if (i == 0) ' ' else lower[i - 1]
            sb.append(if (i == 0 || prev.isWhitespace() || prev in ".(/-") ch.uppercaseChar() else ch)
        }
        return sb.toString()
    }
}
