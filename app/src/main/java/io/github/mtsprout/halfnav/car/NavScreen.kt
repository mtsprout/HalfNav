package io.github.mtsprout.halfnav.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.DriveProgress
import io.github.mtsprout.halfnav.DriveService
import io.github.mtsprout.halfnav.DriveTrip
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.formatManeuverDistance
import io.github.mtsprout.halfnav.maneuverGlyph
import io.github.mtsprout.halfnav.formatMiles
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * Turn instruction and trip summary for the running trip, as a plain pane: the host draws a
 * NavigationTemplate's turn card only over a map, and this screen has none. The trip is still
 * reported to the host through [NavigationManager], which drives its ETA chip and side widget.
 */
class NavScreen(carContext: CarContext) : Screen(carContext) {

    /** Everything the template shows, so it's only rebuilt when the displayed text would change. */
    private data class Ui(
        val glyph: String,
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
        val toNextText: String,
        val remainingText: String,
    )

    private val navigation = carContext.getCarService(NavigationManager::class.java)
    private var ui: Ui? = null
    private var navReady = false

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

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
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
        fun common(glyph: String, cue: String, road: String?, type: Int, toNext: Double, loading: Boolean): Ui =
            Ui(
                glyph = glyph,
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
                toNextText = formatManeuverDistance(toNext),
                remainingText = formatMiles(Geo.metersToMiles(p.remainingMeters(trip))),
            )
        val next = p.next
        return when {
            p.arrived -> common("◉", "You have arrived", trip.destination.name, Maneuver.TYPE_DESTINATION, 0.0, false)
            p.rerouting -> common("↻", "Rerouting…", null, Maneuver.TYPE_STRAIGHT, 0.0, true)
            !guided -> common("•", "You're on your own", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            next == null -> common("↑", "Continue", null, Maneuver.TYPE_STRAIGHT, p.remainingMeters(trip), false)
            else -> common(
                glyph = maneuverGlyph(next.maneuver),
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
        val cue = Row.Builder().setTitle(m.cue)
        m.road?.let { cue.addText(it) }
        val pane = Pane.Builder()
            .addRow(cue.build())
            .addRow(Row.Builder().setTitle("${m.remainingText} to ${m.destinationName}").build())
            .addAction(Action.Builder().setTitle("End").setOnClickListener { endTrip() }.build())
            .build()
        val header = if (m.cue == "You have arrived" || m.loading) m.glyph else "${m.glyph}  ${m.toNextText}"
        return PaneTemplate.Builder(pane).setTitle(header).setHeaderAction(Action.BACK).build()
    }

    private companion object {
        const val TAG = "HalfNavNav"
    }
}
