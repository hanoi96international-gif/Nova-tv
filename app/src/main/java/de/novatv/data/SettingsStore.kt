package de.novatv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Pufferprofile: kurz = schnelles Umschalten, lang = stabil bei 4K und wackeliger Leitung. */
enum class BufferProfile(val label: String) {
    FAST("Schnell umschalten"),
    BALANCED("Ausgewogen"),
    STABLE("Stabil (4K)"),
}

enum class LiveFormat(val label: String, val extension: String) {
    TS("MPEG-TS (schneller Start)", "ts"),
    HLS("HLS (m3u8)", "m3u8"),
}

data class AppSettings(
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val tunneling: Boolean = false,
    val matchFrameRate: Boolean = false,
    val liveFormat: LiveFormat = LiveFormat.TS,
    val userAgent: String = DEFAULT_USER_AGENT,
    val zapWithUpDown: Boolean = true,
) {
    companion object {
        const val DEFAULT_USER_AGENT = "NovaTV/0.1 (Linux; Android) ExoPlayer"
    }
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object Keys {
        val buffer = stringPreferencesKey("buffer_profile")
        val tunneling = booleanPreferencesKey("tunneling")
        val afr = booleanPreferencesKey("match_frame_rate")
        val liveFormat = stringPreferencesKey("live_format")
        val userAgent = stringPreferencesKey("user_agent")
        val zap = booleanPreferencesKey("zap_up_down")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.buffer] = next.bufferProfile.name
            prefs[Keys.tunneling] = next.tunneling
            prefs[Keys.afr] = next.matchFrameRate
            prefs[Keys.liveFormat] = next.liveFormat.name
            prefs[Keys.userAgent] = next.userAgent
            prefs[Keys.zap] = next.zapWithUpDown
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        bufferProfile = enumOrDefault(this[Keys.buffer], BufferProfile.BALANCED),
        tunneling = this[Keys.tunneling] ?: false,
        matchFrameRate = this[Keys.afr] ?: false,
        liveFormat = enumOrDefault(this[Keys.liveFormat], LiveFormat.TS),
        userAgent = this[Keys.userAgent]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_USER_AGENT,
        zapWithUpDown = this[Keys.zap] ?: true,
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
