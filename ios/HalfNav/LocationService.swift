import CoreLocation
import HalfNavCore

/// Where you are. Gentle updates for the map normally; precise 1-second updates that keep running
/// in the background while a trip is on.
@Observable
final class LocationService: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    /// Callers of current() waiting for a fix. Each is resumed exactly once: by a fix or its timeout.
    private var waiters: [UUID: CheckedContinuation<CLLocation?, Never>] = [:]

    private(set) var here: LatLng?
    private(set) var authorized = false
    /// Called with every fix while a trip is running.
    var onTripLocation: ((CLLocation) -> Void)?
    private(set) var tripActive = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 20
        // The last known position is instant, so the map and day/night look are right at once.
        if let last = manager.location { here = LatLng(lat: last.coordinate.latitude, lng: last.coordinate.longitude) }
    }

    func start() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        } else {
            manager.startUpdatingLocation()
        }
    }

    /// A recent fix: the current one if it's under 15 seconds old, otherwise a fresh one, waiting
    /// at most 10 seconds (garage, weak signal) before falling back to the last known position.
    func current() async -> CLLocation? {
        if let l = manager.location, -l.timestamp.timeIntervalSinceNow < 15 { return l }
        return await withCheckedContinuation { (cont: CheckedContinuation<CLLocation?, Never>) in
            let id = UUID()
            waiters[id] = cont
            manager.requestLocation()
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, let c = self.waiters.removeValue(forKey: id) else { return }
                c.resume(returning: self.manager.location)
            }
        }
    }

    /// A position you can pin a house to. The first fix indoors is often from Wi-Fi and a couple
    /// hundred feet off, so listen at full GPS accuracy until a fix is within goodMeters, or until
    /// the timeout, and return the most accurate fix seen.
    func precise(goodMeters: Double = 15, timeout: Duration = .seconds(20)) async -> CLLocation? {
        if !tripActive {
            manager.desiredAccuracy = kCLLocationAccuracyBest
            manager.distanceFilter = kCLDistanceFilterNone
        }
        manager.startUpdatingLocation()
        let started = Date()
        var best: CLLocation?
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if let l = manager.location, l.timestamp >= started, l.horizontalAccuracy >= 0,
               best == nil || l.horizontalAccuracy < best!.horizontalAccuracy {
                best = l
            }
            if let b = best, b.horizontalAccuracy <= goodMeters { break }
            try? await Task.sleep(for: .milliseconds(250))
        }
        if !tripActive {
            manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
            manager.distanceFilter = 20
        }
        return best ?? manager.location
    }

    func startTrip() {
        tripActive = true
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .automotiveNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        manager.startUpdatingLocation()
    }

    func stopTrip() {
        tripActive = false
        manager.allowsBackgroundLocationUpdates = false
        manager.showsBackgroundLocationIndicator = false
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 20
    }

    // MARK: CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ m: CLLocationManager) {
        authorized = [.authorizedWhenInUse, .authorizedAlways].contains(m.authorizationStatus)
        if authorized { m.startUpdatingLocation() }
    }

    func locationManager(_ m: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }
        here = LatLng(lat: loc.coordinate.latitude, lng: loc.coordinate.longitude)
        let waiting = waiters.values
        waiters.removeAll()
        waiting.forEach { $0.resume(returning: loc) }
        if tripActive { onTripLocation?(loc) }
    }

    func locationManager(_ m: CLLocationManager, didFailWithError error: Error) {}
}
