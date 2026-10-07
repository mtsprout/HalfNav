import SwiftUI
import HalfNavCore

private let amber = Color(red: 1, green: 0.957, blue: 0.839)
private let green = Color(red: 0.89, green: 0.957, blue: 0.902)

/// Scrollable part of the route check: summary, legend, construction list.
struct RouteReviewDetails: View {
    var plan: TripPlan

    var body: some View {
        let h = plan.handoff
        let totalMiles = Geo.shared.metersToMiles(m: plan.route.lengthMeters)
        let guidedMiles = Geo.shared.metersToMiles(m: h.offsetMeters)
        let unguided = plan.warnings.filter { $0.unguided }
        let guided = plan.warnings.filter { !$0.unguided }

        Text("Route check: \(plan.destination.name)").font(.title2).fontWeight(.bold)
        if let loc = plan.destination.locality { Text(loc).fontWeight(.medium) }
        Text("\(formatMiles(totalMiles)) · about \(formatMinutes(Int(plan.route.travelTimeSec))) with current traffic")
        Text(h.reachesDestination
             ? "The highway runs nearly to your destination, so you'll be guided the whole way."
             : "Guided to \(h.label) (\(formatMiles(guidedMiles))), then on your own for \(formatMiles(totalMiles - guidedMiles)).")
            .fontWeight(.semibold)
        HStack(spacing: 16) {
            LegendItem(color: Color(red: 0.10, green: 0.45, blue: 0.91), label: "Guided")
            if !h.reachesDestination { LegendItem(color: Color(red: 0.49, green: 0.52, blue: 0.56), label: "On your own") }
            if !plan.warnings.isEmpty { LegendItem(color: Color(red: 0.95, green: 0.60, blue: 0), label: "Construction") }
        }
        if plan.warnings.isEmpty {
            Text("✓ No reported construction on this route.")
                .foregroundStyle(.black)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16).background(green, in: RoundedRectangle(cornerRadius: 12))
        }
        if !unguided.isEmpty {
            WarningGroup(title: "⚠ Construction after guidance ends",
                         note: "You'd hit these without directions. Google can route around them if you let it navigate the whole trip.",
                         warnings: unguided, highlight: true)
        }
        if !guided.isEmpty {
            WarningGroup(title: "Construction while you're guided", note: "You'll be guided through these.",
                         warnings: guided, highlight: false)
        }
        Text("Construction info comes from TomTom's live incident reports and may not include every work zone.")
            .font(.caption)
    }
}

/// Action buttons, pinned below the scrolling details so they're always reachable.
struct RouteReviewActions: View {
    var plan: TripPlan
    var onGuidePartWay: () -> Void
    var onGuideWholeTrip: () -> Void
    var onDriveOnly: () -> Void

    var body: some View {
        let h = plan.handoff
        let partLabel = h.reachesDestination ? "Navigate" : "Guide me to \(h.label)"
        let wholeTripFirst = plan.warnings.contains { $0.unguided }
        VStack(spacing: 8) {
            Button(action: wholeTripFirst ? onGuideWholeTrip : onGuidePartWay) {
                Text(wholeTripFirst ? "Let Google route the whole trip" : partLabel).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            HStack {
                if !h.reachesDestination {
                    Button(action: wholeTripFirst ? onGuidePartWay : onGuideWholeTrip) {
                        Text(wholeTripFirst ? "\(partLabel) anyway" : "Google: whole trip").lineLimit(1).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                Button("Drive view only", action: onDriveOnly)
            }
        }
    }
}

private struct LegendItem: View {
    var color: Color
    var label: String
    var body: some View {
        HStack(spacing: 6) {
            RoundedRectangle(cornerRadius: 3).fill(color).frame(width: 18, height: 5)
            Text(label).font(.caption)
        }
    }
}

private struct WarningGroup: View {
    var title: String
    var note: String
    var warnings: [ConstructionWarning]
    var highlight: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).fontWeight(.bold)
            Text(note).font(.caption)
            ForEach(Array(warnings.enumerated()), id: \.offset) { _, w in
                let what = w.kind == WorkKind.roadClosure ? "Road closed" : "Road work"
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(what)\(w.road.map { " on \($0)" } ?? "")").fontWeight(.semibold)
                    let delay = w.kind == WorkKind.roadClosure ? "" :
                        w.delaySec >= 60 ? " · ~\(formatMinutes(Int(w.delaySec))) delay" : " · little or no delay"
                    Text("At mile \(String(format: "%.1f", Geo.shared.metersToMiles(m: w.startOffsetMeters))), \(formatMiles(Geo.shared.metersToMiles(m: w.lengthMeters))) long\(delay)")
                        .font(.caption)
                }
            }
        }
        .foregroundStyle(highlight ? Color.black : Color.primary)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(highlight ? amber : Color(.tertiarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}
