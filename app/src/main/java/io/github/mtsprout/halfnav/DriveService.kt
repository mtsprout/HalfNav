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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.mtsprout.halfnav.core.Arrival
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.RoutePlanner
import io.github.mtsprout.halfnav.core.SpokenText
import io.github.mtsprout.halfnav.core.VoiceInput
import io.github.mtsprout.halfnav.core.VoicePrompts
import io.github.mtsprout.halfnav.core.WorkKind
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs a trip in the background: follows your GPS along the route, reroutes when you leave it,
 * speaks directions, and (in end mode with Google as the voice) hands off to Google Maps when
 * you get close. Keeps working with the screen off; the drive view just displays its state.
 */
class DriveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var voice: VoiceGuide? = null
    private val prompts = VoicePrompts()
    private var settings = Settings()

    private var lastFix: LatLng? = null
    private var lastFixNanos = 0L
    private var lastBearing = 0f
    private var offRouteFixes = 0
    private var lastRerouteAt = 0L
    private var rerouteJob: Job? = null
    private var routeVersion = 0
    private var handedOffToGoogle = false
    /** Ends of every route this trip has used; a reroute can end on a different nearby street. */
    private val routeEnds = mutableListOf<LatLng>()
    private var lastNotificationText: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        voice = VoiceGuide(this)
        scope.launch {
            Prefs(this@DriveService).settings.collectLatest { s ->
                if (s.voiceMuted && !settings.voiceMuted) voice?.silence()
                settings = s
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || _trip.value == null) {
            stop()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        startUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            stop()
            return
        }
        fused.removeLocationUpdates(callback)
        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build(),
            callback,
            Looper.getMainLooper(),
        )
    }

    private fun stop() {
        fused.removeLocationUpdates(callback)
        rerouteJob?.cancel()
        _trip.value = null
        _progress.value = DriveProgress()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        fused.removeLocationUpdates(callback)
        voice?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    // --- Following the route ---

    private fun onLocation(loc: Location) {
        val trip = _trip.value ?: return
        val here = LatLng(loc.latitude, loc.longitude)
        val prev = _progress.value
        val route = trip.route

        val proj = route.project(here, prev.offsetMeters.takeIf { prev.position != null })
        val onRoute = proj.distanceMeters <= SNAP_METERS

        // Heading: the road's direction when we're on it, otherwise the direction we've been moving.
        val moved = lastFix?.let { Geo.distanceMeters(it, here) } ?: Double.MAX_VALUE
        val movingBearing = when {
            loc.hasBearing() && loc.speed > 2f -> loc.bearing
            lastFix != null && moved > MIN_MOVE_METERS -> Geo.bearing(lastFix!!, here).toFloat()
            else -> lastBearing
        }
        // Some GPS fixes carry no speed; estimate it from how far we moved since the last one.
        val now = loc.elapsedRealtimeNanos
        val speed = when {
            loc.hasSpeed() -> loc.speed
            lastFix != null && lastFixNanos > 0 && now > lastFixNanos ->
                (moved / ((now - lastFixNanos) / 1e9)).toFloat().takeIf { it.isFinite() && it < 90f } ?: prev.speedMps
            else -> 0f
        }
        if (moved > MIN_MOVE_METERS || lastFix == null) {
            lastFix = here
            lastFixNanos = now
        }
        val bearing = if (onRoute) proj.bearing.toFloat() else movingBearing
        lastBearing = bearing

        val display = Location(loc).apply {
            if (onRoute) {
                latitude = proj.point.lat
                longitude = proj.point.lng
            }
            this.bearing = bearing
        }
        val offset = if (proj.distanceMeters < OFF_ROUTE_METERS) proj.offsetMeters else prev.offsetMeters
        // The route can end on the nearest road, short of the place itself (e.g. a pedestrian plaza),
        // so reaching the end of the route counts too.
        if (routeEnds.isEmpty()) routeEnds += route.points.last()
        val toDestination = Geo.distanceMeters(here, trip.destination.latLng)
        val arrived = prev.arrived || Arrival.reached(
            destination = trip.destination,
            onRoute = onRoute,
            routeLeftMeters = route.lengthMeters - offset,
            toDestinationMeters = toDestination,
            toRouteEndMeters = routeEnds.minOf { Geo.distanceMeters(here, it) },
            speedMps = speed.toDouble(),
        )
        val next = route.nextInstruction(offset)
        val warning = trip.warnings.firstOrNull { it.startOffsetMeters + it.lengthMeters > offset }

        offRouteFixes = if (proj.distanceMeters > OFF_ROUTE_METERS) offRouteFixes + 1 else 0
        val progress = DriveProgress(
            offsetMeters = offset,
            position = display,
            onRoute = onRoute,
            speedMps = speed,
            next = next?.first,
            metersToNext = next?.let { it.second - offset } ?: 0.0,
            nextWarning = warning,
            metersToWarning = warning?.let { (it.startOffsetMeters - offset).coerceAtLeast(0.0) } ?: 0.0,
            rerouting = prev.rerouting,
            arrived = arrived,
        )
        _progress.value = progress

        val guided = progress.guided(trip)
        if (guided && trip.mode == Mode.END_MILES && settings.voice == VoiceMode.GOOGLE && !handedOffToGoogle) {
            handedOffToGoogle = true
            MapsLauncher.handOff(
                this, trip.destination.latLng,
                "Almost there: starting navigation",
                "Within ${formatMiles(trip.watchMiles?.toDouble() ?: 0.0)} of ${trip.destination.name}. Tap to navigate.",
            )
        }
        speakFor(trip, progress, guided)
        updateNotification(trip, progress, guided)

        // Right by the destination, rerouting just sends you around the block; let you park.
        if (offRouteFixes >= OFF_ROUTE_FIXES && !arrived && toDestination > NO_REROUTE_METERS &&
            System.currentTimeMillis() - lastRerouteAt > REROUTE_INTERVAL_MS
        ) reroute(here)
    }

    private fun speakFor(trip: DriveTrip, progress: DriveProgress, guided: Boolean) {
        // With Google Maps as the voice, Google talks during the guided part; HalfNav stays out of
        // its way and only speaks up (construction, arrival) while you're on your own.
        if (settings.voice == VoiceMode.GOOGLE && guided) return
        val label = trip.handoff?.label?.let { SpokenText.instruction(it) }
        val starts = when {
            trip.mode == Mode.END_MILES ->
                "You're within ${SpokenText.distance((trip.watchMiles ?: 0f) * Geo.METERS_PER_MILE)} of ${trip.destination.name}. Starting guidance."
            trip.handoff?.reachesDestination == true || label == null -> "Starting guidance to ${trip.destination.name}."
            label.startsWith("mile") -> "Starting guidance for the first ${label.removePrefix("mile ")} miles."
            else -> "Starting guidance to $label."
        }
        val ends = if (label != null && !label.startsWith("mile")) "You're on $label. Guidance ends here. You're on your own."
        else "Guidance ends here. You're on your own."
        val w = progress.nextWarning
        val said = prompts.update(
            VoiceInput(
                guided = guided && settings.voice == VoiceMode.HALFNAV,
                next = progress.next,
                metersToNext = progress.metersToNext,
                speedMps = progress.speedMps,
                warningKey = w?.let { (it.startOffsetMeters / 10).toInt() + routeVersion * 1_000_000 },
                warningWhat = w?.let { if (it.kind == WorkKind.ROAD_CLOSURE) "Road closed" else "Road work" },
                warningRoad = w?.road,
                metersToWarning = progress.metersToWarning,
                rerouting = progress.rerouting,
                arrived = progress.arrived,
                destinationName = trip.destination.name,
                guidanceStarts = starts,
                guidanceEnds = ends,
                routeVersion = routeVersion,
            )
        )
        if (said.isNotEmpty() && !settings.voiceMuted) voice?.speak(said)
    }

    /** You left the route: get a fresh one from here so the line and the directions stay useful. */
    private fun reroute(from: LatLng) {
        if (rerouteJob?.isActive == true) return
        val trip = _trip.value ?: return
        lastRerouteAt = System.currentTimeMillis()
        _progress.value = _progress.value.copy(rerouting = true)
        if (!settings.voiceMuted && settings.voice == VoiceMode.HALFNAV && _progress.value.guided(trip)) {
            voice?.speak(listOf("Rerouting."))
        }
        rerouteJob = scope.launch {
            try {
                val route = TomTomClient.route(from, trip.destination.latLng)
                // Keep the old handoff point if we haven't reached it and the new route still passes it.
                val oldHandoff = trip.handoff?.takeIf { _progress.value.guided(trip) }
                val handoff = oldHandoff?.let { h ->
                    if (h.reachesDestination) h.copy(offsetMeters = route.lengthMeters, point = route.points.last())
                    else route.project(h.point).takeIf { it.distanceMeters < 100 }
                        ?.let { h.copy(point = it.point, offsetMeters = it.offsetMeters) }
                }
                routeVersion++
                routeEnds += route.points.last()
                _trip.value = trip.copy(
                    route = route,
                    handoff = handoff,
                    warnings = RoutePlanner.construction(route, handoff ?: DriveTrip.wholeRouteHandoff(route)),
                )
                offRouteFixes = 0
                _progress.value = DriveProgress(position = _progress.value.position)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _progress.value = _progress.value.copy(rerouting = false)
            }
        }
    }

    // --- Notification ---

    private fun updateNotification(trip: DriveTrip, progress: DriveProgress, guided: Boolean) {
        val text = when {
            progress.arrived -> "You've arrived"
            guided && progress.next?.message != null ->
                "${formatManeuverDistance(progress.metersToNext)} · ${progress.next.message}"
            else -> "On your own · ${formatMiles(Geo.metersToMiles(progress.remainingMeters(trip)))} to go"
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val end = PendingIntent.getService(
            this, 11, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_halfnav)
            .setContentTitle("HalfNav · ${_trip.value?.destination?.name.orEmpty()}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setContentIntent(open)
            .addAction(0, "End trip", end)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Trip in progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val ACTION_STOP = "io.github.mtsprout.halfnav.END_TRIP"
        private const val CHANNEL = "drive"
        private const val NOTIFICATION_ID = 3

        /** Within this distance of the route, the vehicle is drawn on the route line. */
        private const val SNAP_METERS = 35.0
        /** Farther than this from the route counts as off-route. */
        private const val OFF_ROUTE_METERS = 80.0
        private const val OFF_ROUTE_FIXES = 3
        private const val REROUTE_INTERVAL_MS = 30_000L
        private const val MIN_MOVE_METERS = 8.0
        /** Within this distance of the destination, don't reroute. */
        private const val NO_REROUTE_METERS = 300.0

        private val _trip = MutableStateFlow<DriveTrip?>(null)
        val trip: StateFlow<DriveTrip?> = _trip

        private val _progress = MutableStateFlow(DriveProgress())
        val progress: StateFlow<DriveProgress> = _progress

        fun start(context: Context, trip: DriveTrip) {
            _trip.value = trip
            _progress.value = DriveProgress()
            // A fresh service instance per trip keeps voice and reroute state from leaking between trips.
            context.stopService(Intent(context, DriveService::class.java))
            ContextCompat.startForegroundService(context, Intent(context, DriveService::class.java))
        }

        fun stop(context: Context) {
            _trip.value = null
            _progress.value = DriveProgress()
            context.stopService(Intent(context, DriveService::class.java))
        }
    }
}
