package io.github.mtsprout.halfnav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.mtsprout.halfnav.core.ConstructionWarning
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.WorkKind

private val Amber = Color(0xFFFFF4D6)
private val Green = Color(0xFFE3F4E6)

/** Scrollable part of the route check: summary, legend, construction list. */
@Composable
fun RouteReviewDetails(plan: TripPlan) {
    val handoff = plan.handoff
    val totalMiles = Geo.metersToMiles(plan.route.lengthMeters)
    val guidedMiles = Geo.metersToMiles(handoff.offsetMeters)
    val unguided = plan.warnings.filter { it.unguided }
    val guided = plan.warnings.filterNot { it.unguided }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Route check: ${plan.destination.name}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        plan.destination.locality?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
        Text(
            "${formatMiles(totalMiles)} · about ${formatMinutes(plan.route.travelTimeSec)} with current traffic",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            if (handoff.reachesDestination)
                "The highway runs nearly to your destination, so Maps will guide you the whole way."
            else
                "Guided to ${handoff.label} (${formatMiles(guidedMiles)}), then on your own for ${formatMiles(totalMiles - guidedMiles)}.",
            fontWeight = FontWeight.SemiBold,
        )
        Legend(showUnguided = !handoff.reachesDestination, showWork = plan.warnings.isNotEmpty())

        if (plan.warnings.isEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
                Text("✓ No reported construction on this route.", Modifier.padding(16.dp))
            }
        }
        if (unguided.isNotEmpty()) {
            WarningGroup(
                title = "⚠ Construction after guidance ends",
                note = "You'd hit these without directions. Google can route around them if you let it navigate the whole trip.",
                warnings = unguided,
                highlight = true,
            )
        }
        if (guided.isNotEmpty()) {
            WarningGroup(
                title = "Construction while you're guided",
                note = "Google Maps will handle these.",
                warnings = guided,
                highlight = false,
            )
        }

        Text(
            "Construction info comes from TomTom's live incident reports and may not include every work zone.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Action buttons, pinned below the scrolling details so they're always reachable. */
@Composable
fun RouteReviewActions(
    plan: TripPlan,
    onGuidePartWay: () -> Unit,
    onGuideWholeTrip: () -> Unit,
    onDriveOnly: () -> Unit,
) {
    val handoff = plan.handoff
    val partLabel = if (handoff.reachesDestination) "Navigate" else "Guide me to ${handoff.label}"
    val wholeTripFirst = plan.warnings.any { it.unguided }
    val primary = if (wholeTripFirst) "Let Google route the whole trip" else partLabel
    val secondary = when {
        handoff.reachesDestination -> null
        wholeTripFirst -> "$partLabel anyway"
        else -> "Google: whole trip"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = if (wholeTripFirst) onGuideWholeTrip else onGuidePartWay, modifier = Modifier.fillMaxWidth()) {
            Text(primary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) {
                OutlinedButton(
                    onClick = if (wholeTripFirst) onGuidePartWay else onGuideWholeTrip,
                    modifier = Modifier.weight(1f),
                ) { Text(secondary, maxLines = 1) }
            }
            TextButton(onClick = onDriveOnly) { Text("Drive view only") }
        }
    }
}

@Composable
private fun Legend(showUnguided: Boolean, showWork: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(Color(0xFF1A73E8), "Guided")
        if (showUnguided) LegendItem(Color(0xFF7D8590), "On your own")
        if (showWork) LegendItem(Color(0xFFF29900), "Construction")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 18.dp, height = 5.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WarningGroup(title: String, note: String, warnings: List<ConstructionWarning>, highlight: Boolean) {
    Card(
        colors = if (highlight) CardDefaults.cardColors(containerColor = Amber) else CardDefaults.cardColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.bodySmall)
            warnings.forEach { WarningRow(it) }
        }
    }
}

@Composable
private fun WarningRow(w: ConstructionWarning) {
    val what = if (w.kind == WorkKind.ROAD_CLOSURE) "Road closed" else "Road work"
    val where = w.road?.let { " on $it" }.orEmpty()
    val delay = when {
        w.kind == WorkKind.ROAD_CLOSURE -> ""
        w.delaySec >= 60 -> " · ~${formatMinutes(w.delaySec)} delay"
        else -> " · little or no delay"
    }
    Column {
        Text("$what$where", fontWeight = FontWeight.SemiBold)
        Text(
            "At mile ${"%.1f".format(Geo.metersToMiles(w.startOffsetMeters))}, " +
                "${formatMiles(Geo.metersToMiles(w.lengthMeters))} long$delay",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

fun formatMinutes(seconds: Int): String {
    val min = (seconds + 30) / 60
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}
