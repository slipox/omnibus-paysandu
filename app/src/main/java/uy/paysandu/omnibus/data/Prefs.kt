package uy.paysandu.omnibus.data

import android.content.Context

/** Paradas favoritas, guardadas en el dispositivo. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    fun favStops(): Set<Int> =
        sp.getStringSet(KEY_FAV, emptySet()).orEmpty().mapNotNull { it.toIntOrNull() }.toSet()

    fun toggleFav(code: Int): Set<Int> {
        val cur = favStops().toMutableSet()
        if (!cur.add(code)) cur.remove(code)
        sp.edit().putStringSet(KEY_FAV, cur.map { it.toString() }.toSet()).apply()
        return cur
    }

    private companion object { const val KEY_FAV = "fav_stops" }
}
