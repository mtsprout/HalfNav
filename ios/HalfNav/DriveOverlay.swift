import SwiftUI
import HalfNavCore

private let guidedGreen = Color(red: 0x0F / 255, green: 0x7B / 255, blue: 0x4A / 255)
private let ownSlate = Color(red: 0x3C / 255, green: 0x40 / 255, blue: 0x43 / 255)
private let warnOrange = Color(red: 0xF2 / 255, green: 0x99 / 255, blue: 0x00 / 255)
/// Show the construction chip once it's this close.
private let warningHorizonMiles = 30.0

/// GPS-style overlay on the map: next maneuver on top, trip summary on the bottom.
struct DriveOverlay: View {
    var trip: DriveTrip
    var progress: DriveProgress
    var voice: VoiceMode
    var muted: Bool
    var following: Bool
    var onMute: () -> Void
    var onRecenter: () -> Void
    var onGoogle: () -> Void
    var onOverview: () -> Void
    var onEnd: () -> Void
    var onBottomHeight: (CGFloat) -> Void

    var body: some View {
        let guided = progress.guided(trip)
        VStack(spacing: 8) {
            InstructionBanner(trip: trip, progress: progress, guided: guided, voice: voice)
            if let w = progress.nextWarning, !progress.arrived,
               Geo.shared.metersToMiles(m: progress.metersToWarning) <= warningHorizonMiles {
                let what = w.kind == WorkKind.roadClosure ? "Road closed" : "Road work"
                let when = progress.metersToWarning < 100 ? "now" : "in \(formatManeuverDistance(progress.metersToWarning))"
                HStack {
                    Text("⚠ \(what) \(when)\(w.road.map { " · \($0)" } ?? "")")
                        .fontWeight(.semibold).foregroundStyle(.black)
                        .padding(.horizontal, 14).padding(.vertical, 8)
                        .background(warnOrange, in: RoundedRectangle(cornerRadius: 12))
                    Spacer()
                }
            }
            Spacer()
            VStack(spacing: 10) {
                HStack(alignment: .bottom, spacing: 10) {
                    SpeedBubble(mps: progress.speedMps)
                    Button(action: onMute) {
                        Image(systemName: muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                            .font(.title3).frame(width: 52, height: 52)
                            .background(Color(.systemBackground), in: Circle()).shadow(radius: 4)
                    }
                    Spacer()
                    if !following {
                        Button(action: onRecenter) {
                            Label("Re-center", systemImage: "location.fill").padding(.horizontal, 16).padding(.vertical, 12)
                                .background(Color(.systemBackground), in: Capsule()).shadow(radius: 4)
                        }
                    }
                }
                TripBar(trip: trip, progress: progress, onGoogle: onGoogle, onOverview: onOverview, onEnd: onEnd)
            }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { onBottomHeight($0 + 24) }
        }
        .padding(12)
    }
}

private struct InstructionBanner: View {
    var trip: DriveTrip
    var progress: DriveProgress
    var guided: Bool
    var voice: VoiceMode

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if progress.arrived {
                Text("You've arrived").font(.system(size: 26, weight: .bold))
                Text(trip.destination.name)
            } else if progress.rerouting {
                HStack(spacing: 12) { ProgressView().tint(.white); Text("Finding a new route…").font(.title3).fontWeight(.semibold) }
            } else if let next = progress.next {
                HStack(spacing: 14) {
                    Text(maneuverGlyph(next.maneuver)).font(.system(size: 44, weight: .bold))
                    VStack(alignment: .leading) {
                        Text(formatManeuverDistance(progress.metersToNext)).font(.system(size: 26, weight: .bold))
                        Text(next.message ?? next.street ?? "Continue").lineLimit(2)
                    }
                }
            } else if progress.position == nil {
                Text("Waiting for GPS…").font(.title3).fontWeight(.semibold)
            } else {
                Text("Continue to \(trip.destination.name)").font(.title3).fontWeight(.semibold)
            }
            if !progress.arrived {
                Text(phaseText).font(.footnote).opacity(0.85)
            }
        }
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(guided || progress.arrived ? guidedGreen : ownSlate, in: RoundedRectangle(cornerRadius: 16))
        .shadow(radius: 6)
    }

    private var phaseText: String {
        let guide = voice == .halfnav ? "HalfNav" : "Google Maps"
        let zone = formatMiles(trip.watchMiles ?? 0)
        if trip.mode == .endMiles {
            return guided ? (voice == .halfnav ? "HalfNav is guiding you in" : "Google Maps is taking over")
                : "On your own · \(guide) guides you within \(zone)"
        }
        guard let h = trip.handoff else { return "On your own" }
        if h.reachesDestination { return "\(guide) is guiding you the whole way" }
        if guided {
            return "\(guide) is guiding you to \(h.label) · \(formatMiles(Geo.shared.metersToMiles(m: h.offsetMeters - progress.offsetMeters)))"
        }
        return "On your own"
    }
}

private struct SpeedBubble: View {
    var mps: Double
    var body: some View {
        VStack(spacing: 0) {
            Text("\(Int((mps * 2.23694).rounded()))").font(.system(size: 22, weight: .bold))
            Text("mph").font(.caption2)
        }
        .frame(width: 64, height: 64)
        .background(Color(.systemBackground), in: Circle())
        .shadow(radius: 4)
    }
}

private struct TripBar: View {
    var trip: DriveTrip
    var progress: DriveProgress
    var onGoogle: () -> Void
    var onOverview: () -> Void
    var onEnd: () -> Void
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let remaining = progress.remainingSec(trip)
        let eta = Date().addingTimeInterval(Double(remaining)).formatted(date: .omitted, time: .shortened)
        // Dark green on light, light green on dark, so the time is readable on both.
        let timeColor = scheme == .dark ? Color(red: 0x6D / 255, green: 0xD5 / 255, blue: 0x8C / 255) : guidedGreen
        VStack(spacing: 8) {
            HStack {
                Button(action: onOverview) { Image(systemName: "chevron.left").font(.title3).padding(8) }
                VStack(alignment: .leading, spacing: 2) {
                    if progress.arrived {
                        Text("Arrived").font(.system(size: 22, weight: .bold)).foregroundStyle(timeColor)
                        Text(trip.destination.name).font(.caption)
                    } else {
                        HStack(alignment: .lastTextBaseline, spacing: 8) {
                            Text(formatMinutes(remaining)).font(.system(size: 22, weight: .bold)).foregroundStyle(timeColor)
                            Text("· \(formatMiles(Geo.shared.metersToMiles(m: progress.remainingMeters(trip))))")
                        }
                        Text("Arrive \(eta) · \(trip.destination.name)").font(.caption).lineLimit(1)
                    }
                }
                Spacer()
            }
            HStack {
                Button(action: onGoogle) { Text("Google from here").frame(maxWidth: .infinity) }
                    .buttonStyle(.bordered)
                Button(action: onEnd) { Text("End").padding(.horizontal, 12) }
                    .buttonStyle(.borderedProminent).tint(Color(red: 0xD9 / 255, green: 0x30 / 255, blue: 0x25 / 255))
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
        .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 20))
        .shadow(radius: 8)
    }
}
