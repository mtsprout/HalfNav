package io.github.mtsprout.halfnav.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.BuildConfig
import io.github.mtsprout.halfnav.DriveProgress
import io.github.mtsprout.halfnav.DriveService
import io.github.mtsprout.halfnav.DriveTrip
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.Instruction
import io.github.mtsprout.halfnav.Mode
import io.github.mtsprout.halfnav.arrivalTime
import io.github.mtsprout.halfnav.formatManeuverDistance
import io.github.mtsprout.halfnav.formatMiles
import io.github.mtsprout.halfnav.formatMinutes
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * The running trip: a route map ([CarMap]) with the turn card, ETA and End button the car host
 * draws over it. The trip is also reported through [NavigationManager] for the host's ETA chip
 * and side widget.
 */
class NavScreen(carContext: CarContext) : Screen(carContext) {

    /** Everything the template shows, so it's only rebuilt when the displayed text would change. */
    private data class Ui(
        val cue: String,
        val road: String?,
        val maneuver: Int,
        /** Exit to take at a roundabout; 0 when this isn't one. */
        val roundaboutExit: Int,
        /** Which way that exit leaves, measured from the route: degrees clockwise, 0 = straight on. */
        val roundaboutAngle: Int?,
        /** ETA-only trip: the card shows the arrival time with a clock instead of a maneuver. */
        val etaOnly: Boolean,
        val toNext: Distance,
        val loading: Boolean,
        val remaining: Distance,
        val remainingSec: Int,
        val arrivalMinute: Long,
        val destinationName: String,
        val destinationAddress: String,
        val toNextText: String,
        val remainingText: String,
    )

    private val navigation = carContext.getCarService(NavigationManager::class.java)
    private var ui: Ui? = null
    private var navReady = false
    private val map = CarMap(carContext)

