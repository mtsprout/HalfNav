import SwiftUI
import HalfNavCore

struct SearchBar: View {
    @Bindable var model: AppModel
    var focused: FocusState<Bool>.Binding
    var onSettings: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            if model.pickingHome {
                Button { model.stopPickingHome(); focused.wrappedValue = false } label: { Image(systemName: "chevron.left") }
            } else if model.selected != nil {
                Button { model.clearSelection() } label: { Image(systemName: "chevron.left") }
            } else {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            }
            TextField(model.pickingHome ? "Search for your home address" : "Search here", text: Binding(get: { model.search.query }, set: { model.queryChanged($0) }))
                .focused(focused)
                .submitLabel(.search)
                .onSubmit { model.searchNow() }
                .autocorrectionDisabled()
            if !model.search.query.isEmpty {
                Button {
                    if model.pickingHome { model.queryChanged("") } else { model.clearSelection() }
                } label: { Image(systemName: "xmark") }
            } else {
                Button(action: onSettings) { Image(systemName: "gearshape.fill") }
            }
        }
        .font(.body)
        .foregroundStyle(.primary)
        .padding(.horizontal, 18)
        .frame(height: 54)
        .background(Color(.systemBackground), in: Capsule())
        .shadow(radius: 6, y: 2)
    }
}

struct Suggestions: View {
    @Bindable var model: AppModel
    var maxHeight: CGFloat
    var onPicked: () -> Void

    var body: some View {
        let s = model.search
        let showRecent = s.query.isEmpty && !model.pickingHome
        let items: [Place] = showRecent ? model.settings.recent.map(\.place) : s.results
        if !(items.isEmpty && !s.searching && s.error == nil && (showRecent || s.query.count < 2)) {
            FitScroll(maxHeight: maxHeight) {
                VStack(alignment: .leading, spacing: 0) {
                    if showRecent {
                        Text("Recent").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16).padding(.top, 10)
                    }
                    if s.searching && items.isEmpty {
                        HStack { ProgressView(); Text("Searching…") }.padding(16)
                    }
                    if let e = s.error { Text(e).foregroundStyle(.red).padding(16) }
                    if !showRecent && !s.searching && s.error == nil && items.isEmpty {
                        Text("No results for \"\(s.query)\"").padding(16)
                    }
                    ForEach(Array(items.enumerated()), id: \.offset) { i, place in
                        if i > 0 { Divider().padding(.leading, 52) }
                        PlaceRow(place: place, here: model.location.here) {
                            if model.pickingHome { model.setHome(place) } else { model.select(place) }
                            onPicked()
                        }
                    }
                }
            }
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 16))
            .shadow(radius: 6, y: 2)
        }
    }
}

struct PlaceRow: View {
    var place: Place
    var here: LatLng?
    var onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 16) {
                Image(systemName: "mappin.circle.fill").font(.title2).foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    Text(place.name).fontWeight(.semibold).lineLimit(1)
                    // Town first, so look-alike names ("Sts Peter & Paul" in two states) can't be confused.
                    let whereText = [place.locality, place.category.map { $0.prefix(1).uppercased() + $0.dropFirst() }]
                        .compactMap { $0 }.joined(separator: " · ")
                    if !whereText.isEmpty { Text(whereText).font(.caption).fontWeight(.medium).lineLimit(1) }
                    Text(place.address).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 8)
                if let miles = milesBetween(here, place.latLng) {
                    Text(formatMiles(miles)).font(.caption).foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Warn before planning a trip to a place this far away; it may be a look-alike in another town.
private let farAwayMiles = 100.0

struct PlacePanel: View {
    var place: Place
    var here: LatLng?
    @Bindable var settings: Settings
    var onGo: () -> Void
    var onClose: () -> Void

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 2) {
                Text(place.name).font(.title2).fontWeight(.bold)
                let details = [place.category.map { $0.prefix(1).uppercased() + $0.dropFirst() },
                               milesBetween(here, place.latLng).map { formatMiles($0) + " away" }]
                    .compactMap { $0 }.joined(separator: " · ")
                if !details.isEmpty { Text(details) }
                Text(place.address).font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            Button(action: onClose) { Image(systemName: "xmark").padding(6) }
        }
        if let miles = milesBetween(here, place.latLng), miles > farAwayMiles {
            Text("⚠ \(formatMiles(miles)) away\(place.locality.map { " in \($0)" } ?? ""). Make sure this is the right place.")
                .foregroundStyle(.black)
                .padding(.horizontal, 12).padding(.vertical, 8)
                .background(Color(red: 1, green: 0.957, blue: 0.839), in: RoundedRectangle(cornerRadius: 12))
        }
        Text("Help me…").font(.subheadline).fontWeight(.medium)
        Picker("Mode", selection: $settings.mode) {
            Text("To interstate").tag(Mode.startInterstate)
            Text("First miles").tag(Mode.startMiles)
            Text("Last miles").tag(Mode.endMiles)
        }
        .pickerStyle(.segmented)
        Text(modeHelp).font(.caption)
        if settings.mode == .startMiles {
            MilesSlider(label: "Guide me for the first", value: $settings.startMiles, range: 1...30)
        } else if settings.mode == .endMiles {
            MilesSlider(label: "Start guiding me within", value: $settings.endMiles, range: 0.5...10)
        }
        Button(action: onGo) {
            Text(settings.mode == .endMiles ? "Start watching" : "Go").frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
    }

    private var modeHelp: String {
        switch settings.mode {
        case .startInterstate: return "You're guided onto the interstate, then you're on your own."
        case .startMiles: return "You're guided for the first stretch, then you're on your own."
        case .endMiles: return "No guidance until you're close, then you're guided in."
        }
    }
}

