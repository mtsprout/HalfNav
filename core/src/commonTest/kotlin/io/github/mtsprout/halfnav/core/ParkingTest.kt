package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParkingTest {
    private val church = Place(
        "Saints Peter & Paul Catholic Church", "386 North Castell Avenue, New Braunfels, TX 78130",
        LatLng(29.703625, -98.128377), category = "church",
    )

    // Shaped like TomTom's Nearby Search for parking around the church (names and lot positions are
    // real; the entrance positions are made up).
    private val json = """
        {"results":[
          {"type":"POI","poi":{"name":"Indigo Saints Peter & Paul Catholic Church","categories":["open parking area"]},
           "position":{"lat":29.703028,"lon":-98.127619},
           "entryPoints":[{"type":"main","position":{"lat":29.70315,"lon":-98.12779}}]},
          {"type":"POI","poi":{"name":"Indigo 409 N Seguin Avenue","categories":["open parking area"]},
           "position":{"lat":29.7042,"lon":-98.1268},
           "entryPoints":[{"type":"main","position":{"lat":29.7041,"lon":-98.1269}}]}
        ]}
    """

    @Test
    fun parsesLotsAndEntrances() {
        val lots = Parking.parse(json)
        assertEquals(2, lots.size)
        assertEquals(LatLng(29.70315, -98.12779), lots[0].entrance)
    }

    @Test
    fun usesOnlyTheLotNamedForThePlace() {
        assertEquals("Indigo Saints Peter & Paul Catholic Church", Parking.lotFor(church, Parking.parse(json))?.name)
        // A nearby lot that isn't the church's own (maybe someone's paid lot) is never used.
        val others = Parking.parse(json).drop(1)
        assertNull(Parking.lotFor(church, others))
    }

    @Test
    fun recognizesParkingPlaces() {
        assertTrue(Parking.isParking(church.copy(category = "open parking area")))
        assertFalse(Parking.isParking(church))
    }
}
