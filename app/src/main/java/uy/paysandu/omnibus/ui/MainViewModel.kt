package uy.paysandu.omnibus.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.paysandu.omnibus.data.AvlFeed
import uy.paysandu.omnibus.data.Bus
import uy.paysandu.omnibus.data.Prefs
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.data.Repository
import java.text.Normalizer

sealed interface Screen {
    data object Home : Screen
    data class Line(val line: String, val lt: String) : Screen
    data class Stop(val code: Int) : Screen
    /** Paradas cerca de un punto (mi ubicación o un lugar buscado). */
    data class Near(val center: Pt?, val title: String) : Screen
}

enum class FeedMode { LOADING, LIVE, ERROR }

data class UiState(
    val screen: Screen = Screen.Home,
    val buses: List<Bus> = emptyList(),
    val mode: FeedMode = FeedMode.LOADING,
    val lastOkMs: Long? = null,
    val countdown: Int = AvlFeed.REFRESH_S,
    val nowMin: Double = Repository.nowMin(),
    val me: Pt? = null,
    val locating: Boolean = false,
    val locMsg: String = "",
    val favs: Set<Int> = emptySet(),
    val query: String = "",
    /** Pedido de encuadre del mapa; cambia de id para disparar el movimiento. */
    val focus: Focus? = null,
)

data class Focus(val id: Long, val pt: Pt, val zoom: Double)

sealed interface SearchHit {
    data class Place(val name: String, val cat: String, val pt: Pt) : SearchHit
    data class StopHit(val code: Int, val name: String, val ref: String) : SearchHit
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val engine = Repository.engine
    val data = Repository.data
    private val prefs = Prefs(app)

    private val _s = MutableStateFlow(UiState(favs = prefs.favStops()))
    val state: StateFlow<UiState> = _s.asStateFlow()

    init {
        viewModelScope.launch { pollLoop() }
    }

    private suspend fun pollLoop() {
        while (viewModelScope.isActive) {
            val res = withContext(Dispatchers.IO) { runCatching { AvlFeed.fetch() } }
            engine.setDay(Repository.dayOfWeekJs())
            res.onSuccess { list ->
                _s.update { it.copy(buses = engine.prep(list), mode = FeedMode.LIVE, lastOkMs = System.currentTimeMillis()) }
            }.onFailure { e ->
                Log.w(TAG, "Feed GPS no disponible", e)
                // Si nunca hubo datos, no mostramos coches; si ya había, quedan los últimos (marcados como error)
                _s.update { it.copy(mode = FeedMode.ERROR, buses = if (it.lastOkMs == null) emptyList() else it.buses) }
            }
            for (left in AvlFeed.REFRESH_S downTo 1) {
                _s.update { it.copy(countdown = left, nowMin = Repository.nowMin()) }
                delay(1000)
            }
        }
    }

    // ---------- navegación ----------
    fun go(screen: Screen) = _s.update { it.copy(screen = screen, query = "") }
    fun home() = go(Screen.Home)

    fun openLine(line: String) {
        val lt = data.subs.keys.filter { it.startsWith("$line/") }
            .firstOrNull { engine.isOpen(it, _s.value.buses) } ?: "$line/1"
        go(Screen.Line(line, lt))
    }

    fun openStop(code: Int) {
        go(Screen.Stop(code))
        engine.stopInfo[code]?.let { focusOn(it.pt, 17.0) }
    }

    fun focusOn(pt: Pt, zoom: Double) = _s.update { it.copy(focus = Focus(System.nanoTime(), pt, zoom)) }

    // ---------- ubicación ----------
    fun locating() = _s.update { it.copy(locating = true, locMsg = "Buscando tu ubicación…", screen = Screen.Near(null, "Paradas cerca de mí")) }

    fun located(pt: Pt?) {
        if (pt == null) {
            _s.update { it.copy(locating = false, locMsg = "No pudimos obtener tu ubicación. Revisá el permiso o elegí tu línea.") }
            return
        }
        _s.update { it.copy(me = pt, locating = false, locMsg = "", screen = Screen.Near(pt, "Paradas cerca de mí")) }
        focusOn(pt, 16.0)
    }

    fun locationDenied() = _s.update {
        it.copy(locating = false, locMsg = "Sin permiso de ubicación. Podés buscar un lugar o elegir tu línea.")
    }

    // ---------- favoritos ----------
    fun toggleFav(code: Int) = _s.update { it.copy(favs = prefs.toggleFav(code)) }

    // ---------- búsqueda ----------
    fun setQuery(q: String) = _s.update { it.copy(query = q) }

    fun search(q: String): List<SearchHit> {
        val toks = norm(q).split(' ').filter { it.isNotBlank() }
        if (toks.isEmpty() || norm(q).length < 2) return emptyList()
        fun hit(t: String): Boolean { val h = norm(t); return toks.all { h.contains(it) } }
        val places = data.dests.filter { hit(it.name + " " + it.alias) }
            .map { SearchHit.Place(it.name, it.cat, Pt(it.lat, it.lon)) }
        val stops = engine.stopIx.keys.asSequence()
            .map { SearchHit.StopHit(it, engine.stopName(it), engine.stopRef(it)) }
            .filter { hit(it.name + " " + it.ref) }
            .take(12).toList()
        return (places + stops).take(15)
    }

    fun openPlace(p: SearchHit.Place) {
        go(Screen.Near(p.pt, p.name))
        focusOn(p.pt, 16.0)
    }

    private fun norm(t: String) = Normalizer.normalize(t, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("[^a-z0-9 ]"), " ")

    private companion object { const val TAG = "MainViewModel" }
}
