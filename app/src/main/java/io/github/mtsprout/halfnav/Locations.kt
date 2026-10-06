package io.github.mtsprout.halfnav

import android.annotation.SuppressLint
import android.content.Context
import io.github.mtsprout.halfnav.core.LatLng
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
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

    /** The phone's last known position; instant, but may be a few minutes old. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnown(context: Context): LatLng? = runCatching {
        LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
            ?.let { LatLng(it.latitude, it.longitude) }
    }.getOrNull()
}
