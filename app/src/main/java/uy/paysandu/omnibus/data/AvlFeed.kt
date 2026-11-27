package uy.paysandu.omnibus.data

import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Coche en vivo, tal como viene en avl.xml. */
data class Bus(
    val id: String,
    val lat: Double,
    val lon: Double,
    val rum: Int,        // rumbo en grados
    val lin: String,
    val tra: String,     // trayecto / sentido
    val sal: String,     // hora de salida programada "HH:mm"
    val srv: Int,        // servicio (clave en SCHED)
    val p2c: Int,        // código de la próxima parada
    val p2n: String,
    val reg: Int,        // regulación en minutos (+ atrasado, − adelantado)
    val reportedAt: LocalDateTime?,
) {
    val lt get() = "$lin/$tra"
}

object AvlFeed {
    const val URL_FEED = "https://bus.copay.com.uy:10443/pub/avl.xml"
    const val REFRESH_S = 15

    private val ISO = Charset.forName("ISO-8859-1")
    private val MARKER = Regex("<marker>(.*?)</marker>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<(\\w+)>([^<]*)</\\1>")
    private val FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")

    /** Descarga y parsea. Lanza excepción si falla (el que llama decide qué mostrar). */
    fun fetch(): List<Bus> {
        val c = URL("$URL_FEED?noCache=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.useCaches = false
            c.setRequestProperty("User-Agent", "OmnibusPaysandu-Android/0.1")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val body = c.inputStream.use { String(it.readBytes(), ISO) }
            return parse(body)
        } finally {
            c.disconnect()
        }
    }

    fun parse(xml: String): List<Bus> {
        if (!xml.contains("<list")) throw IllegalStateException("Respuesta inesperada del GPS")
        return MARKER.findAll(xml).mapNotNull { m ->
            val f = TAG.findAll(m.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2].trim() }
            val lat = f["lat"]?.toDoubleOrNull() ?: return@mapNotNull null
            val lon = f["lon"]?.toDoubleOrNull() ?: return@mapNotNull null
            if (lat == 0.0 || lon == 0.0) return@mapNotNull null
            Bus(
                id = f["id"].orEmpty(),
                lat = lat, lon = lon,
                rum = f["rum"]?.toIntOrNull() ?: 0,
                lin = f["lin"].orEmpty(),
                tra = f["tra"].orEmpty(),
                sal = f["sal"].orEmpty(),
                srv = f["srv"]?.toIntOrNull() ?: 0,
                p2c = f["p2c"]?.toIntOrNull() ?: 0,
                p2n = f["p2n"].orEmpty(),
                reg = f["reg"]?.toIntOrNull() ?: 0,
                reportedAt = runCatching { LocalDateTime.parse("${f["fec"]} ${f["hor"]}", FMT) }.getOrNull(),
            )
        }.toList()
    }
}
