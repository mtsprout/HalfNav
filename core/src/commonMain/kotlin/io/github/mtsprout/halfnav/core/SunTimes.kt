package io.github.mtsprout.halfnav.core

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Sunrise and sunset for one day. Null when the sun doesn't rise or set that day (polar regions). */
data class SunDay(
    val sunrise: Instant?,
    val sunset: Instant?,
    /** Sun never rises (polar night). */
    val alwaysDown: Boolean = false,
    /** Sun never sets (midnight sun). */
    val alwaysUp: Boolean = false,
)

/**
 * Official sunrise and sunset: the moment the top of the sun crosses the horizon, allowing for
 * atmospheric refraction (sun center 0.833° below the horizon), as NOAA and almanacs define it.
 * Uses the standard sunrise equation; accurate to about a minute.
 */
object SunTimes {
    private const val J2000 = 2451545.0
    private const val UNIX_EPOCH_JD = 2440587.5
    private const val OBLIQUITY = 23.4397
    private const val HORIZON = -0.833

    fun day(date: LocalDate, lat: Double, lng: Double): SunDay {
        // Days since J2000 at noon UTC of this date, adjusted to local solar time by longitude.
        val n = date.toEpochDays() + UNIX_EPOCH_JD + 0.5 - J2000 + 0.0008
        val meanNoon = n - lng / 360.0
        val m = norm(357.5291 + 0.98560028 * meanNoon)
        val mRad = rad(m)
        val center = 1.9148 * sin(mRad) + 0.0200 * sin(2 * mRad) + 0.0003 * sin(3 * mRad)
        val eclipticLng = rad(norm(m + center + 180 + 102.9372))
        val transit = J2000 + meanNoon + 0.0053 * sin(mRad) - 0.0069 * sin(2 * eclipticLng)
        val declination = asin(sin(eclipticLng) * sin(rad(OBLIQUITY)))

        val phi = rad(lat)
        val cosHourAngle = (sin(rad(HORIZON)) - sin(phi) * sin(declination)) /
            (cos(phi) * cos(declination))
        if (cosHourAngle > 1) return SunDay(null, null, alwaysDown = true)
        if (cosHourAngle < -1) return SunDay(null, null, alwaysUp = true)
        val halfDay = deg(acos(cosHourAngle)) / 360.0
        return SunDay(julianToInstant(transit - halfDay), julianToInstant(transit + halfDay))
    }

    /** True between official sunset and the next sunrise, at [lat]/[lng], on the calendar of [zone]. */
    fun isDark(now: Instant, lat: Double, lng: Double, zone: TimeZone): Boolean {
        val today = day(now.toLocalDateTime(zone).date, lat, lng)
        return when {
            today.alwaysDown -> true
            today.alwaysUp -> false
            else -> now < today.sunrise!! || now >= today.sunset!!
        }
    }

    /** Same as [isDark], for callers (iOS) that work in epoch milliseconds and zone IDs. */
    fun isDarkAt(epochMillis: Long, lat: Double, lng: Double, zoneId: String): Boolean =
        isDark(Instant.fromEpochMilliseconds(epochMillis), lat, lng, TimeZone.of(zoneId))

    private fun julianToInstant(jd: Double): Instant =
        Instant.fromEpochMilliseconds(((jd - UNIX_EPOCH_JD) * 86_400_000).toLong())

    private fun norm(deg: Double) = ((deg % 360) + 360) % 360
}
