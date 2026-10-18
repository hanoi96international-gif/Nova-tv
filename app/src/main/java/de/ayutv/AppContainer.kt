package de.ayutv

import android.content.Context
import de.ayutv.data.SettingsStore
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.repo.ContentRepository
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.data.repo.SubtitleRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
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

    val playlists = PlaylistRepository(db, http, settings)
    val content = ContentRepository(db, http)
    // filesDir statt cacheDir: Fire OS leert den Cache bei wenig Speicher, Untertitel sollen bleiben.
    val subtitles = SubtitleRepository(http, context.filesDir)
}
