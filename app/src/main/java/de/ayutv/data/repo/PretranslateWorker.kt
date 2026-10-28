package de.ayutv.data.repo

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
import java.util.concurrent.TimeUnit

/**
 * Vorübersetzen in der Ruhezeit: Für „Weiter schauen“ und die nächste Folge angefangener Serien werden
 * Untertitel in der gewünschten Sprache vorab geholt, und fehlen sie bei OpenSubtitles, auf dem Gerät aus
 * einer anderen Sprache übersetzt. Beim Schauen sind sie dann sofort da. Läuft nur, wenn das Gerät nicht
 * benutzt wird, in Etappen von wenigen Minuten (der Stand jeder Etappe bleibt erhalten).
 */
class PretranslateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AyuApp).container
        return runCatching { Pretranslation(applicationContext, container).run(BUDGET_MS) }
            .onFailure { Log.w(TAG, "Vorübersetzen", it) }
            .fold({ Result.success() }, { Result.success() })
            // Übersetzer-Prozess beenden (Speicher), außer ein Film läuft inzwischen und braucht ihn.
            .also { if (container.foreground?.get() !is de.ayutv.player.PlayerActivity) runCatching { Translators.remote.release() } }
    }

    companion object {
        const val TAG = "DnAPretranslate"
        /** WorkManager bricht nach 10 Minuten ab: rechtzeitig aufhören und den Stand sichern. */
        private const val BUDGET_MS = 8 * 60_000L

        private fun constraints(idle: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .apply { if (idle) setRequiresDeviceIdle(true) }
            .build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PretranslateWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints(idle = true))
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("pretranslate", ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Gleich eine Etappe (Einstellungen „Jetzt vorbereiten“), ohne auf Ruhe zu warten. */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<PretranslateWorker>().setConstraints(constraints(idle = false)).build()
            WorkManager.getInstance(context).enqueueUniqueWork("pretranslate-now", ExistingWorkPolicy.KEEP, request)
        }
    }
}

/** Die eigentliche Arbeit (auch ohne WorkManager aufrufbar, z. B. im Gerätetest). */
class Pretranslation(private val context: Context, private val container: AppContainer) {
    data class Item(val key: String, val url: String, val title: String, val durationMs: Long, val language: String)

    private val prefs = context.getSharedPreferences("pretranslate", Context.MODE_PRIVATE)

    /** Was zuletzt vorbereitet wurde (für die Einstellungen). */
    val lastResult: String? get() = prefs.getString("last", null)

    suspend fun run(budgetMs: Long): String {
        val settings = container.settings.current()
        if (!settings.pretranslate) return "aus"
        val started = SystemClock.elapsedRealtime()
        fun timeLeft() = budgetMs - (SystemClock.elapsedRealtime() - started)
        val done = mutableListOf<String>()
        for (item in items(settings.subtitleLanguage, settings.subtitlesAutoOn)) {
            if (timeLeft() < 30_000) break
            if (container.subtitles.cached(item.key, item.language) != null) continue
            val film = SubtitleRepository.FilmInfo(durationMs = item.durationMs)
            // 1. Fertige Untertitel in der Sprache (ein Download, höchstens das Tagesbudget)
            if (settings.onlineSubtitlesEnabled && takeDownload(settings.openSubtitlesUser.isNotBlank())) {
                val found = runCatching { container.subtitles.find(item.key, item.url, item.title, item.language, settings, film) }
                    .onFailure { Log.w(PretranslateWorker.TAG, "Suche ${item.title}", it) }.getOrNull()
                if (found != null) {
                    done += "${item.title}: Untertitel geladen"
                    continue
                }
            }
            // 2. Aus einer anderen Sprache auf dem Gerät übersetzen
            if (!settings.translationEnabled) continue
            if (container.subtitles.cachedAll(item.key).isEmpty() && !(settings.onlineSubtitlesEnabled && takeDownload(settings.openSubtitlesUser.isNotBlank()))) continue
            val source = runCatching { container.subtitles.findSource(item.key, item.url, item.title, item.language, settings, film) }
                .getOrNull() ?: continue
            if (source.language == item.language) continue
            val finished = translate(item, source, timeLeft = ::timeLeft) ?: break
            done += "${item.title}: übersetzt ($finished Zeilen)"
        }
        val summary = if (done.isEmpty()) "nichts zu tun" else done.joinToString(" · ")
        prefs.edit().putString("last", "${java.text.SimpleDateFormat("dd.MM. HH:mm", java.util.Locale.GERMANY).format(java.util.Date())}: $summary").apply()
        Log.i(PretranslateWorker.TAG, summary)
        return summary
    }

