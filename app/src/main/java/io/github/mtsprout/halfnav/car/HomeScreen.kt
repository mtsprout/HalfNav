package io.github.mtsprout.halfnav.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
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
    }

    override fun onGetTemplate(): Template {
        val s = settings ?: return ListTemplate.Builder().setTitle("HalfNav").setLoading(true).build()
        val list = ItemList.Builder()
        list.addItem(
            Row.Builder()
                .setTitle("Search for a place")
                .setOnClickListener { screenManager.push(SearchScreen(carContext)) }
                .build()
        )
        val places = listOfNotNull(s.home) + s.recent
        places.take((carContext.listLimit() - 1).coerceAtLeast(0)).forEach { place ->
            list.addItem(placeRow(place) { screenManager.push(PlanScreen(carContext, place)) })
        }
        return ListTemplate.Builder()
            .setTitle("HalfNav")
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(list.build())
            .build()
    }
}
