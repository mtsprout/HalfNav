package io.github.mtsprout.halfnav

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What end mode is watching for, shown in the app while the service runs. */
data class Watch(val label: String, val destination: LatLng, val triggerMiles: Float, val milesToGo: Double?)

/**
 * End mode: quietly tracks location while you drive, then hands off to Google Maps once you're
 * within the trigger distance of the destination.
 */
class ArrivalWatchService : Service() {

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var destination: LatLng? = null
    private var label = ""
    private var triggerMiles = 2f

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val dest = destination ?: return
            val fix = result.lastLocation ?: return
            // Ignore stale cached fixes so an old position can't trigger guidance early.
            val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - fix.elapsedRealtimeNanos) / 1_000_000
            if (ageMs > MAX_FIX_AGE_MS) return
            val miles = Geo.metersToMiles(Geo.distanceMeters(LatLng(fix.latitude, fix.longitude), dest))
            _watch.value = Watch(label, dest, triggerMiles, miles)
            if (miles <= triggerMiles) arrive(dest) else updateStatus(miles)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lng = intent?.getDoubleExtra(EXTRA_LNG, Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lng.isNaN()) {
            stopSelf()
            return START_NOT_STICKY
        }
        destination = LatLng(lat, lng)
        label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        triggerMiles = intent?.getFloatExtra(EXTRA_MILES, 2f) ?: 2f
        _watch.value = Watch(label, destination!!, triggerMiles, null)

        createChannels()
        ServiceCompat.startForeground(
            this, STATUS_ID, statusNotification("Waiting for a GPS fix…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        startUpdates()
        return START_REDELIVER_INTENT
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            stop()
            return
        }
        fused.removeLocationUpdates(callback)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_MS)
            .setMinUpdateIntervalMillis(UPDATE_MS / 2)
            .build()
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun arrive(dest: LatLng) {
        fused.removeLocationUpdates(callback)
        MapsLauncher.handOff(
            this, dest,
            "Almost there: starting navigation",
            "Within ${formatMiles(triggerMiles.toDouble())} of $label. Tap to navigate.",
        )
        stop()
    }

    private fun stop() {
        fused.removeLocationUpdates(callback)
        _watch.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        _watch.value = null
        super.onDestroy()
    }

    private fun updateStatus(miles: Double) {
        getSystemService(NotificationManager::class.java)
            .notify(STATUS_ID, statusNotification("${formatMiles(miles)} to go. Guidance starts at ${formatMiles(triggerMiles.toDouble())}."))
    }

    private fun statusNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 2, Intent(this, ArrivalWatchService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openApp = PendingIntent.getActivity(
            this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_halfnav)
            .setContentTitle("HalfNav: heading to $label")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "Cancel", stopIntent)
            .build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Trip watching", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val ACTION_STOP = "io.github.mtsprout.halfnav.STOP"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LNG = "lng"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_MILES = "miles"
        private const val CHANNEL_STATUS = "status"
        private const val STATUS_ID = 1
        private const val UPDATE_MS = 30_000L
        private const val MAX_FIX_AGE_MS = 60_000L

        private val _watch = MutableStateFlow<Watch?>(null)
        val watch: StateFlow<Watch?> = _watch

        fun start(context: Context, place: Place, triggerMiles: Float) {
            val intent = Intent(context, ArrivalWatchService::class.java)
                .putExtra(EXTRA_LAT, place.latLng.lat)
                .putExtra(EXTRA_LNG, place.latLng.lng)
                .putExtra(EXTRA_LABEL, place.name)
                .putExtra(EXTRA_MILES, triggerMiles)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ArrivalWatchService::class.java).setAction(ACTION_STOP))
        }
    }
}

fun formatMiles(miles: Double): String =
    if (miles < 10) "%.1f mi".format(miles) else "%.0f mi".format(miles)
