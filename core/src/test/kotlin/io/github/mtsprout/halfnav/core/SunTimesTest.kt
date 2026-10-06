package io.github.mtsprout.halfnav.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SunTimesTest {
    private val chicago = ZoneId.of("America/Chicago")

    private fun assertNear(expected: ZonedDateTime, actual: Instant?, minutes: Long = 3) {
        assertNotNull(actual)
        val off = Duration.between(expected.toInstant(), actual).abs()
        assertTrue(off <= Duration.ofMinutes(minutes), "expected ~$expected, got ${actual.atZone(expected.zone)}")
    }

    @Test
    fun londonMidsummer() {
        // Published: sunrise 04:43, sunset 21:21 BST on 21 June 2024.
        val london = ZoneId.of("Europe/London")
        val d = SunTimes.day(LocalDate.of(2024, 6, 21), 51.5074, -0.1278)
        assertNear(ZonedDateTime.of(2024, 6, 21, 4, 43, 0, 0, london), d.sunrise)
        assertNear(ZonedDateTime.of(2024, 6, 21, 21, 21, 0, 0, london), d.sunset)
    }

    @Test
    fun newYorkMidwinter() {
        // Published: sunrise 07:16, sunset 16:32 EST on 21 December 2024.
        val ny = ZoneId.of("America/New_York")
        val d = SunTimes.day(LocalDate.of(2024, 12, 21), 40.7128, -74.0060)
        assertNear(ZonedDateTime.of(2024, 12, 21, 7, 16, 0, 0, ny), d.sunrise)
        assertNear(ZonedDateTime.of(2024, 12, 21, 16, 32, 0, 0, ny), d.sunset)
    }

    @Test
    fun darkAfterSunsetUntilSunrise() {
        // Downtown San Antonio, TX.
        val lat = 29.4241
        val lng = -98.4936
        val date = LocalDate.of(2026, 10, 6)
        val d = SunTimes.day(date, lat, lng)
        val noon = ZonedDateTime.of(2026, 10, 6, 12, 0, 0, 0, chicago).toInstant()
        val lateNight = ZonedDateTime.of(2026, 10, 6, 23, 30, 0, 0, chicago).toInstant()
        val earlyMorning = ZonedDateTime.of(2026, 10, 6, 5, 0, 0, 0, chicago).toInstant()

        assertFalse(SunTimes.isDark(noon, lat, lng, chicago))
        assertTrue(SunTimes.isDark(lateNight, lat, lng, chicago))
        assertTrue(SunTimes.isDark(earlyMorning, lat, lng, chicago))
        // Flips exactly at official sunset.
        assertFalse(SunTimes.isDark(d.sunset!!.minusSeconds(60), lat, lng, chicago))
        assertTrue(SunTimes.isDark(d.sunset!!, lat, lng, chicago))
        assertTrue(SunTimes.isDark(d.sunrise!!.minusSeconds(60), lat, lng, chicago))
        assertFalse(SunTimes.isDark(d.sunrise!!, lat, lng, chicago))
    }

    @Test
    fun polarNightAndMidnightSun() {
        val tromso = ZoneId.of("Europe/Oslo")
        val winter = SunTimes.day(LocalDate.of(2024, 12, 21), 69.65, 18.96)
        assertTrue(winter.alwaysDown)
        assertTrue(SunTimes.isDark(ZonedDateTime.of(2024, 12, 21, 12, 0, 0, 0, tromso).toInstant(), 69.65, 18.96, tromso))
        val summer = SunTimes.day(LocalDate.of(2024, 6, 21), 69.65, 18.96)
        assertTrue(summer.alwaysUp)
        assertFalse(SunTimes.isDark(ZonedDateTime.of(2024, 6, 21, 0, 30, 0, 0, tromso).toInstant(), 69.65, 18.96, tromso))
    }
}
