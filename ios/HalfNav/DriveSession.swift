import CoreLocation
import UIKit
import UserNotifications
import HalfNavCore

/// A trip shown in the drive view.
struct DriveTrip {
    var destination: Place
    var route: Route
    var mode: Mode
    /// Start modes: where guidance ends. Nil if there's no guided part.
    var handoff: Handoff?
    var warnings: [ConstructionWarning]
    /// End mode: guidance starts within this many miles of the destination.
    var watchMiles: Double?

    /// A stand-in handoff at the destination, for listing construction along the whole route.
    static func wholeRouteHandoff(_ route: Route) -> Handoff {
        Handoff(point: route.points.last!, offsetMeters: route.lengthMeters, label: "", reachesDestination: true)
    }

    static func endMode(_ destination: Place, _ route: Route, watchMiles: Double) -> DriveTrip {
        DriveTrip(destination: destination, route: route, mode: .endMiles, handoff: nil,
                  warnings: RoutePlanner.shared.construction(route: route, handoff: wholeRouteHandoff(route)),
                  watchMiles: watchMiles)
    }
}

/// Live position along a DriveTrip.
struct DriveProgress {
    var offsetMeters: Double = 0
    /// Where to draw the vehicle: snapped to the route when you're on it.
    var position: CLLocationCoordinate2D?
    var bearing: Double = 0
    var onRoute = true
    var speedMps: Double = 0
    var next: Instruction?
    var metersToNext: Double = 0
    var nextWarning: ConstructionWarning?
    var metersToWarning: Double = 0
    var rerouting = false
    var arrived = false

    func remainingMeters(_ trip: DriveTrip) -> Double { max(0, trip.route.lengthMeters - offsetMeters) }

    /// Remaining drive time, scaled from TomTom's traffic-aware estimate for the whole route.
    func remainingSec(_ trip: DriveTrip) -> Int {
        let len = trip.route.lengthMeters
        return len <= 0 ? 0 : Int(Double(trip.route.travelTimeSec) * remainingMeters(trip) / len)
    }

    /// True while the guided part of the trip is underway.
    func guided(_ trip: DriveTrip) -> Bool {
        switch trip.mode {
        case .endMiles:
            guard let p = position, let miles = trip.watchMiles else { return false }
            let here = LatLng(lat: p.latitude, lng: p.longitude)
            return Geo.shared.metersToMiles(m: Geo.shared.distanceMeters(a: here, b: trip.destination.latLng)) <= miles
        default:
            guard let h = trip.handoff else { return false }
            return h.reachesDestination || offsetMeters < h.offsetMeters
        }
    }
}

/// Runs a trip: follows GPS along the route, reroutes when you leave it, speaks directions, and
/// (with Google Maps as the voice) hands the guided part to Google. Keeps working with the screen off.
@Observable
final class DriveSession {
    private(set) var trip: DriveTrip?
    private(set) var progress = DriveProgress()
    var visible = false

    private let settings: Settings
    private let location: LocationService
    private let voice = VoiceGuide()
    private var prompts = VoicePrompts()

    private var lastFix: CLLocation?
    private var lastBearing: Double = 0
    private var offRouteFixes = 0
    private var lastRerouteAt = Date.distantPast
    private var rerouting = false
    private var routeVersion: Int32 = 0
    private var handedOffToGoogle = false
    /// Ends of every route this trip has used; a reroute can end on a different nearby street.
    private var routeEnds: [LatLng] = []

    private let snapMeters = 35.0, offRouteMeters = 80.0, offRouteNeeded = 3
    private let arrivedMeters = 40.0, nearDestinationMeters = 120.0, noRerouteMeters = 300.0
    private let minMoveMeters = 8.0

    init(settings: Settings, location: LocationService) {
        self.settings = settings
        self.location = location
        location.onTripLocation = { [weak self] in self?.onLocation($0) }
    }

