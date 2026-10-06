package io.github.mtsprout.halfnav

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mtsprout.halfnav.core.ConstructionWarning
import io.github.mtsprout.halfnav.core.Handoff
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import io.github.mtsprout.halfnav.core.Route
import io.github.mtsprout.halfnav.core.RoutePlanner
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TripPlan(
    val destination: Place,
    val route: Route,
    val handoff: Handoff,
    val warnings: List<ConstructionWarning>,
)

sealed interface UiState {
    data object Idle : UiState
    data class Working(val message: String) : UiState
    data class Review(val plan: TripPlan) : UiState
    /** [fallback] is set when we found the destination but couldn't plan the partial route. */
    data class Error(val message: String, val fallback: Place? = null) : UiState
}

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

class TripViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    val prefs = Prefs(app)

    val settings: StateFlow<Settings> =
        prefs.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search

    private val _selected = MutableStateFlow<Place?>(null)
    val selected: StateFlow<Place?> = _selected

    private val _here = MutableStateFlow<LatLng?>(null)
    val here: StateFlow<LatLng?> = _here

    private var searchJob: Job? = null

    /** Call once location permission is granted. */
    fun refreshLocation() {
        viewModelScope.launch {
            runCatching { Locations.current(context) }.getOrNull()?.let { _here.value = it }
        }
    }

    // --- Search ---

    fun onQueryChange(q: String) {
        _search.value = _search.value.copy(query = q, error = null)
        searchJob?.cancel()
        if (q.trim().length < 2) {
            _search.value = SearchState(query = q)
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            runSearch(q, typeahead = true)
        }
    }

    /** Keyboard "search" pressed: search right away for the full text. */
    fun searchNow() {
        val q = _search.value.query
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(q, typeahead = false) }
    }

    private suspend fun runSearch(q: String, typeahead: Boolean) {
        _search.value = _search.value.copy(searching = true)
        // Without a position, results aren't ranked by distance and show no mileage, which makes
        // a same-named place in another state easy to pick by mistake.
        if (_here.value == null) Locations.lastKnown(context)?.let { _here.value = it }
        _search.value = try {
            SearchState(query = q, results = TomTomClient.search(q, _here.value, typeahead))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SearchState(query = q, error = "Search failed: ${e.message ?: "network error"}")
        }
    }

    fun select(place: Place) {
        searchJob?.cancel()
        _selected.value = place
        _search.value = SearchState(query = place.name)
    }

    fun clearSelection() {
        searchJob?.cancel()
        _selected.value = null
        _search.value = SearchState()
        _state.value = UiState.Idle
    }

    // --- Trips ---

    fun reset() {
        _state.value = UiState.Idle
    }

    /** Call only once location permission is granted. */
    fun go(place: Place) {
        viewModelScope.launch {
            val s = prefs.settings.first()
            prefs.addRecent(place)

            _state.value = UiState.Working("Finding your location…")
            val here = runCatching { Locations.current(context) }.getOrNull() ?: _here.value
            if (here == null && s.mode != Mode.END_MILES) {
                _state.value = UiState.Error("Couldn't get your location. Is GPS on?", fallback = place)
                return@launch
            }
            here?.let { _here.value = it }

            _state.value = UiState.Working("Checking the route…")
            val route = try {
                here?.let { TomTomClient.route(it, place.latLng) }
            } catch (e: Exception) {
                if (s.mode != Mode.END_MILES) {
                    _state.value = UiState.Error(
                        "Couldn't check the route or construction: ${e.message ?: "network error"}",
                        fallback = place,
                    )
                    return@launch
                }
                null
            }

            if (s.mode == Mode.END_MILES) {
                clearSelection()
                if (route != null) {
                    startDrive(DriveTrip.endMode(place, route, s.endMiles))
                } else {
                    // No route (no signal?): still watch the distance and hand off to Google Maps when close.
                    ArrivalWatchService.start(context, place, s.endMiles)
                }
                return@launch
            }

            val r = route ?: return@launch
            val handoff = when (s.mode) {
                Mode.START_MILES -> RoutePlanner.pointAtDistance(r, s.startMiles.toDouble())
                else -> RoutePlanner.interstateHandoff(r)
            }
            val plan = TripPlan(place, r, handoff, RoutePlanner.construction(r, handoff))
            if (s.warnConstruction) {
                _state.value = UiState.Review(plan)
            } else {
                guidePartWay(plan)
            }
        }
    }

    /** Google guides the first part; the drive view is waiting with the rest when you come back. */
    /**
     * Guidance for the first part of the trip: HalfNav's own voice by default, or Google Maps if
     * that's the chosen voice. Either way the drive view has the rest of the trip.
     */
    fun guidePartWay(plan: TripPlan) {
        startDrive(DriveTrip.fromPlan(plan, settings.value.mode))
        if (settings.value.voice == VoiceMode.GOOGLE) {
            val target = if (plan.handoff.reachesDestination) plan.destination.latLng else plan.handoff.point
            MapsLauncher.navigateTo(context, target)
        }
    }

    fun guideWholeTrip(place: Place) {
        MapsLauncher.navigateTo(context, place.latLng)
        reset()
    }

    /** Drive view only, no Google Maps. */
    fun driveWithoutGoogle(plan: TripPlan) = startDrive(DriveTrip.fromPlan(plan, settings.value.mode))

    // --- Drive view ---
    // The trip itself runs in DriveService so it keeps going (and talking) with the screen off;
    // the view model only decides whether the drive view is on screen.

    val drive: StateFlow<DriveTrip?> = DriveService.trip
    val driveProgress: StateFlow<DriveProgress> = DriveService.progress

    private val _driveVisible = MutableStateFlow(false)
    val driveVisible: StateFlow<Boolean> = _driveVisible

    init {
        viewModelScope.launch {
            DriveService.progress.collect { p ->
                p.position?.let { _here.value = LatLng(it.latitude, it.longitude) }
            }
        }
    }

    private fun startDrive(trip: DriveTrip) {
        DriveService.start(context, trip)
        _driveVisible.value = true
        _state.value = UiState.Idle
        _selected.value = null
        _search.value = SearchState()
    }

    fun showDrive() {
        if (drive.value != null) _driveVisible.value = true
    }

    /** Back to the overview map; the trip keeps running and can be resumed. */
    fun hideDrive() {
        _driveVisible.value = false
    }

    fun endDrive() {
        ArrivalWatchService.stop(context)
        DriveService.stop(context)
        _driveVisible.value = false
    }

    fun googleFromHere() {
        drive.value?.let { MapsLauncher.navigateTo(context, it.destination.latLng) }
    }

    fun toggleMute() = viewModelScope.launch { prefs.setVoiceMuted(!settings.value.voiceMuted) }

    fun setMode(m: Mode) = viewModelScope.launch { prefs.setMode(m) }
    fun setStartMiles(v: Float) = viewModelScope.launch { prefs.setStartMiles(v) }
    fun setEndMiles(v: Float) = viewModelScope.launch { prefs.setEndMiles(v) }
    fun setWarnConstruction(v: Boolean) = viewModelScope.launch { prefs.setWarnConstruction(v) }
    fun setVehicle(v: Vehicle) = viewModelScope.launch { prefs.setVehicle(v) }
    fun setVoice(v: VoiceMode) = viewModelScope.launch { prefs.setVoice(v) }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
