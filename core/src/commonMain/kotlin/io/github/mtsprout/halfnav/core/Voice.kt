package io.github.mtsprout.halfnav.core

import kotlin.math.roundToInt

/** Turns TomTom's on-screen instruction text and distances into natural spoken phrases. */
object SpokenText {
    private val ROAD_NUMBER = Regex("^(I|US)-\\d")
    private val CONNECTORS = listOf(" onto ", " at ", " on ")

    private val SUFFIXES = mapOf(
        "Ave" to "Avenue", "Blvd" to "Boulevard", "Rd" to "Road", "Dr" to "Drive", "Ln" to "Lane",
        "Pkwy" to "Parkway", "Hwy" to "Highway", "Expy" to "Expressway", "Fwy" to "Freeway",
        "Bus" to "Business", "Ct" to "Court", "Cir" to "Circle", "Pl" to "Place", "Sq" to "Square",
        "Trl" to "Trail", "Plz" to "Plaza", "Pk" to "Park",
    )
    private val DIRECTIONS = mapOf("N" to "North", "S" to "South", "E" to "East", "W" to "West")

    /**
     * "Keep left at Interstate Highway 95 N/I-95 N toward New York" ->
     * "Keep left at I 95 North toward New York".
     */
    fun instruction(message: String): String = expand(pickOneName(message))

    /**
     * TomTom lists every name for a road ("United States Highway 30 E/I-676 E/US-30 E").
     * Speak just one: the interstate or US highway number if there is one, otherwise the first.
     */
    private fun pickOneName(message: String): String {
        if (!message.contains('/')) return message
        val parts = message.split('/')
        val first = parts.first()
        val connector = CONNECTORS.mapNotNull { c -> first.lastIndexOf(c).takeIf { it >= 0 }?.let { it + c.length } }
            .maxOrNull() ?: return message.replace("/", " or ")
        val head = first.substring(0, connector)
        val last = parts.last()
        val towardAt = last.indexOf(" toward ")
        val tail = if (towardAt >= 0) last.substring(towardAt) else ""
        val names = listOf(first.substring(connector)) + parts.drop(1).dropLast(1) +
            (if (towardAt >= 0) last.substring(0, towardAt) else last)
        val pick = names.map { it.trim() }.firstOrNull { ROAD_NUMBER.containsMatchIn(it) } ?: names.first().trim()
        return head + pick + tail
    }

    private fun expand(text: String): String {
        var s = text
        s = s.replace(Regex("\\bI-(\\d+)"), "I $1")
        s = s.replace(Regex("\\bUS-(\\d+)"), "U.S. $1")
        s = s.replace(Regex("\\b([A-Z]{2})-(\\d+)"), "$1 $2")
        // Direction after a road number or name ending: "I 35 S" -> "I 35 South", "Business S".
        s = s.replace(Regex("(?<=[\\w]) ([NSEW])\\b(?![.\\-])")) { " " + DIRECTIONS.getValue(it.groupValues[1]) }
        // Direction before a street name: "N Broad St" -> "North Broad St".
        s = s.replace(Regex("\\b([NSEW]) (?=[A-Z][a-z])")) { DIRECTIONS.getValue(it.groupValues[1]) + " " }
        // "St" before a name is Saint ("St Mary's"); elsewhere it's Street.
        s = s.replace(Regex("\\bSt\\.? (?=[A-Z][a-z])"), "Saint ")
        s = s.replace(Regex("\\bSt\\b\\.?"), "Street")
        SUFFIXES.forEach { (abbr, word) -> s = s.replace(Regex("\\b$abbr\\b\\.?"), word) }
        return s
    }

    /** "500 feet", "a quarter mile", "half a mile", "1.5 miles", "12 miles". */
    fun distance(meters: Double): String {
        val miles = Geo.metersToMiles(meters)
        if (miles < 0.19) {
            val feet = ((meters * 3.28084) / 100).roundToInt().coerceAtLeast(1) * 100
            return "$feet feet"
        }
        if (miles < 0.9) {
            return when ((miles * 4).roundToInt()) {
                1 -> "a quarter mile"
                2 -> "half a mile"
                else -> "three quarters of a mile"
            }
        }
        if (miles < 10) {
            val halves = (miles * 2).roundToInt() / 2.0
            return if (halves == 1.0) "1 mile"
            else if (halves % 1.0 == 0.0) "${halves.toInt()} miles" else "$halves miles"
        }
        return "${miles.roundToInt()} miles"
    }
}

