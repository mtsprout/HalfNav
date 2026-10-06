package io.github.mtsprout.halfnav.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {

    @Test
    fun cleansRealTomTomInstructions() {
        // Messages taken from real TomTom routes (Philadelphia, New Braunfels, San Antonio).
        assertEquals(
            "Keep left at I 95 North toward New York",
            SpokenText.instruction("Keep left at Interstate Highway 95 N/I-95 N toward New York"),
        )
        assertEquals(
            "Turn right onto I 676 East",
            SpokenText.instruction("Turn right onto United States Highway 30 E/I-676 E/US-30 E"),
        )
        assertEquals(
            "Turn right onto I 35 Business South",
            SpokenText.instruction("Turn right onto S Interstate 35/I-35 Bus S"),
        )
        assertEquals(
            "Turn right onto North Broad Street",
            SpokenText.instruction("Turn right onto N Broad St/PA-611"),
        )
        assertEquals("Take the I 35 South freeway", SpokenText.instruction("Take the I-35 S freeway"))
        assertEquals("Turn left onto North Alamo Street", SpokenText.instruction("Turn left onto N Alamo St"))
        assertEquals("Turn left onto Saint Mary's Street", SpokenText.instruction("Turn left onto St Mary's St"))
        assertEquals("Leave from South 15th Street", SpokenText.instruction("Leave from S 15th St"))
    }

    @Test
    fun spokenDistances() {
        assertEquals("300 feet", SpokenText.distance(90.0))
        assertEquals("a quarter mile", SpokenText.distance(400.0))
        assertEquals("half a mile", SpokenText.distance(800.0))
        assertEquals("three quarters of a mile", SpokenText.distance(1200.0))
        assertEquals("1 mile", SpokenText.distance(1609.0))
        assertEquals("1.5 miles", SpokenText.distance(2400.0))
        assertEquals("2 miles", SpokenText.distance(3300.0))
        assertEquals("12 miles", SpokenText.distance(19500.0))
    }

    private val turn = Instruction(40, listOf("I-35 S"), null, "TURN_RIGHT", "Turn right onto S Interstate 35/I-35 Bus S")
    private val merge = Instruction(90, listOf("I-35 S"), null, "ENTER_FREEWAY", "Take the I-35 S freeway")

    private fun input(
        guided: Boolean = true,
        next: Instruction? = turn,
        metersToNext: Double = 3000.0,
        speed: Float = 13f,
        warningKey: Int? = null,
        metersToWarning: Double = 0.0,
        arrived: Boolean = false,
        rerouting: Boolean = false,
        routeVersion: Int = 0,
    ) = VoiceInput(
        guided = guided, next = next, metersToNext = metersToNext, speedMps = speed,
        warningKey = warningKey, warningWhat = warningKey?.let { "Road work" }, warningRoad = "I-35 S",
        metersToWarning = metersToWarning, rerouting = rerouting, arrived = arrived,
        destinationName = "the Alamo",
        guidanceStarts = "Starting guidance to I 35.",
        guidanceEnds = "You're on I 35. Guidance ends here. You're on your own.",
        routeVersion = routeVersion,
    )

    @Test
    fun cityTurnGetsHeadsUpThenPrompt() {
        val v = VoicePrompts()
        assertEquals(
            listOf("Starting guidance to I 35.", "In 2 miles, turn right onto I 35 Business South."),
            v.update(input(metersToNext = 3000.0)),
        )
        assertEquals(emptyList(), v.update(input(metersToNext = 1000.0)))
        assertEquals(emptyList(), v.update(input(metersToNext = 450.0)))
        // Already announced at the start, so no second heads-up at a quarter mile; just the turn itself.
        assertEquals(listOf("Turn right onto I 35 Business South."), v.update(input(metersToNext = 60.0)))
        assertEquals(emptyList(), v.update(input(metersToNext = 20.0)))
    }

    @Test
    fun laterTurnsGetHeadsUpAtTheRightDistance() {
        val v = VoicePrompts()
        v.update(input(metersToNext = 50.0)) // start + immediate turn
        // Next maneuver on the highway: heads-up at ~1 mile, prompt at ~300 m.
        assertEquals(emptyList(), v.update(input(next = merge, metersToNext = 2500.0, speed = 28f)))
        assertEquals(
            listOf("In 1 mile, take the I 35 South freeway."),
            v.update(input(next = merge, metersToNext = 1500.0, speed = 28f)),
        )
        assertEquals(listOf("Take the I 35 South freeway."), v.update(input(next = merge, metersToNext = 280.0, speed = 28f)))
    }

    @Test
    fun handoffAndSilenceWhileOnYourOwn() {
        val v = VoicePrompts()
        v.update(input(metersToNext = 50.0))
        assertEquals(
            listOf("You're on I 35. Guidance ends here. You're on your own."),
            v.update(input(guided = false, next = merge, metersToNext = 280.0, speed = 28f)),
        )
        // No turn prompts while you're on your own.
        assertEquals(emptyList(), v.update(input(guided = false, next = merge, metersToNext = 50.0, speed = 28f)))
    }

    @Test
    fun endModeStartsSpeakingInsideTheZone() {
        val v = VoicePrompts()
        assertEquals(emptyList(), v.update(input(guided = false, metersToNext = 900.0)))
        val said = v.update(input(guided = true, metersToNext = 900.0))
        assertEquals("Starting guidance to I 35.", said.first())
        assertTrue(said.last().startsWith("In half a mile"))
    }

    @Test
    fun constructionIsAnnouncedOnceEvenWhenOnYourOwn() {
        val v = VoicePrompts()
        v.update(input(guided = false))
        assertEquals(emptyList(), v.update(input(guided = false, speed = 28f, warningKey = 7, metersToWarning = 5000.0)))
        assertEquals(
            listOf("Road work ahead in 2 miles on I 35 South."),
            v.update(input(guided = false, speed = 28f, warningKey = 7, metersToWarning = 3200.0)),
        )
        assertEquals(emptyList(), v.update(input(guided = false, speed = 28f, warningKey = 7, metersToWarning = 1000.0)))
    }

    @Test
    fun arrivalAndRerouting() {
        val v = VoicePrompts()
        v.update(input())
        assertEquals(listOf("Rerouting."), v.update(input(rerouting = true)))
        assertEquals(emptyList(), v.update(input(rerouting = true)))
        assertEquals(listOf("You've arrived at the Alamo."), v.update(input(arrived = true)))
        assertEquals(emptyList(), v.update(input(arrived = true)))
    }

    @Test
    fun newRouteAnnouncesTurnsAgain() {
        val v = VoicePrompts()
        v.update(input(metersToNext = 50.0))
        assertEquals(emptyList(), v.update(input(metersToNext = 40.0)))
        // After a reroute the same instruction index belongs to a different route.
        assertEquals(listOf("Turn right onto I 35 Business South."), v.update(input(metersToNext = 40.0, routeVersion = 1)))
    }
}
