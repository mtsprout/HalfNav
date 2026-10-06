package io.github.mtsprout.halfnav

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mtsprout.halfnav.core.Geo
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val vm: TripViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                App(vm)
            }
        }
    }
}

/** Warn before planning a trip to a place this far away; it may be a look-alike in another town. */
private const val FAR_AWAY_MILES = 100.0

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

@Composable
private fun App(vm: TripViewModel) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current

    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val here by vm.here.collectAsStateWithLifecycle()
    val watch by ArrivalWatchService.watch.collectAsStateWithLifecycle()
    val drive by vm.drive.collectAsStateWithLifecycle()
    val driveProgress by vm.driveProgress.collectAsStateWithLifecycle()
    val driveVisible by vm.driveVisible.collectAsStateWithLifecycle()
    val driveShown = drive != null && driveVisible
    var following by remember { mutableStateOf(true) }
    var driveBottomPx by remember { mutableIntStateOf(0) }

    var locationPermitted by remember { mutableStateOf(hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)) }
    var searchFocused by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    var pendingGo by remember { mutableStateOf<Place?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        locationPermitted = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        if (locationPermitted) vm.refreshLocation()
        val place = pendingGo
        pendingGo = null
        if (place != null) {
            if (locationPermitted) vm.go(place) else message = "HalfNav needs precise location to plan your trip."
        }
    }

    LaunchedEffect(Unit) {
        if (locationPermitted) vm.refreshLocation()
        else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun go(place: Place) {
        message = null
        val needed = buildList {
            if (!locationPermitted) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Trips show an ongoing notification (and end mode may alert you), so ask up front.
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) vm.go(place)
        else {
            pendingGo = place
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    if (driveShown) {
        // Keep the screen on while driving.
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }

    BackHandler(enabled = driveShown || searchFocused || state !is UiState.Idle || selected != null) {
        when {
            driveShown -> vm.hideDrive()
            searchFocused -> focusManager.clearFocus()
            state !is UiState.Idle -> vm.reset()
            else -> vm.clearSelection()
        }
    }

    val review = (state as? UiState.Review)?.plan
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxPanel = maxHeight * 0.55f
        TripMap(
            content = if (driveShown) MapContent(drive = drive, progress = driveProgress)
            else MapContent(selected = selected, plan = review, watch = watch),
            insets = MapInsets(
                top = topPx,
                bottom = if (driveShown) driveBottomPx else bottomPx,
                side = with(density) { 40.dp.roundToPx() },
            ),
            here = here,
            locationPermitted = locationPermitted,
            vehicle = settings.vehicle,
            recenterRequests = recenter,
            onFollowingChange = { following = it },
            modifier = Modifier.fillMaxSize(),
        )

        if (driveShown) {
            DriveOverlay(
                trip = drive!!,
                progress = driveProgress,
                following = following,
                voice = settings.voice,
                muted = settings.voiceMuted,
                onToggleMute = { vm.toggleMute() },
                onRecenter = { recenter++ },
                onGoogle = vm::googleFromHere,
                onOverview = vm::hideDrive,
                onEnd = vm::endDrive,
                onBottomHeight = { driveBottomPx = it },
            )
        } else {

        // Top: search bar, suggestions, trip-watching banner.
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchBar(
                query = search.query,
                hasSelection = selected != null,
                onQueryChange = {
                    if (state !is UiState.Idle) vm.reset()
                    vm.onQueryChange(it)
                },
                onSearch = vm::searchNow,
                onClear = {
                    vm.clearSelection()
                },
                onSettings = { showSettings = true },
                onFocusChange = { searchFocused = it },
                modifier = Modifier.onSizeChanged { topPx = it.height + with(density) { 40.dp.roundToPx() } },
            )
            if (searchFocused) {
                Suggestions(
                    search = search,
                    recent = settings.recent,
                    here = here,
                    maxHeight = maxPanel,
                    onPick = {
                        vm.select(it)
                        focusManager.clearFocus()
                    },
                )
            } else {
                val d = drive
                if (d != null) ResumeDriveCard(d, onResume = vm::showDrive, onEnd = vm::endDrive)
                else watch?.let { WatchBanner(it, onCancel = { ArrivalWatchService.stop(context) }) }
            }
        }

        // Bottom: recenter button + whatever panel the current step needs.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(12.dp)
                .onSizeChanged { bottomPx = it.height },
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (locationPermitted && here != null && !searchFocused) {
                SmallFloatingActionButton(
                    onClick = { vm.refreshLocation(); recenter++ },
                    containerColor = MaterialTheme.colorScheme.surface,
                ) { Icon(Icons.Filled.LocationOn, contentDescription = "My location") }
            }
            if (!searchFocused) {
                val panelModifier = Modifier.fillMaxWidth().heightIn(max = maxPanel)
                when (val s = state) {
                    is UiState.Review -> Panel(
                        panelModifier,
                        footer = {
                            RouteReviewActions(
                                plan = s.plan,
                                onGuidePartWay = { vm.guidePartWay(s.plan) },
                                onGuideWholeTrip = { vm.guideWholeTrip(s.plan.destination) },
                                onDriveOnly = { vm.driveWithoutGoogle(s.plan) },
                            )
                        },
                    ) {
                        RouteReviewDetails(s.plan)
                    }
                    is UiState.Working -> Panel(panelModifier) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(s.message)
                        }
                    }
                    is UiState.Error -> Panel(panelModifier) {
                        Text(s.message)
                        if (s.fallback != null) {
                            Button(onClick = { vm.guideWholeTrip(s.fallback) }, Modifier.fillMaxWidth()) {
                                Text("Let Google route the whole trip")
                            }
                        }
                        TextButton(onClick = vm::reset) { Text("Back") }
                    }
                    UiState.Idle -> selected?.let { place ->
                        Panel(panelModifier) {
                            PlacePanel(
                                place = place,
                                here = here,
                                settings = settings,
                                watchActive = watch != null,
                                message = message,
                                vm = vm,
                                onGo = { go(place) },
                                onClose = vm::clearSelection,
                            )
                        }
                    }
                }
            }
        }
        }
    }

    if (showSettings) SettingsDialog(settings, vm, onDismiss = { showSettings = false })
}

