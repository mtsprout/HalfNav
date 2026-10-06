package io.github.mtsprout.halfnav

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
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
import androidx.core.content.ContextCompat
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.SunTimes
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * Remembers whether the app was last dark, so the very first frame of the next launch (window,
 * map placeholder) can match it instead of flashing light. Plain SharedPreferences because it
 * must be readable synchronously in onCreate, before anything draws.
 */
object ThemeCache {
    private const val FILE = "theme_cache"
    private const val KEY = "last_dark"

    fun lastDark(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null
    }

    fun save(context: Context, dark: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, dark).apply()
    }
}

/**
 * Whether to use the dark look right now. In [ThemeMode.AUTO] that's from official sunset to
 * sunrise at [here], re-checked every minute. Until the location is known it keeps whatever the
 * app last showed ([lastDark]), so launching doesn't flip themes.
 */
@Composable
fun rememberDarkMode(mode: ThemeMode, here: LatLng?, lastDark: Boolean?): Boolean {
    val systemDark = isSystemInDarkTheme()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(mode) {
        while (mode == ThemeMode.AUTO) {
            delay(60_000 - System.currentTimeMillis() % 60_000) // on the minute
            now = Instant.now()
        }
    }
    return when (mode) {
        ThemeMode.AUTO -> here?.let { SunTimes.isDark(now, it.lat, it.lng, ZoneId.systemDefault()) }
            ?: lastDark ?: systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
}

@Composable
fun HalfNavTheme(dark: Boolean, content: @Composable () -> Unit) {
    val activity = LocalContext.current as? ComponentActivity
    LaunchedEffect(dark) {
        val a = activity ?: return@LaunchedEffect
        ThemeCache.save(a, dark)
        // Window behind everything, e.g. while the map loads after a switch.
        a.window.setBackgroundDrawable(
            ColorDrawable(ContextCompat.getColor(a, if (dark) R.color.window_dark else R.color.window_light))
        )
        // Status bar icons: light on the dark map, dark on the light one.
        a.enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
            else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