    init {
        // Android Auto can refuse navigation calls (e.g. "not a navigation app"). Don't crash the
        // session over it; the trip screen still works without them.
        navReady = try {
            navigation.setNavigationManagerCallback(object : NavigationManagerCallback {
                override fun onStopNavigation() = endTrip()
                override fun onAutoDriveEnabled() {}
            })
            navigation.navigationStarted()
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Navigation not available; trip estimates won't reach the host", e)
            false
        }

        map.attach()
        lifecycleScope.launch {
            // The map follows every position update; the template below only the text that changes.
            combine(DriveService.trip, DriveService.progress) { trip, progress -> trip to progress }
                .collect { (trip, progress) -> if (trip != null) map.update(trip, progress) }
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                map.detach()
                // The host should stop treating HalfNav as navigating once this screen is gone.
                if (!navReady) return
                runCatching {
                    navigation.clearNavigationManagerCallback()
                    navigation.navigationEnded()
                }
            }
        })

        lifecycleScope.launch {
            combine(DriveService.trip, DriveService.progress) { trip, progress ->
                trip?.let { build(it, progress) }
            }.distinctUntilChanged().collect { model ->
                if (model == null) {
                    // The trip was ended from the phone or its notification.
                    screenManager.popToRoot()
                } else {
                    ui = model
                    if (navReady) runCatching { navigation.updateTrip(tripFor(model)) }
                    invalidate()
                }
            }
        }
    }

    private fun endTrip() {
        DriveService.stop(carContext)
        screenManager.popToRoot()
    }

    private fun build(trip: DriveTrip, p: DriveProgress): Ui {
        val guided = p.guided(trip)
        val remainingSec = p.remainingSec(trip)
        fun common(
            cue: String, road: String?, type: Int, toNext: Double, loading: Boolean,
            exit: Int = 0, exitAngle: Int? = null, etaOnly: Boolean = false,
        ): Ui =
            Ui(
                cue = cue,
                road = road,
                maneuver = type,
                roundaboutExit = exit,
                roundaboutAngle = exitAngle,
                etaOnly = etaOnly,
                toNext = carDistance(toNext),
                loading = loading,
                remaining = carDistance(p.remainingMeters(trip)),
                // Rounded so the estimate doesn't redraw every second.
                remainingSec = (remainingSec / 30) * 30,
                arrivalMinute = (System.currentTimeMillis() + remainingSec * 1000L) / 60_000L,
                destinationName = trip.destination.name,
                destinationAddress = trip.destination.address,
                toNextText = formatManeuverDistance(toNext),
                remainingText = formatMiles(Geo.metersToMiles(p.remainingMeters(trip))),
            )
        val next = p.next
        return when {
            p.arrived -> common("You have arrived", trip.destination.name, Maneuver.TYPE_DESTINATION, 0.0, false)
            p.rerouting -> common("Rerouting…", null, Maneuver.TYPE_STRAIGHT, 0.0, true)
            trip.mode == Mode.ETA_ONLY -> common(
                cue = "ETA - ${arrivalTime(remainingSec)}",
                road = "${formatMinutes(remainingSec)} · ${formatMiles(Geo.metersToMiles(p.remainingMeters(trip)))}",
                type = Maneuver.TYPE_STRAIGHT,
                toNext = p.remainingMeters(trip),
                loading = false,
                etaOnly = true,
            )
            !guided -> common("You're on your own", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            next == null -> common("Continue", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            else -> common(
                cue = next.message ?: "Continue",
                road = next.street ?: next.roadNumbers.firstOrNull(),
                type = carManeuverType(next.maneuver, next.roundaboutExit),
                toNext = p.metersToNext,
                loading = false,
                exit = next.roundaboutExit ?: 0,
                exitAngle = roundaboutExitAngle(trip, next),
            )
        }
    }

    /**
     * Which way the route leaves a roundabout, in degrees clockwise from the way it came in (right
     * is positive), or null if it can't be measured. TomTom follows a roundabout instruction with
     * a "take the exit" one where the route leaves; comparing the road's direction a little before
     * the entry with a little after the exit gives the real turn, however many legs it has.
     */
    private fun roundaboutExitAngle(trip: DriveTrip, next: Instruction): Int? {
        if (next.maneuver?.contains("ROUNDABOUT") != true) return null
        val route = trip.route
        val all = route.instructions
        val leaving = all.getOrNull(all.indexOf(next) + 1)?.takeIf { it.maneuver == "TAKE_EXIT" } ?: return null
        val enterAt = route.offsetOfPoint(next.pointIndex)
        val leaveAt = route.offsetOfPoint(leaving.pointIndex)
        if (leaveAt - enterAt > ROUNDABOUT_MAX_METERS) return null
        val into = Geo.bearing(route.pointAt(enterAt - ROUNDABOUT_SAMPLE_METERS), route.pointAt(enterAt))
        val out = Geo.bearing(route.pointAt(leaveAt), route.pointAt(leaveAt + ROUNDABOUT_SAMPLE_METERS))
        val angle = (((out - into + 540.0) % 360.0) - 180.0).roundToInt()
        if (BuildConfig.DEBUG) Log.d(TAG, "roundabout exit ${next.roundaboutExit}: in ${into.roundToInt()}°, out ${out.roundToInt()}°, turn $angle°")
        return angle
    }

    private fun stepOf(m: Ui): Step {
        val maneuver = Maneuver.Builder(m.maneuver)
            .setIcon(if (m.etaOnly) carClockIcon() else carManeuverIcon(m.maneuver, m.roundaboutExit, m.roundaboutAngle))
        // The host requires an exit number on a roundabout maneuver.
        if (m.maneuver == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) maneuver.setRoundaboutExitNumber(m.roundaboutExit)
        val b = Step.Builder(m.cue).setManeuver(maneuver.build())
        m.road?.let { b.setRoad(it) }
        return b.build()
    }

    private fun estimateOf(m: Ui): TravelEstimate =
        TravelEstimate.Builder(
            m.remaining,
            DateTimeWithZone.create(m.arrivalMinute * 60_000L, TimeZone.getDefault()),
        )
            .setRemainingTimeSeconds(m.remainingSec.toLong())
            .build()

    private fun tripFor(m: Ui): Trip {
        val step = stepOf(m)
        return Trip.Builder()
            .addDestination(
                Destination.Builder().setName(m.destinationName).setAddress(m.destinationAddress).build(),
                estimateOf(m),
            )
            .addStep(step, TravelEstimate.Builder(m.toNext, DateTimeWithZone.create(m.arrivalMinute * 60_000L, TimeZone.getDefault())).build())
            .setCurrentRoad(m.road ?: "")
            .setLoading(m.loading)
            .build()
    }

    override fun onGetTemplate(): Template {
        val m = ui ?: return MessageTemplate.Builder("Starting…").setLoading(true).build()
        val routing = RoutingInfo.Builder()
        if (m.loading) routing.setLoading(true) else routing.setCurrentStep(stepOf(m), m.toNext)

        return NavigationTemplate.Builder()
            .setNavigationInfo(routing.build())
            .setDestinationTravelEstimate(estimateOf(m))
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.Builder().setTitle("End").setOnClickListener { endTrip() }.build())
                    .build()
            )
            .build()
    }

    private companion object {
        const val TAG = "HalfNavNav"

        /** A roundabout's exit cue within this far of its entry belongs to it. */
        const val ROUNDABOUT_MAX_METERS = 400.0

        /** How far before the entry and after the exit to read the road's direction. */
        const val ROUNDABOUT_SAMPLE_METERS = 30.0
    }
}
