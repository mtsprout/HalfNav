package io.github.mtsprout.halfnav.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import io.github.mtsprout.halfnav.Locations
import io.github.mtsprout.halfnav.TomTomClient
import io.github.mtsprout.halfnav.core.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Type or speak a destination; results come from the same TomTom search as the phone. */
class SearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Place> = emptyList()
    private var searching = false
    private var job: Job? = null

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) {
            job?.cancel()
            if (searchText.trim().length < 3) return
            job = lifecycleScope.launch {
                delay(DEBOUNCE_MS)
                search(searchText, typeahead = true)
            }
        }

        override fun onSearchSubmitted(searchText: String) {
            job?.cancel()
            if (searchText.isBlank()) return
            job = lifecycleScope.launch { search(searchText, typeahead = false) }
        }
    }

    private suspend fun search(q: String, typeahead: Boolean) {
        searching = true
        invalidate()
        try {
            results = TomTomClient.search(q, Locations.lastKnown(carContext), typeahead)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            results = emptyList()
            CarToast.makeText(carContext, "Search failed: ${e.message ?: "network error"}", CarToast.LENGTH_LONG).show()
        }
        searching = false
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Where to?")
            .setShowKeyboardByDefault(true)
        if (searching) {
            b.setLoading(true)
        } else {
            b.setItemList(
                carContext.placeList(results) { place -> screenManager.push(PlanScreen(carContext, place)) }
            )
        }
        return b.build()
    }

    private companion object {
        const val DEBOUNCE_MS = 400L
    }
}
