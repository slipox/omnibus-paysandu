package uy.paysandu.omnibus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.paysandu.omnibus.data.AvlFeed
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.data.StaticData
import uy.paysandu.omnibus.domain.Arrival
import uy.paysandu.omnibus.domain.EtaEngine
import java.io.File
import kotlin.math.abs

/**
 * Snapshot real del 27/11/2026 09:33: feed + llegadas calculadas por el JS de copay.com.uy/gps.
 * El motor Kotlin tiene que dar exactamente lo mismo.
 */
class EngineParityTest {
    private val data = StaticData.parse(File("src/main/assets/copay_data.json").readText())
    private val fx = JSONObject(javaClass.getResource("/fixture_20261127_0933.json")!!.readText())
    private val eng = EtaEngine(data).apply { setDay(fx.getInt("dow")) }
    private val buses = eng.prep(AvlFeed.parse(fx.getString("xml")))
    private val now = fx.getDouble("now")

    @Test fun feedParse() {
        assertEquals(13, buses.size)
        assertEquals("2", eng.idt)
    }

    @Test fun arrivalsMatchWeb() {
        val res = fx.getJSONObject("res")
        for (code in res.keys()) {
            val exp = res.getJSONArray(code)
            val got = eng.arrivalsAt(code.toInt(), buses, now)
            assertEquals("parada $code", exp.length(), got.size)
            for (i in 0 until exp.length()) {
                val x = exp.getJSONObject(i); val g = got[i]
                assertEquals(x.getString("lt"), g.lt)
                assertEquals(x.getInt("idx"), g.idx)
                assertEquals(x.getString("kind"), if (g is Arrival.Live) "live" else "sch")
                assertTrue("parada $code min ${g.min}", abs(g.min - x.getDouble("min")) < 0.01)
                if (!x.isNull("bus")) assertEquals(x.getString("bus"), (g as Arrival.Live).bus.id)
            }
        }
    }

    @Test fun nearMatchesWeb() {
        val n = fx.getJSONObject("near")
        val me = n.getJSONArray("me").let { Pt(it.getDouble(0), it.getDouble(1)) }
        val got = eng.nearStops(me, buses, now).flatMap { s -> s.arrivals.map { s.stop.code to it.lt } }
        val st = n.getJSONArray("stops")
        val exp = (0 until st.length()).map { st.getJSONArray(it).let { a -> a.getInt(0) to a.getString(1) } }
        assertEquals(exp, got)
    }
}