    /** Übersetzt die Datei Stück für Stück; null, wenn die Zeit vorher endet (der Stand bleibt gespeichert). */
    private suspend fun translate(item: Item, source: SubtitleRepository.Result, timeLeft: () -> Long): Int? {
        val cues = runCatching { Srt.parse(source.file.readText()) }.getOrDefault(emptyList())
        if (cues.isEmpty()) return 0
        val partial = container.subtitles.lineTranslations(item.key, PARTIAL_TRACK, item.language).toMutableMap()
        fun startOf(cue: SrtCue) = millis(cue.start) ?: 0L
        try {
            for (chunk in cues.filter { startOf(it) !in partial }.chunked(BATCH)) {
                if (timeLeft() < 20_000) return null
                val texts = chunk.map { Srt.plainText(it.text) }
                val result = Translators.remote.translate(texts, item.language, source.language)
                chunk.forEachIndexed { k, cue -> partial[startOf(cue)] = result.getOrElse(k) { texts[k] } }
            }
        } finally {
            container.subtitles.saveLineTranslations(item.key, PARTIAL_TRACK, item.language, partial)
        }
        val translated = cues.map { it.copy(text = partial[startOf(it)] ?: it.text) }
        container.subtitles.saveTranslation(item.key, item.language, translated)
        // Gleiche Zeiten wie die Vorlage: deren Ausrichtung (z. B. Bildrate) gilt auch hier.
        source.language?.let { container.subtitles.timing(item.key, it) }?.let { container.subtitles.saveTiming(item.key, item.language, it) }
        return cues.size
    }

    /** Angefangene Filme und Folgen, dazu die nächste Folge jeder angefangenen Serie (zuletzt geschaut zuerst). */
    private suspend fun items(defaultLanguage: String, autoOn: Boolean): List<Item> {
        val out = LinkedHashMap<String, Item>()
        fun language(key: String): String? {
            val memory = container.playerMemory.get(Episodes.seriesKeyOf(key) ?: key)
            if (memory.textOff) return null
            return memory.load ?: defaultLanguage.takeIf { autoOn }
        }
        for (entry in container.db.history().recent(40)) {
            if (entry.kind == HistoryKind.LIVE || out.size >= MAX_ITEMS) continue
            val unfinished = entry.durationMs <= 0 || entry.positionMs < entry.durationMs * 0.95
            if (unfinished && entry.positionMs > 60_000) {
                language(entry.key)?.let { out.putIfAbsent(entry.key, Item(entry.key, entry.url, entry.title, entry.durationMs, it)) }
            }
            if (entry.kind == HistoryKind.EPISODE) {
                val next = runCatching { container.content.nextEpisode(entry.key) }.getOrNull() ?: continue
                val key = Episodes.key(next.series.key, next.episode.id)
                language(key)?.let { out.putIfAbsent(key, Item(key, next.url, Episodes.title(next.series.name, next.episode), 0L, it)) }
            }
        }
        return out.values.take(MAX_ITEMS)
    }

    /** OpenSubtitles erlaubt ohne Konto 5 Downloads am Tag: höchstens 2 davon nachts, mit Konto 8 von 20. */
    private fun takeDownload(account: Boolean): Boolean {
        val day = System.currentTimeMillis() / 86_400_000L
        val used = if (prefs.getLong("day", -1) == day) prefs.getInt("downloads", 0) else 0
        if (used >= if (account) 8 else 2) return false
        prefs.edit().putLong("day", day).putInt("downloads", used + 1).apply()
        return true
    }

    private fun millis(value: String): Long? {
        val m = TIME.matchEntire(value.trim()) ?: return null
        val (h, min, sec, ms) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    companion object {
        /** Name der Zwischenablage für halb übersetzte Dateien (auch der Player liest sie). */
        const val PARTIAL_TRACK = "file"
        private const val BATCH = 8
        private const val MAX_ITEMS = 6
        private val TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    }
}
