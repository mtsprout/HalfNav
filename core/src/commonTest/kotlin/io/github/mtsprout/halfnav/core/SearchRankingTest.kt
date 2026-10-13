package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SearchRankingTest {
    // Downtown San Antonio, TX.
    private val sanAntonio = LatLng(29.4241, -98.4936)

    private fun place(name: String, lat: Double, lng: Double, town: String) =
        Place(name, "", LatLng(lat, lng), locality = town)

    @Test
    fun normalizesNames() {
        val expected = setOf("saint", "peter", "paul", "catholic", "church")
        assertEquals(expected, SearchRanking.nameWords("Sts Peter & Paul Catholic Church"))
        assertEquals(expected, SearchRanking.nameWords("Saints Peter and Paul Catholic Church"))
        assertEquals(expected, SearchRanking.nameWords("Saint Peter & Paul's Catholic Church"))
        assertEquals(expected, SearchRanking.nameWords("St. Peter & Paul Catholic Church"))
    }

    @Test
    fun closestLookAlikeComesFirst() {
        // The order TomTom gave for "Sts Peter and Paul Catholic Church" with no location bias:
        // the exact-spelling match in Oklahoma ahead of the nearest one, in New Braunfels.
        val cushing = place("Sts Peter & Paul Catholic Church", 35.9818, -96.768, "Cushing, OK")
        val austin = place("Saints Peter & Paul Catholic Church", 30.28852, -97.87538, "Austin, TX")
        val newBraunfels = place("Saints Peter & Paul Catholic Church", 29.703625, -98.128377, "New Braunfels, TX")
        val meyersville = place("Saint Peter & Paul's Catholic Church", 28.9189, -97.3291, "Meyersville, TX")

        val ranked = SearchRanking.nearbyFirst(listOf(cushing, austin, newBraunfels, meyersville), sanAntonio)
        assertEquals(
            listOf("New Braunfels, TX", "Austin, TX", "Meyersville, TX", "Cushing, OK"),
            ranked.map { it.locality },
        )
    }

    @Test
    fun unrelatedNamesKeepTheirPlaces() {
        val school = place("Peter & Paul Catholic School", 29.703682, -98.127597, "New Braunfels, TX")
        val cushing = place("Sts Peter & Paul Catholic Church", 35.9818, -96.768, "Cushing, OK")
        val diner = place("Paul's Diner", 29.43, -98.49, "San Antonio, TX")
        val newBraunfels = place("Saints Peter & Paul Catholic Church", 29.703625, -98.128377, "New Braunfels, TX")

        val ranked = SearchRanking.nearbyFirst(listOf(school, cushing, diner, newBraunfels), sanAntonio)
        // The two churches swap within their own slots (1 and 3); the school and diner don't move.
        assertEquals(listOf(school, newBraunfels, diner, cushing), ranked)
    }

    @Test
    fun resultsMatchingWhatWasTypedAreOrderedNearestFirst() {
        // TomTom's order for "St Helena" near San Antonio: a place named Saint Helena Island in
        // South Carolina came third, ahead of the nearby church group's third member.
        val church = place("Saint Helena Catholic Church", 29.6, -98.4, "San Antonio, TX")
        val fields = place("Saint Helena Baseball & Soccer Fields", 29.6, -98.4, "San Antonio, TX")
        val island = place("Saint Helena Island", 32.4, -80.6, "Saint Helena Island, SC")
        val youth = place("Saint Helenas Catholic Youth Organization", 29.6, -98.4, "San Antonio, TX")
        val street = place("Helena Street", 29.41, -98.5, "San Antonio, TX")
        val court = place("Saint Helena Court", 30.2, -98.0, "Driftwood, TX")
        val california = place("Saint Helena", 38.5, -122.5, "Saint Helena, CA")

        val ranked = SearchRanking.nearbyFirst(listOf(church, fields, island, youth, street, court, california), sanAntonio, "St Helena")
        // "Helena Street" has no "saint", so it stays where TomTom put it (slot 5).
        assertEquals(listOf(church, fields, youth, court, street, island, california).map { it.name }, ranked.map { it.name })
    }

    @Test
    fun typedWordsMatchAsPrefixesWhileTyping() {
        val near = place("Saint Helena Catholic Church", 29.6, -98.4, "San Antonio, TX")
        val far = place("Saint Hilary Church", 45.86, -122.8, "Portland, OR")
        // "Hel" starts a word in only one of them, so only that one matches and nothing moves.
        assertEquals(listOf(far, near), SearchRanking.nearbyFirst(listOf(far, near), sanAntonio, "St Hel"))
        // "Sa" starts a word in both, so both match and the near one moves up.
        assertEquals(listOf(near, far), SearchRanking.nearbyFirst(listOf(far, near), sanAntonio, "Sa"))
    }

    @Test
    fun bestAddressMatchesTheWholeAddress() {
        // The results TomTom gave for "202 S Union Ave, New Braunfels, TX 78130".
        fun address(name: String, full: String, kind: PlaceKind) =
            Place(name, full, LatLng(29.7, -98.1), null, "New Braunfels, TX", kind)
        val north = address("202 North Union Avenue", "202 North Union Avenue, New Braunfels, TX 78130", PlaceKind.ADDRESS)
        val street = address("South Union Avenue", "South Union Avenue, New Braunfels, TX 78130", PlaceKind.STREET)
        val south = address("202 South Union Avenue", "202 South Union Avenue, New Braunfels, TX 78130", PlaceKind.ADDRESS)
        val query = "202 S Union Ave, New Braunfels, TX 78130"

        assertEquals(south, SearchRanking.bestAddress(listOf(north, street, south), query))
        assertEquals(south, SearchRanking.bestAddress(listOf(south, street, north), query))
        // Only the street came back: no address to pick.
        assertEquals(null, SearchRanking.bestAddress(listOf(street), query))
        // Equal scores: the earlier result.
        assertEquals(north, SearchRanking.bestAddress(listOf(north, south), "202 Union Ave"))
    }

    @Test
    fun nearTiesKeepTomTomsOrder() {
        // The church and its parking lot are 0.1 mi apart; TomTom listed the church first.
        val church = place("Saints Peter & Paul Catholic Church", 29.703625, -98.128377, "New Braunfels, TX")
        val parking = place("Indigo Saints Peter & Paul Catholic Church", 29.703028, -98.127619, "New Braunfels, TX")
        assertEquals(listOf(church, parking), SearchRanking.nearbyFirst(listOf(church, parking), sanAntonio))
    }

    @Test
    fun withoutALocationNothingChanges() {
        val list = listOf(
            place("Sts Peter & Paul Catholic Church", 35.9818, -96.768, "Cushing, OK"),
            place("Saints Peter & Paul Catholic Church", 29.703625, -98.128377, "New Braunfels, TX"),
        )
        assertSame(list, SearchRanking.nearbyFirst(list, null))
    }
}
