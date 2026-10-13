package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutePlannerTest {

    // A straight route due north: 200 points 0.001° of latitude (~111 m) apart, ~13.8 miles total.
    private val pointCount = 200
    private val metersPerStep = Geo.distanceMeters(LatLng(40.0, -75.0), LatLng(40.001, -75.0))

    private fun tomTomJson(
        instructions: String = """
            {"pointIndex":0,"street":"Main St","maneuver":"DEPART"},
            {"pointIndex":20,"roadNumbers":["I-95","US-1"],"maneuver":"ENTER_MOTORWAY"},
            {"pointIndex":199,"maneuver":"ARRIVE"}
        """,
        sections: String = """
            {"startPointIndex":0,"endPointIndex":199,"sectionType":"TRAVEL_MODE","travelMode":"car"},
            {"startPointIndex":20,"endPointIndex":199,"sectionType":"MOTORWAY"},
            {"startPointIndex":10,"endPointIndex":15,"sectionType":"TRAFFIC","simpleCategory":"ROAD_WORK","delayInSeconds":30,"magnitudeOfDelay":1},
            {"startPointIndex":50,"endPointIndex":60,"sectionType":"TRAFFIC","simpleCategory":"JAM","delayInSeconds":300,"magnitudeOfDelay":3},
            {"startPointIndex":150,"endPointIndex":160,"sectionType":"TRAFFIC","simpleCategory":"ROAD_CLOSURE","delayInSeconds":0,"magnitudeOfDelay":4},
            {"startPointIndex":100,"endPointIndex":120,"sectionType":"TRAFFIC","simpleCategory":"ROAD_WORK","delayInSeconds":420,"magnitudeOfDelay":2}
        """,
    ): String {
        val points = (0 until pointCount).joinToString(",") {
            """{"latitude":${40.0 + it * 0.001},"longitude":-75.0}"""
        }
        return """
            {"formatVersion":"0.0.12","routes":[{
              "summary":{"lengthInMeters":22129,"travelTimeInSeconds":1200},
              "legs":[{"points":[$points]}],
              "sections":[$sections],
              "guidance":{"instructions":[$instructions]}
            }]}
        """
    }

    private fun route(json: String = tomTomJson()) = TomTomParser.parse(json)

    @Test
    fun parsesRoute() {
        val r = route()
        assertEquals(pointCount, r.points.size)
        assertEquals(1200, r.travelTimeSec)
        assertEquals(listOf("I-95", "US-1"), r.instructions[1].roadNumbers)
        assertEquals(4, r.traffic.size)
        assertEquals(listOf(20..199), r.motorways)
        assertEquals(199 * metersPerStep, r.lengthMeters, 1.0)
    }

    @Test
    fun readsRoundaboutExitFromFieldOrMessage() {
        val json = tomTomJson(
            instructions = """
                {"pointIndex":10,"maneuver":"ROUNDABOUT_CROSS","roundaboutExitNumber":2,"message":"Enter the roundabout"},
                {"pointIndex":20,"maneuver":"ROUNDABOUT_RIGHT","message":"At the roundabout, take the 3rd exit onto Main St"},
                {"pointIndex":30,"maneuver":"ROUNDABOUT_LEFT","message":"Take the first exit"},
                {"pointIndex":40,"maneuver":"TURN_RIGHT","roundaboutExitNumber":0,"message":"Turn right onto Oak St"}
            """,
        )
        val ins = route(json).instructions
        assertEquals(2, ins[0].roundaboutExit)
        assertEquals(3, ins[1].roundaboutExit)
        assertEquals(1, ins[2].roundaboutExit)
        assertEquals(null, ins[3].roundaboutExit)
    }

    /** A route that comes in heading north to a roundabout and leaves heading [exitDegrees]. */
    private fun roundaboutRoute(exitDegrees: Double): Route {
        val enter = LatLng(40.001, -75.0)
        val metersLat = 111_320.0
        fun away(from: LatLng, degrees: Double, meters: Double) = LatLng(
            from.lat + meters * kotlin.math.cos(degrees * kotlin.math.PI / 180) / metersLat,
            from.lng + meters * kotlin.math.sin(degrees * kotlin.math.PI / 180) /
                (metersLat * kotlin.math.cos(enter.lat * kotlin.math.PI / 180)),
        )
        val leave = away(enter, exitDegrees, 120.0)
        val points = listOf(LatLng(40.0, -75.0), enter, leave, away(leave, exitDegrees, 100.0))
        val instructions = listOf(
            Instruction(1, emptyList(), null, "ROUNDABOUT_CROSS", "At the roundabout take the third exit onto Main St", roundaboutExit = 3),
            Instruction(2, emptyList(), "Elm St", "TAKE_EXIT", "Take the exit onto Elm St"),
        )
        return Roundabouts.refine(Route(points, 60, instructions, emptyList(), emptyList()))
    }

    @Test
    fun wordsRoundaboutsFromTheShapeOfTheRoute() {
        val straight = roundaboutRoute(0.0).instructions[0]
        assertEquals("At the roundabout, continue straight onto Elm St", straight.message)
        assertTrue(kotlin.math.abs(straight.roundaboutTurn!!) <= 1)

        val right = roundaboutRoute(90.0).instructions[0]
        assertEquals("At the roundabout, turn right onto Elm St", right.message)
        assertTrue(kotlin.math.abs(right.roundaboutTurn!! - 90) <= 1)

        assertEquals("At the roundabout, turn left onto Elm St", roundaboutRoute(270.0).instructions[0].message)
        assertEquals("At the roundabout, bear right onto Elm St", roundaboutRoute(50.0).instructions[0].message)
        assertEquals("At the roundabout, make a U-turn onto Elm St", roundaboutRoute(180.0).instructions[0].message)
    }

    @Test
    fun readsTheRoadAheadWhenThereIsNoExitCue() {
        // TomTom sometimes gives no separate "take the exit" cue. The road just past the circle is
        // then the road you leave on (due north here, so straight on).
        val json = tomTomJson(
            instructions = """
                {"pointIndex":10,"maneuver":"ROUNDABOUT_CROSS","roundaboutExitNumber":1,"message":"At the roundabout take the first exit onto Main St"},
                {"pointIndex":40,"maneuver":"TURN_RIGHT","message":"Turn right onto Oak St"}
            """,
        )
        val first = route(json).instructions[0]
        assertEquals("At the roundabout, continue straight onto Main St", first.message)
        assertTrue(kotlin.math.abs(first.roundaboutTurn!!) <= 1)
    }

    @Test
    fun handsOffJustPastInterstateEntrance() {
        val r = route()
        val h = RoutePlanner.interstateHandoff(r)
        assertEquals("I-95", h.label)
        assertFalse(h.reachesDestination)
        assertEquals(20 * metersPerStep + RoutePlanner.ONTO_HIGHWAY_METERS, h.offsetMeters, 0.01)
        assertEquals(r.pointAt(h.offsetMeters), h.point)
        assertTrue(h.point.lat > 40.020 && h.point.lat < 40.030)
    }

    @Test
    fun normalizesInterstateNumbers() {
        val json = tomTomJson(instructions = """{"pointIndex":30,"roadNumbers":["i 276"]}""")
        assertEquals("I-276", RoutePlanner.interstateHandoff(route(json)).label)
    }

    @Test
    fun fallsBackToUsHighwayThenMotorwayThenDistance() {
        val us = tomTomJson(instructions = """{"pointIndex":30,"roadNumbers":["US 1"]}""")
        assertEquals("US-1", RoutePlanner.interstateHandoff(route(us)).label)

        val motorway = tomTomJson(instructions = """{"pointIndex":0,"street":"Main St"}""")
        val m = RoutePlanner.interstateHandoff(route(motorway))
        assertEquals("the highway", m.label)
        assertEquals(20 * metersPerStep + RoutePlanner.ONTO_HIGHWAY_METERS, m.offsetMeters, 0.01)

        val nothing = tomTomJson(instructions = """{"pointIndex":0,"street":"Main St"}""", sections = "")
        val n = RoutePlanner.interstateHandoff(route(nothing))
        assertEquals("mile 5", n.label)
        assertEquals(5 * Geo.METERS_PER_MILE, n.offsetMeters, 0.01)
    }

    @Test
    fun pointAtDistanceInterpolatesAlongRoute() {
        val h = RoutePlanner.pointAtDistance(route(), 5.0)
        assertEquals("mile 5", h.label)
        // Due north, so latitude grows linearly with distance.
        assertEquals(40.0 + 5 * Geo.METERS_PER_MILE / metersPerStep * 0.001, h.point.lat, 1e-6)
        assertEquals(-75.0, h.point.lng, 1e-9)
        assertEquals("mile 2.5", RoutePlanner.pointAtDistance(route(), 2.5).label)
    }

    @Test
    fun handoffNearDestinationGuidesWholeWay() {
        val r = route()
        val h = RoutePlanner.pointAtDistance(r, 13.5)
        assertTrue(h.reachesDestination)
        assertEquals(r.points.last(), h.point)
        assertTrue(RoutePlanner.construction(r, h).none { it.unguided })
    }

    @Test
    fun findsConstructionAndTagsGuidedVsUnguided() {
        val r = route()
        val h = RoutePlanner.interstateHandoff(r)
        val warnings = RoutePlanner.construction(r, h)

        // The JAM is not construction; the rest come back sorted by distance.
        assertEquals(listOf(WorkKind.ROAD_WORK, WorkKind.ROAD_WORK, WorkKind.ROAD_CLOSURE), warnings.map { it.kind })
        assertEquals(listOf(false, true, true), warnings.map { it.unguided })

        assertEquals("Main St", warnings[0].road)
        assertEquals("I-95", warnings[1].road)
        assertEquals(420, warnings[1].delaySec)
        assertEquals(100 * metersPerStep, warnings[1].startOffsetMeters, 0.01)
        assertEquals(20 * metersPerStep, warnings[1].lengthMeters, 0.01)
    }

    @Test
    fun zoneStraddlingHandoffCountsAsUnguided() {
        val r = route()
        // Handoff at ~mile 1 (point ~14.5) falls inside the 10..15 road-work zone.
        val h = RoutePlanner.pointAtDistance(r, 1.0)
        assertTrue(RoutePlanner.construction(r, h).first().unguided)
    }

    @Test
    fun reportsTomTomErrors() {
        val e = assertFailsWith<RouteException> {
            TomTomParser.parse("""{"formatVersion":"0.0.12","detailedError":{"code":"BAD_INPUT","message":"Invalid key"}}""")
        }
        assertEquals("Invalid key", e.message)
        assertFailsWith<RouteException> { TomTomParser.parse("""{"routes":[]}""") }
        assertFailsWith<RouteException> { TomTomParser.parse("<html>") }
    }
}
