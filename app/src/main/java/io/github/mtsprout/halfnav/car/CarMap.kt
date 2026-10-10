package io.github.mtsprout.halfnav.car

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import io.github.mtsprout.halfnav.DriveProgress
import io.github.mtsprout.halfnav.DriveTrip
import io.github.mtsprout.halfnav.Mode
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.SunTimes
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A simple heading-up map for the car screen: the route ahead, drawn the way the phone draws it
 * (solid blue where HalfNav guides, dashed gray where you're on your own, orange for construction),
 * with your position low on the screen. There are no street tiles; MapLibre can't draw on the car
 * surface, so this draws the route itself. Nothing here needs a network.
 */
class CarMap(private val carContext: CarContext) : SurfaceCallback {

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var density = 1f
    private var visible: Rect? = null

    private var trip: DriveTrip? = null
    private var progress: DriveProgress? = null
    private var shownLookAhead = 0.0

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Starts receiving the car's drawing surface. Call [detach] when the screen goes away. */
    fun attach() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    fun detach() {
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
        surface = null
    }

    /** New position along the trip; redraws. */
    fun update(trip: DriveTrip, progress: DriveProgress) {
        this.trip = trip
        this.progress = progress
        draw()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        width = surfaceContainer.width
        height = surfaceContainer.height
        density = surfaceContainer.dpi / 160f
        draw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    // --- Drawing ---

    private fun draw() {
        val s = surface?.takeIf { it.isValid } ?: return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: IllegalStateException) {
            return
        } catch (e: IllegalArgumentException) {
            return
        }
        try {
            render(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun render(canvas: Canvas) {
        val trip = trip
        val p = progress
        val dark = isDark(trip, p)
        canvas.drawColor(if (dark) BG_DARK else BG_LIGHT)
        if (trip == null || p == null) return

        val area = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        val route = trip.route
        val offset = p.offsetMeters.coerceIn(0.0, route.lengthMeters)
        val here = p.position?.let { LatLng(it.latitude, it.longitude) } ?: route.pointAt(offset)
        val heading = (p.position?.bearing?.toDouble() ?: Geo.bearing(here, route.pointAt(offset + 20.0)))

        // Your position sits low on the screen so most of it shows the road ahead.
        val cx = area.exactCenterX()
        val cy = area.top + area.height() * 0.72f
        val lookAhead = easedLookAhead(trip, p)
        val pxPerMeter = ((cy - area.top) / lookAhead).toFloat()

        val lat0 = here.lat
        val mPerLng = 111_320.0 * cos(lat0 * PI / 180)
        val h = heading * PI / 180
        val sinH = sin(h)
        val cosH = cos(h)
        fun sx(q: LatLng): Float {
            val east = (q.lng - here.lng) * mPerLng
            val north = (q.lat - lat0) * 110_540.0
            return (cx + (east * cosH - north * sinH) * pxPerMeter).toFloat()
        }
        fun sy(q: LatLng): Float {
            val east = (q.lng - here.lng) * mPerLng
            val north = (q.lat - lat0) * 110_540.0
            return (cy - (east * sinH + north * cosH) * pxPerMeter).toFloat()
        }

        // Which part of the route is guided, as offsets along it.
        val guided = guidedRange(trip)

        val from = (offset - BEHIND_METERS).coerceAtLeast(0.0)
        val to = (offset + lookAhead * 1.6).coerceAtMost(route.lengthMeters)
        val casing = if (dark) 0xFF0F1113.toInt() else 0xFFFFFFFF.toInt()
        val strokeW = 7f * density

        // Route in runs of the same look, so each run is one path.
        var path = Path()
        var runKind = -1
        var started = false
        fun flush() {
            if (!started) return
            drawRun(canvas, path, runKind, strokeW, casing, dark)
            path = Path()
            started = false
        }
        for (i in 1 until route.points.size) {
            val a = route.cumulativeMeters[i - 1]
            val b = route.cumulativeMeters[i]
            if (b < from || a > to) continue
            val mid = (a + b) / 2
            val kind = when {
                mid < offset -> KIND_PAST
                guided != null && mid in guided -> KIND_GUIDED
                else -> KIND_ALONE
            }
            val pa = route.points[i - 1]
            val pb = route.points[i]
            if (kind != runKind) {
                flush()
                runKind = kind
                path.moveTo(sx(pa), sy(pa))
                started = true
            }
            path.lineTo(sx(pb), sy(pb))
        }
        flush()

        // Construction zones over the route.
        trip.warnings.forEach { w ->
            val a = w.startOffsetMeters.coerceAtLeast(from)
            val b = (w.startOffsetMeters + w.lengthMeters).coerceAtMost(to)
            if (b <= a) return@forEach
            val wp = Path()
            val pts = pointsBetween(route, a, b)
            pts.forEachIndexed { k, q -> if (k == 0) wp.moveTo(sx(q), sy(q)) else wp.lineTo(sx(q), sy(q)) }
            line.pathEffect = null
            line.color = ORANGE
            line.strokeWidth = strokeW * 1.15f
            canvas.drawPath(wp, line)
        }

        // Where guidance ends, and the destination.
        trip.handoff?.takeIf { !it.reachesDestination && it.offsetMeters in from..to }?.let {
            marker(canvas, sx(it.point), sy(it.point), 0xFFFFFFFF.toInt(), BLUE, 7f)
        }
        if (route.lengthMeters <= to) {
            val d = route.points.last()
            marker(canvas, sx(d), sy(d), RED, 0xFFFFFFFF.toInt(), 8f)
        }

        vehicle(canvas, cx, cy)
    }

    private fun drawRun(canvas: Canvas, path: Path, kind: Int, width: Float, casing: Int, dark: Boolean) {
        line.pathEffect = null
        // A casing under the line keeps it readable against the background.
        line.color = casing
        line.strokeWidth = width + 3f * density
        canvas.drawPath(path, line)
        when (kind) {
            KIND_GUIDED -> {
                line.color = BLUE
                line.strokeWidth = width
            }
            KIND_ALONE -> {
                line.color = if (dark) GRAY else GRAY_DARK
                line.strokeWidth = width * 0.8f
                line.pathEffect = DashPathEffect(floatArrayOf(14f * density, 10f * density), 0f)
            }
            else -> {
                line.color = if (dark) 0xFF5F6368.toInt() else 0xFFAAB0B7.toInt()
                line.strokeWidth = width * 0.8f
            }
        }
        canvas.drawPath(path, line)
        line.pathEffect = null
    }

    private fun marker(canvas: Canvas, x: Float, y: Float, color: Int, stroke: Int, radiusDp: Float) {
        fill.color = stroke
        canvas.drawCircle(x, y, (radiusDp + 3f) * density, fill)
        fill.color = color
        canvas.drawCircle(x, y, radiusDp * density, fill)
    }

    /** Your position: an arrow pointing up, since the map turns with you. */
    private fun vehicle(canvas: Canvas, cx: Float, cy: Float) {
        val r = 14f * density
        val arrow = Path().apply {
            moveTo(cx, cy - r * 1.5f)
            lineTo(cx + r, cy + r)
            lineTo(cx, cy + r * 0.45f)
            lineTo(cx - r, cy + r)
            close()
        }
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(arrow, fill)
        val inner = Path().apply {
            moveTo(cx, cy - r * 1.1f)
            lineTo(cx + r * 0.7f, cy + r * 0.6f)
            lineTo(cx, cy + r * 0.2f)
            lineTo(cx - r * 0.7f, cy + r * 0.6f)
            close()
        }
        fill.color = BLUE
        canvas.drawPath(inner, fill)
    }

    // --- Helpers ---

    /**
     * How far ahead the screen shows, in meters: more at speed, then closing in as a guided turn
     * approaches so its shape is clear. Eased between updates so the zoom doesn't jump.
     */
    private fun easedLookAhead(trip: DriveTrip, p: DriveProgress): Double {
        val base = (p.speedMps * 25.0).coerceIn(500.0, 2500.0)
        val turnClose = p.next != null && p.guided(trip) && p.metersToNext < base
        val target = if (turnClose) (p.metersToNext * 1.5 + 150.0).coerceIn(MIN_LOOK_AHEAD, base) else base
        val now = if (shownLookAhead <= 0.0) target else shownLookAhead + (target - shownLookAhead) * ZOOM_EASE
        shownLookAhead = now
        return now
    }

    /** Offsets along the route where HalfNav guides, or null if it never does. */
    private fun guidedRange(trip: DriveTrip): ClosedFloatingPointRange<Double>? {
        val route = trip.route
        if (trip.mode == Mode.END_MILES) {
            val radius = (trip.watchMiles ?: return null) * Geo.METERS_PER_MILE
            val dest = trip.destination.latLng
            val first = route.points.indices.firstOrNull { Geo.distanceMeters(route.points[it], dest) <= radius }
                ?: return null
            return route.cumulativeMeters[first]..route.lengthMeters
        }
        val handoff = trip.handoff ?: return null
        return 0.0..(if (handoff.reachesDestination) route.lengthMeters else handoff.offsetMeters)
    }

    private fun pointsBetween(route: io.github.mtsprout.halfnav.core.Route, from: Double, to: Double): List<LatLng> {
        val out = mutableListOf(route.pointAt(from))
        for (i in route.points.indices) {
            val c = route.cumulativeMeters[i]
            if (c > from && c < to) out += route.points[i]
        }
        out += route.pointAt(to)
        return out
    }

    private fun isDark(trip: DriveTrip?, p: DriveProgress?): Boolean {
        val at = p?.position?.let { LatLng(it.latitude, it.longitude) } ?: trip?.route?.points?.first()
            ?: return true
        return SunTimes.isDarkAt(System.currentTimeMillis(), at.lat, at.lng, TimeZone.getDefault().id)
    }

    private companion object {
        const val KIND_PAST = 0
        const val KIND_GUIDED = 1
        const val KIND_ALONE = 2
        const val BEHIND_METERS = 300.0
        const val MIN_LOOK_AHEAD = 300.0
        const val ZOOM_EASE = 0.35

        const val BLUE = 0xFF1A73E8.toInt()
        const val GRAY = 0xFF9AA0A6.toInt()
        const val GRAY_DARK = 0xFF5F6368.toInt()
        const val ORANGE = 0xFFF29900.toInt()
        const val RED = 0xFFD93025.toInt()
        const val BG_DARK = 0xFF202124.toInt()
        const val BG_LIGHT = 0xFFF1F3F4.toInt()
    }
}
