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
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.data.LiveFormat
import de.ayutv.desktop.data.TimingMark
import de.ayutv.desktop.data.UserData
import de.ayutv.desktop.player.DisplayRate
import de.ayutv.desktop.player.PlayerConfig
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
class AppState(val store: Store, val sync: Sync, val scope: CoroutineScope) {
    val player = VideoPlayer(PlayerConfig.from(store.user.value)).also { it.restoreVolume(store.user.value.volume) }
    val backup = BackupImport(store)
    val subtitles = Subtitles(sync.http, java.io.File(store.dir, "subtitles"))
    val remote = PhoneRemote(store) { key -> store.channel(key)?.let { play(Playback.Live(it, store.allVisible())) } }
    var remoteAddress by mutableStateOf<String?>(null); private set

    /** Handy-Fernbedienung ein-/ausschalten (Windows fragt beim ersten Mal nach der Firewall-Freigabe). */
    fun setPhoneRemote(on: Boolean) {
        runCatching { if (on) remote.start() else remote.stop() }
            .onFailure { toast("Fernbedienung lässt sich nicht starten: ${it.message}") }
        store.updateUser { it.copy(phoneRemote = on && remote.running) }
        remoteAddress = remote.address()
    }

    var screen by mutableStateOf(if (store.playlists.value.isEmpty()) Screen.SETTINGS else Screen.LIVE)
    var playing by mutableStateOf<Playback?>(null); private set
    /** Player im Vollbild (dasselbe Fenster über den ganzen Bildschirm). */
    var fullscreen by mutableStateOf(false); private set
    /** Vollbild kam von selbst mit dem Start (Einstellung): dann führt Esc direkt zurück und schließt es wieder. */
    var autoFullscreen = false; private set
    /** Hauptfenster (für den Bildschirm, auf dem das Vollbild erscheint, und für Tasten vom Handy). */
    @Volatile var mainWindow: java.awt.Window? = null

    fun changeFullscreen(on: Boolean) {
        fullscreen = on
        autoFullscreen = false
    }
    /** Geöffnete Serie (Folgenansicht), auch aus der Suche heraus. */
    var openSeries by mutableStateOf<Series?>(null)
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null

    /** Zurück-Aktionen, die neueste zuerst (Dialog, Unterseite, Player). */
    private val backStack = mutableStateListOf<() -> Boolean>()

    init {
        if (store.user.value.phoneRemote) setPhoneRemote(true)
        Palette.use(store.user.value.theme)
    }

    /**
     * Einstellung ändern und sofort übernehmen: Ton-Versatz gleich; braucht VLC für Bild, Ton oder Untertitel-Aussehen
     * einen Neustart, läuft das Video an derselben Stelle weiter.
     */
    fun updateSettings(transform: (UserData) -> UserData) {
        store.updateUser(transform)
        val user = store.user.value
        Palette.use(user.theme)
        val next = PlayerConfig.from(user)
        val restart = next.vlcOptions() != player.config.vlcOptions() || next.equalizerBands() != player.config.equalizerBands()
        player.config = next
        player.applyRuntime()
        if (!user.matchFrameRate) scope.launch(Dispatchers.IO) { DisplayRate.restore() }
        val current = playing ?: return
        if (restart) {
            savePosition()
            play(if (current is Playback.Vod) current.copy(startMs = player.positionMs) else current)
        }
    }