struct MilesSlider: View {
    var label: String
    @Binding var value: Double
    var range: ClosedRange<Double>

    var body: some View {
        VStack(alignment: .leading) {
            Text("\(label) \(formatMiles(value))")
            Slider(value: $value, in: range, step: 0.5)
        }
    }
}

/// Small button under the search bar: go home, or set it up the first time.
struct HomeChip: View {
    var hasHome: Bool
    var onTap: () -> Void
    var onChange: () -> Void
    var onRemove: () -> Void

    var body: some View {
        HStack {
            Button(action: onTap) {
                Label("Home", systemImage: "house.fill")
                    .font(.subheadline.weight(.medium))
                    .padding(.horizontal, 14).padding(.vertical, 8)
                    .background(Color(.systemBackground), in: Capsule())
                    .shadow(radius: 4, y: 1)
            }
            .buttonStyle(.plain)
            .contextMenu {
                if hasHome {
                    Button("Change Home", systemImage: "pencil", action: onChange)
                    Button("Remove Home", systemImage: "trash", role: .destructive, action: onRemove)
                }
            }
            Spacer()
        }
    }
}

/// Card on the overview map to get back into a trip you stepped out of.
struct ResumeCard: View {
    var trip: DriveTrip
    var onResume: () -> Void
    var onEnd: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading) {
                Text("Driving to \(trip.destination.name)").fontWeight(.bold).lineLimit(1)
                if trip.mode == .endMiles {
                    Text("Guidance starts within \(formatMiles(trip.watchMiles ?? 0))").font(.caption)
                }
            }
            Spacer()
            Button("End", action: onEnd)
            Button("Drive view", action: onResume).buttonStyle(.borderedProminent)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 16))
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct SettingsView: View {
    @Bindable var settings: Settings
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Appearance", selection: $settings.theme) {
                        Text("Auto").tag(ThemeMode.auto)
                        Text("Light").tag(ThemeMode.light)
                        Text("Dark").tag(ThemeMode.dark)
                    }
                    .pickerStyle(.segmented)
                } header: { Text("Appearance") } footer: {
                    Text(settings.theme == .auto ? "Dark from official sunset to sunrise where you are."
                         : settings.theme == .light ? "Always light." : "Always dark.")
                }
                Section {
                    Toggle("Check the whole route for construction before start-mode trips", isOn: $settings.warnConstruction)
                }
                Section {
                    Picker("Voice", selection: $settings.voice) {
                        Text("HalfNav").tag(VoiceMode.halfnav)
                        Text("Google Maps").tag(VoiceMode.google)
                    }
                    .pickerStyle(.segmented)
                } header: { Text("Turn-by-turn voice on the guided part") } footer: {
                    Text(settings.voice == .halfnav
                         ? "HalfNav speaks the turns itself and never opens Google Maps."
                         : "HalfNav hands the guided part to Google Maps (or Apple Maps). On iPhone, apps can't open other apps from the background, so in end mode you'll get a notification to tap.")
                }
                Section("Vehicle icon in drive view") {
                    Picker("Vehicle", selection: $settings.vehicle) {
                        Text("Car").tag(Vehicle.car)
                        Text("Arrow").tag(Vehicle.arrow)
                    }
                    .pickerStyle(.segmented)
                }
            }
            .navigationTitle("Settings")
            .toolbar { Button("Done") { dismiss() } }
        }
    }
}
