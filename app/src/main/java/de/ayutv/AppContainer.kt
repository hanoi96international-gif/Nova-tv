package de.ayutv

import android.content.Context
import de.ayutv.data.SettingsStore
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.repo.ContentRepository
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.data.repo.SubtitleRepository
import de.ayutv.data.repo.Translators
import de.ayutv.data.reminders.Reminders
import de.ayutv.data.parental.ParentalControl
import de.ayutv.data.translate.OnDeviceTranslator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val db = AyuDatabase.build(context)

    /** Ein gemeinsamer Client: Verbindungen zum IPTV-Server bleiben offen, das spart beim Zappen den TCP/TLS-Aufbau. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followSslRedirects(true)
        .build()

    val epgOverrides = de.ayutv.data.EpgOverrides(context)
    val accountLimits = de.ayutv.data.AccountLimits(context)
    val playlists = PlaylistRepository(db, http, settings, context.cacheDir, epgOverrides, accountLimits)
    val content = ContentRepository(db, http)
    // filesDir statt cacheDir: Fire OS leert den Cache bei wenig Speicher, Untertitel sollen bleiben.
    val subtitles = SubtitleRepository(http, context.filesDir).also { repo ->
        scope.launch(Dispatchers.IO) { repo.purgeLegacyCache() }
    }

    /** Suche von außen (Suchtaste der Fernbedienung, Sprachassistent): Begriff für die Suche, "" = nur öffnen. */
    val searchRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val reminders = Reminders(context)
    val intros = de.ayutv.data.IntroStore(context)
    val playerMemory = de.ayutv.data.PlayerMemory(context)
    /** Fenster der App im Vordergrund (für Tasten vom Handy). */
    @Volatile var foreground: java.lang.ref.WeakReference<android.app.Activity>? = null
    val remote = de.ayutv.data.remote.RemoteServer(context, this)
    val recordings = de.ayutv.data.record.Recordings(context)
    val profiles = de.ayutv.data.profile.Profiles(context, this)
    val updates = de.ayutv.data.update.Updater(context, http)
    val parental = ParentalControl(context, db)

    /** Spiele der gefolgten Vereine als Erinnerungen vormerken (nach jedem TV-Guide-Update). */
    suspend fun refreshTeamReminders() {
        runCatching {
            val current = settings.current()
            if (current.followedTeams.isNotEmpty()) reminders.ensure(content.teamReminders(current.followedTeams))
            if (current.alertKeywords.isNotEmpty()) reminders.ensure(content.keywordReminders(current.alertKeywords))
        }.onFailure { android.util.Log.w("AyuTeams", "Vereins-Erinnerungen", it) }
    }

    init {
        Translators.onDevice = OnDeviceTranslator(context.filesDir, http)
        Translators.remote = de.ayutv.data.translate.RemoteTranslator(context.applicationContext)
        // Passwörter älterer Versionen verschlüsseln (einmalig, danach ohne Wirkung).
        scope.launch {
            runCatching { playlists.protectStoredPasswords() }
            runCatching { settings.protectStoredSecrets() }
        }
    }
}
