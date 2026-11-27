package uy.paysandu.omnibus.data

import android.content.Context
import uy.paysandu.omnibus.domain.EtaEngine
import java.time.LocalDateTime

/** Datos estáticos + motor, cargados una sola vez desde assets. */
object Repository {
    lateinit var data: StaticData; private set
    lateinit var engine: EtaEngine; private set

    fun init(ctx: Context) {
        if (::data.isInitialized) return
        val json = ctx.assets.open("copay_data.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        data = StaticData.parse(json)
        engine = EtaEngine(data)
        engine.setDay(dayOfWeekJs())
    }

    /** 0 = domingo … 6 = sábado, igual que Date.getDay() de JS. */
    fun dayOfWeekJs(t: LocalDateTime = LocalDateTime.now()) = t.dayOfWeek.value % 7

    /** Minutos desde medianoche, con fracción por segundos. */
    fun nowMin(t: LocalDateTime = LocalDateTime.now()) = t.hour * 60 + t.minute + t.second / 60.0
}
