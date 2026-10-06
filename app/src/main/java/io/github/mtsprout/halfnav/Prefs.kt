package io.github.mtsprout.halfnav

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.mtsprout.halfnav.core.LatLng
import io.github.mtsprout.halfnav.core.Place
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("settings")

enum class Mode { START_INTERSTATE, START_MILES, END_MILES }

/** The icon that marks you on the drive view. */
enum class Vehicle { CAR, ARROW }

/** Who speaks turn-by-turn directions on the guided part of a trip. */
enum class VoiceMode {
    /** HalfNav's own voice; Google Maps is never opened. */
    HALFNAV,
    /** Hand the guided part to Google Maps, as before. */
    GOOGLE,
}

/** When to use the dark look (map and panels). */
enum class ThemeMode {
    /** Dark from official sunset to sunrise where you are. */
    SUN,
    /** Follow the phone's own dark-mode setting. */
    SYSTEM,
    LIGHT,
    DARK,
}

data class Settings(
    val mode: Mode = Mode.START_INTERSTATE,
    val startMiles: Float = 5f,
    val endMiles: Float = 2f,
    val warnConstruction: Boolean = true,
    val vehicle: Vehicle = Vehicle.CAR,
    val voice: VoiceMode = VoiceMode.HALFNAV,
    val voiceMuted: Boolean = false,
    val theme: ThemeMode = ThemeMode.SUN,
    val recent: List<Place> = emptyList(),
)

class Prefs(private val context: Context) {
    private object Keys {
        val mode = stringPreferencesKey("mode")
        val startMiles = floatPreferencesKey("start_miles")
        val endMiles = floatPreferencesKey("end_miles")
        val warn = booleanPreferencesKey("warn_construction")
        val recent = stringPreferencesKey("recent_places")
        val vehicle = stringPreferencesKey("vehicle")
        val voice = stringPreferencesKey("voice")
        val voiceMuted = booleanPreferencesKey("voice_muted")
        val theme = stringPreferencesKey("theme")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            mode = p[Keys.mode]?.let { runCatching { Mode.valueOf(it) }.getOrNull() } ?: Mode.START_INTERSTATE,
            startMiles = p[Keys.startMiles] ?: 5f,
            endMiles = p[Keys.endMiles] ?: 2f,
            warnConstruction = p[Keys.warn] ?: true,
            vehicle = p[Keys.vehicle]?.let { runCatching { Vehicle.valueOf(it) }.getOrNull() } ?: Vehicle.CAR,
            voice = p[Keys.voice]?.let { runCatching { VoiceMode.valueOf(it) }.getOrNull() } ?: VoiceMode.HALFNAV,
            voiceMuted = p[Keys.voiceMuted] ?: false,
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SUN,
            recent = decode(p[Keys.recent]),
        )
    }

    suspend fun setMode(mode: Mode) = context.dataStore.edit { it[Keys.mode] = mode.name }
    suspend fun setStartMiles(v: Float) = context.dataStore.edit { it[Keys.startMiles] = v }
    suspend fun setEndMiles(v: Float) = context.dataStore.edit { it[Keys.endMiles] = v }
    suspend fun setWarnConstruction(v: Boolean) = context.dataStore.edit { it[Keys.warn] = v }
    suspend fun setVehicle(v: Vehicle) = context.dataStore.edit { it[Keys.vehicle] = v.name }
    suspend fun setVoice(v: VoiceMode) = context.dataStore.edit { it[Keys.voice] = v.name }
    suspend fun setVoiceMuted(v: Boolean) = context.dataStore.edit { it[Keys.voiceMuted] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }

    suspend fun addRecent(place: Place) = context.dataStore.edit { p ->
        val others = decode(p[Keys.recent]).filterNot { it.name == place.name && it.address == place.address }
        p[Keys.recent] = encode((listOf(place) + others).take(MAX_RECENT))
    }

    private fun encode(places: List<Place>): String = JSONArray(places.map {
        JSONObject()
            .put("name", it.name)
            .put("address", it.address)
            .put("lat", it.latLng.lat)
            .put("lng", it.latLng.lng)
            .put("category", it.category)
            .put("locality", it.locality)
    }).toString()

    private fun decode(json: String?): List<Place> = runCatching {
        val arr = JSONArray(json ?: return emptyList())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(
                o.getString("name"),
                o.optString("address"),
                LatLng(o.getDouble("lat"), o.getDouble("lng")),
                o.optString("category").ifBlank { null },
                o.optString("locality").ifBlank { null },
            )
        }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_RECENT = 6
    }
}