    func start(_ trip: DriveTrip) {
        self.trip = trip
        progress = DriveProgress()
        prompts = VoicePrompts()
        lastFix = nil; offRouteFixes = 0; routeVersion = 0; handedOffToGoogle = false
        routeEnds = [trip.route.points.last!]
        visible = true
        location.startTrip()
        // Notifications are only used to hand end mode to Google Maps; ask only when that applies.
        if settings.voice == .google && trip.mode == .endMiles {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
    }

    func stop() {
        trip = nil
        progress = DriveProgress()
        visible = false
        voice.silence()
        location.stopTrip()
    }

    func setMuted(_ muted: Bool) {
        settings.voiceMuted = muted
        if muted { voice.silence() }
    }

    func googleFromHere() {
        if let t = trip { MapsLauncher.navigate(to: t.destination.latLng) }
    }

    // MARK: Following the route

    private func onLocation(_ loc: CLLocation) {
        guard let trip else { return }
        let here = LatLng(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        let prev = progress
        let route = trip.route

        let hint: KotlinDouble? = prev.position == nil ? nil : KotlinDouble(double: prev.offsetMeters)
        let proj = route.project(p: here, hintOffsetMeters: hint)
        let onRoute = proj.distanceMeters <= snapMeters

        // Heading: the road's direction when we're on it, otherwise the direction we've been moving.
        let moved = lastFix.map { loc.distance(from: $0) } ?? .greatestFiniteMagnitude
        var movingBearing = lastBearing
        if loc.course >= 0 && loc.speed > 2 {
            movingBearing = loc.course
        } else if let lf = lastFix, moved > minMoveMeters {
            movingBearing = Geo.shared.bearing(a: LatLng(lat: lf.coordinate.latitude, lng: lf.coordinate.longitude), b: here)
        }
        // Some fixes carry no speed; estimate it from how far we moved since the last one.
        var speed = loc.speed >= 0 ? loc.speed : 0
        if loc.speed < 0, let lf = lastFix, moved < .greatestFiniteMagnitude {
            let dt = loc.timestamp.timeIntervalSince(lf.timestamp)
            if dt > 0 { speed = min(90, moved / dt) }
        }
        if moved > minMoveMeters || lastFix == nil { lastFix = loc }
        let bearing = onRoute ? proj.bearing : movingBearing
        lastBearing = bearing

        let display = onRoute
            ? CLLocationCoordinate2D(latitude: proj.point.lat, longitude: proj.point.lng)
            : loc.coordinate
        let offset = proj.distanceMeters < offRouteMeters ? proj.offsetMeters : prev.offsetMeters
        let toDestination = Geo.shared.distanceMeters(a: here, b: trip.destination.latLng)
        // The route can end on the nearest road, short of the place itself (e.g. a pedestrian plaza),
        // so reaching the end of any route this trip used counts too.
        let arrived = prev.arrived || toDestination < nearDestinationMeters ||
            routeEnds.contains { Geo.shared.distanceMeters(a: here, b: $0) < nearDestinationMeters } ||
            (onRoute && route.lengthMeters - offset < arrivedMeters)
        let next = route.nextInstruction(offsetMeters: offset, aheadOfMeters: 10)
        let warning = trip.warnings.first { $0.startOffsetMeters + $0.lengthMeters > offset }

        offRouteFixes = proj.distanceMeters > offRouteMeters ? offRouteFixes + 1 : 0
        var p = DriveProgress()
        p.offsetMeters = offset
        p.position = display
        p.bearing = bearing
        p.onRoute = onRoute
        p.speedMps = speed
        p.next = next?.first
        p.metersToNext = next.flatMap { $0.second?.doubleValue }.map { $0 - offset } ?? 0
        p.nextWarning = warning
        p.metersToWarning = warning.map { max(0, $0.startOffsetMeters - offset) } ?? 0
        p.rerouting = rerouting
        p.arrived = arrived
        progress = p

        let guided = p.guided(trip)
        if guided && trip.mode == .endMiles && settings.voice == .google && !handedOffToGoogle {
            handedOffToGoogle = true
            handOffToGoogle(trip)
        }
        speak(trip, p, guided: guided)

        if offRouteFixes >= offRouteNeeded && !arrived && toDestination > noRerouteMeters &&
            Date().timeIntervalSince(lastRerouteAt) > 30 {
            reroute(from: here)
        }
    }

    private func speak(_ trip: DriveTrip, _ p: DriveProgress, guided: Bool) {
        // With Google Maps as the voice, Google talks during the guided part; HalfNav only speaks up
        // (construction, arrival) while you're on your own.
        if settings.voice == .google && guided { return }
        let label = trip.handoff.map { SpokenText.shared.instruction(message: $0.label) }
        let starts: String
        if trip.mode == .endMiles {
            let zone = SpokenText.shared.distance(meters: (trip.watchMiles ?? 0) * Geo.shared.METERS_PER_MILE)
            starts = "You're within \(zone) of \(trip.destination.name). Starting guidance."
        } else if trip.handoff?.reachesDestination == true || label == nil {
            starts = "Starting guidance to \(trip.destination.name)."
        } else if label!.hasPrefix("mile") {
            starts = "Starting guidance for the first \(label!.dropFirst(5)) miles."
        } else {
            starts = "Starting guidance to \(label!)."
        }
        let ends = (label != nil && !label!.hasPrefix("mile"))
            ? "You're on \(label!). Guidance ends here. You're on your own."
            : "Guidance ends here. You're on your own."
        let w = p.nextWarning
        let input = VoiceInput(
            guided: guided && settings.voice == .halfnav,
            next: p.next,
            metersToNext: p.metersToNext,
            speedMps: Float(p.speedMps),
            warningKey: w.map { KotlinInt(int: Int32($0.startOffsetMeters / 10) + routeVersion * 1_000_000) },
            warningWhat: w.map { $0.kind == WorkKind.roadClosure ? "Road closed" : "Road work" },
            warningRoad: w?.road,
            metersToWarning: p.metersToWarning,
            rerouting: p.rerouting,
            arrived: p.arrived,
            destinationName: trip.destination.name,
            guidanceStarts: starts,
            guidanceEnds: ends,
            routeVersion: routeVersion
        )
        let said = prompts.update(v: input)
        if !said.isEmpty && !settings.voiceMuted { voice.speak(said) }
    }

    /// You left the route: get a fresh one from here so the line and the directions stay useful.
    private func reroute(from: LatLng) {
        guard !rerouting, let trip else { return }
        rerouting = true
        lastRerouteAt = Date()
        progress.rerouting = true
        if !settings.voiceMuted && settings.voice == .halfnav && progress.guided(trip) {
            voice.speak(["Rerouting."])
        }
        Task { @MainActor in
            defer { rerouting = false; progress.rerouting = false }
            guard let route = try? await TomTom.route(from: from, to: trip.destination.latLng),
                  self.trip != nil else { return }
            // Keep the old handoff point if we haven't reached it and the new route still passes it.
            var handoff: Handoff?
            if let h = trip.handoff, progress.guided(trip) {
                if h.reachesDestination {
                    handoff = Handoff(point: route.points.last!, offsetMeters: route.lengthMeters, label: h.label, reachesDestination: true)
                } else {
                    let pr = route.project(p: h.point, hintOffsetMeters: nil)
                    if pr.distanceMeters < 100 {
                        handoff = Handoff(point: pr.point, offsetMeters: pr.offsetMeters, label: h.label, reachesDestination: false)
                    }
                }
            }
            routeVersion += 1
            routeEnds.append(route.points.last!)
            var t = trip
            t.route = route
            t.handoff = handoff
            t.warnings = RoutePlanner.shared.construction(route: route, handoff: handoff ?? DriveTrip.wholeRouteHandoff(route))
            self.trip = t
            offRouteFixes = 0
            progress = DriveProgress(position: progress.position, bearing: progress.bearing)
        }
    }

    /// End mode with Google as the voice: hand the last stretch to Google Maps. iOS never lets an app
    /// open another one from the background, so off-screen this is a notification you tap.
    private func handOffToGoogle(_ trip: DriveTrip) {
        if UIApplication.shared.applicationState == .active {
            MapsLauncher.navigate(to: trip.destination.latLng)
            return
        }
        let c = UNMutableNotificationContent()
        c.title = "Almost there: starting navigation"
        c.body = "Within \(formatMiles(trip.watchMiles ?? 0)) of \(trip.destination.name). Tap to navigate."
        c.sound = .default
        c.interruptionLevel = .timeSensitive
        c.userInfo = ["lat": trip.destination.latLng.lat, "lng": trip.destination.latLng.lng]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "handoff", content: c, trigger: nil))
    }
}
