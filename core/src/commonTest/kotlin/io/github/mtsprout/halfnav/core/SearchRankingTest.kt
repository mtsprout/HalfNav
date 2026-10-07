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
