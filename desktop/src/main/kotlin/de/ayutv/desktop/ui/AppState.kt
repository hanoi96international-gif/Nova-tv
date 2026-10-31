package de.ayutv.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.desktop.data.BackupImport
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.GroupRef
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.data.TimingMark
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.player.BufferProfile
import de.ayutv.desktop.player.SubtitleFiles
import de.ayutv.desktop.player.Subtitles
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.remote.PhoneRemote
import de.ayutv.player.SpeechSync
import de.ayutv.player.SubtitleTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val label: String, val icon: String) {
    HOME("Start", "⌂"),
    LIVE("Live TV", "📺"),
    GUIDE("TV-Guide", "🗓"),
    SPORT("Sport", "⚽"),
    MOVIES("Filme", "🎬"),
    SERIES("Serien", "🎞"),
    SEARCH("Suche", "🔍"),
    SETTINGS("Einstellungen", "⚙"),
}

/** Was gerade läuft. */
sealed interface Playback {
    val title: String

    /** Live-Sender; [list] ist die Liste, in der gezappt wird. */
    data class Live(val channel: Channel, val list: List<Channel>) : Playback {
        override val title get() = channel.name
    }

    /** Film oder Folge. [key] merkt den Wiedergabestand. */
    data class Vod(
        val key: String,
        override val title: String,
        val url: String,
        val startMs: Long = 0,
        val series: Series? = null,
        val episodes: List<Pair<XtreamEpisode, String>> = emptyList(),
        val episodeId: String? = null,
    ) : Playback
}

/** Zustand der ganzen App (eine Instanz je Fenster). */
class AppState(val store: Store, val sync: Sync, val scope: CoroutineScope, val input: RemoteInput = RemoteInput()) {
    val player = VideoPlayer()
    val backup = BackupImport(store)
    val subtitles = Subtitles(sync.http, java.io.File(store.dir, "subtitles"))
    val remote = PhoneRemote(store, input) { key -> store.channel(key)?.let { play(Playback.Live(it, store.allVisible())) } }
    var remoteAddress by mutableStateOf<String?>(null); private set

    /** Handy-Fernbedienung ein-/ausschalten (Windows fragt beim ersten Mal nach der Firewall-Freigabe). */
    fun setPhoneRemote(on: Boolean) {
        runCatching { if (on) remote.start() else remote.stop() }
            .onFailure { toast("Fernbedienung lässt sich nicht starten: ${it.message}") }
        store.updateUser { it.copy(phoneRemote = on && remote.running) }
        remoteAddress = remote.address()
    }

    var screen by mutableStateOf(if (store.playlists.value.isEmpty()) Screen.SETTINGS else Screen.HOME)
    var playing by mutableStateOf<Playback?>(null); private set
    /** Geöffnete Serie (Folgenansicht), auch aus der Suche heraus. */
    var openSeries by mutableStateOf<Series?>(null)
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null
    /** Live TV: zuletzt gewählte Gruppe und Sender (nach dem Schauen geht es dort weiter). */
    var liveGroup: GroupRef? = null
    var liveFocus: String? = null
    /** Länderauswahl anbieten (nach dem ersten Laden). */
    var askCountries by mutableStateOf(false)

    /** Zurück-Aktionen, die neueste zuerst (Dialog, Unterseite, Player). */
    private val backStack = mutableStateListOf<() -> Boolean>()

    init {
        if (store.user.value.phoneRemote) setPhoneRemote(true)
        applyBuffer()
        // Wie auf dem Fire TV: Playlists alle 12 Stunden, TV-Guide alle 8 Stunden nachladen, auch wenn DnA-TV läuft.
        scope.launch {
            delay(1_500)
            while (true) {
                val first = store.playlists.value.any { it.enabled && it.lastSync == 0L }
                val problems = runCatching { sync.refreshDue() }.getOrElse { listOf(it.message ?: "Fehler") }
                applyProxy()
                if (problems.isNotEmpty()) toast("Aktualisieren: ${problems.first()}")
                if (first && !store.user.value.countriesAsked && store.countries().size > 1) askCountries = true
                delay(REFRESH_CHECK_MS)
            }
        }
    }

    /** Proxy geändert (Einstellungen, Sicherung) oder neue Sender geladen: Wege neu festlegen. */
    fun applyProxy() = Network.apply(store)

