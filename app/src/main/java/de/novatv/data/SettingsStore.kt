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

enum class TranslationProvider(val label: String) {
    NONE("Aus"),
    DEEPL("DeepL"),
    LIBRE("LibreTranslate"),
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
    /** Sprache, die beim Einschalten der Untertitel gewählt wird (ISO 639-1). */
    val subtitleLanguage: String = "en",
    /** Untertitel sind optional: standardmäßig aus, einschalten im Player über die Menütaste. */
    val subtitlesAutoOn: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.LARGE,
    val openSubtitlesKey: String = "",
    val openSubtitlesUser: String = "",
    val openSubtitlesPassword: String = "",
    val translationProvider: TranslationProvider = TranslationProvider.NONE,
    val deeplKey: String = "",
    val libreUrl: String = "",
    val libreKey: String = "",
) {
    val onlineSubtitlesEnabled: Boolean get() = openSubtitlesKey.isNotBlank()
    val translationEnabled: Boolean
        get() = when (translationProvider) {
            TranslationProvider.NONE -> false
            TranslationProvider.DEEPL -> deeplKey.isNotBlank()
            TranslationProvider.LIBRE -> libreUrl.isNotBlank()
        }

    companion object {
        const val DEFAULT_USER_AGENT = "NovaTV/0.3 (Linux; Android) ExoPlayer"
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
        subtitleLanguage = this[Keys.subLang]?.takeIf { Languages.of(it) != null } ?: "en",
        subtitlesAutoOn = this[Keys.subAuto] ?: false,
        translationProvider = enumOrDefault(this[Keys.provider], TranslationProvider.NONE),
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
