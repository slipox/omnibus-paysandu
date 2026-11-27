package uy.paysandu.omnibus.data

import org.json.JSONArray
import org.json.JSONObject

/** Punto geográfico. */
data class Pt(val lat: Double, val lon: Double)

/** Parada dentro de un recorrido. type: 0 normal, 1 punto de control, 2 extremo. */
data class Stop(val code: Int, val raw: String, val lat: Double, val lon: Double, val type: Int) {
    val pt get() = Pt(lat, lon)
}

/** Sublínea = línea/trayecto ("101/1"). */
data class Sub(val key: String, val name: String, val stops: List<Stop>) {
    val line get() = key.substringBefore('/')
}

data class LineInfo(val id: String, val from: String, val to: String, val color: Long)

/** [servicio, sublínea, salida en minutos del día, índice de patrón] */
data class SchedRow(val srv: Int, val lt: String, val dep: Double, val pat: Int)

data class Dest(val name: String, val alias: String, val cat: String, val lat: Double, val lon: Double)

class StaticData(
    val lines: Map<String, LineInfo>,
    val permanentes: Set<String>,
    val idtempPorDia: Map<Int, String>,
    val subs: Map<String, Sub>,
    val pat: List<List<Pair<Int, Double>>>,
    val sched: Map<String, List<SchedRow>>,
    val trace: Map<String, List<Pt>>,
    val names: Map<Int, Pair<String, String>>,
    val dests: List<Dest>,
) {
    fun shape(lt: String): List<Pt> = trace[lt] ?: subs.getValue(lt).stops.map { it.pt }

    companion object {
        const val OTHER_COLOR = 0xFF57534EL

        fun parse(json: String): StaticData {
            val o = JSONObject(json)

            val lines = o.getJSONObject("LINES").let { L ->
                L.keys().asSequence().associateWith { k ->
                    val a = L.getJSONArray(k)
                    LineInfo(k, a.getString(0), a.getString(1), parseColor(a.getString(2)))
                }
            }
            val perm = o.getJSONArray("PERMANENTES").strings().toSet()
            val idt = o.getJSONObject("IDTEMP_POR_DIA").let { I ->
                I.keys().asSequence().associate { it.toInt() to I.getString(it) }
            }
            val subs = o.getJSONObject("SUB").let { S ->
                S.keys().asSequence().associateWith { k ->
                    val so = S.getJSONObject(k)
                    val arr = so.getJSONArray("s")
                    Sub(k, so.getString("n"), (0 until arr.length()).map { i ->
                        val s = arr.getJSONArray(i)
                        Stop(s.getInt(0), s.getString(1), s.getDouble(2), s.getDouble(3), s.optInt(4, 0))
                    })
                }
            }
            val pat = o.getJSONArray("PAT").let { P ->
                (0 until P.length()).map { i ->
                    val p = P.getJSONArray(i)
                    (0 until p.length()).map { j -> p.getJSONArray(j).let { it.getInt(0) to it.getDouble(1) } }
                }
            }
            val sched = o.getJSONObject("SCHED").let { S ->
                S.keys().asSequence().associateWith { k ->
                    val a = S.getJSONArray(k)
                    (0 until a.length()).map { i ->
                        val r = a.getJSONArray(i)
                        SchedRow(r.getInt(0), r.getString(1), r.getDouble(2), r.getInt(3))
                    }
                }
            }
            val trace = o.getJSONObject("TRACE").let { T ->
                T.keys().asSequence().associateWith { k ->
                    val a = T.getJSONArray(k)
                    (0 until a.length()).map { i -> a.getJSONArray(i).let { Pt(it.getDouble(0), it.getDouble(1)) } }
                }
            }
            val names = o.getJSONObject("NAMES").let { N ->
                N.keys().asSequence().associate { k ->
                    val a = N.getJSONArray(k)
                    k.toInt() to (a.optString(0, "") to a.optString(1, ""))
                }
            }
            val dests = o.getJSONArray("DEST").let { D ->
                (0 until D.length()).map { i ->
                    val d = D.getJSONArray(i)
                    Dest(d.getString(0), d.optString(1, ""), d.optString(2, ""), d.getDouble(3), d.getDouble(4))
                }
            }
            return StaticData(lines, perm, idt, subs, pat, sched, trace, names, dests)
        }

        private fun JSONArray.strings() = (0 until length()).map { getString(it) }

        private fun parseColor(hex: String): Long =
            0xFF000000L or hex.removePrefix("#").toLong(16)
    }
}
