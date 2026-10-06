package io.github.mtsprout.halfnav

import android.location.Location
import io.github.mtsprout.halfnav.core.ConstructionWarning
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.Handoff
import io.github.mtsprout.halfnav.core.Instruction
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import io.github.mtsprout.halfnav.core.Route
import io.github.mtsprout.halfnav.core.RoutePlanner

/** A trip shown in the drive view. */
data class DriveTrip(
    val destination: Place,
    val route: Route,
    val mode: Mode,
    /** Start modes: where Google's guidance ends. Null if there's no guided part (e.g. after a reroute past it). */
    val handoff: Handoff?,
    val warnings: List<ConstructionWarning>,
    /** End mode: Google Maps takes over within this many miles of the destination. */
    val watchMiles: Float? = null,
) {
    companion object {
        fun fromPlan(plan: TripPlan, mode: Mode) =
            DriveTrip(plan.destination, plan.route, mode, plan.handoff, plan.warnings)

        fun endMode(destination: Place, route: Route, watchMiles: Float) = DriveTrip(
            destination, route, Mode.END_MILES, handoff = null,
            warnings = RoutePlanner.construction(route, wholeRouteHandoff(route)),
            watchMiles = watchMiles,
        )

        /** A stand-in handoff at the destination, for listing construction along the whole route. */
        fun wholeRouteHandoff(route: Route) = Handoff(route.points.last(), route.lengthMeters, "", true)
    }
}

/** Live position along a [DriveTrip]. */
data class DriveProgress(
    val offsetMeters: Double = 0.0,
    /** Where to draw the vehicle: snapped to the route when you're on it. */
    val position: Location? = null,
    val onRoute: Boolean = true,
    val speedMps: Float = 0f,
    val next: Instruction? = null,
    val metersToNext: Double = 0.0,
    val nextWarning: ConstructionWarning? = null,
    val metersToWarning: Double = 0.0,
    val rerouting: Boolean = false,
    val arrived: Boolean = false,
) {
    fun remainingMeters(trip: DriveTrip) = (trip.route.lengthMeters - offsetMeters).coerceAtLeast(0.0)

    /** Remaining drive time, scaled from TomTom's traffic-aware estimate for the whole route. */
    fun remainingSec(trip: DriveTrip): Int =
        if (trip.route.lengthMeters <= 0) 0
        else (trip.route.travelTimeSec * remainingMeters(trip) / trip.route.lengthMeters).toInt()

    /** True while Google Maps is (or should be) guiding this part of the trip. */
    fun guided(trip: DriveTrip): Boolean = when (trip.mode) {
        Mode.END_MILES -> {
            val here = position?.let { LatLng(it.latitude, it.longitude) }
            here != null && trip.watchMiles != null &&
                Geo.metersToMiles(Geo.distanceMeters(here, trip.destination.latLng)) <= trip.watchMiles
        }
        else -> trip.handoff != null && (trip.handoff.reachesDestination || offsetMeters < trip.handoff.offsetMeters)
    }
}

/** Arrow for a TomTom maneuver code. */
fun maneuverGlyph(maneuver: String?): String {
    val m = maneuver ?: return "↑"
    return when {
        m.startsWith("ARRIVE") -> "◉"
        m.contains("UTURN") -> "↶"
        m.contains("ROUNDABOUT") -> "↻"
        m == "TURN_LEFT" || m.contains("SHARP_LEFT") -> "↰"
        m == "TURN_RIGHT" || m.contains("SHARP_RIGHT") -> "↱"
        m.contains("LEFT") -> "↖"
        m.contains("RIGHT") -> "↗"
        else -> "↑"
    }
}

/** "500 ft" up close, then miles. */
fun formatManeuverDistance(meters: Double): String {
    val miles = Geo.metersToMiles(meters)
    return if (miles < 0.19) "${((meters * 3.28084) / 50).toInt().coerceAtLeast(1) * 50} ft" else formatMiles(miles)
}
