package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrivalTest {
    private val house = Place("100 Main Street", "100 Main Street, Springfield", LatLng(39.80, -89.64))
    private val store = Place(
        "Kwik-E-Mart", "200 Main Street, Springfield", LatLng(39.81, -89.65), category = "convenience store",
    )

    private fun reached(
        place: Place, onRoute: Boolean, routeLeft: Double, toDest: Double, toEnd: Double = toDest, speed: Double = 9.0,
    ) = Arrival.reached(place, onRoute, routeLeft, toDest, toEnd, speed)

    @Test
    fun houseMeansTheDriveway() {
        // Corner lot: driving down the side street passes within 30 m of the house.
        assertFalse(reached(house, onRoute = true, routeLeft = 51.0, toDest = 28.0))
        // Just turned onto the street: 26 m of road left, 32 m from the house.
        assertFalse(reached(house, onRoute = true, routeLeft = 26.0, toDest = 32.0))
        // Crawling nearby isn't the driveway either.
        assertFalse(reached(house, onRoute = false, routeLeft = 60.0, toDest = 50.0, toEnd = 40.0, speed = 1.0))
        // Off the route at speed close by isn't the driveway.
        assertFalse(reached(house, onRoute = false, routeLeft = 40.0, toDest = 20.0, speed = 9.0))
        // End of the route, in front of the house.
        assertTrue(reached(house, onRoute = true, routeLeft = 8.0, toDest = 24.0))
        // Pulled into the driveway.
        assertTrue(reached(house, onRoute = false, routeLeft = 20.0, toDest = 15.0, speed = 0.0))
    }

    @Test
    fun businessAllowsTheParkingLot() {
        // Driving past on the route with road still to go is not arriving.
        assertFalse(reached(store, onRoute = true, routeLeft = 150.0, toDest = 100.0, speed = 9.0))
        // Near the end of the route.
        assertTrue(reached(store, onRoute = true, routeLeft = 30.0, toDest = 45.0))
        // In the lot, off the route line, nearly stopped.
        assertTrue(reached(store, onRoute = false, routeLeft = 200.0, toDest = 100.0, toEnd = 110.0, speed = 1.0))
        // Off the route at speed on a nearby street.
        assertFalse(reached(store, onRoute = false, routeLeft = 200.0, toDest = 100.0, toEnd = 110.0, speed = 12.0))
    }
}
