package io.github.mtsprout.halfnav.car

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
import io.github.mtsprout.halfnav.DriveProgress
import io.github.mtsprout.halfnav.DriveService
import io.github.mtsprout.halfnav.DriveTrip
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.TimeZone

/** Turn banner and trip estimate for the running trip. There's no map; the phone has that. */
class NavScreen(carContext: CarContext) : Screen(carContext) {

    /** Everything the template shows, so it's only rebuilt when the displayed text would change. */
    private data class Ui(
        val cue: String,
        val road: String?,
        val maneuver: Int,
        val toNext: Distance,
        val loading: Boolean,
        val remaining: Distance,
        val remainingSec: Int,
        val arrivalMinute: Long,
        val destinationName: String,
        val destinationAddress: String,
    )

    private val navigation = carContext.getCarService(NavigationManager::class.java)
    private var ui: Ui? = null

    init {
        navigation.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = endTrip()
            override fun onAutoDriveEnabled() {}
        })
        navigation.navigationStarted()

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                // The host should stop treating HalfNav as navigating once this screen is gone.
                navigation.clearNavigationManagerCallback()
                navigation.navigationEnded()
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
                    navigation.updateTrip(tripFor(model))
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
        fun common(cue: String, road: String?, type: Int, toNext: Double, loading: Boolean): Ui =
            Ui(
                cue = cue,
                road = road,
                maneuver = type,
                toNext = carDistance(toNext),
                loading = loading,
                remaining = carDistance(p.remainingMeters(trip)),
                // Rounded so the estimate doesn't redraw every second.
                remainingSec = (remainingSec / 30) * 30,
                arrivalMinute = (System.currentTimeMillis() + remainingSec * 1000L) / 60_000L,
                destinationName = trip.destination.name,
                destinationAddress = trip.destination.address,
            )
        val next = p.next
        return when {
            p.arrived -> common("You have arrived", trip.destination.name, Maneuver.TYPE_DESTINATION, 0.0, false)
            p.rerouting -> common("Rerouting…", null, Maneuver.TYPE_STRAIGHT, 0.0, true)
            !guided -> common("You're on your own", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            next == null -> common("Continue", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            else -> common(
                cue = next.message ?: "Continue",
                road = next.street ?: next.roadNumbers.firstOrNull(),
                type = carManeuverType(next.maneuver),
                toNext = p.metersToNext,
                loading = false,
            )
        }
    }

    private fun stepOf(m: Ui): Step {
        val b = Step.Builder(m.cue).setManeuver(Maneuver.Builder(m.maneuver).build())
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
                    .addAction(
                        Action.Builder()
                            .setTitle("End")
                            .setOnClickListener { endTrip() }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