@Composable
private fun SearchBar(
    query: String,
    hasSelection: Boolean,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    onSettings: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth(),
    ) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search here") },
            singleLine = true,
            leadingIcon = {
                if (hasSelection) {
                    IconButton(onClick = onClear) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                } else {
                    Icon(Icons.Filled.Search, null)
                }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Close, "Clear") }
                } else {
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { onFocusChange(it.isFocused) },
        )
    }
}

@Composable
private fun Suggestions(
    search: SearchState,
    recent: List<Place>,
    here: LatLng?,
    maxHeight: androidx.compose.ui.unit.Dp,
    onPick: (Place) -> Unit,
) {
    val showRecent = search.query.isBlank()
    val items = if (showRecent) recent else search.results
    if (items.isEmpty() && !search.searching && search.error == null && (showRecent || search.query.length < 2)) return

    Surface(
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            if (showRecent) SectionLabel("Recent")
            if (search.searching && items.isEmpty()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Searching…")
                }
            }
            search.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            if (!showRecent && !search.searching && search.error == null && items.isEmpty()) {
                Text("No results for \"${search.query}\"", Modifier.padding(16.dp))
            }
            items.forEachIndexed { i, place ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 56.dp))
                PlaceRow(place, here, onClick = { onPick(place) })
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun PlaceRow(place: Place, here: LatLng?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(place.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Town first, so look-alike names ("Sts Peter & Paul" in two states) can't be confused.
            val where = listOfNotNull(place.locality, place.category?.replaceFirstChar { it.uppercase() })
            if (where.isNotEmpty()) {
                Text(
                    where.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                place.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        here?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                formatMiles(Geo.metersToMiles(Geo.distanceMeters(it, place.latLng))),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Bottom card. [content] scrolls if it's tall; [footer] stays pinned at the bottom. */
@Composable
private fun Panel(
    modifier: Modifier,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        modifier = modifier,
    ) {
        Column {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = if (footer == null) 20.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
            if (footer != null) {
                HorizontalDivider()
                Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) { footer() }
            }
        }
    }
}

@Composable
private fun PlacePanel(
    place: Place,
    here: LatLng?,
    settings: Settings,
    watchActive: Boolean,
    message: String?,
    vm: TripViewModel,
    onGo: () -> Unit,
    onClose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            val details = listOfNotNull(
                place.category?.replaceFirstChar { it.uppercase() },
                here?.let { formatMiles(Geo.metersToMiles(Geo.distanceMeters(it, place.latLng))) + " away" },
            ).joinToString(" · ")
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodyMedium)
            Text(place.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close") }
    }
    here?.let { Geo.metersToMiles(Geo.distanceMeters(it, place.latLng)) }
        ?.takeIf { it > FAR_AWAY_MILES }
        ?.let { miles ->
            Surface(color = Color(0xFFFFF4D6), shape = RoundedCornerShape(12.dp)) {
                Text(
                    "⚠ ${formatMiles(miles)} away" + (place.locality?.let { " in $it" } ?: "") +
                        ". Make sure this is the right place.",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = Color.Black,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

    Text("Help me…", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModeChip(settings.mode, Mode.START_INTERSTATE, "To interstate", vm::setMode)
        ModeChip(settings.mode, Mode.START_MILES, "First miles", vm::setMode)
        ModeChip(settings.mode, Mode.END_MILES, "Last miles", vm::setMode)
    }
    Text(
        when (settings.mode) {
            Mode.START_INTERSTATE -> "Google Maps guides you onto the interstate, then you're on your own."
            Mode.START_MILES -> "Google Maps guides you for the first stretch, then you're on your own."
            Mode.END_MILES -> "No guidance until you're close, then Google Maps takes you in."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    when (settings.mode) {
        Mode.START_MILES -> MilesSlider("Guide me for the first", settings.startMiles, 1f..30f, vm::setStartMiles)
        Mode.END_MILES -> MilesSlider("Start guiding me within", settings.endMiles, 0.5f..10f, vm::setEndMiles)
        Mode.START_INTERSTATE -> {}
    }
    if (settings.mode == Mode.END_MILES && watchActive) {
        Text("This replaces the trip you're already watching.", style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onGo, modifier = Modifier.fillMaxWidth()) {
        Text(if (settings.mode == Mode.END_MILES) "Start watching" else "Go")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ModeChip(current: Mode, mode: Mode, label: String, onSelect: (Mode) -> Unit) {
    FilterChip(selected = current == mode, onClick = { onSelect(mode) }, label = { Text(label, maxLines = 1) })
}

@Composable
private fun MilesSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onDone: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(value) }
    LaunchedEffect(value) { v = value }
    Column {
        Text("$label ${formatMiles(v.toDouble())}")
        Slider(
            value = v,
            onValueChange = { v = (it * 2).roundToInt() / 2f }, // half-mile steps
            onValueChangeFinished = { onDone(v) },
            valueRange = range,
        )
    }
}

@Composable
private fun WatchBanner(w: Watch, onCancel: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Watching: ${w.label}", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (w.milesToGo?.let { "${formatMiles(it)} to go · " } ?: "Waiting for GPS · ") +
                        "Maps starts at ${formatMiles(w.triggerMiles.toDouble())}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun SettingsDialog(settings: Settings, vm: TripViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Check the whole route for construction before start-mode trips", Modifier.weight(1f))
                    Switch(checked = settings.warnConstruction, onCheckedChange = vm::setWarnConstruction)
                }
                Column {
                    Text("Turn-by-turn voice on the guided part")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.voice == VoiceMode.HALFNAV,
                            onClick = { vm.setVoice(VoiceMode.HALFNAV) },
                            label = { Text("HalfNav") },
                        )
                        FilterChip(
                            selected = settings.voice == VoiceMode.GOOGLE,
                            onClick = { vm.setVoice(VoiceMode.GOOGLE) },
                            label = { Text("Google Maps") },
                        )
                    }
                    Text(
                        if (settings.voice == VoiceMode.HALFNAV) "HalfNav speaks the turns itself and never opens Google Maps."
                        else "HalfNav hands the guided part to Google Maps and speaks only construction and arrival alerts.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column {
                    Text("Vehicle icon in drive view")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = settings.vehicle == Vehicle.CAR,
                            onClick = { vm.setVehicle(Vehicle.CAR) },
                            label = { Text("Car") },
                        )
                        FilterChip(
                            selected = settings.vehicle == Vehicle.ARROW,
                            onClick = { vm.setVehicle(Vehicle.ARROW) },
                            label = { Text("Arrow") },
                        )
                    }
                }
                OverlayPermissionRow()
            }
        },
    )
}

/** End mode can only open Maps by itself if "Display over other apps" is allowed. */
@Composable
private fun OverlayPermissionRow() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = AndroidSettings.canDrawOverlays(context) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (allowed) {
            Text("End mode will open Google Maps automatically when you're close.")
        } else {
            Text(
                "In end mode, Android only lets HalfNav show a notification when you're close. " +
                    "To have Google Maps open by itself, allow \"Display over other apps\".",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                )
            }) { Text("Allow auto-open") }
        }
    }
}
