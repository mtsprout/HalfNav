import SwiftUI
import MapLibre
import HalfNavCore

/// Free OpenStreetMap-based street maps; no key needed.
private let styleLight = URL(string: "https://tiles.openfreemap.org/styles/liberty")!
private let styleDark = URL(string: "https://tiles.openfreemap.org/styles/dark")!

/// The map's own background colors, so nothing flashes before it draws.
let windowLight = UIColor(red: 0xF8 / 255, green: 0xF4 / 255, blue: 0xF0 / 255, alpha: 1)
let windowDark = UIColor(red: 0x0C / 255, green: 0x0C / 255, blue: 0x0C / 255, alpha: 1)

/// What the map shows.
struct MapContent {
    var selected: Place?
    var plan: TripPlan?
    /// When set, drive view: tilted, route ahead only.
    var drive: DriveTrip?
    var progress: DriveProgress?
    /// Drive view camera follows the vehicle (false once you pan away).
    var following = true
}

/// Points of screen covered by UI on top of the map, so the camera keeps things visible.
struct MapInsets: Equatable {
    var top: CGFloat = 0
    var bottom: CGFloat = 0
    var side: CGFloat = 40
}

/// Drive view keeps the vehicle at this fraction of the visible height, so you see the road ahead.
let driveFocusTop: CGFloat = 0.45

struct TripMap: UIViewRepresentable {
    var content: MapContent
    var insets: MapInsets
    var dark: Bool
    var vehicle: Vehicle
    var showsUserLocation: Bool
    var here: LatLng?
    var recenterToken: Int
    var onUserPanned: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> MLNMapView {
        let map = MLNMapView(frame: .zero, styleURL: dark ? styleDark : styleLight)
        map.backgroundColor = dark ? windowDark : windowLight
        map.delegate = context.coordinator
        map.automaticallyAdjustsContentInset = false
        map.allowsRotating = false
        map.allowsTilting = false
        map.compassView.isHidden = true
        // Continental US until we know where you are.
        map.setCenter(CLLocationCoordinate2D(latitude: 39.5, longitude: -98.35), zoomLevel: 3.5, animated: false)
        context.coordinator.map = map
        context.coordinator.dark = dark
        return map
    }

    func updateUIView(_ map: MLNMapView, context: Context) {
        let c = context.coordinator
        c.parent = self
        if c.dark != dark {
            c.dark = dark
            c.styleReady = false
            map.backgroundColor = dark ? windowDark : windowLight
            map.styleURL = dark ? styleDark : styleLight
        }
        map.showsUserLocation = showsUserLocation && content.drive == nil
        // Tilt only in the drive view (programmatic pitch is clamped to 0 while tilting is off).
        map.allowsTilting = content.drive != nil
        c.render()
    }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        weak var map: MLNMapView?
        var parent: TripMap?
        var dark = false
        var styleReady = false
        private var centeredOnUser = false
        private var lastFocusKey = ""
        private var lastRecenter = 0
        private var wasDriving = false
        private var lastCameraFix: CLLocationCoordinate2D?

        // MARK: Delegate

        func mapView(_ mapView: MLNMapView, didFinishLoading style: MLNStyle) {
            addLayers(style)
            styleReady = true
            lastFocusKey = "" // re-apply camera and layers on the new style
            render()
        }

        func mapView(_ mapView: MLNMapView, regionWillChangeWith reason: MLNCameraChangeReason, animated: Bool) {
            let gesture: MLNCameraChangeReason = [.gesturePan, .gesturePinch, .gestureZoomIn, .gestureZoomOut, .gestureOneFingerZoom]
            if !reason.intersection(gesture).isEmpty, parent?.content.drive != nil {
                DispatchQueue.main.async { self.parent?.onUserPanned() }
            }
        }

        // MARK: Rendering

        func render() {
            guard let map, let parent, styleReady, let style = map.style else { return }
            updateLayers(style, parent.content)
            updateCamera(map, parent)
        }

