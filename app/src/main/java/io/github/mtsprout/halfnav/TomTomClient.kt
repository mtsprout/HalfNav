package io.github.mtsprout.halfnav

import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Parking
import io.github.mtsprout.halfnav.core.ParkingLot
import io.github.mtsprout.halfnav.core.Place
import io.github.mtsprout.halfnav.core.Route
import io.github.mtsprout.halfnav.core.RouteException
import io.github.mtsprout.halfnav.core.SearchRanking
import io.github.mtsprout.halfnav.core.TomTomParser
import io.github.mtsprout.halfnav.core.TomTomSearchParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TomTomClient {
    private const val BASE = "https://api.tomtom.com"

    /** Driving route with live traffic, guidance instructions, and motorway/traffic sections. */
    suspend fun route(from: LatLng, to: LatLng): Route = TomTomParser.parse(
        get(
            "$BASE/routing/1/calculateRoute/${from.lat},${from.lng}:${to.lat},${to.lng}/json" +
                "?key=${key()}&travelMode=car&traffic=true&routeRepresentation=polyline" +
                "&instructionsType=text&language=en-US&sectionType=traffic&sectionType=motorway"
        )
    )

    /**
     * Places and addresses matching [query] ("The Alamo", "gas station", "123 Main St"), ranked with
     * a bias toward [near]. [typeahead] tunes results for partially typed text.
     */
    suspend fun search(query: String, near: LatLng?, typeahead: Boolean): List<Place> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lng}" }.orEmpty()
        val places = TomTomSearchParser.parse(
            get("$BASE/search/2/search/$q.json?key=${key()}&limit=10&typeahead=$typeahead$bias")
        )
        return SearchRanking.nearbyFirst(places, near)
    }

    /** [place]'s own parking lot, if TomTom knows one (see [Parking.lotFor]). */
    suspend fun parkingFor(place: Place): ParkingLot? = Parking.lotFor(
        place,
        Parking.parse(
            get(
                "$BASE/search/2/nearbySearch/.json?key=${key()}&lat=${place.latLng.lat}&lon=${place.latLng.lng}" +
                    "&radius=${Parking.SEARCH_METERS}&categorySet=${Parking.CATEGORY_SET}&limit=20"
            )
        ),
    )

    /** The street address at [at], or null if TomTom has none there. */
    suspend fun addressAt(at: LatLng): Place? = TomTomSearchParser.parseAddress(
        get("$BASE/search/2/reverseGeocode/${at.lat},${at.lng}.json?key=${key()}&language=en-US"),
        at,
    )

    private fun key(): String = BuildConfig.TOMTOM_KEY.ifBlank {
        throw RouteException("No TomTom API key. Add TOMTOM_KEY to local.properties.")
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.bufferedReader()?.use { it.readText() }
                ?: throw RouteException("TomTom returned HTTP ${conn.responseCode}")
        } finally {
            conn.disconnect()
        }
    }
}
