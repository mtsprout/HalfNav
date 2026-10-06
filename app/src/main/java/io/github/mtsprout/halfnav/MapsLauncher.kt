package io.github.mtsprout.halfnav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import io.github.mtsprout.halfnav.core.LatLng

object MapsLauncher {
    private const val MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Intent that starts Google Maps turn-by-turn driving navigation to [to]. */
    fun navigationIntent(context: Context, to: LatLng): Intent {
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${to.lat},${to.lng}&mode=d"))
            .setPackage(MAPS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (nav.resolveActivity(context.packageManager) != null) return nav
        // No Google Maps app: a maps URL opens whatever can handle it (another map app or the browser).
        return Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${to.lat},${to.lng}&travelmode=driving&dir_action=navigate"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Hand the rest of the trip to Google Maps from the background: a heads-up notification
     * (full-screen where allowed) that opens navigation, plus opening Maps directly when
     * "Display over other apps" is allowed. Android blocks background apps from opening
     * screens otherwise.
     */
    fun handOff(context: Context, to: LatLng, title: String, text: String) {
        val nav = navigationIntent(context, to)
        val navPending = PendingIntent.getActivity(
            context, 1, nav, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(HANDOFF_CHANNEL, "Arriving soon", NotificationManager.IMPORTANCE_HIGH)
        )
        val builder = NotificationCompat.Builder(context, HANDOFF_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_halfnav)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(navPending)
            .setAutoCancel(true)
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) builder.setFullScreenIntent(navPending, true)
        nm.notify(HANDOFF_NOTIFICATION_ID, builder.build())
        if (Settings.canDrawOverlays(context)) runCatching { context.startActivity(nav) }
    }

    private const val HANDOFF_CHANNEL = "arrival"
    private const val HANDOFF_NOTIFICATION_ID = 2

    fun navigateTo(context: Context, to: LatLng) {
        try {
            context.startActivity(navigationIntent(context, to))
        } catch (e: ActivityNotFoundException) {
            // Nothing on the phone can show directions; stay in HalfNav.
        }
    }
}
