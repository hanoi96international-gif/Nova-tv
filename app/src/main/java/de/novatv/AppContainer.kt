package de.novatv

import android.content.Context
import de.novatv.data.SettingsStore
import de.novatv.data.db.NovaDatabase
import de.novatv.data.repo.ContentRepository
import de.novatv.data.repo.PlaylistRepository
import de.novatv.data.repo.SubtitleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = SettingsStore(context)
    val db = NovaDatabase.build(context)

    /** Ein gemeinsamer Client: Verbindungen zum IPTV-Server bleiben offen, das spart beim Zappen den TCP/TLS-Aufbau. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followSslRedirects(true)
        .build()

    val playlists = PlaylistRepository(db, http, settings)
    val content = ContentRepository(db, http)
    // filesDir statt cacheDir: Fire OS leert den Cache bei wenig Speicher, Untertitel sollen bleiben.
    val subtitles = SubtitleRepository(http, context.filesDir)
}
