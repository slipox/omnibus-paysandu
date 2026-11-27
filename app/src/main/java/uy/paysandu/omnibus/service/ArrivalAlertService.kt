package uy.paysandu.omnibus.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uy.paysandu.omnibus.MainActivity
import uy.paysandu.omnibus.R
import uy.paysandu.omnibus.data.AvlFeed
import uy.paysandu.omnibus.data.Repository
import uy.paysandu.omnibus.domain.Arrival
import uy.paysandu.omnibus.domain.fmtMin

/**
 * "Avisame cuando venga": consulta el GPS cada 15 s mientras espera y notifica
 * cuando el coche en vivo de esa sublínea está a [threshold] minutos o menos.
 * Se corta solo a los 90 minutos. Una alerta activa a la vez (una nueva reemplaza la anterior).
 */
class ArrivalAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { stopSelf(); return START_NOT_STICKY }
        val stop = intent?.getIntExtra(EXTRA_STOP, -1) ?: -1
        val lt = intent?.getStringExtra(EXTRA_LT)
        val threshold = intent?.getIntExtra(EXTRA_MIN, 5) ?: 5
        if (stop < 0 || lt == null) { stopSelf(); return START_NOT_STICKY }

        Repository.init(applicationContext)
        ensureChannels()
        val line = lt.substringBefore('/')
        val stopName = Repository.engine.stopName(stop)
        ServiceCompat.startForeground(
            this, ID_ONGOING, ongoing(line, stopName, "Esperando datos del GPS…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

        job?.cancel()
        job = scope.launch {
            val until = System.currentTimeMillis() + MAX_WAIT_MS
            val eng = Repository.engine
            while (isActive && System.currentTimeMillis() < until) {
                runCatching { AvlFeed.fetch() }
                    .onSuccess { list ->
                        eng.setDay(Repository.dayOfWeekJs())
                        val buses = eng.prep(list)
                        val a = eng.arrivalsAt(stop, buses, Repository.nowMin()).firstOrNull { it.lt == lt }
                        if (a is Arrival.Live && a.min <= threshold) {
                            arrived(line, stopName, a.min)
                            stopSelf()
                            return@launch
                        }
                        val txt = when (a) {
                            is Arrival.Live -> "Coche ${a.bus.id} a ${fmtMin(a.min)}"
                            is Arrival.Scheduled -> "Próxima salida en ${fmtMin(a.min)} (según horario)"
                            null -> "Sin pasadas próximas por ahora"
                        }
                        post(ID_ONGOING, ongoing(line, stopName, txt))
                    }
                    .onFailure { Log.w(TAG, "Feed GPS no disponible; reintento", it) }
                delay(AvlFeed.REFRESH_S * 1000L)
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------- notificaciones ----------
    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_WAIT, "Esperando ómnibus", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ARRIVE, "Tu ómnibus llega", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
        })
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ongoing(line: String, stop: String, text: String) =
        NotificationCompat.Builder(this, CH_WAIT)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Línea $line → $stop")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .addAction(0, "Cancelar", PendingIntent.getService(
                this, 1, Intent(this, ArrivalAlertService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            ))
            .build()

    private fun arrived(line: String, stop: String, min: Double) = post(
        ID_ARRIVE,
        NotificationCompat.Builder(this, CH_ARRIVE)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("¡Salí ya! Viene la $line")
            .setContentText("Llega a $stop en ${fmtMin(min)}")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build(),
    )

    private fun post(id: Int, n: android.app.Notification) {
        // el permiso se pide antes de iniciar el servicio; si lo revocaron, no se puede notificar
        if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            getSystemService(NotificationManager::class.java).notify(id, n)
        } else Log.w(TAG, "Notificaciones deshabilitadas; no se pudo avisar")
    }

    companion object {
        private const val TAG = "ArrivalAlert"
        private const val CH_WAIT = "wait"
        private const val CH_ARRIVE = "arrive"
        private const val ID_ONGOING = 10
        private const val ID_ARRIVE = 11
        private const val MAX_WAIT_MS = 90 * 60 * 1000L
        private const val ACTION_CANCEL = "cancel"
        private const val EXTRA_STOP = "stop"
        private const val EXTRA_LT = "lt"
        private const val EXTRA_MIN = "min"

        fun start(ctx: Context, stop: Int, lt: String, thresholdMin: Int) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ArrivalAlertService::class.java)
                .putExtra(EXTRA_STOP, stop).putExtra(EXTRA_LT, lt).putExtra(EXTRA_MIN, thresholdMin))
        }
    }
}
