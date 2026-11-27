@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package uy.paysandu.omnibus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.domain.Arrival
import uy.paysandu.omnibus.domain.fmtMin
import uy.paysandu.omnibus.domain.hhmm
import uy.paysandu.omnibus.domain.regBand
import uy.paysandu.omnibus.domain.regText

/** Pedido de alerta: el panel lo emite, la Activity se encarga de permisos y del servicio. */
data class AlertRequest(val stopCode: Int, val lt: String, val thresholdMin: Int)

@Composable
fun Panel(state: UiState, vm: MainViewModel, onLocate: () -> Unit, onAlert: (AlertRequest) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        StatusBar(state)
        when (val s = state.screen) {
            Screen.Home -> HomePanel(state, vm, onLocate)
            is Screen.Line -> LinePanel(state, vm, s)
            is Screen.Stop -> StopPanel(state, vm, s.code, onAlert)
            is Screen.Near -> NearPanel(state, vm, s)
            Screen.Accessible -> AccessiblePanel(state, vm)
        }
    }
}

// ---------------------------------------------------------------- estado del feed
@Composable
private fun StatusBar(state: UiState) {
    val (dot, txt) = when (state.mode) {
        FeedMode.LIVE -> Color(0xFF2F7D4F) to "En vivo · ${state.buses.size} coches · actualiza en ${state.countdown} s"
        FeedMode.ERROR -> Color(0xFFC81E1E) to "GPS sin respuesta · reintentando en ${state.countdown} s"
        FeedMode.LOADING -> Color.Gray to "Conectando…"
    }
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF2A0E12)).padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(txt, color = Color.White, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- inicio
@Composable
private fun HomePanel(state: UiState, vm: MainViewModel, onLocate: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Card(
                Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClick = onLocate),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFB3182A)),
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MyLocation, null, tint = Color.White)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Paradas cerca de mí", color = Color.White, fontWeight = FontWeight.Bold)
                        Text("La parada más cercana y cuánto falta para cada ómnibus", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            TextButton(onClick = { vm.accessible() }) { Text("Modo accesible (voz)") }
        }
        item {
            OutlinedTextField(
                value = state.query, onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                placeholder = { Text("¿A dónde querés ir? Hospital, Terminal, una calle…") },
                leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
            )
        }
        val hits = vm.search(state.query)
        if (state.query.isNotBlank()) {
            if (hits.isEmpty()) item { Hint("Sin resultados. Probá con otro nombre o una calle.") }
            items(hits) { h ->
                when (h) {
                    is SearchHit.Place -> Row2(Icons.Default.Place, h.name, h.cat) { vm.openPlace(h) }
                    is SearchHit.StopHit -> Row2(Icons.Default.Place, h.name, h.ref.ifBlank { "Parada" }) { vm.openStop(h.code) }
                }
            }
            return@LazyColumn
        }
        if (state.favs.isNotEmpty()) {
            item { Section("Mis paradas") }
            items(state.favs.toList()) { code ->
                val next = vm.engine.arrivalsAt(code, state.buses, state.nowMin).firstOrNull()
                Row2(Icons.Default.Star, vm.engine.stopName(code),
                    next?.let { "Línea ${it.lt.substringBefore('/')} · ${fmtMin(it.min)}" } ?: "Sin pasadas próximas") { vm.openStop(code) }
            }
        }
        item { Section("O elegí tu línea") }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val visible = vm.data.lines.keys.filter { l ->
                    vm.data.subs.keys.any { it.startsWith("$l/") && vm.engine.isOpen(it, state.buses) }
                }.sorted()
                visible.forEach { l ->
                    val info = vm.data.lines.getValue(l)
                    Card(
                        Modifier.width(172.dp).clickable { vm.openLine(l) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            LineBadge(l, Color(info.color))
                            Spacer(Modifier.width(8.dp))
                            Text("${info.from} · ${info.to}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
            Hint("Datos de posición publicados por COPAY. Los minutos son estimados.")
        }
    }
}

// ---------------------------------------------------------------- línea
@Composable
private fun LinePanel(state: UiState, vm: MainViewModel, s: Screen.Line) {
    val info = vm.data.lines[s.line]
    val color = Color(vm.engine.lineColor(s.line))
    val subs = vm.data.subs.keys.filter { it.startsWith("${s.line}/") && vm.engine.isOpen(it, state.buses) }.sorted()
    val sub = vm.data.subs.getValue(s.lt)
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Header(vm::home) {
                LineBadge(s.line, color)
                Spacer(Modifier.width(8.dp))
                Text(info?.let { "${it.from} – ${it.to}" } ?: sub.name, fontWeight = FontWeight.Bold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                subs.forEach { lt ->
                    FilterChip(selected = lt == s.lt, onClick = { vm.go(Screen.Line(s.line, lt)) },
                        label = { Text("Hacia ${vm.engine.destOf(lt)}", fontSize = 12.sp) })
                }
            }
        }
        val mine = state.buses.filter { it.lt == s.lt }
        if (mine.isNotEmpty()) {
            item { Section("En la calle ahora") }
            items(mine) { b ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Coche ${b.id}", Modifier.width(80.dp), fontWeight = FontWeight.SemiBold)
                    Text("Próxima: ${vm.engine.stopName(b.p2c)}", Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    RegPill(b.reg)
                }
            }
        }
        item { Section("Paradas") }
        items(sub.stops.withIndex().toList().dropLast(1)) { (i, st) ->
            val arr = vm.engine.arrivalsAt(st.code, state.buses, state.nowMin).firstOrNull { it.lt == s.lt && it.idx == i }
            Row(
                Modifier.fillMaxWidth().clickable { vm.openStop(st.code) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(10.dp))
                Text(vm.engine.stopName(st.code), Modifier.weight(1f), fontSize = 14.sp)
                if (arr != null) ArrivalTime(arr)
            }
            HorizontalDivider(thickness = 0.5.dp)
        }
    }
}