/** Everything the voice needs to know about the trip right now. */
data class VoiceInput(
    /** True while HalfNav should be giving turn-by-turn directions. */
    val guided: Boolean,
    val next: Instruction?,
    val metersToNext: Double,
    val speedMps: Float,
    /** Identifies the next construction zone so it's announced once. */
    val warningKey: Int?,
    /** e.g. "Road work" or "Road closed". */
    val warningWhat: String?,
    /** Road the zone is on, if known. */
    val warningRoad: String?,
    val metersToWarning: Double,
    val rerouting: Boolean,
    val arrived: Boolean,
    val destinationName: String,
    /** Said when guidance begins, e.g. "Starting guidance to I 35." */
    val guidanceStarts: String,
    /** Said when guidance hands over to you, e.g. "You're on I 35. Guidance ends here. You're on your own." */
    val guidanceEnds: String,
    /** Changes whenever the route is replaced (reroute), so instructions are announced afresh. */
    val routeVersion: Int,
    /** The route ends at the destination's parking lot: arriving means turning into it. */
    val parkingLot: Boolean = false,
)

/**
 * Decides what to say, and when, as the trip progresses. Each turn gets an early heads-up
 * ("In half a mile, turn right onto Main Street") and a prompt at the turn itself. Construction
 * and arrival are announced even while you're on your own.
 */
class VoicePrompts {
    private val early = mutableSetOf<Int>()
    private val now = mutableSetOf<Int>()
    private val warned = mutableSetOf<Int>()
    private var wasGuided: Boolean? = null
    private var wasRerouting = false
    private var arrivedSpoken = false
    private var routeVersion: Int? = null

    fun update(v: VoiceInput): List<String> {
        val out = mutableListOf<String>()
        if (routeVersion != v.routeVersion) {
            early.clear()
            now.clear()
            routeVersion = v.routeVersion
        }

        if (v.arrived) {
            if (!arrivedSpoken) out += if (v.parkingLot) "Turn into the parking lot." else "You've arrived at ${v.destinationName}."
            arrivedSpoken = true
            wasGuided = v.guided
            return out
        }

        if (v.rerouting && !wasRerouting && v.guided) out += "Rerouting."
        wasRerouting = v.rerouting

        val highway = v.speedMps >= HIGHWAY_MPS
        val earlyAt = if (highway) EARLY_HIGHWAY_M else EARLY_CITY_M
        val nowAt = if (highway) NOW_HIGHWAY_M else NOW_CITY_M
        val next = v.next?.takeIf { !it.maneuver.orEmpty().startsWith("ARRIVE") && it.message != null }

        when {
            v.guided && wasGuided != true -> {
                // Guidance just began: say so, then where the first turn is.
                out += v.guidanceStarts
                if (next != null) {
                    val text = SpokenText.instruction(next.message!!)
                    if (v.metersToNext <= nowAt) {
                        out += "$text."
                        now += next.pointIndex
                    } else {
                        out += "In ${SpokenText.distance(v.metersToNext)}, ${text.decapitalize()}."
                    }
                    early += next.pointIndex
                }
            }
            !v.guided && wasGuided == true -> out += v.guidanceEnds
            v.guided && next != null && !v.rerouting -> {
                val id = next.pointIndex
                val text = SpokenText.instruction(next.message!!)
                if (v.metersToNext <= nowAt && id !in now) {
                    out += "$text."
                    now += id
                    early += id
                } else if (v.metersToNext <= earlyAt && id !in early) {
                    out += "In ${SpokenText.distance(v.metersToNext)}, ${text.decapitalize()}."
                    early += id
                }
            }
        }
        wasGuided = v.guided

        val warnAt = if (highway) WARN_HIGHWAY_M else WARN_CITY_M
        if (v.warningKey != null && v.warningWhat != null && v.warningKey !in warned && v.metersToWarning <= warnAt) {
            val where = v.warningRoad?.let { " on ${SpokenText.instruction(it)}" }.orEmpty()
            out += if (v.metersToWarning < 150) "${v.warningWhat} ahead$where."
            else "${v.warningWhat} ahead in ${SpokenText.distance(v.metersToWarning)}$where."
            warned += v.warningKey
        }
        return out.map { it.replace("..", ".") }
    }

    private fun String.decapitalize() = replaceFirstChar { it.lowercase() }

    companion object {
        /** ~45 mph and up counts as highway driving: prompts come earlier. */
        const val HIGHWAY_MPS = 20f
        const val EARLY_HIGHWAY_M = Geo.METERS_PER_MILE
        const val EARLY_CITY_M = 400.0
        const val NOW_HIGHWAY_M = 300.0
        const val NOW_CITY_M = 70.0
        const val WARN_HIGHWAY_M = 2 * Geo.METERS_PER_MILE
        const val WARN_CITY_M = 0.5 * Geo.METERS_PER_MILE
    }
}
