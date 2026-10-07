import Foundation
import HalfNavCore

enum Mode: String { case startInterstate, startMiles, endMiles }

/// The icon that marks you on the drive view.
enum Vehicle: String { case car, arrow }

/// Who speaks turn-by-turn directions on the guided part of a trip.
enum VoiceMode: String { case halfnav, google }

/// When to use the dark look (map and panels).
enum ThemeMode: String { case auto, light, dark }

/// A destination, stored in recents. Mirrors the shared core's Place.
struct SavedPlace: Codable, Equatable {
    var name: String
    var address: String
    var lat: Double
    var lng: Double
    var category: String?
    var locality: String?

    init(_ p: Place) {
        name = p.name; address = p.address; lat = p.latLng.lat; lng = p.latLng.lng
        category = p.category; locality = p.locality
    }

    var place: Place {
        Place(name: name, address: address, latLng: LatLng(lat: lat, lng: lng), category: category, locality: locality)
    }
}

/// User settings, saved in UserDefaults. Same choices as the Android app.
@Observable
final class Settings {
    private let d = UserDefaults.standard

    var mode: Mode { didSet { d.set(mode.rawValue, forKey: "mode") } }
    var startMiles: Double { didSet { d.set(startMiles, forKey: "startMiles") } }
    var endMiles: Double { didSet { d.set(endMiles, forKey: "endMiles") } }
    var warnConstruction: Bool { didSet { d.set(warnConstruction, forKey: "warnConstruction") } }
    var vehicle: Vehicle { didSet { d.set(vehicle.rawValue, forKey: "vehicle") } }
    var voice: VoiceMode { didSet { d.set(voice.rawValue, forKey: "voice") } }
    var voiceMuted: Bool { didSet { d.set(voiceMuted, forKey: "voiceMuted") } }
    var theme: ThemeMode { didSet { d.set(theme.rawValue, forKey: "theme") } }
    var recent: [SavedPlace] {
        didSet { d.set(try? JSONEncoder().encode(recent), forKey: "recent") }
    }

    init() {
        mode = Mode(rawValue: d.string(forKey: "mode") ?? "") ?? .startInterstate
        startMiles = d.object(forKey: "startMiles") as? Double ?? 5
        endMiles = d.object(forKey: "endMiles") as? Double ?? 2
        warnConstruction = d.object(forKey: "warnConstruction") as? Bool ?? true
        vehicle = Vehicle(rawValue: d.string(forKey: "vehicle") ?? "") ?? .car
        voice = VoiceMode(rawValue: d.string(forKey: "voice") ?? "") ?? .halfnav
        voiceMuted = d.bool(forKey: "voiceMuted")
        theme = ThemeMode(rawValue: d.string(forKey: "theme") ?? "") ?? .auto
        recent = (d.data(forKey: "recent")).flatMap { try? JSONDecoder().decode([SavedPlace].self, from: $0) } ?? []
    }

    func addRecent(_ place: Place) {
        let saved = SavedPlace(place)
        recent = Array(([saved] + recent.filter { !($0.name == saved.name && $0.address == saved.address) }).prefix(6))
    }

    /// Whether the app was last dark, so the next launch can start in that look (no white flash).
    var lastDark: Bool? {
        get { d.object(forKey: "lastDark") as? Bool }
        set { d.set(newValue, forKey: "lastDark") }
    }
}
