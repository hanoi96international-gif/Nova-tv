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
    val playlists = PlaylistRepository(db, http, settings, context.cacheDir, epgOverrides, accountLimits).also { repo ->
        // Nach jedem Laden einer Playlist kurz die wichtigsten Sender prüfen: „Best of“ ist so gleich gefüllt.
        repo.onImported = { runCatching { de.ayutv.data.repo.ChannelCheckWorker.runQuick(context) } }
    }
    val content = ContentRepository(db, http)
    // filesDir statt cacheDir: Fire OS leert den Cache bei wenig Speicher, Untertitel sollen bleiben.
    val subtitles = SubtitleRepository(http, context.filesDir).also { repo ->
        scope.launch(Dispatchers.IO) { repo.purgeLegacyCache() }
    }

    /** Suche von außen (Suchtaste der Fernbedienung, Sprachassistent): Begriff für die Suche, "" = nur öffnen. */
    val searchRequests = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val reminders = Reminders(context)
    /** Erinnerung, die gerade fällig ist (Anstoß eines gefolgten Vereins …): der Player blendet sie ein. */
    val dueReminder = kotlinx.coroutines.flow.MutableStateFlow<de.ayutv.data.reminders.Reminder?>(null)
    val intros = de.ayutv.data.IntroStore(context)
    val playerMemory = de.ayutv.data.PlayerMemory(context)
    /** Fenster der App im Vordergrund (für Tasten vom Handy). */
    @Volatile var foreground: java.lang.ref.WeakReference<android.app.Activity>? = null
    val remote = de.ayutv.data.remote.RemoteServer(context, this)
    /** Gemessene Qualität der Senderfassungen ([de.ayutv.data.parse.ChannelQuality]). */
    val qualityFile = java.io.File(context.filesDir, "channel_quality.tsv")
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
        Translators.withPhone = de.ayutv.data.translate.HelperAwareTranslator(Translators.remote, http)
        // Aussehen gleich anwenden (Menüs und Player lesen die Farben aus de.ayutv.ui.theme.AyuColors).
        scope.launch { runCatching { de.ayutv.ui.theme.AyuColors.use(settings.current().uiTheme) } }
        // Senderqualität: Gemessenes laden, Filter und Vorlieben aus den Einstellungen übernehmen.
        scope.launch(Dispatchers.IO) { de.ayutv.data.parse.ChannelQuality.load(qualityFile) }
        scope.launch {
            settings.settings.collect { s -> de.ayutv.data.parse.ChannelQuality.configure(s.minQuality.height, s.preferUhd, s.hideBrokenChannels) }
        }
        // Passwörter älterer Versionen verschlüsseln (einmalig, danach ohne Wirkung).
        scope.launch {
            runCatching { playlists.protectStoredPasswords() }
            runCatching { settings.protectStoredSecrets() }
        }
    }
}