        private func updateCamera(_ map: MLNMapView, _ p: TripMap) {
            let content = p.content
            let h = map.bounds.height
            if let drive = content.drive {
                if !wasDriving { lastFocusKey = "" }
                wasDriving = true
                let recentered = p.recenterToken != lastRecenter
                lastRecenter = p.recenterToken
                guard content.following, let progress = content.progress, let pos = progress.position else {
                    if recentered, let pos = content.progress?.position { map.setCenter(pos, animated: true) }
                    return
                }
                // Vehicle in the lower part of the screen, road ahead visible above it.
                let inset = UIEdgeInsets(top: max(h * driveFocusTop, p.insets.top), left: 0, bottom: p.insets.bottom, right: 0)
                if map.contentInset != inset { map.setContentInset(inset, animated: false, completionHandler: nil) }
                // Zoom out with speed: closer in town, wider on the highway.
                let across: CLLocationDistance = progress.speedMps > 25 ? 1600 : progress.speedMps > 13 ? 900 : 500
                let cam = MLNMapCamera(lookingAtCenter: pos, acrossDistance: across, pitch: 55, heading: progress.bearing)
                let first = lastCameraFix == nil || recentered
                lastCameraFix = pos
                map.setCamera(cam, withDuration: first ? 0.6 : 1.0,
                              animationTimingFunction: CAMediaTimingFunction(name: first ? .easeInEaseOut : .linear))
                _ = drive
                return
            }
            if wasDriving {
                wasDriving = false
                lastCameraFix = nil
                map.setContentInset(.zero, animated: false, completionHandler: nil)
                let flat = MLNMapCamera(lookingAtCenter: map.centerCoordinate, acrossDistance: 3000, pitch: 0, heading: 0)
                map.setCamera(flat, animated: true)
            }
            let pad = UIEdgeInsets(top: p.insets.top + p.insets.side, left: p.insets.side,
                                   bottom: p.insets.bottom + p.insets.side, right: p.insets.side)
            if let plan = content.plan {
                let key = "plan:\(plan.destination.name):\(plan.route.lengthMeters)"
                if key != lastFocusKey {
                    lastFocusKey = key
                    fit(map, plan.route.points.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }, pad)
                }
            } else if let sel = content.selected {
                let key = "sel:\(sel.latLng.lat),\(sel.latLng.lng)"
                if key != lastFocusKey {
                    lastFocusKey = key
                    let c = CLLocationCoordinate2D(latitude: sel.latLng.lat, longitude: sel.latLng.lng)
                    map.setContentInset(UIEdgeInsets(top: p.insets.top, left: 0, bottom: p.insets.bottom, right: 0),
                                        animated: false, completionHandler: nil)
                    map.setCenter(c, zoomLevel: 15, direction: 0, animated: true)
                }
            } else {
                lastFocusKey = ""
                if p.recenterToken != lastRecenter, let here = p.here {
                    lastRecenter = p.recenterToken
                    map.setCenter(CLLocationCoordinate2D(latitude: here.lat, longitude: here.lng), zoomLevel: 15, animated: true)
                } else if !centeredOnUser, let here = p.here {
                    centeredOnUser = true
                    map.setCenter(CLLocationCoordinate2D(latitude: here.lat, longitude: here.lng), zoomLevel: 13, animated: false)
                }
            }
        }

        private func fit(_ map: MLNMapView, _ coords: [CLLocationCoordinate2D], _ pad: UIEdgeInsets) {
            guard coords.count > 1 else { return }
            map.setContentInset(.zero, animated: false, completionHandler: nil)
            var c = coords
            let poly = MLNPolyline(coordinates: &c, count: UInt(c.count))
            let cam = map.cameraThatFitsShape(poly, direction: 0, edgePadding: pad)
            map.setCamera(cam, animated: true)
        }

        // MARK: Layers

        private enum Id {
            static let guided = "halfnav-guided", unguided = "halfnav-unguided", work = "halfnav-work"
            static let radius = "halfnav-radius", points = "halfnav-points", vehicle = "halfnav-vehicle"
        }

        private func json(_ o: Any) -> NSExpression { NSExpression(mglJSONObject: o) }

        /// Line width that grows with zoom, so the route reads well from overview down to street level.
        private func widthByZoom(_ low: Double, _ high: Double) -> NSExpression {
            json(["interpolate", ["exponential", 1.5], ["zoom"], 5, low, 18, high])
        }

        private func addLayers(_ s: MLNStyle) {
            for id in [Id.guided, Id.unguided, Id.work, Id.radius, Id.points, Id.vehicle] {
                s.addSource(MLNShapeSource(identifier: id, shape: nil, options: nil))
            }
            DispatchQueue.main.async {
                if let car = VehicleIcon.image(.car) { s.setImage(car, forName: "car") }
                if let arrow = VehicleIcon.image(.arrow) { s.setImage(arrow, forName: "arrow") }
            }
            let blue = UIColor(red: 0x1A / 255, green: 0x73 / 255, blue: 0xE8 / 255, alpha: 1)
            let blueDark = UIColor(red: 0x0B / 255, green: 0x4F / 255, blue: 0xB3 / 255, alpha: 1)
            let gray = UIColor(red: 0x7D / 255, green: 0x85 / 255, blue: 0x90 / 255, alpha: 1)
            let grayDark = UIColor(red: 0x5F / 255, green: 0x63 / 255, blue: 0x68 / 255, alpha: 1)
            let orange = UIColor(red: 0xF2 / 255, green: 0x99 / 255, blue: 0x00 / 255, alpha: 1)

            func src(_ id: String) -> MLNSource { s.source(withIdentifier: id)! }

            let fill = MLNFillStyleLayer(identifier: "\(Id.radius)-fill", source: src(Id.radius))
            fill.fillColor = NSExpression(forConstantValue: blue)
            fill.fillOpacity = NSExpression(forConstantValue: 0.12)
            s.addLayer(fill)
            let ring = MLNLineStyleLayer(identifier: "\(Id.radius)-line", source: src(Id.radius))
            ring.lineColor = NSExpression(forConstantValue: blue)
            ring.lineWidth = NSExpression(forConstantValue: 2)
            ring.lineDashPattern = NSExpression(forConstantValue: [2, 2])
            s.addLayer(ring)

            func line(_ id: String, _ source: String, _ color: UIColor, _ low: Double, _ high: Double) {
                let l = MLNLineStyleLayer(identifier: id, source: src(source))
                l.lineColor = NSExpression(forConstantValue: color)
                l.lineWidth = widthByZoom(low, high)
                l.lineCap = NSExpression(forConstantValue: "round")
                l.lineJoin = NSExpression(forConstantValue: "round")
                s.addLayer(l)
            }
            line("\(Id.unguided)-casing", Id.unguided, grayDark, 5, 18)
            line(Id.unguided, Id.unguided, gray, 3, 13)
            line("\(Id.guided)-casing", Id.guided, blueDark, 6, 20)
            line(Id.guided, Id.guided, blue, 4, 15)
            line(Id.work, Id.work, orange, 5, 17)

            let circles = MLNCircleStyleLayer(identifier: Id.points, source: src(Id.points))
            circles.circleRadius = json(["match", ["get", "kind"], "dest", 9, 7])
            circles.circleColor = json(["match", ["get", "kind"], "dest", "#D93025", "handoff", "#FFFFFF", "work", "#F29900", "#7D8590"])
            circles.circleStrokeColor = json(["match", ["get", "kind"], "handoff", "#1A73E8", "#FFFFFF"])
            circles.circleStrokeWidth = NSExpression(forConstantValue: 3)
            circles.circlePitchAlignment = NSExpression(forConstantValue: "map")
            s.addLayer(circles)

            let labels = MLNSymbolStyleLayer(identifier: "\(Id.points)-label", source: src(Id.points))
            labels.text = json(["get", "label"])
            labels.textFontNames = NSExpression(forConstantValue: ["Noto Sans Bold"])
            labels.textFontSize = NSExpression(forConstantValue: 13)
            labels.textAnchor = NSExpression(forConstantValue: "top")
            labels.textOffset = NSExpression(forConstantValue: NSValue(cgVector: CGVector(dx: 0, dy: 1.1)))
            labels.textColor = NSExpression(forConstantValue: dark ? UIColor(white: 0.91, alpha: 1) : UIColor(white: 0.13, alpha: 1))
            labels.textHaloColor = NSExpression(forConstantValue: dark ? UIColor(white: 0.13, alpha: 1) : UIColor.white)
            labels.textHaloWidth = NSExpression(forConstantValue: 2)
            labels.textOptional = NSExpression(forConstantValue: true)
            s.addLayer(labels)

            // Vehicle on the map, used when you've panned away (while following, it's drawn on top).
            let v = MLNSymbolStyleLayer(identifier: Id.vehicle, source: src(Id.vehicle))
            v.iconImageName = json(["get", "icon"])
            v.iconRotation = json(["get", "bearing"])
            v.iconRotationAlignment = NSExpression(forConstantValue: "map")
            v.iconPitchAlignment = NSExpression(forConstantValue: "map")
            v.iconAllowsOverlap = NSExpression(forConstantValue: true)
            v.iconScale = NSExpression(forConstantValue: 0.9)
            s.addLayer(v)
        }

        private func coords(_ pts: [LatLng]) -> [CLLocationCoordinate2D] {
            pts.map { CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lng) }
        }

        private func lineShape(_ pts: [LatLng]) -> MLNShape? {
            guard pts.count >= 2 else { return nil }
            var c = coords(pts)
            return MLNPolylineFeature(coordinates: &c, count: UInt(c.count))
        }

        private func point(_ p: LatLng, _ kind: String, _ label: String) -> MLNPointFeature {
            let f = MLNPointFeature()
            f.coordinate = CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)
            f.attributes = ["kind": kind, "label": label]
            return f
        }

        private func workLabel(_ w: ConstructionWarning) -> String {
            w.kind == WorkKind.roadClosure ? "Road closed" : "Road work"
        }

        private func updateLayers(_ s: MLNStyle, _ content: MapContent) {
            var points: [MLNPointFeature] = []
            var guided: [LatLng] = []
            var unguided: [LatLng] = []
            var work: [MLNPolylineFeature] = []
            var radius: (LatLng, Double)?
            var dest: Place?

            if let drive = content.drive, content.progress?.arrived == true {
                dest = drive.destination // arrived: just the destination pin
            } else if let drive = content.drive {
                // Drive view: only the road ahead of you.
                let route = drive.route
                let from = content.progress?.offsetMeters ?? 0
                if drive.mode == .endMiles || drive.handoff == nil {
                    unguided = route.slice(fromMeters: from, toMeters: route.lengthMeters)
                } else if let h = drive.handoff {
                    if h.reachesDestination {
                        guided = route.slice(fromMeters: from, toMeters: route.lengthMeters)
                    } else {
                        if from < h.offsetMeters {
                            guided = route.slice(fromMeters: from, toMeters: h.offsetMeters)
                            points.append(point(h.point, "handoff", "Guidance ends · \(h.label)"))
                        }
                        unguided = route.slice(fromMeters: max(from, h.offsetMeters), toMeters: route.lengthMeters)
                    }
                }
                for w in drive.warnings where w.startOffsetMeters + w.lengthMeters > from {
                    let stretch = route.slice(fromMeters: max(from, w.startOffsetMeters), toMeters: w.startOffsetMeters + w.lengthMeters)
                    var c = coords(stretch)
                    work.append(MLNPolylineFeature(coordinates: &c, count: UInt(c.count)))
                    if w.startOffsetMeters > from { points.append(point(stretch[0], "work", workLabel(w))) }
                }
                if let m = drive.watchMiles { radius = (drive.destination.latLng, m * Geo.shared.METERS_PER_MILE) }
                dest = drive.destination
            } else if let plan = content.plan {
                let route = plan.route
                let h = plan.handoff
                guided = route.slice(fromMeters: 0, toMeters: h.offsetMeters)
                if !h.reachesDestination {
                    unguided = route.slice(fromMeters: h.offsetMeters, toMeters: route.lengthMeters)
                    points.append(point(h.point, "handoff", "Guidance ends · \(h.label)"))
                }
                for w in plan.warnings {
                    let stretch = route.slice(fromMeters: w.startOffsetMeters, toMeters: w.startOffsetMeters + w.lengthMeters)
                    var c = coords(stretch)
                    work.append(MLNPolylineFeature(coordinates: &c, count: UInt(c.count)))
                    points.append(point(stretch[0], "work", workLabel(w)))
                }
                dest = plan.destination
            }
            if dest == nil { dest = content.selected }
            if let d = dest { points.append(point(d.latLng, "dest", d.name)) }

            (s.source(withIdentifier: Id.guided) as? MLNShapeSource)?.shape = lineShape(guided)
            (s.source(withIdentifier: Id.unguided) as? MLNShapeSource)?.shape = lineShape(unguided)
            (s.source(withIdentifier: Id.work) as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: work)
            (s.source(withIdentifier: Id.points) as? MLNShapeSource)?.shape = MLNShapeCollectionFeature(shapes: points)
            (s.source(withIdentifier: Id.radius) as? MLNShapeSource)?.shape = radius.map { circle($0.0, $0.1) }

            // Map-drawn vehicle only when the camera isn't following (the overlay covers that case).
            var vehicle: MLNShape?
            if content.drive != nil, !content.following, let p = content.progress, let pos = p.position {
                let f = MLNPointFeature()
                f.coordinate = pos
                f.attributes = ["icon": parent?.vehicle == .arrow ? "arrow" : "car", "bearing": p.bearing]
                vehicle = f
            }
            (s.source(withIdentifier: Id.vehicle) as? MLNShapeSource)?.shape = vehicle
        }

        /// A ring of points around [center], for the end-mode trigger zone.
        private func circle(_ center: LatLng, _ radiusMeters: Double) -> MLNPolygonFeature {
            let d = radiusMeters / 6_371_008.8
            let lat1 = center.lat * .pi / 180, lng1 = center.lng * .pi / 180
            var ring: [CLLocationCoordinate2D] = (0...64).map { i in
                let b = Double(i) * 2 * .pi / 64
                let lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
                let lng2 = lng1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
                return CLLocationCoordinate2D(latitude: lat2 * 180 / .pi, longitude: lng2 * 180 / .pi)
            }
            return MLNPolygonFeature(coordinates: &ring, count: UInt(ring.count))
        }
    }
}
