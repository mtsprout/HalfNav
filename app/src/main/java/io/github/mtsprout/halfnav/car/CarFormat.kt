package io.github.mtsprout.halfnav.car

import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import io.github.mtsprout.halfnav.core.Geo

/** Maps a TomTom maneuver code to the car host's maneuver type. */
fun carManeuverType(maneuver: String?): Int {
    val m = maneuver ?: return Maneuver.TYPE_STRAIGHT
    return when {
        m == "ARRIVE_LEFT" -> Maneuver.TYPE_DESTINATION_LEFT
        m == "ARRIVE_RIGHT" -> Maneuver.TYPE_DESTINATION_RIGHT
        m.startsWith("ARRIVE") -> Maneuver.TYPE_DESTINATION
        m == "DEPART" -> Maneuver.TYPE_DEPART
        m.contains("UTURN") -> Maneuver.TYPE_U_TURN_LEFT
        m.contains("FERRY") -> Maneuver.TYPE_FERRY_BOAT
        m.contains("EXIT") && m.contains("LEFT") -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
        m.contains("EXIT") && m.contains("RIGHT") -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        m.contains("EXIT") -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        m.contains("KEEP") && m.contains("LEFT") -> Maneuver.TYPE_KEEP_LEFT
        m.contains("KEEP") && m.contains("RIGHT") -> Maneuver.TYPE_KEEP_RIGHT
        m.contains("SHARP") && m.contains("LEFT") -> Maneuver.TYPE_TURN_SHARP_LEFT
        m.contains("SHARP") && m.contains("RIGHT") -> Maneuver.TYPE_TURN_SHARP_RIGHT
        m.contains("BEAR") && m.contains("LEFT") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        m.contains("BEAR") && m.contains("RIGHT") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        m == "TURN_LEFT" -> Maneuver.TYPE_TURN_NORMAL_LEFT
        m == "TURN_RIGHT" -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        m.contains("LEFT") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        m.contains("RIGHT") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        // Roundabouts need an exit number TomTom's code doesn't carry; the cue text says which exit.
        else -> Maneuver.TYPE_STRAIGHT
    }
}

/** "500 ft" up close, then miles with one decimal, like the phone's turn banner. */
fun carDistance(meters: Double): Distance {
    val miles = Geo.metersToMiles(meters)
    return if (miles < 0.19) {
        val feet = ((meters * 3.28084) / 50).toInt().coerceAtLeast(1) * 50
        Distance.create(feet.toDouble(), Distance.UNIT_FEET)
    } else if (miles < 10) {
        Distance.create(Math.round(miles * 10) / 10.0, Distance.UNIT_MILES_P1)
    } else {
        Distance.create(Math.round(miles).toDouble(), Distance.UNIT_MILES)
    }
}
