package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SearchTest {

    // Trimmed from a real TomTom response for "The Alamo" searched near Philadelphia.
    private val alamoJson = """
        {"summary":{"query":"the alamo","numResults":5},"results":[
          {"type":"POI","poi":{"name":"Alamo","categories":["rent-a-car facility"]},
           "address":{"freeformAddress":"2955 Market Street, Philadelphia, PA 19104"},
           "position":{"lat":39.955749,"lon":-75.181991}},
          {"type":"POI","poi":{"name":"The Alamo","categories":["important tourist attraction"]},
           "address":{"freeformAddress":"300 Alamo Plaza, San Antonio, TX 78205"},
           "position":{"lat":29.425967,"lon":-98.486142}},
          {"type":"Street","address":{"streetName":"West William Street","freeformAddress":"West William Street, Philadelphia, PA 19132"},
           "position":{"lat":39.99,"lon":-75.16}},
          {"type":"Point Address","address":{"streetNumber":"1600","streetName":"Market Street","freeformAddress":"1600 Market Street, Philadelphia, PA 19103"},
           "position":{"lat":39.9527,"lon":-75.1675}},
          {"type":"POI","poi":{"name":"The Alamo","categories":["important tourist attraction"]},
           "address":{"freeformAddress":"300 Alamo Plz, San Antonio, TX 78205"},
           "position":{"lat":29.42587,"lon":-98.48613}}
        ]}
    """

    @Test
    fun parsesPoisStreetsAndAddresses() {
        val places = TomTomSearchParser.parse(alamoJson)
        assertEquals(
            listOf("Alamo", "The Alamo", "West William Street", "1600 Market Street"),
            places.map { it.name },
        )
        val alamo = places[1]
        assertEquals("300 Alamo Plaza, San Antonio, TX 78205", alamo.address)
        assertEquals("important tourist attraction", alamo.category)
        assertEquals(29.425967, alamo.latLng.lat, 1e-9)
    }

    @Test
    fun localityTellsLookAlikesApart() {
        // Trimmed from a real search for "Sts Peter and Paul Catholic Church" near San Antonio, TX.
        val json = """
            {"results":[
              {"type":"POI","poi":{"name":"Saints Peter & Paul Catholic Church","categories":["church"]},
               "address":{"freeformAddress":"386 North Castell Avenue, New Braunfels, TX 78130","municipality":"New Braunfels",
                 "countrySubdivisionCode":"TX","countryCode":"US","country":"United States"},
               "position":{"lat":29.703625,"lon":-98.128377}},
              {"type":"POI","poi":{"name":"Sts Peter & Paul Catholic Church","categories":["church"]},
               "address":{"freeformAddress":"301 North Little Avenue, Cushing, OK 74023","municipality":"Cushing",
                 "countrySubdivisionCode":"OK","countryCode":"US","country":"United States"},
               "position":{"lat":35.9818,"lon":-96.768}},
              {"type":"POI","poi":{"name":"Sts. Peter and Paul Residence"},
               "address":{"freeformAddress":"221 Milner Avenue, Scarborough ON M1S 4P4","municipality":"Scarborough",
                 "countrySubdivisionCode":"ON","countryCode":"CA","country":"Canada"},
               "position":{"lat":43.79,"lon":-79.25}}
            ]}
        """
        assertEquals(
            listOf("New Braunfels, TX", "Cushing, OK", "Scarborough, ON, Canada"),
            TomTomSearchParser.parse(json).map { it.locality },
        )
    }

    @Test
    fun dropsNearDuplicateResults() {
        assertEquals(1, TomTomSearchParser.parse(alamoJson).count { it.name == "The Alamo" })
    }

    @Test
    fun emptyAndErrorResponses() {
        assertTrue(TomTomSearchParser.parse("""{"summary":{},"results":[]}""").isEmpty())
        assertFailsWith<RouteException> { TomTomSearchParser.parse("""{"errorText":"Invalid key"}""") }
        assertFailsWith<RouteException> { TomTomSearchParser.parse("Forbidden") }
    }

    @Test
    fun routeSliceFollowsGeometry() {
        val pts = (0..10).map { LatLng(40.0 + it * 0.001, -75.0) }
        val r = Route(pts, 0, emptyList(), emptyList(), emptyList())
        val step = r.cumulativeMeters[1]
        val s = r.slice(step * 2.5, step * 5.5)
        assertEquals(5, s.size) // interpolated start, points 3..5, interpolated end
        assertEquals(40.0025, s.first().lat, 1e-6)
        assertEquals(40.0055, s.last().lat, 1e-6)
        assertEquals(pts[3], s[1])
        assertEquals(listOf(pts.first(), pts.last()), r.slice(-5.0, 1e9).let { listOf(it.first(), it.last()) })
    }
}
