package de.ayutv.player

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import de.ayutv.AppContainer
import de.ayutv.data.IntroStore
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.IntroSkip
import de.ayutv.data.repo.ContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Serien im Player: „Rückblick und Intro überspringen“ (am Ton erkannt, sonst nach Zeit und gelernter Länge), „Nächste
 * Folge“ ab dem Abspann (gelernt je Serie) und der Countdown am Ende. Der Player ([Host]) liefert Wiedergabe, Ton und
 * Untertitel-Zeiten; hier liegt nur, was Folgen betrifft.
 */
class EpisodeExtras(private val host: Host) {
    interface Host {
        val player: ExoPlayer?
        val vod: VodRequest?
        val positionMs: Long
        val durationMs: Long
        /** War schon einmal bereit (kurzes Nachladen lässt den Knopf stehen). */
        val everReady: Boolean
        val scope: CoroutineScope
        val container: AppContainer
        val autoNextEpisode: Boolean
        /** Lautstärke je 100 ms zwischen [fromMs] und [toMs]: gehört, sonst (Ton an der Soundbar) selbst dekodiert. */
        fun levels(fromMs: Long, toMs: Long): FloatArray
        /** Filmzeit, zu der die letzte Zeile geladener Untertitel endet (Hinweis auf den Abspann), sonst null. */
        fun lastSubtitleEnd(): Long?
        /** Genau an [to] springen und die angezeigte Position mitziehen. */
        fun seekExact(to: Long)
        fun showStatus(text: String)
        fun closeOverlay()
        /** Fortschritt sichern, [next] starten und diesen Player schließen. */
        fun startEpisode(next: ContentRepository.NextEpisode)
    }

    /** Die nächste Folge (im Hintergrund nachgeschlagen), sonst null. */
    var nextEpisode by mutableStateOf<ContentRepository.NextEpisode?>(null); private set
    /** Hinweis „Nächste Folge“ (ab dem Abspann und nach dem Ende). */
    var nextCardVisible by mutableStateOf(false); private set
    /** Sekunden bis zum automatischen Start der nächsten Folge, -1 = kein Countdown. */
    var nextCountdown by mutableIntStateOf(-1); private set
    /** Knopf „Intro überspringen“. */
    var introVisible by mutableStateOf(false); private set
    /** Beschriftung des Knopfs: vor dem Intro (Rückblick läuft) oder im Intro. */
    var introLabel by mutableStateOf("⏭ Intro überspringen"); private set

    private var countdownJob: Job? = null
    private var introLearned: IntroSkip.Intro? = null
    /** Wie oft schon übersprungen (Rückblick, dann Intro: der Knopf kommt danach wieder). */
    private var introSkips = 0
    private var introHiddenUntil = 0L
    /** Am Ton erkannter Intro-Klang dieser Serie, und wohin das gerade erkannte Intro reicht (ms, -1 = noch nicht). */
    private var introFingerprint: IntroStore.Fingerprint? = null
    private var introAudioEnd = -1L
    private var introAudioStart = -1L
    /** Gelernte Länge des Abspanns dieser Serie (ms vor dem Ende), null = noch unbekannt. */
    private var creditsLearned: Long? = null
    private var introSearchJob: Job? = null
    private var openingLearned = false
    /** Letztes Überspringen: von wo, wohin (inkl. Korrekturen) und wann, zum Lernen der Intro-Länge. */
    private var introFrom = -1L
    private var introTo = 0L
    private var introAt = 0L

    /** Neue Folge: Gelerntes zur Serie laden und die nächste Folge nachschlagen. */
    fun prepare(request: VodRequest) {
        val seriesKey = Episodes.seriesKeyOf(request.key) ?: return
        val container = host.container
        introLearned = container.intros.get(seriesKey)
        creditsLearned = container.intros.credits(seriesKey)
        host.scope.launch { nextEpisode = container.content.nextEpisode(request.key) }
        host.scope.launch { introFingerprint = withContext(Dispatchers.IO) { container.intros.fingerprint(seriesKey) } }
    }

