package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EtaTest {

    // A straight road north: 20 steps of ~111 m, about 2.2 km.
    private val pts = (0..20).map { LatLng(40.0 + it * 0.001, -75.0) }

    private fun route(seconds: Int, traffic: List<TrafficSection> = emptyList(), points: List<LatLng> = pts) =
        Route(points, seconds, emptyList(), traffic, emptyList())

    private fun jam(from: Int, to: Int, delay: Int) = TrafficSection(from, to, "JAM", delay, 3)

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue(kotlin.math.abs(expected - actual) <= tolerance, "expected $expected ± $tolerance, was $actual")

    @Test
    fun withoutTrafficTimeShrinksWithDistance() {
        val r = route(600)
        assertEquals(600, Eta.remainingSec(r, 0.0))
        assertNear(300, Eta.remainingSec(r, r.lengthMeters / 2), 1)
        assertEquals(0, Eta.remainingSec(r, r.lengthMeters))
    }

    @Test
    fun aJamAheadKeepsItsFullDelayUntilYouReachIt() {
        // 600 s total, of which 300 s is a jam in the last quarter.
        val r = route(600, listOf(jam(15, 20, 300)))
        // Halfway there, the 300 s of free-flow time is half used up; the whole jam still lies ahead.
        assertNear(150 + 300, Eta.remainingSec(r, r.lengthMeters / 2), 2)
        // Evenly spread scaling would have said 300.
    }

    @Test
    fun aJamYouAreInsideCountsOnlyWhatIsLeft() {
        val r = route(600, listOf(jam(10, 20, 400)))
        // Free-flow time is 200 s. At the jam's midpoint a quarter of the road and half the delay are left.
        assertNear(50 + 200, Eta.remainingSec(r, r.offsetOfPoint(15)), 2)
    }

    @Test
    fun aJamBehindYouIsGone() {
        val r = route(600, listOf(jam(0, 5, 300)))
        // 300 s free flow over the whole road; past the jam, 3/4 of it is left.
        assertNear(225, Eta.remainingSec(r, r.offsetOfPoint(5)), 2)
    }

    @Test
    fun delaysLargerThanTheTotalDontGoNegative() {
        val r = route(100, listOf(jam(0, 20, 500)))
        assertTrue(Eta.remainingSec(r, 0.0) >= 0)
    }

    @Test
    fun aRouteOnTheSameRoadIsTheSameRoad() {
        val fresh = route(500, points = pts.drop(5))
        assertTrue(Eta.sameRoad(route(600), route(600).offsetOfPoint(5), fresh))
    }

    @Test
    fun aRouteTakingAnotherRoadIsNot() {
        // Goes east, then north along a road ~1.3 km away, then back: shares only its start.
        val detour = listOf(LatLng(40.005, -75.0)) +
            (1..15).map { LatLng(40.005, -75.0 + it * 0.001) } +
            (1..10).map { LatLng(40.005 + it * 0.001, -74.985) } +
            (1..15).map { LatLng(40.015, -74.985 - it * 0.001) }
        val old = route(600)
        assertFalse(Eta.sameRoad(old, old.offsetOfPoint(5), route(500, points = detour)))
    }
}
