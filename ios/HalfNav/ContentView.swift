import SwiftUI
import HalfNavCore

struct ContentView: View {
    @Bindable var model: AppModel
    var dark: Bool

    @Environment(\.horizontalSizeClass) private var sizeClass
    @FocusState private var searchFocused: Bool
    @State private var showSettings = false
    @State private var following = true
    @State private var recenter = 0
    @State private var topInset: CGFloat = 0
    @State private var bottomInset: CGFloat = 0
    @State private var sideInset: CGFloat = 0

    private var drive: DriveSession { model.drive }
    private var driveShown: Bool { drive.trip != nil && drive.visible }
    /// iPad (and large landscape phones): panels in a side column instead of a bottom sheet.
    private var wide: Bool { sizeClass == .regular }

    private var review: TripPlan? {
        if case .review(let p) = model.state { return p }
        return nil
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                TripMap(
                    content: driveShown
                        ? MapContent(drive: drive.trip, progress: drive.progress, following: following)
                        : MapContent(selected: model.selected, plan: review),
                    insets: wide && !driveShown
                        ? MapInsets(top: 0, bottom: 0, side: 40)
                        : MapInsets(top: topInset, bottom: bottomInset, side: 40),
                    dark: dark,
                    vehicle: model.settings.vehicle,
                    showsUserLocation: model.location.authorized,
                    here: model.location.here,
                    recenterToken: recenter,
                    onUserPanned: { following = false }
                )
                .padding(.leading, wide && !driveShown ? sideInset : 0)
                .ignoresSafeArea()

                if driveShown, let trip = drive.trip {
                    if following, drive.progress.position != nil {
                        VehicleMarker(vehicle: model.settings.vehicle)
                            .position(x: geo.size.width / 2, y: focusY(geo))
                            .allowsHitTesting(false)
                    }
                    DriveOverlay(
                        trip: trip, progress: drive.progress, voice: model.settings.voice,
                        muted: model.settings.voiceMuted, following: following,
                        onMute: { drive.setMuted(!model.settings.voiceMuted) },
                        onRecenter: { following = true; recenter += 1 },
                        onGoogle: { drive.googleFromHere() },
                        onOverview: { drive.visible = false },
                        onEnd: { drive.stop() },
                        onBottomHeight: { bottomInset = $0 }
                    )
                } else if wide {
                    HStack(spacing: 0) {
                        sidePanel
                            .frame(width: 400)
                            .background(Color(.systemBackground).opacity(0.97))
                            .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { sideInset = $0 }
                        Spacer(minLength: 0)
                    }
                } else {
                    phoneOverlay(geo)
                }
            }
        }
        .sheet(isPresented: $showSettings) { SettingsView(settings: model.settings) }
        .onChange(of: drive.trip == nil) { _, ended in if !ended { following = true } }
        .onAppear { model.location.start() }
    }

    /// Vehicle screen position while following: middle of the area below driveFocusTop.
    private func focusY(_ geo: GeometryProxy) -> CGFloat {
        let h = geo.size.height + geo.safeAreaInsets.top + geo.safeAreaInsets.bottom
        let top = max(h * driveFocusTop, topInset)
        return (top + (h - bottomInset)) / 2 - geo.safeAreaInsets.top
    }

    // MARK: Phone layout

    @ViewBuilder
    private func phoneOverlay(_ geo: GeometryProxy) -> some View {
        VStack(spacing: 8) {
            SearchBar(model: model, focused: $searchFocused, onSettings: { showSettings = true })
                .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).maxY } action: { topInset = $0 + 16 }
            if searchFocused {
                Suggestions(model: model, maxHeight: geo.size.height * 0.55) { searchFocused = false }
            } else if let trip = drive.trip {
                ResumeCard(trip: trip, onResume: { drive.visible = true }, onEnd: { drive.stop() })
            }
            Spacer()
            if !searchFocused {
                HStack {
                    Spacer()
                    if model.location.here != nil {
                        Button { recenter += 1 } label: {
                            Image(systemName: "location.fill").padding(12)
                                .background(.regularMaterial, in: Circle())
                        }
                    }
                }
                bottomPanel(maxHeight: geo.size.height * 0.55)
                    .onGeometryChange(for: CGFloat.self) { geo.size.height + geo.safeAreaInsets.bottom - $0.frame(in: .global).minY + 8 } action: {
                        bottomInset = $0
                    }
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
    }

    // MARK: iPad layout

    private var sidePanel: some View {
        VStack(alignment: .leading, spacing: 12) {
            SearchBar(model: model, focused: $searchFocused, onSettings: { showSettings = true })
            if searchFocused {
                Suggestions(model: model, maxHeight: 600) { searchFocused = false }
            } else {
                if let trip = drive.trip {
                    ResumeCard(trip: trip, onResume: { drive.visible = true }, onEnd: { drive.stop() })
                }
                bottomPanel(maxHeight: .infinity)
            }
            Spacer(minLength: 0)
        }
        .padding(16)
    }

    // MARK: Panels

    @ViewBuilder
    private func bottomPanel(maxHeight: CGFloat) -> some View {
        switch model.state {
        case .review(let plan):
            Panel(maxHeight: maxHeight) {
                RouteReviewDetails(plan: plan)
            } footer: {
                RouteReviewActions(plan: plan,
                                   onGuidePartWay: { model.guidePartWay(plan) },
                                   onGuideWholeTrip: { model.guideWholeTrip(plan.destination) },
                                   onDriveOnly: { model.driveOnly(plan) })
            }
        case .working(let msg):
            Panel(maxHeight: maxHeight) {
                HStack(spacing: 12) { ProgressView(); Text(msg) }
            }
        case .error(let msg, let fallback):
            Panel(maxHeight: maxHeight) {
                Text(msg)
                if let place = fallback {
                    Button("Let Google route the whole trip") { model.guideWholeTrip(place) }
                        .buttonStyle(.borderedProminent).frame(maxWidth: .infinity)
                }
                Button("Back") { model.reset() }
            }
        case .idle:
            if let place = model.selected {
                Panel(maxHeight: maxHeight) {
                    PlacePanel(place: place, here: model.location.here, settings: model.settings,
                               onGo: { model.go(place) }, onClose: { model.clearSelection() })
                }
            }
        }
    }
}

