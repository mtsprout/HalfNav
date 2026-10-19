package io.github.mtsprout.halfnav.car

import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

import io.github.mtsprout.halfnav.DriveProgress
import io.github.mtsprout.halfnav.DriveTrip
import io.github.mtsprout.halfnav.MapContent
import io.github.mtsprout.halfnav.Mode
import io.github.mtsprout.halfnav.Prefs
import io.github.mtsprout.halfnav.STYLE_DARK
import io.github.mtsprout.halfnav.STYLE_LIGHT
import io.github.mtsprout.halfnav.Vehicle
import io.github.mtsprout.halfnav.activateLocation
import io.github.mtsprout.halfnav.addTripLayers
import io.github.mtsprout.halfnav.arrivalTime
import io.github.mtsprout.halfnav.puckOptions
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.SunTimes
import io.github.mtsprout.halfnav.formatMinutes
import io.github.mtsprout.halfnav.formatMiles
import io.github.mtsprout.halfnav.updateTripLayers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import java.util.TimeZone
import kotlin.math.abs

/**
 * The car screen's map: a real MapLibre street map with the route on it, tilted and following
 * you like the phone's drive view. MapLibre can't draw onto the car's surface itself, so the map
 * lives in a [Presentation] on a [VirtualDisplay] that is backed by that surface.
 *
 * If any of that fails, or the map style can't be loaded, it falls back to [CarMap], which draws
 * just the route and needs no network.
 */
class CarLiveMap(private val carContext: CarContext) : SurfaceCallback {

    private val fallback = CarMap(carContext)
    private var usingFallback = false

    private var container: SurfaceContainer? = null
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleDark = false
    private var locationReady = false
    private var cameraFollowing = false
    private var visible: Rect? = null

    private var etaPanel: LinearLayout? = null
    private var etaTime: TextView? = null
    private var etaDetail: TextView? = null
    private var density = 1f

    private var trip: DriveTrip? = null
    private var progress: DriveProgress? = null

    fun attach() {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    }

    fun detach() {
        runCatching { carContext.getCarService(AppManager::class.java).setSurfaceCallback(null) }
        teardown()
        fallback.detach()
    }

    /** New position along the trip. */
    fun update(trip: DriveTrip, progress: DriveProgress) {
        this.trip = trip
        this.progress = progress
        if (usingFallback) fallback.update(trip, progress) else runCatching { render() }.onFailure { failed(it) }
    }

    // --- Surface ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        usingFallback = false
        try {
            start(surfaceContainer)
        } catch (e: Throwable) {
            failed(e)
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        Log.d(TAG, "Visible area: $visibleArea")
        fallback.onVisibleAreaChanged(visibleArea)
        if (!usingFallback) runCatching { follow(); positionEtaPanel() }
    }

    /** Keeps the ETA-only panel inside the part of the screen the host's cards leave free. */
    private fun positionEtaPanel() {
        val panel = etaPanel ?: return
        val c = container ?: return
        val area = visible?.takeIf { !it.isEmpty } ?: return
        val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val edge = (8 * density).toInt()
        lp.gravity = Gravity.TOP or Gravity.START
        lp.width = (area.width() - 2 * edge).coerceAtLeast(1)
        lp.leftMargin = area.left + edge
        lp.rightMargin = 0
        // Below the host's End button, which sits in the top corner.
        lp.topMargin = area.top + (84 * density).toInt()
        panel.layoutParams = lp
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        teardown()
        fallback.onSurfaceDestroyed(surfaceContainer)
        container = null
    }

    private fun start(c: SurfaceContainer) {
        density = c.dpi / 160f
        val surface = c.surface ?: error("No surface")
        MapLibre.getInstance(carContext.applicationContext)

        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "HalfNavCarMap", c.width, c.height, c.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
        ) ?: error("No virtual display")
        display = vd

        val p = Presentation(carContext, vd.display)
        presentation = p
        val dark = isDark()
        styleDark = dark
        // A texture-based map: surfaces nested inside a virtual display's window don't composite reliably.
        val options = MapLibreMapOptions.createFromAttributes(p.context)
            .textureMode(true)
            .foregroundLoadColor(if (dark) 0xFF202124.toInt() else 0xFFF1F3F4.toInt())
        val mv = MapView(p.context, options)
        mv.onCreate(null)
        mapView = mv

