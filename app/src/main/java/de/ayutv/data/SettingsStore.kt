package de.ayutv.data

import android.content.Context
import de.ayutv.BuildConfig
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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

/** Tonausgabe: Formate an den AV-Receiver durchreichen oder immer im Gerät dekodieren (Stereo/PCM). */
enum class AudioOutput(val label: String) {
    AUTO("Automatisch (Durchreichen an Receiver)"),
    PCM("Immer im Gerät dekodieren"),
}

/**
 * Übersetzung von Untertiteln, falls es keine in der Wunschsprache gibt: direkt auf dem Gerät
 * (OPUS-MT, offen lizenziert, ohne Konto und ohne Grenze).
 */
enum class TranslationProvider(val label: String) {
    ON_DEVICE("An (auf dem Gerät, unbegrenzt)"),
    NONE("Aus"),
}

enum class SubtitleSize(val label: String, val scale: Float) {
    NORMAL("Normal", 1.0f),
    LARGE("Groß", 1.3f),
    XL("Sehr groß", 1.6f),
}

data class AppSettings(
    val bufferProfile: BufferProfile = BufferProfile.BALANCED,
    val tunneling: Boolean = false,
    val matchFrameRate: Boolean = false,
    val liveFormat: LiveFormat = LiveFormat.TS,
    val userAgent: String = DEFAULT_USER_AGENT,
    val zapWithUpDown: Boolean = true,
    /**
     * Hardware-Decoder asynchron ansteuern. Auf Fire OS (Android 9–11) nicht Standard und mit manchen
     * Decodern instabil, daher aus; ab Android 12 nutzt Media3 es ohnehin automatisch.
     */
    val asyncDecoding: Boolean = false,
    val audioOutput: AudioOutput = AudioOutput.AUTO,
    /** Raster-TV-Guide im Menü. Die EPG-Daten werden unabhängig davon für "Jetzt läuft" genutzt. */
    val showGuide: Boolean = true,
    /** Korrektur für EPGs mit falscher Zeitzone, in Stunden. */
    val epgShiftHours: Int = 0,
    /** Sender ohne Programm beim Anbieter aus einem freien EPG (Deutschland, Österreich, Schweiz) ergänzen. */
    val internetGuide: Boolean = true,
    /** Freies EPG zuerst, der Anbieter füllt nur noch auf (bei Sport oft genauere Angaben, z. B. die Paarung). */
    val preferInternetGuide: Boolean = false,
    /** Sprache, die beim Einschalten der Untertitel gewählt wird (ISO 639-1). */
    val subtitleLanguage: String = "en",
    /** Untertitel sind optional: standardmäßig aus, einschalten im Player über die Menütaste. */
    val subtitlesAutoOn: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.LARGE,
    /** Untertitel bei OpenSubtitles suchen, wenn der Film selbst keine passenden hat. */
    val onlineSubtitles: Boolean = true,
    val openSubtitlesKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val translationProvider: TranslationProvider = TranslationProvider.ON_DEVICE,
) {
    /** Eigener Schlüssel aus den Einstellungen, sonst der in die App eingebaute. */
    val openSubtitlesApiKey: String get() = openSubtitlesKey.trim().ifEmpty { BuildConfig.OPENSUBTITLES_API_KEY }
    val onlineSubtitlesEnabled: Boolean get() = onlineSubtitles && openSubtitlesApiKey.isNotBlank()
    val translationEnabled: Boolean get() = translationProvider != TranslationProvider.NONE

    companion object {
        const val DEFAULT_USER_AGENT = "DnATV/0.7 (Linux; Android) ExoPlayer"
    }
}

/** Eine einzige DataStore-Instanz für die Datei "settings" (zwei Instanzen derselben Datei sind nicht erlaubt). */
val Context.settingsDataStore by preferencesDataStore(name = "settings")

private const val SEARCH_HISTORY_SIZE = 12

