import Foundation
import HalfNavCore

struct TripPlan {
    var destination: Place
    var route: Route
    var handoff: Handoff
    var warnings: [ConstructionWarning]
}

enum UiState {
    case idle
    case working(String)
    case review(TripPlan)
    /// fallback is set when we found the destination but couldn't plan the partial route.
    case error(String, fallback: Place?)
}

struct SearchState {
    var query = ""
    var results: [Place] = []
    var searching = false
    var error: String?
}

/// Everything on screen outside the drive view: search, the selected place, the route check.
@MainActor
@Observable
final class AppModel {
    let settings = Settings()
    let location = LocationService()
    let drive: DriveSession

    var state: UiState = .idle
    var search = SearchState()
    var selected: Place?
    /// True while the search bar is picking a home address instead of a destination.
    var pickingHome = false
    private var searchTask: Task<Void, Never>?

    init() {
        drive = DriveSession(settings: settings, location: location)
    }

    // MARK: Search

    func queryChanged(_ q: String) {
        search.query = q
        search.error = nil
        searchTask?.cancel()
        if case .idle = state {} else { state = .idle }
        guard q.trimmingCharacters(in: .whitespaces).count >= 2 else {
            search = SearchState(query: q)
            return
        }
        searchTask = Task {
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
            await runSearch(q, typeahead: true)
        }
    }

    /// Keyboard "search" pressed: search right away for the full text.
    func searchNow() {
        let q = search.query
        guard !q.isEmpty else { return }
        searchTask?.cancel()
        searchTask = Task { await runSearch(q, typeahead: false) }
    }

    private func runSearch(_ q: String, typeahead: Bool) async {
        search.searching = true
        do {
            let results = try await TomTom.search(q, near: location.here, typeahead: typeahead)
            if Task.isCancelled { return }
            search = SearchState(query: q, results: results)
        } catch {
            if Task.isCancelled { return }
            search = SearchState(query: q, error: "Search failed: \(error.localizedDescription)")
        }
    }

    func select(_ place: Place) {
        searchTask?.cancel()
        selected = place
        search = SearchState(query: place.name)
    }

    func clearSelection() {
        searchTask?.cancel()
        selected = nil
        search = SearchState()
        state = .idle
    }

    func reset() { state = .idle }

    // MARK: Home

    static let homeName = "Home"

    /// Search results now set Home instead of choosing a destination.
    func startPickingHome() {
        clearSelection()
        pickingHome = true
    }

    func stopPickingHome() {
        guard pickingHome else { return }
        pickingHome = false
        searchTask?.cancel()
        search = SearchState()
    }

    /// Saves place as Home and shows it, so you can check the address.
    func setHome(_ place: Place) {
        let home = Place(name: Self.homeName, address: place.address, latLng: place.latLng,
                         category: nil, locality: place.locality)
        pickingHome = false
        settings.home = SavedPlace(home)
        select(home)
    }

    func setHomeHere() {
        Task {
            state = .working("Pinpointing your location…")
            guard let fix = await location.precise() else {
                state = .error("Couldn't get your location. Is location turned on for HalfNav?", fallback: nil)
                return
            }
            let here = LatLng(lat: fix.coordinate.latitude, lng: fix.coordinate.longitude)
            state = .working("Looking up the address…")
            // No address (no signal, rural spot) is fine: Home is still the spot you're standing on.
            let found = try? await TomTom.address(at: here)
            let place = found ?? Place(name: Self.homeName, address: String(format: "%.5f, %.5f", here.lat, here.lng),
                                       latLng: here, category: nil, locality: nil)
            state = .idle
            setHome(place)
        }
    }

    func removeHome() {
        if selected?.name == Self.homeName { clearSelection() }
        settings.home = nil
    }

    // MARK: Trips

    func go(_ place: Place) {
        settings.addRecent(place)
        Task {
            state = .working("Finding your location…")
            guard let fix = await location.current() else {
                state = .error("Couldn't get your location. Is location turned on for HalfNav?", fallback: place)
                return
            }
            let here = LatLng(lat: fix.coordinate.latitude, lng: fix.coordinate.longitude)
            state = .working("Checking the route…")
            let route: Route
            do {
                route = try await TomTom.route(from: here, to: place.latLng)
            } catch {
                state = .error("Couldn't check the route or construction: \(error.localizedDescription)", fallback: place)
                return
            }
            if settings.mode == .endMiles {
                clearSelection()
                drive.start(.endMode(place, route, watchMiles: settings.endMiles))
                return
            }
            let handoff = settings.mode == .startMiles
                ? RoutePlanner.shared.pointAtDistance(route: route, miles: settings.startMiles)
                : RoutePlanner.shared.interstateHandoff(route: route)
            let plan = TripPlan(destination: place, route: route, handoff: handoff,
                                warnings: RoutePlanner.shared.construction(route: route, handoff: handoff))
            if settings.warnConstruction {
                state = .review(plan)
            } else {
                guidePartWay(plan)
            }
        }
    }

    /// Guidance for the first part: HalfNav's own voice by default, or Google Maps if that's the
    /// chosen voice. Either way the drive view has the rest of the trip.
    func guidePartWay(_ plan: TripPlan) {
        startDrive(plan)
        if settings.voice == .google {
            MapsLauncher.navigate(to: plan.handoff.reachesDestination ? plan.destination.latLng : plan.handoff.point)
        }
    }

    func driveOnly(_ plan: TripPlan) { startDrive(plan) }

    func guideWholeTrip(_ place: Place) {
        MapsLauncher.navigate(to: place.latLng)
        reset()
    }

    private func startDrive(_ plan: TripPlan) {
        drive.start(DriveTrip(destination: plan.destination, route: plan.route, mode: settings.mode,
                              handoff: plan.handoff, warnings: plan.warnings, watchMiles: nil))
        state = .idle
        selected = nil
        search = SearchState()
    }
}
