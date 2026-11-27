package uy.paysandu.omnibus.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import uy.paysandu.omnibus.data.Bus
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.domain.RegBand
import uy.paysandu.omnibus.domain.regBand

private val CENTER = GeoPoint(-32.3170658, -58.0728708)

val REG_COLOR = mapOf(
    RegBand.OK to 0xFF2F7D4F.toInt(),
    RegBand.LATE to 0xFFE08A00.toInt(),
    RegBand.VLATE to 0xFFC81E1E.toInt(),
    RegBand.EARLY to 0xFF2C5FD6.toInt(),
)

@Composable
fun MapPane(
    state: UiState,
    vm: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val icons = remember { BusIcons(ctx) }
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            controller.setZoom(14.0)
            controller.setCenter(CENTER)
        }
    }

    // ciclo de vida del MapView
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> map.onResume()
                Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); map.onDetach() }
    }

    // recorrido seleccionado: encuadrar al cambiar
    val selLt = (state.screen as? Screen.Line)?.lt
    LaunchedEffect(selLt) {
        if (selLt != null) {
            val pts = vm.data.shape(selLt).map { GeoPoint(it.lat, it.lon) }
            if (pts.size > 1) map.post { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.15f), true) }
        }
    }
    LaunchedEffect(state.focus?.id) {
        state.focus?.let { f -> map.controller.animateTo(GeoPoint(f.pt.lat, f.pt.lon), f.zoom, 600L) }
    }

    AndroidView(factory = { map }, modifier = modifier, update = { mv ->
        mv.overlays.clear()

        // 1) recorrido + paradas de la sublínea elegida
        if (selLt != null) {
            val color = vm.engine.lineColor(selLt.substringBefore('/')).toInt()
            mv.overlays.add(Polyline(mv).apply {
                setPoints(vm.data.shape(selLt).map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = color
                outlinePaint.strokeWidth = 10f
                outlinePaint.strokeCap = Paint.Cap.ROUND
            })
            vm.data.subs[selLt]?.stops?.forEach { s ->
                mv.overlays.add(Marker(mv).apply {
                    position = GeoPoint(s.lat, s.lon)
                    icon = icons.stopDot(color)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> vm.openStop(s.code); true }
                })
            }
        }

        // 2) parada abierta
        (state.screen as? Screen.Stop)?.let { st ->
            vm.engine.stopInfo[st.code]?.let { s ->
                mv.overlays.add(Marker(mv).apply {
                    position = GeoPoint(s.lat, s.lon)
                    icon = icons.stopDot(0xFF111111.toInt(), big = true)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    setOnMarkerClickListener { _, _ -> true } // sin globo de info
                })
            }
        }

        // 3) mi ubicación
        state.me?.let { me -> mv.overlays.add(meMarker(mv, icons, me)) }

        // 4) coches (si hay línea elegida, solo los de esa línea)
        val line = (state.screen as? Screen.Line)?.line
        state.buses.filter { line == null || it.lin == line }.forEach { b ->
            mv.overlays.add(Marker(mv).apply {
                position = GeoPoint(b.lat, b.lon)
                icon = icons.bus(b, vm.engine.lineColor(b.lin).toInt())
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> vm.openLine(b.lin); true }
            })
        }
        mv.invalidate()
    })
}

private fun meMarker(mv: MapView, icons: BusIcons, me: Pt) = Marker(mv).apply {
    position = GeoPoint(me.lat, me.lon)
    icon = icons.meDot()
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
    setOnMarkerClickListener { _, _ -> true } // sin globo de info
}

/** Genera y cachea los íconos (mismo diseño que la web: núcleo con el número y anillo con la flecha del rumbo). */
class BusIcons(private val ctx: Context) {
    private val d = ctx.resources.displayMetrics.density
    private val cache = HashMap<String, BitmapDrawable>()

    fun bus(b: Bus, lineColor: Int): BitmapDrawable {
        val band = regBand(b.reg)
        val rum = ((b.rum % 360) + 360) % 360 / 10 * 10 // agrupado de a 10° para el caché
        return cache.getOrPut("b|${b.lin}|$band|$rum|$lineColor") {
            val size = (46 * d).toInt()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val cx = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            // flecha de rumbo (color = puntualidad)
            p.color = REG_COLOR.getValue(band)
            c.save(); c.rotate(rum.toFloat(), cx, cx)
            c.drawPath(Path().apply {
                moveTo(cx, 1 * d); lineTo(cx + 8 * d, 12 * d); lineTo(cx - 8 * d, 12 * d); close()
            }, p)
            c.restore()
            c.drawCircle(cx, cx, 15 * d, p)
            // núcleo con color de línea
            p.color = lineColor
            c.drawCircle(cx, cx, 12 * d, p)
            p.color = 0xFFFFFFFF.toInt()
            p.textSize = 10.5f * d
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            c.drawText(b.lin, cx, cx - (p.descent() + p.ascent()) / 2, p)
            BitmapDrawable(ctx.resources, bmp)
        }
    }

    fun stopDot(color: Int, big: Boolean = false): BitmapDrawable = cache.getOrPut("s|$color|$big") {
        val r = (if (big) 9 else 5) * d
        val size = (r * 2 + 2 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(size / 2f, size / 2f, r, p)
        p.color = color; p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f * d
        c.drawCircle(size / 2f, size / 2f, r - 1.5f * d, p)
        BitmapDrawable(ctx.resources, bmp)
    }

    fun meDot(): BitmapDrawable = cache.getOrPut("me") {
        val size = (22 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x552C5FD6; c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(size / 2f, size / 2f, 7 * d, p)
        p.color = 0xFF2C5FD6.toInt(); c.drawCircle(size / 2f, size / 2f, 5 * d, p)
        BitmapDrawable(ctx.resources, bmp)
    }
}