    /** Jede Sekunde: Knöpfe ein- oder ausblenden, Intro suchen, Korrekturen lernen. */
    fun update(request: VodRequest) {
        val p = host.player ?: return
        val position = host.positionMs
        val duration = host.durationMs
        searchIntroByAudio(position)
        // Auch während kurzem Nachladen (z. B. direkt nach dem Spulen) stehen lassen: Sonst verschwindet der
        // Knopf für einen Moment, und OK blendet stattdessen die Steuerleiste ein.
        val playing = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING && host.everReady
        introVisible = playing && when {
            // Am Ton erkannt: vom Rückblick bis kurz vor Ende des Intros.
            introAudioEnd > 0 -> {
                introLabel = if (introAudioStart > 0 && position < introAudioStart - 1_000) "⏭ Rückblick und Intro überspringen" else "⏭ Intro überspringen"
                position in INTRO_EARLIEST_MS until introAudioEnd - 2_000
            }
            // Am Ton erkanntes Intro schon übersprungen.
            introAudioEnd == 0L -> false
            // Sonst nach Zeit; nach einem Sprung kurz weg, dann wieder da (erst Rückblick, dann Intro).
            else -> (introSkips < MAX_INTRO_SKIPS && SystemClock.elapsedRealtime() > introHiddenUntil && IntroSkip.visible(position, introLearned))
                .also { if (it) introLabel = "⏭ Intro überspringen" }
        }
        // Korrekturen nach dem Überspringen gelten als Intro-Länge.
        if (introFrom >= 0 && SystemClock.elapsedRealtime() - introAt > IntroSkip.LEARN_WINDOW_MS) {
            IntroSkip.learn(introFrom, introTo)?.let { intro ->
                Episodes.seriesKeyOf(request.key)?.let { host.container.intros.put(it, intro) }
                introLearned = intro
            }
            introFrom = -1
        }
        // Nie in der ersten Hälfte (kurze Folge, Abspann falsch gelernt): OK soll dort nicht versehentlich weiterschalten.
        nextCardVisible = nextEpisode != null && (nextCountdown >= 0 || duration > 0 && position in maxOf(creditsStart(), duration / 2)..duration)
    }

    /** Kurz nach „Intro überspringen“ von Hand gespult: Das korrigiert, wo das Intro endet. */
    fun onManualSeek(target: Long) {
        if (introFrom >= 0 && SystemClock.elapsedRealtime() - introAt <= IntroSkip.LEARN_WINDOW_MS) {
            introTo = target
            introAt = SystemClock.elapsedRealtime()
        }
    }

    /** Läuft gerade das bekannte Intro? Am Ton der letzten Sekunden suchen (alle 2 s, in den ersten Minuten). */
    private fun searchIntroByAudio(position: Long) {
        val fingerprint = introFingerprint ?: return
        if (introAudioEnd >= 0 || position > OPENING_MS || introSearchJob?.isActive == true) return
        val from = (position - INTRO_SEARCH_BACK_MS).coerceAtLeast(0)
        introSearchJob = host.scope.launch(Dispatchers.Default) {
            // Gehörter Ton bis jetzt und (geht er an die Soundbar) der vorausgelesene: So steht das Intro fest, bevor es beginnt.
            val heard = host.levels(from, position + INTRO_SEARCH_AHEAD_MS)
            val at = IntroMatch.find(heard, fingerprint.pattern, 0, heard.size) ?: return@launch
            val start = from + at * 100L
            val end = start + fingerprint.lengthBins * 100L
            withContext(Dispatchers.Main) {
                if (end > position + 3_000) {
                    introAudioStart = start
                    introAudioEnd = end
                    Log.i(TAG, "Intro am Ton erkannt: ${start / 1000}–${end / 1000} s")
                }
            }
        }
    }

    /**
     * Folge verlassen: Anfang dieser Folge mit dem der zuletzt gesehenen vergleichen. Der gemeinsame Abschnitt
     * ist die Titelmusik; ihr Anfang wird gespeichert und in den nächsten Folgen am Ton wiedererkannt.
     */
    fun learnOpening(request: VodRequest) {
        if (openingLearned || request.kind != HistoryKind.EPISODE) return
        val seriesKey = Episodes.seriesKeyOf(request.key) ?: return
        val opening = host.levels(0, OPENING_MS)
        val heard = opening.count { !it.isNaN() }
        if (heard < opening.size / 3) return
        openingLearned = true
        val intros = host.container.intros
        host.container.scope.launch(Dispatchers.Default) {
            val previous = intros.opening(seriesKey)
            val segment = previous?.let { IntroMatch.commonSegment(opening, it) }
            if (segment != null && segment.length in INTRO_MIN_BINS..INTRO_MAX_BINS) {
                // Muster aus der Folge nehmen, in der der Anfang vollständig gehört wurde.
                val fromCurrent = opening.copyOfRange(segment.startA, segment.startA + INTRO_PATTERN_BINS)
                val pattern = if (fromCurrent.none { it.isNaN() }) fromCurrent
                    else previous!!.copyOfRange(segment.startB, segment.startB + INTRO_PATTERN_BINS)
                if (pattern.none { it.isNaN() }) intros.saveFingerprint(seriesKey, IntroStore.Fingerprint(pattern, segment.length))
                Log.i(TAG, "Intro erkannt: bei ${segment.startA / 10} s, ${segment.length / 10} s lang")
            }
            // Für den nächsten Vergleich den vollständigeren Anfang behalten.
            if (previous == null || heard >= previous.count { !it.isNaN() }) intros.saveOpening(seriesKey, opening)
        }
    }

