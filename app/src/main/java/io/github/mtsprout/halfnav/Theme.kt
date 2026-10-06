package io.github.mtsprout.halfnav

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.SunTimes
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Whether to use the dark look right now. In [ThemeMode.SUN] that's from official sunset to
 * sunrise at [here], re-checked every minute; without a location yet, it follows the phone.
 */
@Composable
fun rememberDarkMode(mode: ThemeMode, here: LatLng?): Boolean {
    val systemDark = isSystemInDarkTheme()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(mode) {
        while (mode == ThemeMode.SUN) {
            delay(60_000 - System.currentTimeMillis() % 60_000) // on the minute
            now = Instant.now()
        }
    }
    return when (mode) {
        ThemeMode.SUN -> here?.let { SunTimes.isDark(now, it.lat, it.lng, ZoneId.systemDefault()) } ?: systemDark
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

@Composable
fun HalfNavTheme(dark: Boolean, content: @Composable () -> Unit) {
    val activity = LocalContext.current as? ComponentActivity
    // Status bar icons: light on the dark map, dark on the light one.
    LaunchedEffect(dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
