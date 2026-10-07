package io.github.mtsprout.halfnav.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SunTimesTest {
    private val chicago = TimeZone.of("America/Chicago")

    private fun at(zone: TimeZone, y: Int, mo: Int, d: Int, h: Int, mi: Int): Instant =
        LocalDateTime(y, mo, d, h, mi).toInstant(zone)

    private fun assertNear(expected: Instant, actual: Instant?, zone: TimeZone, minutes: Int = 3) {
        assertNotNull(actual)
        val offMinutes = (actual - expected).inWholeMinutes.absoluteValue
        assertTrue(
            offMinutes <= minutes,
            "expected ~${expected.toLocalDateTime(zone)}, got ${actual.toLocalDateTime(zone)}",
        )
    }

    @Test
    fun londonMidsummer() {
        // Published: sunrise 04:43, sunset 21:21 BST on 21 June 2024.
        val london = TimeZone.of("Europe/London")
        val d = SunTimes.day(LocalDate(2024, 6, 21), 51.5074, -0.1278)
        assertNear(at(london, 2024, 6, 21, 4, 43), d.sunrise, london)
        assertNear(at(london, 2024, 6, 21, 21, 21), d.sunset, london)
    }

    @Test
    fun newYorkMidwinter() {
        // Published: sunrise 07:16, sunset 16:32 EST on 21 December 2024.
        val ny = TimeZone.of("America/New_York")
        val d = SunTimes.day(LocalDate(2024, 12, 21), 40.7128, -74.0060)
        assertNear(at(ny, 2024, 12, 21, 7, 16), d.sunrise, ny)
        assertNear(at(ny, 2024, 12, 21, 16, 32), d.sunset, ny)
    }

    @Test
    fun darkAfterSunsetUntilSunrise() {
        // Downtown San Antonio, TX.
        val lat = 29.4241
        val lng = -98.4936
        val d = SunTimes.day(LocalDate(2026, 10, 6), lat, lng)

        assertFalse(SunTimes.isDark(at(chicago, 2026, 10, 6, 12, 0), lat, lng, chicago))
        assertTrue(SunTimes.isDark(at(chicago, 2026, 10, 6, 23, 30), lat, lng, chicago))
        assertTrue(SunTimes.isDark(at(chicago, 2026, 10, 6, 5, 0), lat, lng, chicago))
        // Flips exactly at official sunset and sunrise.
        assertFalse(SunTimes.isDark(d.sunset!! - 60.seconds, lat, lng, chicago))
        assertTrue(SunTimes.isDark(d.sunset!!, lat, lng, chicago))
        assertTrue(SunTimes.isDark(d.sunrise!! - 1.minutes, lat, lng, chicago))
        assertFalse(SunTimes.isDark(d.sunrise!!, lat, lng, chicago))
        // Same answer through the epoch-millis entry point iOS uses.
        assertTrue(SunTimes.isDarkAt(d.sunset!!.toEpochMilliseconds(), lat, lng, "America/Chicago"))
    }

    @Test
    fun polarNightAndMidnightSun() {
        val tromso = TimeZone.of("Europe/Oslo")
        val winter = SunTimes.day(LocalDate(2024, 12, 21), 69.65, 18.96)
        assertTrue(winter.alwaysDown)
        assertTrue(SunTimes.isDark(at(tromso, 2024, 12, 21, 12, 0), 69.65, 18.96, tromso))
        val summer = SunTimes.day(LocalDate(2024, 6, 21), 69.65, 18.96)
        assertTrue(summer.alwaysUp)
        assertFalse(SunTimes.isDark(at(tromso, 2024, 6, 21, 0, 30), 69.65, 18.96, tromso))
    }
}
