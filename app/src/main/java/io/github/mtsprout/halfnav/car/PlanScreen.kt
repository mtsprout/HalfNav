package io.github.mtsprout.halfnav.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.ArrivalWatchService
import io.github.mtsprout.halfnav.DriveService
import io.github.mtsprout.halfnav.DriveTrip
import io.github.mtsprout.halfnav.Mode
import io.github.mtsprout.halfnav.Prefs
import io.github.mtsprout.halfnav.Settings
import io.github.mtsprout.halfnav.TripPlanner
import io.github.mtsprout.halfnav.formatMiles
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.Place
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Plans the trip to [place], shows how it will be guided, and starts it. */
class PlanScreen(carContext: CarContext, private val place: Place) : Screen(carContext) {
    private var working: String? = "Finding your location…"
    private var failure: String? = null
    private var outcome: TripPlanner.Outcome.Ready? = null
    private var endTrip: DriveTrip? = null
    private lateinit var settings: Settings

    init {
        lifecycleScope.launch { Prefs(carContext).addRecent(place) }
        load()
    }

    /** Plans the trip with the saved settings; run again after the mode changes. */
    private fun load() {
        working = "Finding your location…"
        failure = null
        outcome = null
        endTrip = null
        invalidate()
        lifecycleScope.launch {
            settings = Prefs(carContext).settings.first()
            if (ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
                fail("Open HalfNav on your phone once and allow location, then try again.")
                return@launch
            }
            val result = TripPlanner(carContext).plan(place, settings, null) {
                working = it
                invalidate()
            }
            working = null
            when (result) {
                is TripPlanner.Outcome.Failed -> failure = result.message
                is TripPlanner.Outcome.Ready -> outcome = result
                is TripPlanner.Outcome.EndMode -> endTrip = result.trip
                is TripPlanner.Outcome.WatchOnly -> {
                    ArrivalWatchService.start(carContext, result.place, result.miles)
                    CarToast.makeText(carContext, "No route yet. Watching for ${result.place.name}.", CarToast.LENGTH_LONG).show()
                    screenManager.popToRoot()
                    return@launch
                }
            }
            invalidate()
        }
    }

    private fun fail(message: String) {
        working = null
        failure = message
        invalidate()
    }

    override fun onGetTemplate(): Template {
        working?.let {
            return MessageTemplate.Builder(it).setTitle(place.name).setHeaderAction(Action.BACK).setLoading(true).build()
        }
        failure?.let {
            return MessageTemplate.Builder(it).setTitle(place.name).setHeaderAction(Action.BACK).build()
        }

        val pane = Pane.Builder()
        val trip: DriveTrip
        val plan = outcome?.plan
        if (plan != null) {
            trip = DriveTrip.fromPlan(plan, settings.mode)
            val handoff = plan.handoff
            val guidance = when {
                handoff.reachesDestination -> "Guidance all the way"
                handoff.label.startsWith("mile") -> "Guidance for the first ${formatMiles(Geo.metersToMiles(handoff.offsetMeters))}"
                else -> "Guidance to ${handoff.label}, then you're on your own"
            }
            pane.addRow(Row.Builder().setTitle(guidance).build())
        } else {
            trip = endTrip ?: return MessageTemplate.Builder("Nothing to start.").setTitle(place.name).build()
            pane.addRow(
                Row.Builder()
                    .setTitle("Quiet until within ${formatMiles((trip.watchMiles ?: 0f).toDouble())}")
                    .build()
            )
        }
        val minutes = (trip.route.travelTimeSec + 30) / 60
        pane.addRow(
            Row.Builder()
                .setTitle("${formatMiles(Geo.metersToMiles(trip.route.lengthMeters))} · $minutes min")
                .build()
        )
        if (trip.warnings.isNotEmpty()) {
            val n = trip.warnings.size
            pane.addRow(Row.Builder().setTitle("$n construction zone${if (n == 1) "" else "s"} on the way").build())
        }
        pane.addAction(
            Action.Builder()
                .setTitle("Change mode")
                .setOnClickListener {
                    screenManager.pushForResult(ModeScreen(carContext, settings)) { load() }
                }
                .build()
        )
        pane.addAction(
            Action.Builder()
                .setTitle("Start")
                .setOnClickListener {
                    DriveService.start(carContext, trip.copy(inCar = true))
                    screenManager.push(NavScreen(carContext))
                }
                .build()
        )
        return PaneTemplate.Builder(pane.build())
            .setTitle(place.name)
            .setHeaderAction(Action.BACK)
            .build()
    }
}
