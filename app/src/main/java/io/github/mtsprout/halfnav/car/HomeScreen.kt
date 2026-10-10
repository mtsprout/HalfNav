package io.github.mtsprout.halfnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.DriveService
import io.github.mtsprout.halfnav.Prefs
import io.github.mtsprout.halfnav.Settings
import kotlinx.coroutines.launch

/** Start screen: search, Home and recent places. */
class HomeScreen(carContext: CarContext) : Screen(carContext) {
    private var settings: Settings? = null

    init {
        lifecycleScope.launch {
            Prefs(carContext).settings.collect {
                settings = it
                invalidate()
            }
        }
        lifecycleScope.launch { DriveService.trip.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val s = settings ?: return ListTemplate.Builder().setTitle("HalfNav").setLoading(true).build()
        val list = ItemList.Builder()
        DriveService.trip.value?.let { trip ->
            list.addItem(
                Row.Builder()
                    .setTitle("Resume trip")
                    .addText(trip.destination.name)
                    .setOnClickListener { screenManager.push(NavScreen(carContext)) }
                    .build()
            )
        }
        list.addItem(
            Row.Builder()
                .setTitle("Search for a place")
                .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
                .build()
        )
        val places = listOfNotNull(s.home) + s.recent
        val used = if (DriveService.trip.value != null) 2 else 1
        places.take((carContext.listLimit() - used).coerceAtLeast(0)).forEach { place ->
            list.addItem(placeRow(place) { screenManager.push(PlanScreen(carContext, place)) })
        }
        return ListTemplate.Builder()
            .setTitle("HalfNav")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}
