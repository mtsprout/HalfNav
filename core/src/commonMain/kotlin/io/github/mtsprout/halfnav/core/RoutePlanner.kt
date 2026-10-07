package io.github.mtsprout.halfnav.core

import kotlin.math.roundToInt

/** Where Google Maps should take you before you're on your own. */
data class Handoff(
    val point: LatLng,
    val offsetMeters: Double,
    /** e.g. "I-95", "US-1", "the highway", "mile 5". */
    val label: String,
    /** True when the handoff is so close to the end that we should just navigate the whole way. */
    val reachesDestination: Boolean,
)

enum class WorkKind { ROAD_WORK, ROAD_CLOSURE }

data class ConstructionWarning(
    val kind: WorkKind,
    val startOffsetMeters: Double,
    val lengthMeters: Double,
    val delaySec: Int,
    val magnitude: Int,
    /** Road number or street name at the start of the zone, if known. */
    val road: String?,
    /** True if any part of the zone is past the handoff, i.e. you'd hit it without guidance. */
    val unguided: Boolean,
)

object RoutePlanner {
    /** Hand off ~0.3 mi onto the interstate so Maps puts you on it heading the right way. */
    const val ONTO_HIGHWAY_METERS = 480.0
    const val FALLBACK_MILES = 5.0
    /** If guidance would end within this distance of the destination, guide the whole way. */
    const val NEAR_END_METERS = Geo.METERS_PER_MILE

    private val INTERSTATE = Regex("^I[ -]?\\d+", RegexOption.IGNORE_CASE)
    private val US_HIGHWAY = Regex("^US[ -]?\\d+", RegexOption.IGNORE_CASE)

    fun interstateHandoff(route: Route): Handoff {
        for (pattern in listOf(INTERSTATE, US_HIGHWAY)) {
            for (ins in route.instructions) {
                val match = ins.roadNumbers.firstNotNullOfOrNull { pattern.find(it.trim()) } ?: continue
                val label = match.value.uppercase().replace(' ', '-')
                return handoffAt(route, route.offsetOfPoint(ins.pointIndex) + ONTO_HIGHWAY_METERS, label)
            }
        }
        route.motorways.firstOrNull()?.let { range ->
            return handoffAt(route, route.offsetOfPoint(range.first) + ONTO_HIGHWAY_METERS, "the highway")
        }
        return pointAtDistance(route, FALLBACK_MILES)
    }

    fun pointAtDistance(route: Route, miles: Double): Handoff =
        handoffAt(route, miles * Geo.METERS_PER_MILE, "mile ${formatMiles(miles)}")

    fun construction(route: Route, handoff: Handoff): List<ConstructionWarning> =
        route.traffic.mapNotNull { section ->
            val kind = when (section.category) {
                "ROAD_WORK" -> WorkKind.ROAD_WORK
                "ROAD_CLOSURE" -> WorkKind.ROAD_CLOSURE
                else -> return@mapNotNull null
            }
            val start = route.offsetOfPoint(section.startPointIndex)
            val end = route.offsetOfPoint(section.endPointIndex)
            ConstructionWarning(
                kind = kind,
                startOffsetMeters = start,
                lengthMeters = end - start,
                delaySec = section.delaySec,
                magnitude = section.magnitude,
                road = roadAt(route, section.startPointIndex),
                unguided = !handoff.reachesDestination && end > handoff.offsetMeters,
            )
        }.sortedBy { it.startOffsetMeters }

    private fun handoffAt(route: Route, offsetMeters: Double, label: String): Handoff {
        val nearEnd = offsetMeters >= route.lengthMeters - NEAR_END_METERS
        val offset = if (nearEnd) route.lengthMeters else offsetMeters
        return Handoff(route.pointAt(offset), offset, label, reachesDestination = nearEnd)
    }

    private fun roadAt(route: Route, pointIndex: Int): String? =
        route.instructions
            .lastOrNull { it.pointIndex <= pointIndex && (it.roadNumbers.isNotEmpty() || it.street != null) }
            ?.let { it.roadNumbers.firstOrNull() ?: it.street }

    private fun formatMiles(miles: Double): String =
        (miles * 10).roundToInt().let { tenths ->
            if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
        }
}
