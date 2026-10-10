package io.github.mtsprout.halfnav.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import io.github.mtsprout.halfnav.core.Geo
import kotlin.math.cos
import kotlin.math.sin

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

private val iconCache = HashMap<Int, CarIcon>()

/**
 * A simple arrow image for a maneuver type. The car host draws no icons of its own, so the turn
 * card needs one from the app. White on transparent; the host tints it to suit its theme.
 */
fun carManeuverIcon(type: Int): CarIcon = iconCache.getOrPut(type) {
    val size = 160
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 18f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    // Angle the road turns to, in degrees clockwise from straight ahead.
    val angle = when (type) {
        Maneuver.TYPE_TURN_NORMAL_RIGHT, Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT -> 90f
        Maneuver.TYPE_TURN_NORMAL_LEFT, Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT -> -90f
        Maneuver.TYPE_TURN_SHARP_RIGHT -> 135f
        Maneuver.TYPE_TURN_SHARP_LEFT -> -135f
        Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT -> 40f
        Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT -> -40f
        Maneuver.TYPE_DESTINATION_RIGHT -> 40f
        Maneuver.TYPE_DESTINATION_LEFT -> -40f
        else -> 0f
    }
    when (type) {
        Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_LEFT, Maneuver.TYPE_DESTINATION_RIGHT -> {
            c.drawCircle(size / 2f, size / 2f - 8f, 40f, stroke)
            c.drawCircle(size / 2f, size / 2f - 8f, 14f, fill)
        }
        Maneuver.TYPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_RIGHT -> {
            val p = Path().apply {
                moveTo(112f, 140f)
                lineTo(112f, 70f)
                arcTo(RectF(48f, 24f, 112f, 88f), 0f, -180f)
                lineTo(48f, 100f)
            }
            c.drawPath(p, stroke)
            arrowHead(c, fill, 48f, 108f, 180f)
        }
        else -> {
            // Up the middle, then bending off by [angle]; the head sits on the end of the bend.
            val baseX = size / 2f
            val bendY = 92f
            val len = 62f
            val rad = Math.toRadians(angle.toDouble())
            val endX = baseX + (len * sin(rad)).toFloat()
            val endY = bendY - (len * cos(rad)).toFloat()
            val p = Path().apply {
                moveTo(baseX, 148f)
                lineTo(baseX, bendY)
                lineTo(endX, endY)
            }
            c.drawPath(p, stroke)
            arrowHead(c, fill, endX, endY, angle)
        }
    }
    CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
}

/** A filled triangle at ([x], [y]) pointing [angle] degrees clockwise from straight up. */
private fun arrowHead(c: Canvas, paint: Paint, x: Float, y: Float, angle: Float) {
    c.save()
    c.rotate(angle, x, y)
    val head = Path().apply {
        moveTo(x, y - 30f)
        lineTo(x + 28f, y + 10f)
        lineTo(x - 28f, y + 10f)
        close()
    }
    c.drawPath(head, paint)
    c.restore()
}
