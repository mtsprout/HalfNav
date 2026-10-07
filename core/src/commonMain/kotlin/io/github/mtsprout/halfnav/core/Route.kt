package io.github.mtsprout.halfnav.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lng: Double)

internal fun rad(degrees: Double) = degrees * PI / 180
internal fun deg(radians: Double) = radians * 180 / PI

object Geo {
    const val METERS_PER_MILE = 1609.344
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = rad(b.lat - a.lat)
        val dLng = rad(b.lng - a.lng)
        val h = sin(dLat / 2).pow(2) +
            cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLng / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    fun metersToMiles(m: Double) = m / METERS_PER_MILE

    /** Compass bearing from [a] to [b], 0..360 degrees (0 = north). */
    fun bearing(a: LatLng, b: LatLng): Double {
        val lat1 = rad(a.lat)
        val lat2 = rad(b.lat)
        val dLng = rad(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (deg(atan2(y, x)) + 360) % 360
    }
}

/** Where a GPS fix falls on a route. */
data class Projection(
    /** Snapped point on the route. */
    val point: LatLng,
    /** Distance along the route to [point]. */
    val offsetMeters: Double,
    /** How far the fix is from the route. */
    val distanceMeters: Double,
    /** Direction of travel along the route at [point]. */
    val bearing: Double,
)

/** A turn-by-turn instruction. [roadNumbers] describe the road you're on *after* the maneuver. */
data class Instruction(
    val pointIndex: Int,
    val roadNumbers: List<String>,
    val street: String?,
    val maneuver: String?,
    /** Human-readable text, e.g. "Keep left at I-95 N toward New York". */
    val message: String? = null,
)

/** A stretch of the route TomTom flags with live traffic info (jam, road work, closure...). */
data class TrafficSection(
    val startPointIndex: Int,
    val endPointIndex: Int,
    /** TomTom simpleCategory: JAM, ROAD_WORK, ROAD_CLOSURE or OTHER. */
    val category: String,
    val delaySec: Int,
    /** 0 unknown, 1 minor, 2 moderate, 3 major, 4 indefinite. */
    val magnitude: Int,
)

class Route(
    val points: List<LatLng>,
    val travelTimeSec: Int,
    val instructions: List<Instruction>,
    val traffic: List<TrafficSection>,
    /** Point-index ranges TomTom marks as motorway. */
    val motorways: List<IntRange>,
) {
    init {
        require(points.size >= 2) { "Route needs at least two points" }
    }

    /** Distance from the start of the route to each point, in meters. */
    val cumulativeMeters: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + Geo.distanceMeters(points[i - 1], points[i])
    }

    val lengthMeters: Double get() = cumulativeMeters.last()

    fun offsetOfPoint(index: Int): Double = cumulativeMeters[index.coerceIn(0, points.lastIndex)]

    /**
     * Snaps [p] onto the route. Looks near [hintOffsetMeters] first (where we were last time) so
     * the position doesn't jump to a different part of a route that loops back near itself.
     */
    fun project(p: LatLng, hintOffsetMeters: Double? = null): Projection {
        if (hintOffsetMeters != null) {
            val near = projectWithin(p, hintOffsetMeters - HINT_BEHIND_M, hintOffsetMeters + HINT_AHEAD_M)
            if (near.distanceMeters < HINT_MAX_DISTANCE_M) return near
        }
        return projectWithin(p, 0.0, lengthMeters)
    }

    private fun projectWithin(p: LatLng, fromMeters: Double, toMeters: Double): Projection {
        // Local flat-earth projection in meters around p; plenty accurate for snapping.
        val mPerLat = 111_320.0
        val mPerLng = 111_320.0 * cos(rad(p.lat))
        fun x(q: LatLng) = (q.lng - p.lng) * mPerLng
        fun y(q: LatLng) = (q.lat - p.lat) * mPerLat

        var best: Projection? = null
        var bestDist2 = Double.MAX_VALUE
        for (i in 1 until points.size) {
            if (cumulativeMeters[i] < fromMeters || cumulativeMeters[i - 1] > toMeters) continue
            val a = points[i - 1]
            val b = points[i]
            val ax = x(a); val ay = y(a)
            val dx = x(b) - ax; val dy = y(b) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + t * dx; val py = ay + t * dy
            val d2 = px * px + py * py
            if (d2 < bestDist2) {
                bestDist2 = d2
                val snapped = LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
                val offset = cumulativeMeters[i - 1] + (cumulativeMeters[i] - cumulativeMeters[i - 1]) * t
                best = Projection(snapped, offset, 0.0, if (len2 == 0.0) 0.0 else Geo.bearing(a, b))
            }
        }
        val result = best ?: Projection(points.first(), 0.0, 0.0, Geo.bearing(points[0], points[1]))
        return result.copy(distanceMeters = sqrt(if (best == null) Double.MAX_VALUE else bestDist2))
    }

    /** The next instruction more than [aheadOfMeters] past [offsetMeters], with its offset. */
    fun nextInstruction(offsetMeters: Double, aheadOfMeters: Double = 10.0): Pair<Instruction, Double>? =
        instructions.asSequence()
            .map { it to offsetOfPoint(it.pointIndex) }
            .firstOrNull { (_, off) -> off > offsetMeters + aheadOfMeters }

    private companion object {
        const val HINT_BEHIND_M = 500.0
        const val HINT_AHEAD_M = 5_000.0
        const val HINT_MAX_DISTANCE_M = 200.0
    }

    /** The point [offsetMeters] along the route, interpolated between polyline points. */
    fun pointAt(offsetMeters: Double): LatLng {
        if (offsetMeters <= 0) return points.first()
        if (offsetMeters >= lengthMeters) return points.last()
        // First index whose offset is at or past offsetMeters (binary search).
        var lo = 0
        var hi = cumulativeMeters.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cumulativeMeters[mid] < offsetMeters) lo = mid + 1 else hi = mid
        }
        if (cumulativeMeters[lo] == offsetMeters) return points[lo]
        val i = lo
        val a = points[i - 1]
        val b = points[i]
        val segment = cumulativeMeters[i] - cumulativeMeters[i - 1]
        val t = if (segment == 0.0) 0.0 else (offsetMeters - cumulativeMeters[i - 1]) / segment
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
    }
}

/** The part of a route between two offsets, for drawing the guided / unguided / construction stretches. */
fun Route.slice(fromMeters: Double, toMeters: Double): List<LatLng> {
    val from = fromMeters.coerceIn(0.0, lengthMeters)
    val to = toMeters.coerceIn(from, lengthMeters)
    val inside = points.indices.filter { cumulativeMeters[it] > from && cumulativeMeters[it] < to }.map { points[it] }
    return listOf(pointAt(from)) + inside + pointAt(to)
}
