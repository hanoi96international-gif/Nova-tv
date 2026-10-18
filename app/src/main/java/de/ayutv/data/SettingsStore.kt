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
 * Maschinelle Übersetzung, falls OpenSubtitles keine Untertitel in der Wunschsprache hat.
 * (Googles Übersetzung auf dem Gerät ist laut Google-Bedingungen auf Fernsehern nicht erlaubt
 * und braucht Google-Dienste, die Fire TV nicht hat.)
 */
enum class TranslationProvider(val label: String) {
    DEEPL("DeepL"),
    LIBRE("LibreTranslate"),
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
    /** Sprache, die beim Einschalten der Untertitel gewählt wird (ISO 639-1). */
    val subtitleLanguage: String = "en",
    /** Untertitel sind optional: standardmäßig aus, einschalten im Player über die Menütaste. */
    val subtitlesAutoOn: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.LARGE,
    val openSubtitlesKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val translationProvider: TranslationProvider = TranslationProvider.DEEPL,
    val deeplKey: String = "",
    val libreUrl: String = "",
    val libreKey: String = "",
) {
    /** Eigener Schlüssel aus den Einstellungen, sonst der in die App eingebaute. */
    val openSubtitlesApiKey: String get() = openSubtitlesKey.trim().ifEmpty { BuildConfig.OPENSUBTITLES_API_KEY }
    val onlineSubtitlesEnabled: Boolean get() = openSubtitlesApiKey.isNotBlank()
    val translationEnabled: Boolean
        get() = when (translationProvider) {
            TranslationProvider.NONE -> false
            TranslationProvider.DEEPL -> deeplKey.isNotBlank()
            TranslationProvider.LIBRE -> libreUrl.isNotBlank()
        }

    companion object {
        const val DEFAULT_USER_AGENT = "AyuTV/0.7 (Linux; Android) ExoPlayer"
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
        val epgShift = intPreferencesKey("epg_shift_hours")
        val asyncDecoding = booleanPreferencesKey("async_decoding_v2")
        val audioOutput = stringPreferencesKey("audio_output")
        val showGuide = booleanPreferencesKey("show_guide")
        val subLang = stringPreferencesKey("subtitle_language_code")
        val subAuto = booleanPreferencesKey("subtitles_auto_on")
        val provider = stringPreferencesKey("translation_provider")
        val deepl = stringPreferencesKey("deepl_key")
        val libreUrl = stringPreferencesKey("libre_url")
        val libreKey = stringPreferencesKey("libre_key")
        val subSize = stringPreferencesKey("subtitle_size")
        val osKey = stringPreferencesKey("opensubtitles_key")
        val osUser = stringPreferencesKey("opensubtitles_user")
        val osPassword = stringPreferencesKey("opensubtitles_password")
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
            prefs[Keys.epgShift] = next.epgShiftHours
            prefs[Keys.asyncDecoding] = next.asyncDecoding
            prefs[Keys.audioOutput] = next.audioOutput.name
            prefs[Keys.showGuide] = next.showGuide
            prefs[Keys.subLang] = next.subtitleLanguage
            prefs[Keys.subAuto] = next.subtitlesAutoOn
            prefs[Keys.provider] = next.translationProvider.name
            prefs[Keys.deepl] = next.deeplKey.trim()
            prefs[Keys.libreUrl] = next.libreUrl.trim()
            prefs[Keys.libreKey] = next.libreKey.trim()
            prefs[Keys.subSize] = next.subtitleSize.name
            prefs[Keys.osKey] = next.openSubtitlesKey.trim()
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
        asyncDecoding = this[Keys.asyncDecoding] ?: false,
        audioOutput = enumOrDefault(this[Keys.audioOutput], AudioOutput.AUTO),
        showGuide = this[Keys.showGuide] ?: true,
        subtitleLanguage = this[Keys.subLang]?.takeIf { Languages.of(it) != null } ?: "en",
        subtitlesAutoOn = this[Keys.subAuto] ?: false,
        translationProvider = enumOrDefault(this[Keys.provider], TranslationProvider.DEEPL),
        deeplKey = this[Keys.deepl].orEmpty(),
        libreUrl = this[Keys.libreUrl].orEmpty(),
        libreKey = this[Keys.libreKey].orEmpty(),
        subtitleSize = enumOrDefault(this[Keys.subSize], SubtitleSize.LARGE),
        openSubtitlesKey = this[Keys.osKey].orEmpty(),
        openSubtitlesUser = this[Keys.osUser].orEmpty(),
        openSubtitlesPassword = this[Keys.osPassword].orEmpty(),
    )

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default
}
