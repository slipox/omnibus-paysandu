package uy.paysandu.omnibus

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import uy.paysandu.omnibus.data.Pt
import uy.paysandu.omnibus.service.ArrivalAlertService
import uy.paysandu.omnibus.ui.AlertRequest
import uy.paysandu.omnibus.ui.MainViewModel
import uy.paysandu.omnibus.ui.MapPane
import uy.paysandu.omnibus.ui.Panel
import uy.paysandu.omnibus.ui.Screen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var pendingAlert: AlertRequest? = null

    private val locPerm = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) fetchLocation() else vm.locationDenied()
    }

    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val req = pendingAlert ?: return@registerForActivityResult
        pendingAlert = null
        if (ok) startAlert(req)
        else Toast.makeText(this, "Sin permiso de notificaciones no podemos avisarte.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFB3182A))) {
                val state by vm.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.screen != Screen.Home) { vm.home() }
                Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                    Column(Modifier.fillMaxSize()) {
                        MapPane(state, vm, Modifier.fillMaxWidth().weight(0.48f))
                        Surface(Modifier.fillMaxWidth().weight(0.52f), shadowElevation = 6.dp) {
                            Panel(state, vm, onLocate = ::locate, onAlert = ::requestAlert)
                        }
                    }
                }
            }
        }
    }

    // ---------- ubicación ----------
    private fun locate() {
        vm.locating()
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        if (granted(fine) || granted(coarse)) fetchLocation() else locPerm.launch(arrayOf(fine, coarse))
    }

    @SuppressLint("MissingPermission") // se verifica antes de llamar
    private fun fetchLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) && granted(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> { vm.located(null); return }
        }
        // respuesta rápida con la última conocida (si es reciente), y después la actual
        lm.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time < 60_000 }
            ?.let { vm.located(Pt(it.latitude, it.longitude)) }
        LocationManagerCompat.getCurrentLocation(
            lm, provider, null as android.os.CancellationSignal?, ContextCompat.getMainExecutor(this),
        ) { loc ->
            if (loc != null) vm.located(Pt(loc.latitude, loc.longitude))
            else if (vm.state.value.me == null) vm.located(null)
        }
    }

    // ---------- alertas ----------
    private fun requestAlert(req: AlertRequest) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            pendingAlert = req
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startAlert(req)
    }

    private fun startAlert(req: AlertRequest) {
        ArrivalAlertService.start(this, req.stopCode, req.lt, req.thresholdMin)
        Toast.makeText(this, "Listo: te avisamos cuando falten ${req.thresholdMin} min.", Toast.LENGTH_SHORT).show()
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
