package io.github.mtsprout.halfnav.core

/** Time-left estimates that know where the traffic delays are, and whether two routes are the same road. */
object Eta {
    /** A fresh route counts as the one you're on when this share of it lies on the old route. */
    private const val SAME_ROAD_SHARE = 0.9
    private const val SAME_ROAD_METERS = 60.0
    private const val SAMPLE_METERS = 400.0

    /**
     * Seconds left from [offsetMeters]. TomTom's total includes the delays on the route, so this
     * takes them out, spreads the rest evenly over the distance, and adds back only the delays you
     * haven't driven through yet. A jam ahead no longer shrinks as you drive toward it.
     */
    fun remainingSec(route: Route, offsetMeters: Double): Int {
        val length = route.lengthMeters
        if (length <= 0) return 0
        val offset = offsetMeters.coerceIn(0.0, length)
        val delays = route.traffic.sumOf { it.delaySec }
        val base = (route.travelTimeSec - delays).coerceAtLeast(0)
        var ahead = 0.0
        for (s in route.traffic) {
            val start = route.offsetOfPoint(s.startPointIndex)
            val end = route.offsetOfPoint(s.endPointIndex)
            ahead += s.delaySec * when {
                end <= start -> if (start >= offset) 1.0 else 0.0
                else -> ((end - maxOf(start, offset)) / (end - start)).coerceIn(0.0, 1.0)
            }
        }
        return (base * (length - offset) / length + ahead).toInt()
    }

    /**
     * True when [fresh], planned from where you are now, follows [old] ahead of [oldOffsetMeters]:
     * the same road, with a new estimate. A different road is a different route.
     */
    fun sameRoad(old: Route, oldOffsetMeters: Double, fresh: Route): Boolean {
        var samples = 0
        var near = 0
        var at = 0.0
        while (at <= fresh.lengthMeters) {
            samples++
            if (old.project(fresh.pointAt(at), oldOffsetMeters).distanceMeters <= SAME_ROAD_METERS) near++
            at += SAMPLE_METERS
        }
        return samples > 0 && near.toDouble() / samples >= SAME_ROAD_SHARE
    }
}
