package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectionTest {

    // An L-shaped route: 10 steps north, then 10 steps east (~111 m / ~85 m per step at 40°N).
    private val pts = (0..10).map { LatLng(40.0 + it * 0.001, -75.0) } +
        (1..10).map { LatLng(40.010, -75.0 + it * 0.001) }
    private val route = Route(
        pts, 600,
        instructions = listOf(
            Instruction(0, emptyList(), "Main St", "DEPART", "Leave from Main St"),
            Instruction(10, listOf("I-95 N"), null, "TURN_RIGHT", "Turn right onto I-95 N"),
            Instruction(20, emptyList(), null, "ARRIVE", "You have arrived"),
        ),
        traffic = emptyList(),
        motorways = emptyList(),
    )

    @Test
    fun bearings() {
        assertEquals(0.0, Geo.bearing(LatLng(40.0, -75.0), LatLng(40.1, -75.0)), 0.01)
        assertEquals(90.0, Geo.bearing(LatLng(40.0, -75.0), LatLng(40.0, -74.9)), 0.1)
        assertEquals(180.0, Geo.bearing(LatLng(40.1, -75.0), LatLng(40.0, -75.0)), 0.01)
        assertEquals(270.0, Geo.bearing(LatLng(40.0, -74.9), LatLng(40.0, -75.0)), 0.1)
    }

    @Test
    fun snapsFixBesideTheRoad() {
        // ~30 m west of the north-bound leg, halfway up it.
        val fix = LatLng(40.005, -75.00035)
        val p = route.project(fix)
        assertEquals(40.005, p.point.lat, 1e-6)
        assertEquals(-75.0, p.point.lng, 1e-9)
        assertEquals(30.0, p.distanceMeters, 1.5)
        assertEquals(route.cumulativeMeters[5], p.offsetMeters, 0.5)
        assertEquals(0.0, p.bearing, 0.5) // heading north
    }

    @Test
    fun bearingFollowsTheTurn() {
        val p = route.project(LatLng(40.0102, -74.995))
        assertEquals(90.0, p.bearing, 1.0) // heading east after the corner
        assertTrue(p.offsetMeters > route.cumulativeMeters[10])
    }

    @Test
    fun hintPrefersNearbyStretch() {
        // A fix near the corner is equally close to both legs; with a hint just before the corner
        // it should land on the first leg, not jump ahead.
        val corner = LatLng(40.0099, -75.0001)
        val hinted = route.project(corner, hintOffsetMeters = route.cumulativeMeters[9])
        assertTrue(hinted.offsetMeters <= route.cumulativeMeters[10] + 1)
        // A far-away hint falls back to searching the whole route.
        val far = route.project(LatLng(40.010, -74.991), hintOffsetMeters = 0.0)
        assertEquals(route.cumulativeMeters[19], far.offsetMeters, 1.0)
    }

    @Test
    fun farFromRouteReportsDistance() {
        val p = route.project(LatLng(40.0, -74.99)) // ~850 m east of the start
        assertTrue(p.distanceMeters > 800)
    }

    @Test
    fun nextInstructionIsAhead() {
        val (ins, off) = assertNotNull(route.nextInstruction(route.cumulativeMeters[3]))
        assertEquals("TURN_RIGHT", ins.maneuver)
        assertEquals("Turn right onto I-95 N", ins.message)
        assertEquals(route.cumulativeMeters[10], off, 1e-9)
        assertEquals("ARRIVE", route.nextInstruction(route.cumulativeMeters[12])?.first?.maneuver)
    }
}
