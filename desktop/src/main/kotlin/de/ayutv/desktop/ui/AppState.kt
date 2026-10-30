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
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.player.Subtitles
import de.ayutv.desktop.player.VideoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Screen(val label: String, val icon: String) {
    LIVE("Live TV", "📺"),
    GUIDE("TV-Guide", "🗓"),
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
    val player = VideoPlayer()
    val backup = BackupImport(store)
    val subtitles = Subtitles(sync.http, java.io.File(store.dir, "subtitles"))

    var screen by mutableStateOf(if (store.playlists.value.isEmpty()) Screen.SETTINGS else Screen.LIVE)
    var playing by mutableStateOf<Playback?>(null); private set
    /** Geöffnete Serie (Folgenansicht), auch aus der Suche heraus. */
    var openSeries by mutableStateOf<Series?>(null)
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null

    /** Zurück-Aktionen, die neueste zuerst (Dialog, Unterseite, Player). */
    private val backStack = mutableStateListOf<() -> Boolean>()

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_000)
            message = null
        }
    }

    fun play(playback: Playback) {
        playing = playback
        when (playback) {
            is Playback.Live -> {
                store.rememberWatched(playback.channel.key)
                player.play(playback.channel.url, Sync.USER_AGENT)
            }
            is Playback.Vod -> player.play(playback.url, Sync.USER_AGENT, playback.startMs)
        }
    }

    fun stopPlayback() {
        savePosition()
        player.stop()
        playing = null
    }

    /** Wiedergabestand des laufenden Films merken (fast am Ende = gesehen, beim nächsten Mal von vorn). */
    fun savePosition() {
        val vod = playing as? Playback.Vod ?: return
        val position = player.positionMs
        val duration = player.durationMs
        if (position < 10_000) return
        store.savePosition(vod.key, if (duration > 0 && position > duration - 60_000) 0 else position, duration)
    }

    /** Untertitel in der eingestellten Sprache suchen (OpenSubtitles, sonst DeepL) und einblenden. */
    fun loadSubtitles(vod: Playback.Vod) = scope.launch {
        val user = store.user.value
        runCatching {
            subtitles.find(vod.key, vod.title, user.subtitleLanguage.ifBlank { "de" }, user.openSubtitlesKey, user.deeplKey) { toast(it) }
        }.onSuccess { found ->
            if (playing == vod || (playing as? Playback.Vod)?.key == vod.key) {
                player.addSubtitleFile(found.file)
                toast("Untertitel eingeblendet (${found.source}). G/H verschiebt sie.")
            }
        }.onFailure { toast(it.message ?: "Keine Untertitel") }
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
    }

    fun close() {
        savePosition()
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
