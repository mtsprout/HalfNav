package io.github.mtsprout.halfnav

import android.content.Context
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Parking
import io.github.mtsprout.halfnav.core.Place
import io.github.mtsprout.halfnav.core.RoutePlanner

/**
 * Turns a chosen destination into something ready to drive. Shared by the phone screen
 * ([TripViewModel]) and the Android Auto screens, which have no view model.
 */
class TripPlanner(private val context: Context) {

    sealed interface Outcome {
        /** Start modes: a route and where HalfNav's guidance ends. */
        data class Ready(val plan: TripPlan, val here: LatLng?) : Outcome

        /** End mode with a route: drive it, guiding only the last miles. */
        data class EndMode(val trip: DriveTrip, val here: LatLng?) : Outcome

        /** End mode without a route (no signal): just watch the distance and hand off when close. */
        data class WatchOnly(val place: Place, val miles: Float) : Outcome

        /** [fallback] is set when the destination was found but the route couldn't be planned. */
        data class Failed(val message: String, val fallback: Place? = null) : Outcome
    }

    /**
     * Finds the position, checks the route and works out where guidance ends. [onStatus] gets
     * short progress messages. Call only once location permission is granted.
     */
    suspend fun plan(
        place: Place,
        settings: Settings,
        lastHere: LatLng?,
        onStatus: (String) -> Unit = {},
    ): Outcome {
        onStatus("Finding your location…")
        val here = runCatching { Locations.current(context) }.getOrNull() ?: lastHere
        if (here == null && settings.mode != Mode.END_MILES) {
            return Outcome.Failed("Couldn't get your location. Is GPS on?", fallback = place)
        }

        onStatus("Checking the route…")
        // A business's own lot, when TomTom knows it: drive to the lot's entrance.
        val parking = if (place.isBusiness && !Parking.isParking(place)) {
            runCatching { TomTomClient.parkingFor(place) }.getOrNull()
        } else null
        val route = try {
            here?.let { TomTomClient.route(it, parking?.entrance ?: place.latLng) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (settings.mode != Mode.END_MILES) {
                return Outcome.Failed(
                    "Couldn't check the route or construction: ${e.message ?: "network error"}",
                    fallback = place,
                )
            }
            null
        }

        if (settings.mode == Mode.END_MILES) {
            return if (route != null) {
                Outcome.EndMode(DriveTrip.endMode(place, route, settings.endMiles, parking), here)
            } else {
                Outcome.WatchOnly(place, settings.endMiles)
            }
        }

        val r = route ?: return Outcome.Failed("Couldn't find a route.", fallback = place)
        val handoff = when (settings.mode) {
            Mode.START_MILES -> RoutePlanner.pointAtDistance(r, settings.startMiles.toDouble())
            else -> RoutePlanner.interstateHandoff(r)
        }
        return Outcome.Ready(TripPlan(place, r, handoff, RoutePlanner.construction(r, handoff), parking), here)
    }
}
