package io.github.mtsprout.halfnav

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import io.github.mtsprout.halfnav.core.LatLng
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

object Locations {
    private const val FRESH_FIX_TIMEOUT_MS = 10_000L

    /** Current position. Caller must already hold location permission. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): LatLng? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        // Don't wait forever for a fresh fix (garage, weak signal); fall back to the last known one.
        val cancel = CancellationTokenSource()
        val fresh = withTimeoutOrNull(FRESH_FIX_TIMEOUT_MS) {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancel.token).await()
        }
        if (fresh == null) cancel.cancel()
        val fix = fresh ?: client.lastLocation.await()
        return fix?.let { LatLng(it.latitude, it.longitude) }
    }

    /**
     * A position you can pin a house to. The first fix indoors is often from Wi-Fi and a couple
     * hundred feet off, so keep listening to GPS until one is within [goodMeters], or until
     * [timeoutMs], and return the most accurate fix seen. Caller must hold location permission.
     */
    @SuppressLint("MissingPermission")
    suspend fun precise(context: Context, goodMeters: Float = 15f, timeoutMs: Long = 20_000L): LatLng? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        var best: Location? = null
        val good = CompletableDeferred<Unit>()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    if (best == null || l.accuracy < best!!.accuracy) best = l
                }
                if ((best?.accuracy ?: Float.MAX_VALUE) <= goodMeters) good.complete(Unit)
            }
        }
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper()).await()
        try {
            withTimeoutOrNull(timeoutMs) { good.await() }
        } finally {
            client.removeLocationUpdates(callback)
        }
        return (best ?: client.lastLocation.await())?.let { LatLng(it.latitude, it.longitude) }
    }

    /** The phone's last known position; instant, but may be a few minutes old. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(context: Context): LatLng? = runCatching {
        LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            ?.let { LatLng(it.latitude, it.longitude) }
    }.getOrNull()
}
