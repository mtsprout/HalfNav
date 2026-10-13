package io.github.mtsprout.halfnav.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Says which way to go at a roundabout from the shape of the route, not from TomTom's exit number.
 * That number isn't a reliable count: measured on real routes, straight across was "the third
 * exit" at one roundabout and "the first exit" at another.
 *
 * TomTom follows a roundabout instruction with a "take the exit" one where the route leaves the
 * circle. The road's direction a little before the entrance, compared with its direction a little
 * after the exit, is the real turn, however many legs the roundabout has.
 */
object Roundabouts {
    /** An exit cue within this far of the entrance belongs to the same roundabout. */
    const val MAX_CIRCLE_METERS = 400.0

    /** Without an exit cue, how far past the entrance to look for the road you leave on. */
    const val NO_CUE_EXIT_METERS = 80.0

    /** How far before the entrance and after the exit to read the road's direction. */
    const val SAMPLE_METERS = 30.0

    /** [route] with each roundabout's turn measured and its wording replaced. */
    fun refine(route: Route): Route {
        val all = route.instructions
        val refined = all.mapIndexed { i, ins ->
            if (ins.maneuver?.contains("ROUNDABOUT") != true) return@mapIndexed ins
            val enterAt = route.offsetOfPoint(ins.pointIndex)
            val next = all.getOrNull(i + 1)
            val exitCue = next?.takeIf { it.maneuver == "TAKE_EXIT" && route.offsetOfPoint(it.pointIndex) - enterAt <= MAX_CIRCLE_METERS }
            val leaveAt = when {
                exitCue != null -> route.offsetOfPoint(exitCue.pointIndex)
                // No separate exit cue: the road a little way on is the road you leave on, as long as
                // nothing else (another turn) comes up first.
                next == null || route.offsetOfPoint(next.pointIndex) - enterAt > NO_CUE_EXIT_METERS + SAMPLE_METERS ->
                    enterAt + NO_CUE_EXIT_METERS
                else -> return@mapIndexed ins
            }
            if (leaveAt + SAMPLE_METERS > route.lengthMeters) return@mapIndexed ins
            val turn = turn(route, enterAt, leaveAt)
            val street = exitCue?.street ?: ins.street ?: streetIn(ins.message)
            ins.copy(
                roundaboutTurn = turn,
                message = "At the roundabout, ${direction(turn)}" + (street?.let { " onto $it" } ?: ""),
            )
        }
        return Route(route.points, route.travelTimeSec, refined, route.traffic, route.motorways)
    }

    /** Degrees clockwise from the way the route enters at [enterAt] to the way it leaves at [leaveAt]. */
    fun turn(route: Route, enterAt: Double, leaveAt: Double): Int {
        val into = Geo.bearing(route.pointAt(enterAt - SAMPLE_METERS), route.pointAt(enterAt))
        val out = Geo.bearing(route.pointAt(leaveAt), route.pointAt(leaveAt + SAMPLE_METERS))
        return (((out - into + 540.0) % 360.0) - 180.0).roundToInt()
    }

    /** The street name in "... onto Main St", or null. */
    private fun streetIn(message: String?): String? =
        message?.let { Regex("""\bonto (.+)$""").find(it)?.groupValues?.get(1)?.trim()?.ifBlank { null } }

    /** "continue straight", "turn right", ... for a turn of [degrees] (right is positive). */
    fun direction(degrees: Int): String {
        val side = if (degrees >= 0) "right" else "left"
        return when (abs(degrees)) {
            in 0..35 -> "continue straight"
            in 36..75 -> "bear $side"
            in 76..135 -> "turn $side"
            in 136..165 -> "turn sharply $side"
            else -> "make a U-turn"
        }
    }
}