    /** Pufferprofil aus den Einstellungen an den Player geben. */
    fun applyBuffer() {
        player.networkCacheMs = BufferProfile.of(store.user.value.bufferProfile).cacheMs
    }

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_000)
            message = null
        }
    }

    // ---------------------------------------------------------------- Wiedergabe

    /** Vorher geschauter Sender (◀ bzw. „Letzter Sender“ springt zurück). */
    var previousChannel by mutableStateOf<Channel?>(null); private set
    /** Was der Player gerade versucht (Neu verbinden, Ausweichen); null = nichts. */
    var playerStatus by mutableStateOf<String?>(null); private set
    /** Fassungen, die für den laufenden Sender schon versucht wurden. */
    private var triedVariants = mutableSetOf<String>()
    private var watchdog: Job? = null

    fun play(playback: Playback) {
        stopAligning()
        val before = (playing as? Playback.Live)?.channel
        // Ton nur abgreifen, wenn Untertitel ausgerichtet werden sollen (sonst gibt VLC den Ton selbst aus).
        player.tapAudio = false
        playerStatus = null
        if (playback is Playback.Live) {
            if (before != null && before.key != playback.channel.key) previousChannel = before
            triedVariants = mutableSetOf(playback.channel.key)
        }
        playing = playback
        start(playback)
        watch(playback)
    }

    private fun start(playback: Playback, atMs: Long? = null) = when (playback) {
        is Playback.Live -> {
            store.rememberWatched(playback.channel.key)
            player.play(playback.channel.url, Sync.USER_AGENT, 0, *Network.vlcOptions(playback.channel.url).toTypedArray())
        }
        is Playback.Vod -> player.play(playback.url, Sync.USER_AGENT, atMs ?: playback.startMs, *Network.vlcOptions(playback.url).toTypedArray())
    }

    /**
     * Watchdog wie auf dem Fire TV: Fehler oder stehendes Bild (Dauer-Puffern) → neu verbinden, bis zu drei Mal mit
     * wachsender Pause; danach bei Live-TV auf die nächste Fassung desselben Senders ausweichen („RTL HD“ → „RTL 4K“).
     */
    private fun watch(playback: Playback) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var attempts = 0
            var healthySince = System.currentTimeMillis()
            while (playing === playback) {
                delay(1_000)
                if (playing !== playback) break
                val now = System.currentTimeMillis()
                // Radiosender haben kein Bild: die zählen nicht als eingefroren, solange der Ton läuft.
                val stalled = !player.paused && now - player.lastFrameAt > STALL_MS && !(player.playing && !player.hasVideo)
                val failed = player.error != null
                if (!failed && !stalled) {
                    if (player.playing && now - healthySince > HEALTHY_MS) attempts = 0
                    if (player.playing && playerStatus != null && now - player.lastFrameAt < 1_000) playerStatus = null
                    continue
                }
                healthySince = now
                if (attempts < MAX_RETRIES) {
                    attempts++
                    playerStatus = (if (failed) "Stream unterbrochen" else "Bild steht") + " – verbinde neu ($attempts/$MAX_RETRIES) …"
                    delay(500L shl (attempts - 1))
                    if (playing !== playback) break
                    start(playback, atMs = (playback as? Playback.Vod)?.let { player.positionMs })
                    continue
                }
                val live = playback as? Playback.Live
                val next = live?.let { l -> store.alternatives(l.channel).firstOrNull { it.key !in triedVariants } }
                if (live != null && next != null) {
                    triedVariants.add(next.key)
                    playerStatus = "Weiche aus auf „${next.name}“ …"
                    // In der Liste bleibt der ursprüngliche Sender stehen (Zappen geht von dort weiter).
                    val switched = Playback.Live(next, live.list.map { if (it.key == live.channel.key) next else it })
                    playing = switched
                    start(switched)
                    watch(switched)
                    return@launch
                }
                playerStatus = null
                if (player.error == null) player.stopWithError("Der Stream liefert kein Bild. Später noch einmal versuchen oder einen anderen Sender wählen.")
                break
            }
        }
    }

    /** Ziel beim schnellen Umschalten (wird erst nach kurzer Pause gestartet). */
    var zapTarget by mutableStateOf<Channel?>(null); private set
    private var zapJob: Job? = null
    private var lastZapAt = 0L

    /** Umschalten in der laufenden Liste; schnell hintereinander gedrückt wird nur der letzte Sender gestartet. */
    fun zap(step: Int) {
        val live = playing as? Playback.Live ?: return
        val list = live.list
        if (list.size < 2) return
        val from = zapTarget ?: live.channel
        val index = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        val target = list[(index + step).mod(list.size)]
        val now = System.currentTimeMillis()
        val burst = now - lastZapAt < ZAP_BURST_MS
        lastZapAt = now
        if (!burst && zapJob == null) {
            play(live.copy(channel = target))
            return
        }
        zapTarget = target
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            zapJob = null
            zapTarget = null
            val current = playing as? Playback.Live ?: return@launch
            play(current.copy(channel = target))
        }
    }

    /** Zum vorher geschauten Sender (und wieder zurück). */
    fun backZap(): Boolean {
        val live = playing as? Playback.Live ?: return false
        val previous = previousChannel?.takeIf { it.key != live.channel.key } ?: return false
        val list = if (live.list.any { it.key == previous.key }) live.list else store.allVisible()
        play(Playback.Live(previous, list))
        toast("◀ Zurück: ${previous.name}")
        return true
    }

    /** Eingetippte Sendernummer (Zifferntasten im Bild). */
    var typedNumber by mutableStateOf("")
        private set
    private var numberJob: Job? = null

    fun typeDigit(digit: Int) {
        val live = playing as? Playback.Live ?: return
        typedNumber = (typedNumber + digit).takeLast(4)
        val typed = typedNumber
        val numbers = live.list.mapIndexed { i, c -> (c.number.takeIf { it > 0 } ?: (i + 1)) to c }
        val canGrow = numbers.any { (n, _) -> n.toString().let { it.length > typed.length && it.startsWith(typed) } }
        numberJob?.cancel()
        numberJob = scope.launch {
            if (canGrow) delay(1_500)
            val wanted = typed.toIntOrNull()
            typedNumber = ""
            val target = numbers.firstOrNull { it.first == wanted }?.second
            if (target == null) toast("Kein Sender mit der Nummer $typed") else if ((playing as? Playback.Live)?.channel?.key != target.key) {
                play(live.copy(channel = target))
            }
        }
    }

    fun stopPlayback() {
        stopAligning()
        savePosition()
        watchdog?.cancel()
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        playerStatus = null
        player.stop()
        playing = null
    }

    /** Taste Home: wie auf dem Fire TV zur Startseite (Wiedergabe und offene Dialoge schließen). */
    fun goHome() {
        if (playing != null) stopPlayback()
        openSeries = null
        // Offene Dialoge und Unterseiten schließen (begrenzt: ein Handler bleibt bis zum nächsten Zeichnen aktiv).
        repeat(minOf(backStack.size, 8)) {
            for (i in backStack.indices.reversed()) if (backStack[i]()) break
        }
        screen = Screen.HOME
    }

    /** Wiedergabestand des laufenden Films merken (fast am Ende = gesehen, beim nächsten Mal von vorn). */
    fun savePosition() {
        val vod = playing as? Playback.Vod ?: return
        val position = player.positionMs
        val duration = player.durationMs
        if (position < 10_000) return
        store.savePosition(vod.key, if (duration > 0 && position > duration - 60_000) 0 else position, duration)
    }

    /** Stand der Ausrichtung am Ton (für die Leiste im Player; null = keine Untertitel geladen). */
    var subtitleSync by mutableStateOf<String?>(null); private set
    private var alignJob: Job? = null

    private fun stopAligning() {
        alignJob?.cancel()
        alignJob = null
        subtitleSync = null
    }

    /**
     * Untertitel in der eingestellten Sprache suchen (OpenSubtitles, sonst DeepL), einblenden und am Ton ausrichten:
     * Wie auf dem Fire TV werden die Sprechpausen des Films mit den Einblendungen verglichen; nach rund 1,5 Minuten
     * Gespräch sitzt der Versatz (auch bei anderer Bildrate). Die Ausrichtung wird je Film gemerkt.
     */
    fun loadSubtitles(vod: Playback.Vod) = scope.launch {
        val user = store.user.value
        runCatching {
            subtitles.find(vod.key, vod.title, user.subtitleLanguage.ifBlank { "de" }, user.openSubtitlesKey, user.deeplKey) { toast(it) }
        }.onSuccess { found ->
            if ((playing as? Playback.Vod)?.key != vod.key) return@onSuccess
            val text = withContext(Dispatchers.IO) { found.file.readText() }
            val cues = SubtitleFiles.cues(text)
            val known = user.subtitleTimings[vod.key]?.let { SubtitleTiming(it.ratio, it.offsetMs) }
            if (known != null) {
                showSubtitles(vod, found.file, text, known)
                toast("Untertitel eingeblendet (${found.source}), wie beim letzten Mal ausgerichtet.")
                subtitleSync = "Ausgerichtet (gemerkt)"
                return@onSuccess
            }
            player.addSubtitleFile(found.file)
            toast("Untertitel eingeblendet (${found.source}). Sie richten sich gleich am Ton aus.")
            align(vod, found.file, text, cues)
        }.onFailure { toast(it.message ?: "Keine Untertitel") }
    }

    private suspend fun showSubtitles(vod: Playback.Vod, file: java.io.File, text: String, timing: SubtitleTiming) {
        val aligned = java.io.File(file.parentFile, file.nameWithoutExtension + ".sync.srt")
        withContext(Dispatchers.IO) { aligned.writeText(SubtitleFiles.shifted(text, timing)) }
        if ((playing as? Playback.Vod)?.key == vod.key) player.addSubtitleFile(aligned)
    }

    private fun align(vod: Playback.Vod, file: java.io.File, text: String, cues: List<Pair<Long, Long>>) {
        alignJob?.cancel()
        alignJob = scope.launch {
            if (cues.size < 20) {
                subtitleSync = "Zu wenige Einblendungen zum Ausrichten"
                return@launch
            }
            // Für die Auswertung den Ton selbst ausgeben: kurz an derselben Stelle neu starten.
            if (!player.tapAudio) {
                player.tapAudio = true
                val position = player.positionMs
                player.play(vod.url, Sync.USER_AGENT, position, *Network.vlcOptions(vod.url).toTypedArray())
                delay(1_500)
                player.addSubtitleFile(file)
            }
            var applied: SubtitleTiming? = null
            var lastHeard = -1
            while (true) {
                delay(10_000)
                if (!player.playing) continue
                val heard = player.speech.seconds()
                if (heard < SpeechSync.MIN_SECONDS) {
                    if (applied == null) subtitleSync = "Hört zu: $heard von ${SpeechSync.MIN_SECONDS} Sekunden Ton"
                    continue
                }
                if (heard == lastHeard) continue
                lastHeard = heard
                val position = player.positionMs
                val fit = withContext(Dispatchers.Default) {
                    SpeechSync.estimate(player.speech.signal(position - WINDOW_MS, position + 5_000), cues)
                }
                if (fit == null) {
                    if (applied == null) subtitleSync = "${heard / 60} min Ton geprüft, noch kein eindeutiger Takt – prüft weiter"
                    continue
                }
                val timing = fit.timing
                // Nur neu einblenden, wenn sich an der aktuellen Stelle wirklich etwas ändert.
                val now = applied
                if (now != null && kotlin.math.abs(timing.toVideo(position) - now.toVideo(position)) < 250) continue
                showSubtitles(vod, file, text, timing)
                applied = timing
                store.updateUser { it.copy(subtitleTimings = it.subtitleTimings + (vod.key to TimingMark(timing.ratio, timing.offsetMs))) }
                val shift = "%+.1f s".format(java.util.Locale.GERMAN, timing.offsetMs / 1000.0) + if (timing.ratio != 1.0) ", Bildrate angepasst" else ""
                subtitleSync = "Am Ton ausgerichtet ($shift)"
                toast("Untertitel am Ton ausgerichtet ($shift)")
            }
        }
    }

    fun back(): Boolean {
        for (i in backStack.indices.reversed()) if (backStack[i]()) return true
        if (playing != null) {
            stopPlayback(); return true
        }
        return false
    }

    /** Zurück auf oberster Ebene: zur Startseite (wie die Zurück-Taste auf dem Fire TV-Startbildschirm). */
    fun backToHome(): Boolean {
        if (back()) return true
        if (screen != Screen.HOME && store.playlists.value.isNotEmpty()) {
            screen = Screen.HOME
            return true
        }
        return false
    }

    internal fun pushBack(handler: () -> Boolean) = backStack.add(handler)
    internal fun popBack(handler: () -> Boolean) = backStack.remove(handler)

    fun syncNow() = scope.launch {
        runCatching { sync.syncAll() }.onFailure { toast("Laden fehlgeschlagen: ${it.message}") }
        applyProxy()
    }

    private companion object {
        /** So viel gehörter Ton (rückwärts ab jetzt) zählt für die Ausrichtung. */
        const val WINDOW_MS = 15 * 60_000L
        const val REFRESH_CHECK_MS = 15 * 60_000L
        /** So lange ohne neues Bild gilt ein Stream als eingefroren. */
        const val STALL_MS = 12_000L
        const val HEALTHY_MS = 30_000L
        const val MAX_RETRIES = 3
        const val ZAP_BURST_MS = 700L
        const val ZAP_SETTLE_MS = 450L
    }

    fun close() {
        savePosition()
        watchdog?.cancel()
        remote.stop()
        input.stop()
        player.release()
    }
}

/** Esc/Zurück abfangen, solange [enabled]. */
@Composable
fun BackHandler(app: AppState, enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val active by rememberUpdatedState(enabled)
    DisposableEffect(app) {
        val handler: () -> Boolean = { if (active) { current(); true } else false }
        app.pushBack(handler)
        onDispose { app.popBack(handler) }
    }
}