    /** Proxy geändert (Einstellungen, Sicherung) oder neue Sender geladen: Wege neu festlegen. */
    fun applyProxy() = Network.apply(store)

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_000)
            message = null
        }
    }

    fun play(playback: Playback) {
        stopAligning()
        // Ton nur abgreifen, wenn Untertitel ausgerichtet werden sollen (sonst gibt VLC den Ton selbst aus).
        player.tapAudio = false
        val user = store.user.value
        if (playing == null && user.fullscreenPlayback && !fullscreen) {
            fullscreen = true
            autoFullscreen = true
        }
        playing = playback
        player.config = PlayerConfig.from(user)
        val agent = user.userAgent.ifBlank { Sync.USER_AGENT }
        when (playback) {
            is Playback.Live -> {
                store.rememberWatched(playback.channel.key)
                val url = streamUrl(playback.channel, user.liveFormat)
                player.play(url, agent, 0, *Network.vlcOptions(url).toTypedArray())
                matchFrameRate(playback.channel.key)
            }
            is Playback.Vod -> {
                player.play(playback.url, agent, playback.startMs, *Network.vlcOptions(playback.url).toTypedArray())
                matchFrameRate(playback.key)
                // Untertitel automatisch: eingebaute in der Sprache wählt VLC selbst; fehlen sie, aus dem Internet holen.
                if (user.subtitlesAuto) scope.launch {
                    delay(6_000)
                    if ((playing as? Playback.Vod)?.key == playback.key && !player.subtitleActive()) loadSubtitles(playback)
                }
            }
        }
    }

    /** Live-Format der Einstellung (Xtream: „…/live/…/123.ts“ ↔ „…/123.m3u8“). */
    private fun streamUrl(channel: Channel, format: LiveFormat): String {
        if (format != LiveFormat.HLS) return channel.url
        val xtream = store.playlists.value.firstOrNull { it.id == channel.playlistId }?.type == de.ayutv.desktop.data.PlaylistType.XTREAM
        return if (xtream) channel.url.replace(Regex("""(/live/[^?#]+)\.ts$"""), "$1.m3u8") else channel.url
    }

    private var rateJob: Job? = null

    /**
     * Bildwiederholrate des Bildschirms an das Video anpassen (Einstellung). Bekannte Rate (vom letzten Mal) gleich
     * beim Laden, sonst sobald VLC sie kennt; neue Werte werden je Sender/Film gemerkt.
     */
    private fun matchFrameRate(key: String) {
        rateJob?.cancel()
        val user = store.user.value
        if (!user.matchFrameRate || !DisplayRate.supported) return
        rateJob = scope.launch {
            user.frameRates[key]?.let { known -> withContext(Dispatchers.IO) { DisplayRate.match(known) }?.let { toast("Bildschirm auf $it Hz") } }
            repeat(30) {
                delay(500)
                val fps = player.frameRate()
                if (fps <= 0) return@repeat
                withContext(Dispatchers.IO) { DisplayRate.match(fps) }?.let { toast("Bildschirm auf $it Hz (Video: ${"%.3f".format(java.util.Locale.GERMAN, fps).trimEnd('0').trimEnd(',')} Bilder/s)") }
                if (store.user.value.frameRates[key] != fps) store.updateUser { u ->
                    val rates = u.frameRates - key + (key to fps)
                    u.copy(frameRates = if (rates.size > 3_000) rates.entries.drop(rates.size - 3_000).associate { it.key to it.value } else rates)
                }
                return@launch
            }
        }
    }

    fun stopPlayback() {
        stopAligning()
        rateJob?.cancel()
        if (autoFullscreen) changeFullscreen(false)
        savePosition()
        player.stop()
        playing = null
        if (store.user.value.volume != player.volume) store.updateUser { it.copy(volume = player.volume) }
        scope.launch(Dispatchers.IO) { DisplayRate.restore() }
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
            subtitles.find(vod.key, vod.title, user.subtitleLanguage.ifBlank { "de" }, user.openSubtitlesKey, if (user.translateSubtitles) user.deeplKey else "") { toast(it) }
        }.onSuccess { found ->
            if ((playing as? Playback.Vod)?.key != vod.key) return@onSuccess
            val text = withContext(Dispatchers.IO) { found.file.readText() }
            val cues = SubtitleFiles.cues(text)
            val known = user.subtitleTimings[vod.key]?.let { SubtitleTiming(it.ratio, it.offsetMs) }
            if (!user.subtitleAlign) {
                player.addSubtitleFile(found.file)
                toast("Untertitel eingeblendet (${found.source}).")
                return@onSuccess
            }
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

    internal fun pushBack(handler: () -> Boolean) = backStack.add(handler)
    internal fun popBack(handler: () -> Boolean) = backStack.remove(handler)

    fun syncNow() = scope.launch {
        runCatching { sync.syncAll() }.onFailure { toast("Laden fehlgeschlagen: ${it.message}") }
        applyProxy()
    }

    private companion object {
        /** So viel gehörter Ton (rückwärts ab jetzt) zählt für die Ausrichtung. */
        const val WINDOW_MS = 15 * 60_000L
    }

    fun close() {
        savePosition()
        if (store.user.value.volume != player.volume) store.updateUser { it.copy(volume = player.volume) }
        DisplayRate.restore()
        remote.stop()
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
