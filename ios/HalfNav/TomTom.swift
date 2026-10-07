import Foundation
import HalfNavCore

/// TomTom routing and search. Responses are parsed by the shared Kotlin core, exactly as on Android.
enum TomTom {
    private static let base = "https://api.tomtom.com"

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static var key: String {
        get throws {
            let k = (Bundle.main.object(forInfoDictionaryKey: "TomTomKey") as? String) ?? ""
            if k.isEmpty || k.hasPrefix("$(") {
                throw Failure(message: "No TomTom API key. Add TOMTOM_KEY to ios/Config/Secrets.xcconfig.")
            }
            return k
        }
    }

    /// Driving route with live traffic, guidance instructions, and motorway/traffic sections.
    static func route(from: LatLng, to: LatLng) async throws -> Route {
        let url = "\(base)/routing/1/calculateRoute/\(from.lat),\(from.lng):\(to.lat),\(to.lng)/json" +
            "?key=\(try key)&travelMode=car&traffic=true&routeRepresentation=polyline" +
            "&instructionsType=text&language=en-US&sectionType=traffic&sectionType=motorway"
        let body = try await get(url)
        do {
            return try TomTomParser.shared.parse(json: body)
        } catch {
            throw Failure(message: kotlinMessage(error) ?? "Unexpected response from TomTom")
        }
    }

    /// Places and addresses matching [query], ranked with a bias toward [near].
    static func search(_ query: String, near: LatLng?, typeahead: Bool) async throws -> [Place] {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/?#")
        let q = query.trimmingCharacters(in: .whitespaces).addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
        let bias = near.map { "&lat=\($0.lat)&lon=\($0.lng)" } ?? ""
        let body = try await get("\(base)/search/2/search/\(q).json?key=\(try key)&limit=10&typeahead=\(typeahead)\(bias)")
        let places: [Place]
        do {
            places = try TomTomSearchParser.shared.parse(json: body)
        } catch {
            throw Failure(message: kotlinMessage(error) ?? "Unexpected response from TomTom search")
        }
        return SearchRanking.shared.nearbyFirst(places: places, here: near)
    }

    private static func get(_ url: String) async throws -> String {
        guard let u = URL(string: url) else { throw Failure(message: "Bad request") }
        var req = URLRequest(url: u)
        req.timeoutInterval = 15
        let (data, _) = try await URLSession.shared.data(for: req)
        return String(decoding: data, as: UTF8.self)
    }

    /// Kotlin exceptions arrive as NSError with the Kotlin message attached.
    private static func kotlinMessage(_ error: Error) -> String? {
        let ns = error as NSError
        return (ns.userInfo["KotlinException"] as? KotlinThrowable)?.message ?? ns.userInfo[NSLocalizedDescriptionKey] as? String
    }
}
