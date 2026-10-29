package de.ayutv.data.repo

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.ayutv.AppContainer
import de.ayutv.AyuApp
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.parse.ChannelQuality
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.player.PlayerActivity
import de.ayutv.player.PlayerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Sender-Check in der Ruhezeit: Fassungen doppelt geführter Sender (und Favoriten) kurz unsichtbar anspielen,
 * ohne Bild und Ton zu dekodieren. Gemessen werden Auflösung, Startzeit und ob die Fassung überhaupt läuft.
 * Damit zeigt die Liste je Sender die wirklich beste Fassung, der Qualitätsfilter kennt die echte Auflösung, und
 * ausgefallene Fassungen landen hinten. Läuft nie, während ein Film oder Sender läuft (Verbindungslimit).
 */
class ChannelCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AyuApp).container
        if (!container.settings.current().channelCheck) return Result.success()
        val quick = inputData.getBoolean(KEY_QUICK, false)
        runCatching {
            val check = ChannelCheck(applicationContext, container)
            if (quick) check.run(QUICK_BUDGET_MS, maxChannels = QUICK_CHANNELS, quick = true) else check.run(BUDGET_MS)
        }.onFailure { Log.w(TAG, "Sender-Check", it) }
        return Result.success()
    }

    companion object {
        const val TAG = "DnAChannelCheck"
        private const val BUDGET_MS = 8 * 60_000L
        private const val QUICK_BUDGET_MS = 5 * 60_000L
        private const val QUICK_CHANNELS = 40
        private const val KEY_QUICK = "quick"

        private fun constraints(idle: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .apply { if (idle) setRequiresDeviceIdle(true) }
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ChannelCheckWorker>(6, TimeUnit.HOURS).setConstraints(constraints(idle = true)).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("channel-check", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChannelCheckWorker>().setConstraints(constraints(idle = false)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("channel-check-now", ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Kurzer Check gleich nach dem Laden einer Playlist (nicht erst nachts): Favoriten, Sportsender und zuletzt
         * geschaute Sender, damit „Best of“ und die beste Fassung je Sender sofort etwas zeigen. Eine Minute
         * Verzögerung, damit der Import und das erste Umschalten nicht um die Verbindung konkurrieren.
         */
        fun runQuick(context: Context) {
            val request = OneTimeWorkRequestBuilder<ChannelCheckWorker>()
                .setConstraints(constraints(idle = false))
                .setInitialDelay(1, TimeUnit.MINUTES)
                .setInputData(androidx.work.workDataOf(KEY_QUICK to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("channel-check-quick", ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Je Sendername nur eine Fassung (die nach Namen beste), damit der kurze Check viele verschiedene Sender schafft. */
internal object ContentPicks {
    fun onePerName(channels: List<ChannelEntity>): List<ChannelEntity> =
        channels.groupBy { ChannelVariants.key(it.name) ?: it.name.lowercase() }.values
            .map { group -> group.minBy { ChannelQuality.score(it.key, it.name) } }
}

/** Die eigentliche Prüfung (auch direkt aufrufbar, z. B. im Gerätetest). */
class ChannelCheck(private val context: Context, private val container: AppContainer) {
    private val prefs = context.getSharedPreferences("channel_check", Context.MODE_PRIVATE)

    /** Ergebnis des letzten Laufs (für die Einstellungen). */
    val lastResult: String? get() = prefs.getString("last", null)

    /** [quick]: nur Favoriten, Sportsender und zuletzt geschaute Sender (für „Best of“ gleich nach dem Import). */
    suspend fun run(budgetMs: Long, maxChannels: Int = Int.MAX_VALUE, quick: Boolean = false): String {
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val all = container.db.channels().all()
        val favorites = container.db.favorites().keys().toHashSet()
        val recent = container.db.history().recentLiveKeys(40).toHashSet()
        fun sport(c: ChannelEntity) = de.ayutv.data.parse.ProgrammeText.isSportChannel(c.name) || c.groupTitle.contains("sport", ignoreCase = true)
        // Zuerst Favoriten, dann zuletzt geschaute, dann Sport, dann Fassungen doppelter Sender (dort entscheidet der
        // Check); je seit drei Tagen ungeprüft.
        val variants = if (quick) emptyList() else ChannelVariants.groups(all, { it.name }, { it.playlistId }).flatten()
        val sportChannels = if (quick) all.filter(::sport).let { list -> ContentPicks.onePerName(list) } else emptyList()
        val candidates = (all.filter { it.key in favorites } + all.filter { it.key in recent } + sportChannels + variants).distinctBy { it.key }
            .filter { ChannelQuality.info(it.key)?.let { info -> now - info.checkedAt > RECHECK_MS } ?: true }
            .sortedBy { if (it.key in favorites) 0 else if (it.key in recent) 1 else 2 }
        var checked = 0
        var working = 0
        for (channel in candidates) {
            if (checked >= maxChannels || SystemClock.elapsedRealtime() - started > budgetMs) break
            // Läuft etwas im Player: aufhören, sonst nähme der Check die einzige Verbindung beim Anbieter weg.
            if (container.foreground?.get() is PlayerActivity) break
            val result = probe(channel)
            ChannelQuality.recordCheck(channel.key, result.first, result.second)
            checked++
            if (result.first > 0) working++
            Log.i(ChannelCheckWorker.TAG, "${channel.name}: ${if (result.first > 0) "${result.first}p, ${result.second} ms" else "läuft nicht"}")
        }
        ChannelQuality.saveIfChanged(container.qualityFile)
        val summary = "$checked Fassungen geprüft, $working laufen" + (if (candidates.size > checked) ", ${candidates.size - checked} folgen beim nächsten Mal" else "")
        prefs.edit().putString("last", "${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())}: $summary").apply()
        return summary
    }

    /** Auflösung (Höhe, 0 = läuft nicht) und Zeit bis zur ersten Bildinformation. */
    suspend fun probe(channel: ChannelEntity): Pair<Int, Long> {
        val thread = HandlerThread("channel-check").apply { start() }
        val handler = Handler(thread.looper)
        val done = CompletableDeferred<Pair<Int, Long>>()
        var player: androidx.media3.exoplayer.ExoPlayer? = null
        val started = SystemClock.elapsedRealtime()
        handler.post {
            runCatching {
                val p = PlayerFactory.createProbe(context, container.http, channel.userAgent ?: DEFAULT_AGENT, thread.looper)
                player = p
                p.addListener(object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        val height = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                            .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }.maxOrNull() ?: 0
                        if (height > 0) done.complete(height to SystemClock.elapsedRealtime() - started)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        done.complete(0 to 0L)
                    }
                })
                val url = channel.url
                val mime = if (url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }) MimeTypes.APPLICATION_M3U8 else null
                p.setMediaItem(MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build())
                p.prepare()
            }.onFailure { done.complete(0 to 0L) }
        }
        val result = withTimeoutOrNull(PROBE_TIMEOUT_MS) { done.await() } ?: (0 to 0L)
        handler.post {
            runCatching { player?.release() }
            thread.quitSafely()
        }
        // Dem Anbieter Zeit geben, die Verbindung zu schließen (sonst 403 „zu viele Verbindungen“ beim nächsten).
        kotlinx.coroutines.delay(600)
        return result
    }

    private companion object {
        const val RECHECK_MS = 3 * 24 * 3_600_000L
        const val PROBE_TIMEOUT_MS = 10_000L
        const val DEFAULT_AGENT = de.ayutv.data.AppSettings.DEFAULT_USER_AGENT
    }
}