/// Rounded card at the bottom (phone) or in the side column (iPad). Content scrolls; footer stays put.
struct Panel<Content: View, Footer: View>: View {
    var maxHeight: CGFloat
    @ViewBuilder var content: Content
    @ViewBuilder var footer: Footer

    init(maxHeight: CGFloat, @ViewBuilder content: () -> Content, @ViewBuilder footer: () -> Footer) {
        self.maxHeight = maxHeight
        self.content = content()
        self.footer = footer()
    }

    private var footerless: Bool { Footer.self == EmptyView.self }

    var body: some View {
        VStack(spacing: 0) {
            FitScroll(maxHeight: footerless ? maxHeight : max(120, maxHeight - 130)) {
                VStack(alignment: .leading, spacing: 12) { content }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(20)
            }
            if !footerless {
                Divider()
                footer.padding(.horizontal, 20).padding(.vertical, 12)
            }
        }
        .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 24))
        .shadow(radius: 8, y: 2)
    }
}

extension Panel where Footer == EmptyView {
    init(maxHeight: CGFloat, @ViewBuilder content: () -> Content) {
        self.init(maxHeight: maxHeight, content: content, footer: { EmptyView() })
    }
}

/// The car or arrow drawn at the follow point while the camera tracks the vehicle.
struct VehicleMarker: View {
    var vehicle: Vehicle
    var body: some View {
        VehicleIcon(vehicle: vehicle)
            .frame(width: 40, height: 40)
            .shadow(radius: 2)
    }
}

/// A scroll area exactly as tall as its content, up to maxHeight, then it scrolls.
/// (A plain ScrollView either fills all available space or, if sized to fit, overflows its box.)
struct FitScroll<Content: View>: View {
    var maxHeight: CGFloat
    @ViewBuilder var content: Content
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        ScrollView {
            content.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(height: min(contentHeight, maxHeight))
    }
}
