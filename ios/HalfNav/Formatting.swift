import Foundation
import UIKit
import HalfNavCore

func formatMiles(_ miles: Double) -> String {
    miles < 10 ? String(format: "%.1f mi", miles) : String(format: "%.0f mi", miles)
}

func formatMinutes(_ seconds: Int) -> String {
    let min = (seconds + 30) / 60
    return min < 60 ? "\(min) min" : "\(min / 60) h \(min % 60) min"
}

/// "500 ft" up close, then miles.
func formatManeuverDistance(_ meters: Double) -> String {
    let miles = Geo.shared.metersToMiles(m: meters)
    return miles < 0.19 ? "\(max(1, Int(meters * 3.28084 / 50)) * 50) ft" : formatMiles(miles)
}

/// Arrow for a TomTom maneuver code.
func maneuverGlyph(_ maneuver: String?) -> String {
    guard let m = maneuver else { return "↑" }
    if m.hasPrefix("ARRIVE") { return "◉" }
    if m.contains("UTURN") { return "↶" }
    if m.contains("ROUNDABOUT") { return "↻" }
    if m == "TURN_LEFT" || m.contains("SHARP_LEFT") { return "↰" }
    if m == "TURN_RIGHT" || m.contains("SHARP_RIGHT") { return "↱" }
    if m.contains("LEFT") { return "↖" }
    if m.contains("RIGHT") { return "↗" }
    return "↑"
}

func milesBetween(_ a: LatLng?, _ b: LatLng) -> Double? {
    a.map { Geo.shared.metersToMiles(m: Geo.shared.distanceMeters(a: $0, b: b)) }
}

/// Opens turn-by-turn directions: Google Maps if installed, otherwise Apple Maps.
enum MapsLauncher {
    static func navigate(to p: LatLng) {
        let google = URL(string: "comgooglemaps://?daddr=\(p.lat),\(p.lng)&directionsmode=driving")!
        if UIApplication.shared.canOpenURL(google) {
            UIApplication.shared.open(google)
        } else {
            UIApplication.shared.open(URL(string: "maps://?daddr=\(p.lat),\(p.lng)&dirflg=d")!)
        }
    }
}