// ---------------------------------------------------------------- parada
@Composable
private fun StopPanel(state: UiState, vm: MainViewModel, code: Int, onAlert: (AlertRequest) -> Unit) {
    var ask by remember { mutableStateOf<Arrival?>(null) }
    val arrivals = vm.engine.arrivalsAt(code, state.buses, state.nowMin)
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item {
            Header(vm::home) {
                Column(Modifier.weight(1f)) {
                    Text(vm.engine.stopName(code), fontWeight = FontWeight.Bold)
                    vm.engine.stopRef(code).takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                }
                IconButton(onClick = { vm.toggleFav(code) }) {
                    Icon(if (code in state.favs) Icons.Default.Star else Icons.Default.StarBorder, "Favorita",
                        tint = Color(0xFFE0A800))
                }
            }
        }
        if (arrivals.isEmpty()) item { Hint("No hay ómnibus en los próximos 90 minutos para esta parada.") }
        items(arrivals) { a -> ArrivalRow(vm, a, onBell = { ask = a }) }
    }
    ask?.let { a ->
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("Avisame cuando venga la ${a.lt.substringBefore('/')}") },
            text = { Text("Te mandamos una notificación cuando le falte:") },
            confirmButton = {
                Row {
                    listOf(3, 5, 10).forEach { m ->
                        TextButton(onClick = { onAlert(AlertRequest(code, a.lt, m)); ask = null }) { Text("$m min") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ArrivalRow(vm: MainViewModel, a: Arrival, onBell: (() -> Unit)?) {
    val line = a.lt.substringBefore('/')
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LineBadge(line, Color(vm.engine.lineColor(line)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Hacia ${vm.engine.destOf(a.lt)}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            when (a) {
                is Arrival.Live -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill("en vivo", Color(0xFF2F7D4F)); Spacer(Modifier.width(6.dp)); RegPill(a.bus.reg)
                }
                is Arrival.Scheduled -> Text("Sale ${hhmm(a.at)} según horario", fontSize = 12.sp, color = Color.Gray)
            }
        }
        ArrivalTime(a)
        if (onBell != null) IconButton(onClick = onBell) { Icon(Icons.Default.NotificationsActive, "Avisame") }
    }
    HorizontalDivider(thickness = 0.5.dp)
}

// ---------------------------------------------------------------- cerca
@Composable
private fun NearPanel(state: UiState, vm: MainViewModel, s: Screen.Near) {
    val center: Pt? = s.center
    LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        item { Header(vm::home) { Text(s.title, fontWeight = FontWeight.Bold) } }
        if (center == null) {
            item { Hint(state.locMsg.ifBlank { "Buscando tu ubicación…" }) }
            return@LazyColumn
        }
        val near = vm.engine.nearStops(center, state.buses, state.nowMin)
        if (near.isEmpty()) item { Hint("No hay paradas a menos de 600 m.") }
        near.forEach { n ->
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { vm.openStop(n.stop.code) }.padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.engine.stopName(n.stop.code), Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Text("${(n.distM / 10).toInt() * 10} m", fontSize = 12.sp, color = Color.Gray)
                }
                if (n.arrivals.isEmpty()) Hint("Sin pasadas próximas.")
            }
            items(n.arrivals) { a -> ArrivalRow(vm, a, onBell = null) }
        }
    }
}

// ---------------------------------------------------------------- piezas
@Composable
fun LineBadge(line: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { Text(line, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
}

@Composable
private fun ArrivalTime(a: Arrival) {
    Text(
        fmtMin(a.min),
        fontWeight = FontWeight.Bold,
        color = if (a is Arrival.Live) Color(0xFF2F7D4F) else Color.Gray,
        fontSize = 15.sp,
    )
}

@Composable
private fun RegPill(reg: Int) = Pill(regText(reg), Color(REG_COLOR.getValue(regBand(reg))))

@Composable
private fun Pill(text: String, color: Color) {
    Text(
        text, color = Color.White, fontSize = 11.sp,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color).padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun Header(onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver") }
        content()
    }
}

@Composable
private fun Section(t: String) = Text(
    t.uppercase(), Modifier.padding(top = 16.dp, bottom = 6.dp),
    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB3182A),
)

@Composable
private fun Hint(t: String) = Text(t, Modifier.padding(vertical = 8.dp), fontSize = 12.sp, color = Color.Gray)

@Composable
private fun Row2(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFFB3182A))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(sub, fontSize = 12.sp, color = Color.Gray)
        }
    }
}

@Composable
fun AccessiblePanel(state: UiState, vm: MainViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val tts = remember { android.speech.tts.TextToSpeech(ctx) { } }
    DisposableEffect(Unit) { onDispose { tts.stop(); tts.shutdown() } }
    Column(Modifier.fillMaxWidth().padding(12.dp)) {
        Text("Modo accesible", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { tts.speak("Hay ${state.buses.size} omnibus en linea.", android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts1") }) {
            Text("Hablar estado")
        }
        TextButton(onClick = {
            val lines = vm.engine.d.lines.keys.sorted()
            tts.speak("Lineas disponibles: " + lines.joinToString(", "), android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts2")
        }) { Text("Listar") }
        TextButton(onClick = { vm.home() }) { Text("Volver") }
    }
}