    /**
     * Ab wann „Nächste Folge“ angeboten wird: wo der Abspann beginnt. Gelernt je Serie (wann man weitergeschaltet oder
     * aufgehört hat), sonst nach der letzten Zeile geladener Untertitel, sonst [DEFAULT_CREDITS_MS] vor dem Ende.
     */
    private fun creditsStart(): Long {
        val duration = host.durationMs
        creditsLearned?.let { return duration - it - CREDITS_MARGIN_MS }
        host.lastSubtitleEnd()?.let { end ->
            if (duration - end in CREDITS_MIN_MS..CREDITS_MAX_MS) return end + 2_000
        }
        return duration - DEFAULT_CREDITS_MS
    }

    /**
     * Abspann lernen: Wer in den letzten Minuten weiterschaltet oder aufhört, ist im Abspann. Gleitender Mittelwert je
     * Serie, damit ein Ausreißer (früher aufgehört) nicht alles verschiebt.
     */
    fun learnCredits(positionMs: Long) {
        val request = host.vod ?: return
        val duration = host.durationMs
        if (request.kind != HistoryKind.EPISODE || duration <= 0) return
        val remaining = duration - positionMs
        if (remaining !in CREDITS_MIN_MS..CREDITS_MAX_MS || positionMs < duration * 7 / 10) return
        val seriesKey = Episodes.seriesKeyOf(request.key) ?: return
        val learned = creditsLearned?.let { (it * 2 + remaining) / 3 } ?: remaining
        creditsLearned = learned
        host.container.intros.saveCredits(seriesKey, learned)
        Log.i(TAG, "Abspann gelernt: ${learned / 1000} s vor dem Ende")
    }

    /** OK bei sichtbarem Knopf: Intro (und Rückblick) überspringen. */
    fun skipIntro() {
        val p = host.player ?: return
        val from = p.currentPosition
        if (introAudioEnd > from) {
            // Am Ton erkannt: genau ans Ende, nichts zu lernen.
            host.seekExact(introAudioEnd)
            introAudioEnd = 0
            introVisible = false
            host.showStatus("Intro übersprungen")
            return
        }
        val to = IntroSkip.target(from, introLearned)
        introSkips++
        introHiddenUntil = SystemClock.elapsedRealtime() + 4_000
        introVisible = false
        introFrom = from
        introTo = to
        introAt = SystemClock.elapsedRealtime()
        host.seekExact(to)
        host.showStatus("Intro übersprungen · mit ◀ ▶ nachkorrigieren, DnA-TV merkt es sich für diese Serie")
    }

    /** Folge zu Ende: nächste Folge anbieten und (wenn eingestellt) nach einem Countdown starten. false = keine nächste. */
    fun onEnded(): Boolean {
        if (nextEpisode == null) return false
        nextCardVisible = true
        host.closeOverlay()
        if (!host.autoNextEpisode) return true
        countdownJob?.cancel()
        countdownJob = host.scope.launch {
            for (left in NEXT_COUNTDOWN_SECONDS downTo 1) {
                nextCountdown = left
                delay(1_000)
            }
            playNext()
        }
        return true
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        nextCountdown = -1
    }

    fun playNext() {
        val next = nextEpisode ?: return
        if (nextCountdown < 0) learnCredits(host.player?.currentPosition ?: host.positionMs)
        cancelCountdown()
        host.startEpisode(next)
    }

    companion object {
        private const val TAG = "DnAEpisodes"
        /** Anfang einer Folge, in dem das Intro gesucht und der Ton gelernt wird. */
        const val OPENING_MS = 8 * 60_000L
        /** Intro im vorausgelesenen Ton suchen (so weit voraus). */
        const val INTRO_SEARCH_AHEAD_MS = 40_000L
        private const val INTRO_SEARCH_BACK_MS = 40_000L
        private const val INTRO_PATTERN_BINS = 80
        private const val INTRO_MIN_BINS = 150
        private const val INTRO_MAX_BINS = 2_400
        private const val MAX_INTRO_SKIPS = 3
        /** Den Knopf frühestens ab hier zeigen. */
        private const val INTRO_EARLIEST_MS = 3_000L
        /** Abspann: ohne Wissen so lange vor dem Ende „Nächste Folge“ anbieten; gelernt zählt nur 10 s bis 10 min. */
        private const val DEFAULT_CREDITS_MS = 60_000L
        private const val CREDITS_MIN_MS = 10_000L
        private const val CREDITS_MAX_MS = 10 * 60_000L
        private const val CREDITS_MARGIN_MS = 3_000L
        private const val NEXT_COUNTDOWN_SECONDS = 10
    }
}