        val frame = FrameLayout(p.context)
        frame.addView(mv, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        frame.addView(buildEtaPanel(p), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = (12 * density).toInt() })
        p.setContentView(frame)
        p.show()
        positionEtaPanel()
        mv.onStart()
        mv.onResume()

        mv.addOnDidFailLoadingMapListener { message ->
            Log.w(TAG, "Map style failed to load: $message")
            if (style == null) failed(IllegalStateException(message))
        }
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isCompassEnabled = false
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            loadStyle(m, dark)
        }
    }

    private fun loadStyle(m: MapLibreMap, dark: Boolean) {
        style = null
        locationReady = false
        cameraFollowing = false
        styleDark = dark
        m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_LIGHT)) { s ->
            addTripLayers(s, dark)
            // The dark style's street names are too dim to read from the driver's seat.
            if (dark) {
                s.layers.filterIsInstance<SymbolLayer>().filter { !it.id.startsWith("halfnav") }.forEach {
                    it.setProperties(
                        PropertyFactory.textColor(LABEL_COLOR),
                        PropertyFactory.textHaloColor(LABEL_HALO),
                        PropertyFactory.textHaloWidth(1.6f),
                    )
                    // A bit larger, keeping each label's own size-by-zoom.
                    runCatching {
                        val size = it.textSize
                        val base = when {
                            size.isExpression -> size.expression
                            size.isValue -> Expression.literal(size.value as Number)
                            else -> null
                        }
                        base?.let { b -> it.setProperties(PropertyFactory.textSize(Expression.product(Expression.literal(LABEL_SCALE), b))) }
                    }
                }
            }
            // Thin route lines near the horizon alias into moiré; a little blur smooths them.
            listOf("halfnav-guided", "halfnav-guided-casing", "halfnav-unguided", "halfnav-unguided-casing", "halfnav-work")
                .forEach { id -> (s.getLayer(id) as? LineLayer)?.setProperties(PropertyFactory.lineBlur(ROUTE_BLUR)) }
            style = s
            runCatching { render() }.onFailure { failed(it) }
        }
    }

    private fun teardown() {
        runCatching { presentation?.dismiss() }
        mapView?.let { mv ->
            runCatching {
                mv.onPause()
                mv.onStop()
                mv.onDestroy()
            }
        }
        runCatching { display?.release() }
        presentation = null
        mapView = null
        display = null
        map = null
        style = null
        etaPanel = null
        etaTime = null
        etaDetail = null
        locationReady = false
        cameraFollowing = false
    }

    /** Gives up on the live map for this surface and draws the route-only map instead. */
    private fun failed(e: Throwable) {
        Log.w(TAG, "Live car map unavailable; drawing the route only", e)
        teardown()
        val c = container ?: return
        usingFallback = true
        runCatching {
            fallback.onSurfaceAvailable(c)
            visible?.let { fallback.onVisibleAreaChanged(it) }
            val t = trip
            val p = progress
            if (t != null && p != null) fallback.update(t, p)
        }
    }

    // --- Drawing ---

    private fun render() {
        val m = map ?: return
        val s = style?.takeIf { it.isFullyLoaded } ?: return
        val trip = trip ?: return
        val p = progress ?: return

        // Light or dark follows the sun.
        val dark = isDark()
        if (dark != styleDark) {
            loadStyle(m, dark)
            return
        }

        updateTripLayers(s, MapContent(drive = trip, progress = p))
        updateEtaPanel(trip, p)

        if (!locationReady) {
            val vehicle = runCatching { runBlocking { Prefs(carContext).settings.first().vehicle } }.getOrDefault(Vehicle.CAR)
            activateLocation(presentation?.context ?: carContext, m, s, vehicle)
            val lc = m.locationComponent
            lc.applyStyle(puckOptions(presentation?.context ?: carContext, vehicle, PUCK_SCALE))
            // We feed the position ourselves: snapped to the route, with a heading.
            lc.locationEngine = null
            lc.renderMode = RenderMode.GPS
            locationReady = true
        }
        val lc = m.locationComponent
        p.position?.let { lc.forceLocationUpdate(it) }
        if (!cameraFollowing) follow()

        val zoom = when {
            p.speedMps > 25 -> 14.5
            p.speedMps > 13 -> 15.5
            else -> 16.5
        }
        if (lc.cameraMode != CameraMode.NONE) {
            if (abs(m.cameraPosition.zoom - zoom) > 0.4) lc.zoomWhileTracking(zoom, 1500)
            if (abs(m.cameraPosition.tilt - CAR_TILT) > 2) lc.tiltWhileTracking(CAR_TILT, 800)
        }
    }

    /** Follows the vehicle with it low on the screen, inside whatever part the cards leave visible. */
    private fun follow() {
        val m = map ?: return
        if (!locationReady) return
        val c = container ?: return
        val area = visible?.takeIf { !it.isEmpty } ?: Rect(0, 0, c.width, c.height)
        val top = area.top + area.height() * 0.45
        m.cancelTransitions()
        // Any camera move cancels following, so set the padding first, then follow.
        m.moveCamera(CameraUpdateFactory.paddingTo(area.left.toDouble(), top, (c.width - area.right).toDouble(), (c.height - area.bottom).toDouble()))
        m.locationComponent.setCameraMode(CameraMode.TRACKING_GPS, 750L, 16.0, null, CAR_TILT, null)
        cameraFollowing = true
    }

    private fun isDark(): Boolean {
        val at = progress?.position?.let { LatLng(it.latitude, it.longitude) } ?: trip?.route?.points?.first() ?: return true
        return SunTimes.isDarkAt(System.currentTimeMillis(), at.lat, at.lng, TimeZone.getDefault().id)
    }

    // --- ETA-only panel ---

    private fun px(sp: Float) = sp * density

    private fun buildEtaPanel(p: Presentation): LinearLayout {
        val panel = LinearLayout(p.context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = (20 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, (12 * density).toInt())
            visibility = android.view.View.GONE
        }
        val time = TextView(p.context).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setSingleLine(true)
            gravity = Gravity.CENTER
            // As large as fits the panel's width, never wrapping onto a hidden second line.
            setAutoSizeTextTypeUniformWithConfiguration(
                px(24f).toInt(), px(72f).toInt(), 1, TypedValue.COMPLEX_UNIT_PX,
            )
        }
        val detail = TextView(p.context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22f))
            gravity = Gravity.CENTER
        }
        panel.addView(time, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(84f).toInt()))
        panel.addView(detail)
        etaPanel = panel
        etaTime = time
        etaDetail = detail
        return panel
    }

    private fun updateEtaPanel(trip: DriveTrip, p: DriveProgress) {
        val panel = etaPanel ?: return
        if (trip.mode != Mode.ETA_ONLY) {
            panel.visibility = android.view.View.GONE
            return
        }
        val dark = styleDark
        val sec = p.remainingSec(trip)
        val text = if (dark) Color.WHITE else 0xFF111111.toInt()
        panel.background = GradientDrawable().apply {
            setColor(if (dark) 0xE6202124.toInt() else 0xE6FFFFFF.toInt())
            cornerRadius = 20 * density
        }
        etaTime?.apply {
            setTextColor(text)
            this.text = "ETA - ${arrivalTime(sec)}"
        }
        etaDetail?.apply {
            setTextColor(text)
            this.text = "${formatMinutes(sec)} · ${formatMiles(Geo.metersToMiles(p.remainingMeters(trip)))} to go"
        }
        panel.visibility = android.view.View.VISIBLE
    }

    private companion object {
        const val TAG = "HalfNavCarMap"
        /** Less tilt than the phone shows less far-off road, where the route line is thinnest. */
        const val CAR_TILT = 45.0
        const val ROUTE_BLUR = 0.8f
        const val PUCK_SCALE = 3.0f
        const val LABEL_SCALE = 1.25f
        const val LABEL_COLOR = 0xFFF1F3F4.toInt()
        const val LABEL_HALO = 0xFF15171A.toInt()
    }
}