class SettingsStore(private val context: Context) {
    private object Keys {
        val buffer = stringPreferencesKey("buffer_profile")
        val tunneling = booleanPreferencesKey("tunneling")
        val afr = booleanPreferencesKey("match_frame_rate")
        val liveFormat = stringPreferencesKey("live_format")
        val userAgent = stringPreferencesKey("user_agent")
        val zap = booleanPreferencesKey("zap_up_down")
        val epgShift = intPreferencesKey("epg_shift_hours")
        val internetGuide = booleanPreferencesKey("internet_guide")
        val preferInternetGuide = booleanPreferencesKey("prefer_internet_guide")
        val asyncDecoding = booleanPreferencesKey("async_decoding_v2")
        val audioOutput = stringPreferencesKey("audio_output")
        val showGuide = booleanPreferencesKey("show_guide")
        val subLang = stringPreferencesKey("subtitle_language_code")
        val subAuto = booleanPreferencesKey("subtitles_auto_on")
        val provider = stringPreferencesKey("translation_provider")
        val subSize = stringPreferencesKey("subtitle_size")
        val osKey = stringPreferencesKey("opensubtitles_key")
        val osOn = booleanPreferencesKey("opensubtitles_on")
        val osUser = stringPreferencesKey("opensubtitles_user")
        val osPassword = stringPreferencesKey("opensubtitles_password")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    private val searchHistoryKey = stringPreferencesKey("search_history")

    /** Letzte Suchbegriffe, neueste zuerst. */
    val searchHistory: Flow<List<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[searchHistoryKey].orEmpty().split('\n').filter { it.isNotBlank() }
    }

    suspend fun rememberSearch(query: String) {
        val q = query.trim().takeIf { it.length >= 2 } ?: return
        context.settingsDataStore.edit { prefs ->
            val old = prefs[searchHistoryKey].orEmpty().split('\n').filter { it.isNotBlank() }
            prefs[searchHistoryKey] = (listOf(q) + old.filterNot { it.equals(q, ignoreCase = true) })
                .take(SEARCH_HISTORY_SIZE).joinToString("\n")
        }
    }

    suspend fun clearSearchHistory() {
        context.settingsDataStore.edit { it.remove(searchHistoryKey) }
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.buffer] = next.bufferProfile.name
            prefs[Keys.tunneling] = next.tunneling
            prefs[Keys.afr] = next.matchFrameRate
            prefs[Keys.liveFormat] = next.liveFormat.name
            prefs[Keys.userAgent] = next.userAgent
            prefs[Keys.zap] = next.zapWithUpDown
            prefs[Keys.epgShift] = next.epgShiftHours
            prefs[Keys.internetGuide] = next.internetGuide
            prefs[Keys.preferInternetGuide] = next.preferInternetGuide
            prefs[Keys.asyncDecoding] = next.asyncDecoding
            prefs[Keys.audioOutput] = next.audioOutput.name
            prefs[Keys.showGuide] = next.showGuide
            prefs[Keys.subLang] = next.subtitleLanguage
            prefs[Keys.subAuto] = next.subtitlesAutoOn
            prefs[Keys.provider] = next.translationProvider.name
            prefs[Keys.subSize] = next.subtitleSize.name
            prefs[Keys.osKey] = next.openSubtitlesKey.trim()
            prefs[Keys.osOn] = next.onlineSubtitles
            prefs[Keys.osUser] = next.openSubtitlesUser.trim()
            prefs[Keys.osPassword] = next.openSubtitlesPassword
        }
    }

    private fun Preferences.toSettings() = AppSettings(
        bufferProfile = enumOrDefault(this[Keys.buffer], BufferProfile.BALANCED),
        tunneling = this[Keys.tunneling] ?: false,
        matchFrameRate = this[Keys.afr] ?: false,
        liveFormat = enumOrDefault(this[Keys.liveFormat], LiveFormat.TS),
        userAgent = this[Keys.userAgent]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_USER_AGENT,
        zapWithUpDown = this[Keys.zap] ?: true,
        epgShiftHours = this[Keys.epgShift] ?: 0,
        internetGuide = this[Keys.internetGuide] ?: true,
        preferInternetGuide = this[Keys.preferInternetGuide] ?: false,
        asyncDecoding = this[Keys.asyncDecoding] ?: false,
        audioOutput = enumOrDefault(this[Keys.audioOutput], AudioOutput.AUTO),
        showGuide = this[Keys.showGuide] ?: true,
        subtitleLanguage = this[Keys.subLang]?.takeIf { Languages.of(it) != null } ?: "en",
        subtitlesAutoOn = this[Keys.subAuto] ?: false,
        translationProvider = enumOrDefault(this[Keys.provider], TranslationProvider.ON_DEVICE),
        subtitleSize = enumOrDefault(this[Keys.subSize], SubtitleSize.LARGE),
        openSubtitlesKey = this[Keys.osKey].orEmpty(),
        onlineSubtitles = this[Keys.osOn] ?: true,
        openSubtitlesUser = this[Keys.osUser].orEmpty(),
        openSubtitlesPassword = this[Keys.osPassword].orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
