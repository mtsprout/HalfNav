package io.github.mtsprout.halfnav

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import io.github.mtsprout.halfnav.core.WorkKind
import io.github.mtsprout.halfnav.core.slice
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.engine.LocationEngineDefault
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.maplibre.android.geometry.LatLng as MapLatLng

/** Free OpenStreetMap-based street map; no key needed. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

private const val BLUE = 0xFF1A73E8.toInt()
private const val BLUE_DARK = 0xFF0B4FB3.toInt()
private const val GRAY = 0xFF7D8590.toInt()
private const val GRAY_DARK = 0xFF5F6368.toInt()
private const val ORANGE = 0xFFF29900.toInt()
private const val RED = 0xFFD93025.toInt()

/** Camera tilt in the drive view, degrees. */
private const val DRIVE_TILT = 55.0

private object Ids {
    const val GUIDED = "halfnav-guided"
    const val UNGUIDED = "halfnav-unguided"
    const val WORK = "halfnav-work"
    const val RADIUS = "halfnav-radius"
    const val POINTS = "halfnav-points"
}

/** What the map should show. */
data class MapContent(
    val selected: Place? = null,
    val plan: TripPlan? = null,
    val watch: Watch? = null,
    /** When set, the map is in drive view: following the vehicle, tilted, route ahead only. */
    val drive: DriveTrip? = null,
    val progress: DriveProgress? = null,
)

/** Pixel insets of UI drawn on top of the map, so the camera keeps things visible. */
data class MapInsets(val top: Int = 0, val bottom: Int = 0, val side: Int = 0)

