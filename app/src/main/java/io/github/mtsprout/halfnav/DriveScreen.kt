package io.github.mtsprout.halfnav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.WorkKind
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val GuidedGreen = Color(0xFF0F7B4A)
/** Lighter green for text on the dark theme's dark surfaces. */
private val GuidedGreenOnDark = Color(0xFF6DD58C)

/** Green for text on the current surface: dark green on light, light green on dark. */
@Composable
private fun guidedTextColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) GuidedGreenOnDark else GuidedGreen
private val OwnSlate = Color(0xFF3C4043)
private val WarnOrange = Color(0xFFF29900)

/** Show the construction chip once it's this close. */
private const val WARNING_HORIZON_MILES = 30.0

/** GPS-style overlay on the map: next maneuver on top, trip summary on the bottom. */
@Composable
fun BoxScope.DriveOverlay(
    trip: DriveTrip,
    progress: DriveProgress,
    following: Boolean,
    voice: VoiceMode,
    muted: Boolean,
    onToggleMute: () -> Unit,
    onRecenter: () -> Unit,
    onGoogle: () -> Unit,
    onOverview: () -> Unit,
    onEnd: () -> Unit,
    onBottomHeight: (Int) -> Unit,
) {
    val guided = progress.guided(trip)

    Column(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InstructionBanner(trip, progress, guided, voice)
        progress.nextWarning
            ?.takeIf { !progress.arrived && Geo.metersToMiles(progress.metersToWarning) <= WARNING_HORIZON_MILES }
            ?.let { w ->
                val what = if (w.kind == WorkKind.ROAD_CLOSURE) "Road closed" else "Road work"
                val where = w.road?.let { " · $it" }.orEmpty()
                val whenText = if (progress.metersToWarning < 100) "now" else "in ${formatManeuverDistance(progress.metersToWarning)}"
                Surface(color = WarnOrange, shape = RoundedCornerShape(12.dp)) {
                    Text(
                        "⚠ $what $whenText$where",
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.Black,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
    }

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .onSizeChanged { onBottomHeight(it.height) },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpeedBubble(progress.speedMps)
            Surface(
                onClick = onToggleMute,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                modifier = Modifier.size(52.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (muted) "🔇" else "🔊", fontSize = 22.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!following) {
                ExtendedFloatingActionButton(
                    onClick = onRecenter,
                    icon = { Icon(Icons.Filled.LocationOn, null) },
                    text = { Text("Re-center") },
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            }
        }
        TripBar(trip, progress, onGoogle, onOverview, onEnd)
    }
}

@Composable
private fun InstructionBanner(trip: DriveTrip, progress: DriveProgress, guided: Boolean, voice: VoiceMode) {
    val color = if (guided || progress.arrived) GuidedGreen else OwnSlate
    Surface(color = color, contentColor = Color.White, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val remainingSec = progress.remainingSec(trip)
            when {
                progress.arrived -> {
                    Text(
                        if (trip.endsAtParking) "Turn into the parking lot" else "You've arrived",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(trip.destination.name, fontSize = 16.sp)
                }
                progress.rerouting -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Finding a new route…", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    EtaText(remainingSec)
                }
                progress.next != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(maneuverGlyph(progress.next.maneuver), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                formatManeuverDistance(progress.metersToNext),
                                Modifier.weight(1f),
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            EtaText(remainingSec)
                        }
                        Text(
                            progress.next.message ?: progress.next.street ?: "Continue",
                            fontSize = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                progress.position == null -> Text("Waiting for GPS…", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Continue to ${trip.destination.name}",
                        Modifier.weight(1f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    EtaText(remainingSec)
                }
            }
            if (!progress.arrived) {
                Spacer(Modifier.size(6.dp))
                Text(phaseText(trip, progress, guided, voice), fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f))
            }
        }
    }
}

/** "ETA 3:42 PM", with the digits as big as the turn distance beside them. */
@Composable
private fun EtaText(remainingSec: Int) {
    val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + remainingSec * 1000L))
    // Keep the digits big and let "PM" (or "p.m.") ride along smaller.
    val split = Regex("""^(.*\d)[\s\u00A0\u202F]*(\p{L}.*)$""").find(eta)
    val small = SpanStyle(fontSize = 15.sp)
    Text(
        buildAnnotatedString {
            withStyle(small) { append("ETA:") }
            append(" ") // full-size space, so the time doesn't crowd the label
            append(split?.groupValues?.get(1) ?: eta)
            split?.groupValues?.get(2)?.let { withStyle(small) { append(" $it") } }
        },
        Modifier.padding(start = 12.dp),
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
    )
}

private fun phaseText(trip: DriveTrip, progress: DriveProgress, guided: Boolean, voice: VoiceMode): String {
    val guide = if (voice == VoiceMode.HALFNAV) "HalfNav" else "Google Maps"
    val zone = formatMiles(trip.watchMiles?.toDouble() ?: 0.0)
    return when (trip.mode) {
        Mode.END_MILES ->
            if (guided) (if (voice == VoiceMode.HALFNAV) "HalfNav is guiding you in" else "Google Maps is taking over")
            else "On your own · $guide guides you within $zone"
        else -> {
            val h = trip.handoff
            when {
                h == null -> "On your own"
                h.reachesDestination -> "$guide is guiding you the whole way"
                guided -> "$guide is guiding you to ${h.label} · ${formatMiles(Geo.metersToMiles(h.offsetMeters - progress.offsetMeters))}"
                else -> "On your own"
            }
        }
    }
}

@Composable
private fun SpeedBubble(speedMps: Float) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp, modifier = Modifier.size(64.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("${(speedMps * 2.23694f).roundToInt()}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("mph", fontSize = 11.sp)
        }
    }
}

@Composable
private fun TripBar(
    trip: DriveTrip,
    progress: DriveProgress,
    onGoogle: () -> Unit,
    onOverview: () -> Unit,
    onEnd: () -> Unit,
) {
    val remainingSec = progress.remainingSec(trip)
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOverview) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Overview map") }
                Column(Modifier.weight(1f)) {
                    if (progress.arrived) {
                        Text("Arrived", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = guidedTextColor())
                    } else {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(formatMinutes(remainingSec), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = guidedTextColor())
                            Spacer(Modifier.width(8.dp))
                            Text("· ${formatMiles(Geo.metersToMiles(progress.remainingMeters(trip)))}", fontSize = 16.sp)
                        }
                    }
                    Text(
                        trip.destination.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGoogle, modifier = Modifier.weight(1f)) { Text("Google from here", maxLines = 1) }
                Button(
                    onClick = onEnd,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD93025), contentColor = Color.White),
                ) { Text("End") }
            }
        }
    }
}

/** Card on the overview map to get back into a trip you stepped out of. */
@Composable
fun ResumeDriveCard(trip: DriveTrip, onResume: () -> Unit, onEnd: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Driving to ${trip.destination.name}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (trip.mode == Mode.END_MILES) {
                    Text(
                        "Guidance starts within ${formatMiles(trip.watchMiles?.toDouble() ?: 0.0)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = onEnd) { Text("End") }
            Button(onClick = onResume) { Text("Drive view") }
        }
    }
}