@Composable
fun TripMap(
    content: MapContent,
    insets: MapInsets,
    here: LatLng?,
    locationPermitted: Boolean,
    vehicle: Vehicle,
    recenterRequests: Int,
    onFollowingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var locationReady by remember { mutableStateOf(false) }
    var centeredOnUser by remember { mutableStateOf(false) }
    val driving = content.drive != null
    val followingChanged by rememberUpdatedState(onFollowingChange)

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isRotateGesturesEnabled = false
            m.uiSettings.isTiltGesturesEnabled = false
            m.cameraPosition = CameraPosition.Builder()
                .target(MapLatLng(39.5, -98.35)) // continental US until we know where you are
                .zoom(3.5)
                .build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                addTripLayers(s)
                style = s
            }
        }
    }

    // Keep the logo / attribution / compass clear of the search bar and bottom panel.
    LaunchedEffect(map, insets) {
        val m = map ?: return@LaunchedEffect
        val pad = (8 * context.resources.displayMetrics.density).toInt()
        m.uiSettings.logoGravity = Gravity.BOTTOM or Gravity.START
        m.uiSettings.setLogoMargins(pad, 0, 0, insets.bottom + pad)
        m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
        m.uiSettings.setAttributionMargins(pad * 12, 0, 0, insets.bottom + pad)
        m.uiSettings.setCompassMargins(0, insets.top + pad, pad, 0)
    }

    LaunchedEffect(style, locationPermitted) {
        val s = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!locationPermitted || locationReady) return@LaunchedEffect
        activateLocation(context, m, s, vehicle)
        m.locationComponent.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
            override fun onCameraTrackingDismissed() = followingChanged(false)
            override fun onCameraTrackingChanged(currentMode: Int) =
                followingChanged(currentMode != CameraMode.NONE)
        })
        locationReady = true
    }

    LaunchedEffect(locationReady, vehicle) {
        val m = map ?: return@LaunchedEffect
        if (locationReady) m.locationComponent.applyStyle(puckOptions(context, vehicle))
    }

    // Enter / leave drive view.
    LaunchedEffect(locationReady, driving) {
        val m = map ?: return@LaunchedEffect
        if (!locationReady) return@LaunchedEffect
        val lc = m.locationComponent
        if (driving) {
            // We feed the position ourselves (snapped to the route, with a heading).
            lc.locationEngine = null
            content.progress?.position?.let { lc.forceLocationUpdate(it) }
            lc.renderMode = RenderMode.GPS
        } else {
            lc.cameraMode = CameraMode.NONE
            lc.renderMode = RenderMode.NORMAL
            lc.locationEngine = LocationEngineDefault.getDefaultLocationEngine(context)
            m.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, 0.0))
            m.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(m.cameraPosition).tilt(0.0).bearing(0.0).build()
                ),
                500,
            )
        }
    }

    // Vehicle position and speed-based zoom while driving.
    LaunchedEffect(locationReady, content.progress) {
        val m = map ?: return@LaunchedEffect
        val p = content.progress ?: return@LaunchedEffect
        if (!locationReady || !driving) return@LaunchedEffect
        p.position?.let { m.locationComponent.forceLocationUpdate(it) }
        val zoom = when {
            p.speedMps > 25 -> 14.5 // highway
            p.speedMps > 13 -> 15.5
            else -> 16.5
        }
        val lc = m.locationComponent
        if (lc.cameraMode != CameraMode.NONE) {
            if (abs(m.cameraPosition.zoom - zoom) > 0.4) lc.zoomWhileTracking(zoom, 1500)
            // Other camera updates can interrupt the tilt animation; ease back to it if so.
            if (abs(m.cameraPosition.tilt - DRIVE_TILT) > 2) lc.tiltWhileTracking(DRIVE_TILT, 800)
        }
    }

    // Follow the vehicle, tilted, with it in the lower part of the screen so you see the road ahead.
    // Any camera move (padding included) cancels following, so set padding first, then follow.
    LaunchedEffect(locationReady, driving, insets) {
        val m = map ?: return@LaunchedEffect
        if (!locationReady || !driving) return@LaunchedEffect
        val h = mapView.height.takeIf { it > 0 } ?: return@LaunchedEffect
        val top = (h * 0.45).coerceAtLeast(insets.top.toDouble())
        m.cancelTransitions()
        m.moveCamera(CameraUpdateFactory.paddingTo(0.0, top, 0.0, insets.bottom.toDouble()))
        m.locationComponent.setCameraMode(CameraMode.TRACKING_GPS, 750L, 16.0, null, DRIVE_TILT, null)
        followingChanged(true)
    }

    // Draw the trip.
    LaunchedEffect(style, content) {
        val s = style ?: return@LaunchedEffect
        updateTripLayers(s, content)
    }

    // Overview camera: show whatever the user is focused on.
    LaunchedEffect(style, content.plan, content.selected, content.watch?.destination, insets, driving) {
        val m = map ?: return@LaunchedEffect
        if (style == null || driving) return@LaunchedEffect
        val plan = content.plan
        val watch = content.watch
        when {
            plan != null -> fit(m, plan.route.points, insets)
            watch != null -> fit(m, listOfNotNull(here, watch.destination), insets, maxZoom = 14.0)
            content.selected != null -> fit(m, listOf(content.selected.latLng), insets, maxZoom = 15.0)
        }
    }

    LaunchedEffect(style, here) {
        val m = map ?: return@LaunchedEffect
        if (style == null || here == null || centeredOnUser) return@LaunchedEffect
        centeredOnUser = true
        if (content.plan == null && content.selected == null && content.watch == null && !driving) {
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(here.toMap(), 13.0))
        }
    }

    LaunchedEffect(recenterRequests) {
        val m = map ?: return@LaunchedEffect
        if (recenterRequests == 0) return@LaunchedEffect
        if (driving && locationReady) {
            m.locationComponent.setCameraMode(CameraMode.TRACKING_GPS, 750L, null, null, DRIVE_TILT, null)
        } else {
            // Prefer the location dot's own fix; ours may be a moment behind.
            val dot = if (locationReady) m.locationComponent.lastKnownLocation else null
            val target = dot?.let { MapLatLng(it.latitude, it.longitude) } ?: here?.toMap()
            target?.let { m.animateCamera(CameraUpdateFactory.newLatLngZoom(it, 15.0), 600) }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun puckOptions(context: Context, vehicle: Vehicle): LocationComponentOptions =
    LocationComponentOptions.builder(context)
        .gpsDrawable(if (vehicle == Vehicle.CAR) R.drawable.ic_vehicle_car else R.drawable.ic_vehicle_arrow)
        .accuracyAlpha(0.12f)
        .build()

@SuppressLint("MissingPermission")
private fun activateLocation(context: Context, m: MapLibreMap, s: Style, vehicle: Vehicle) {
    val lc = m.locationComponent
    lc.activateLocationComponent(
        LocationComponentActivationOptions.builder(context, s)
            .locationComponentOptions(puckOptions(context, vehicle))
            .useDefaultLocationEngine(true)
            .build()
    )
    lc.isLocationComponentEnabled = true
    lc.cameraMode = CameraMode.NONE
    lc.renderMode = RenderMode.NORMAL
}

/** Line width that grows with zoom, so the route reads well from overview down to street level. */
private fun widthByZoom(atLow: Float, atHigh: Float): Expression =
    Expression.interpolate(
        Expression.exponential(1.5f), Expression.zoom(),
        Expression.stop(5, atLow), Expression.stop(18, atHigh),
    )

private fun addTripLayers(s: Style) {
    listOf(Ids.GUIDED, Ids.UNGUIDED, Ids.WORK, Ids.RADIUS, Ids.POINTS).forEach { s.addSource(GeoJsonSource(it)) }

    s.addLayer(
        FillLayer("${Ids.RADIUS}-fill", Ids.RADIUS).withProperties(
            PropertyFactory.fillColor(BLUE),
            PropertyFactory.fillOpacity(0.12f),
        )
    )
    s.addLayer(
        LineLayer("${Ids.RADIUS}-line", Ids.RADIUS).withProperties(
            PropertyFactory.lineColor(BLUE),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        )
    )
    s.addLayer(
        LineLayer("${Ids.UNGUIDED}-casing", Ids.UNGUIDED).withProperties(
            PropertyFactory.lineColor(GRAY_DARK),
            PropertyFactory.lineWidth(widthByZoom(5f, 18f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer(Ids.UNGUIDED, Ids.UNGUIDED).withProperties(
            PropertyFactory.lineColor(GRAY),
            PropertyFactory.lineWidth(widthByZoom(3f, 13f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer("${Ids.GUIDED}-casing", Ids.GUIDED).withProperties(
            PropertyFactory.lineColor(BLUE_DARK),
            PropertyFactory.lineWidth(widthByZoom(6f, 20f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer(Ids.GUIDED, Ids.GUIDED).withProperties(
            PropertyFactory.lineColor(BLUE),
            PropertyFactory.lineWidth(widthByZoom(4f, 15f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
    )
    s.addLayer(
        LineLayer(Ids.WORK, Ids.WORK).withProperties(
            PropertyFactory.lineColor(ORANGE),
            PropertyFactory.lineWidth(widthByZoom(5f, 17f)),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        )
    )
    s.addLayer(
        CircleLayer(Ids.POINTS, Ids.POINTS).withProperties(
            PropertyFactory.circleRadius(
                Expression.match(
                    Expression.get("kind"), Expression.literal(7f),
                    Expression.stop("dest", 9f),
                )
            ),
            PropertyFactory.circleColor(
                Expression.match(
                    Expression.get("kind"), Expression.color(GRAY),
                    Expression.stop("dest", Expression.color(RED)),
                    Expression.stop("handoff", Expression.color(0xFFFFFFFF.toInt())),
                    Expression.stop("work", Expression.color(ORANGE)),
                )
            ),
            PropertyFactory.circleStrokeColor(
                Expression.match(
                    Expression.get("kind"), Expression.color(0xFFFFFFFF.toInt()),
                    Expression.stop("handoff", Expression.color(BLUE)),
                )
            ),
            PropertyFactory.circleStrokeWidth(3f),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
        )
    )
    s.addLayer(
        SymbolLayer("${Ids.POINTS}-label", Ids.POINTS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textColor(0xFF202124.toInt()),
            PropertyFactory.textHaloColor(0xFFFFFFFF.toInt()),
            PropertyFactory.textHaloWidth(2f),
            PropertyFactory.textOptional(true),
        )
    )
}

private fun updateTripLayers(s: Style, content: MapContent) {
    val points = mutableListOf<Feature>()
    var guided: List<LatLng> = emptyList()
    var unguided: List<LatLng> = emptyList()
    val work = mutableListOf<Feature>()
    var radius: Pair<LatLng, Double>? = null
    var dest: Place? = null

    val drive = content.drive
    val plan = content.plan
    if (drive != null && content.progress?.arrived == true) {
        dest = drive.destination // arrived: just the destination pin
    } else if (drive != null) {
        // Drive view: only the road ahead of you.
        val route = drive.route
        val from = content.progress?.offsetMeters ?: 0.0
        val h = drive.handoff
        when {
            drive.mode == Mode.END_MILES -> unguided = route.slice(from, route.lengthMeters)
            h == null -> unguided = route.slice(from, route.lengthMeters)
            h.reachesDestination -> guided = route.slice(from, route.lengthMeters)
            else -> {
                if (from < h.offsetMeters) {
                    guided = route.slice(from, h.offsetMeters)
                    points += point(h.point, "handoff", "Guidance ends · ${h.label}")
                }
                unguided = route.slice(maxOf(from, h.offsetMeters), route.lengthMeters)
            }
        }
        drive.warnings.filter { it.startOffsetMeters + it.lengthMeters > from }.forEach { w ->
            val stretch = route.slice(maxOf(from, w.startOffsetMeters), w.startOffsetMeters + w.lengthMeters)
            work += Feature.fromGeometry(LineString.fromLngLats(stretch.map { it.toPoint() }))
            if (w.startOffsetMeters > from) points += point(stretch.first(), "work", w.label())
        }
        drive.watchMiles?.let { radius = drive.destination.latLng to it * Geo.METERS_PER_MILE }
        dest = drive.destination
    } else if (plan != null) {
        val route = plan.route
        val h = plan.handoff
        guided = route.slice(0.0, h.offsetMeters)
        if (!h.reachesDestination) {
            unguided = route.slice(h.offsetMeters, route.lengthMeters)
            points += point(h.point, "handoff", "Guidance ends · ${h.label}")
        }
        plan.warnings.forEach { w ->
            val stretch = route.slice(w.startOffsetMeters, w.startOffsetMeters + w.lengthMeters)
            work += Feature.fromGeometry(LineString.fromLngLats(stretch.map { it.toPoint() }))
            points += point(stretch.first(), "work", w.label())
        }
        dest = plan.destination
    }
    content.watch?.let { w ->
        if (radius == null) radius = w.destination to w.triggerMiles * Geo.METERS_PER_MILE
        if (dest == null) dest = Place(w.label, "", w.destination)
    }
    if (dest == null) dest = content.selected
    dest?.let { points += point(it.latLng, "dest", it.name) }

    s.source(Ids.GUIDED).setLine(guided)
    s.source(Ids.UNGUIDED).setLine(unguided)
    s.source(Ids.WORK).setGeoJson(FeatureCollection.fromFeatures(work))
    s.source(Ids.POINTS).setGeoJson(FeatureCollection.fromFeatures(points))
    s.source(Ids.RADIUS).setGeoJson(
        radius?.let { (center, meters) ->
            FeatureCollection.fromFeature(Feature.fromGeometry(Polygon.fromLngLats(listOf(circle(center, meters)))))
        } ?: FeatureCollection.fromFeatures(emptyList())
    )
}

private fun io.github.mtsprout.halfnav.core.ConstructionWarning.label() =
    if (kind == WorkKind.ROAD_CLOSURE) "Road closed" else "Road work"

private fun fit(m: MapLibreMap, pts: List<LatLng>, insets: MapInsets, maxZoom: Double = 16.0) {
    if (pts.isEmpty()) return
    val pad = insets.side
    if (pts.size == 1) {
        m.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(pts.first().toMap())
                    .zoom(maxZoom)
                    .tilt(0.0)
                    .bearing(0.0)
                    .padding(pad.toDouble(), insets.top.toDouble(), pad.toDouble(), insets.bottom.toDouble())
                    .build()
            ),
            600,
        )
        return
    }
    val bounds = LatLngBounds.Builder().includes(pts.map { it.toMap() }).build()
    m.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, pad, insets.top + pad, pad, insets.bottom + pad), 600)
}

private fun Style.source(id: String) = getSourceAs<GeoJsonSource>(id)!!

private fun GeoJsonSource.setLine(pts: List<LatLng>) = setGeoJson(
    if (pts.size < 2) FeatureCollection.fromFeatures(emptyList())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { it.toPoint() })))
)

private fun point(p: LatLng, kind: String, label: String): Feature =
    Feature.fromGeometry(p.toPoint()).apply {
        addStringProperty("kind", kind)
        addStringProperty("label", label)
    }

private fun LatLng.toPoint(): Point = Point.fromLngLat(lng, lat)
private fun LatLng.toMap() = MapLatLng(lat, lng)

/** A ring of points [radiusMeters] around [center], for the end-mode trigger zone. */
private fun circle(center: LatLng, radiusMeters: Double): List<Point> {
    val d = radiusMeters / 6_371_008.8
    val lat1 = Math.toRadians(center.lat)
    val lng1 = Math.toRadians(center.lng)
    return (0..64).map { i ->
        val bearing = Math.toRadians(i * 360.0 / 64)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(bearing))
        val lng2 = lng1 + atan2(sin(bearing) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        Point.fromLngLat(Math.toDegrees(lng2), Math.toDegrees(lat2))
    }
}
