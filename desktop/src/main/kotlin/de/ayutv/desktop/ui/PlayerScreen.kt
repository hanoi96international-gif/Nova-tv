package de.ayutv.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.IntroSkip
import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.Choice
import de.ayutv.desktop.data.Deinterlace
import de.ayutv.desktop.data.IntroMark
import de.ayutv.desktop.data.PictureFit
import de.ayutv.desktop.data.SubtitleBackground
import de.ayutv.desktop.data.SubtitleColor
import de.ayutv.desktop.data.SubtitlePosition
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.languageName
import de.ayutv.desktop.player.DisplayRate
import kotlinx.coroutines.delay

/**
 * Vollbild-Wiedergabe. Tastatur/Fernbedienung: ↑/↓ Sender wechseln (Live) bzw. Lautstärke (Film), ←/→ spulen,
 * Enter/Leertaste Info bzw. Pause, Esc zurück, F Vollbild, M Ton aus, T Tonspur, U Untertitel, L Untertitel laden
 * (OpenSubtitles/DeepL), G/H Untertitel früher/später, N nächste Folge.
 * Maus: Bewegen zeigt die Leiste, Klick Pause/Info, Doppelklick Vollbild, Mausrad Lautstärke.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(app: AppState, playback: Playback) {
    val player = app.player
    val guide by app.store.guide.collectAsState()
    val focus = remember { FocusRequester() }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var osd by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val fullscreen = app.fullscreen
    var subtitleDelay by remember(playback) { mutableLongStateOf(0L) }

    fun shiftSubtitles(deltaMs: Long) {
        subtitleDelay += deltaMs
        player.subtitleDelay(subtitleDelay)
        app.toast("Untertitel ${if (subtitleDelay >= 0) "+" else ""}${subtitleDelay / 1000.0} s")
    }

    fun poke() {
        lastInput = System.currentTimeMillis()
        osd = true
    }

    fun toggleFullscreen() = app.changeFullscreen(!app.fullscreen)

    fun zap(delta: Int) {
        val live = playback as? Playback.Live ?: return
        val list = live.list
        if (list.size < 2) return
        val at = list.indexOfFirst { it.key == live.channel.key }.coerceAtLeast(0)
        app.play(live.copy(channel = list[(at + delta).mod(list.size)]))
        poke()
    }

    val nextEpisode = (playback as? Playback.Vod)?.let { vod ->
        val id = vod.episodeId ?: return@let null
        Episodes.next(vod.episodes.map { it.first }, id)?.let { next -> vod.episodes.first { it.first.id == next.id } }
    }

    fun playNext() {
        val vod = playback as? Playback.Vod ?: return
        val (episode, url) = nextEpisode ?: return
        val series = vod.series ?: return
        app.savePosition()
        app.play(vod.copy(key = Episodes.key(series.key, episode.id), title = Episodes.title(series.name, episode), url = url, startMs = 0, episodeId = episode.id))
    }

    // Intro überspringen (Serien): Knopf am Anfang, gelernte Stelle je Serie; Spulen kurz danach korrigiert sie.
    val series = (playback as? Playback.Vod)?.series
    val learned = series?.let { s -> app.store.user.value.intros[s.key]?.let { IntroSkip.Intro(it.startMs, it.lengthMs) } }
    var skippedFrom by remember(playback) { mutableLongStateOf(-1L) }
    var skippedAt by remember(playback) { mutableLongStateOf(0L) }
    val introVisible = series != null && skippedFrom < 0 && IntroSkip.visible(player.positionMs, learned)

    fun skipIntro() {
        val from = player.positionMs
        skippedFrom = from
        skippedAt = System.currentTimeMillis()
        player.seekTo(IntroSkip.target(from, learned))
    }

    fun seek(delta: Long) {
        player.seekBy(delta)
        val s = series ?: return
        if (skippedFrom >= 0 && System.currentTimeMillis() - skippedAt < IntroSkip.LEARN_WINDOW_MS) {
            skippedAt = System.currentTimeMillis()
            val target = (player.positionMs + delta).coerceAtLeast(0)
            IntroSkip.learn(skippedFrom, target)?.let { intro ->
                app.store.updateUser { it.copy(intros = it.intros + (s.key to IntroMark(intro.startMs, intro.lengthMs))) }
            }
        }
    }

    fun trackMenu(audio: Boolean) {
        val tracks = if (audio) player.audioTracks else player.subtitleTracks
        menu = {
            MenuDialog(
                app, if (audio) "Tonspur" else "Untertitel",
                tracks.map { (id, name) -> MenuItem(name) { if (audio) player.selectAudio(id) else player.selectSubtitle(id) } },
                onDismiss = { menu = null; runCatching { focus.requestFocus() } },
            )
        }
    }

    val user by app.store.user.collectAsState()
    val zapKeys = user.zapWithUpDown

    // Intro automatisch überspringen (Einstellung), sobald die Stelle für die Serie bekannt ist.
    LaunchedEffect(introVisible) {
        if (introVisible && learned != null && user.autoSkipIntro) {
            skipIntro()
            app.toast("Intro übersprungen")
        }
    }

    fun close() {
        menu = null
        runCatching { focus.requestFocus() }
    }

    /** Auswahl aus einer Einstellung (✓ = aktuell), danach zurück ins Bild. */
    fun <T : Choice> pick(title: String, current: T, options: List<T>, apply: (T) -> Unit) {
        menu = { MenuDialog(app, title, options.map { o -> MenuItem((if (o == current) "✓  " else "     ") + o.label) { apply(o) } }, onDismiss = ::close) }
    }

    /** Player-Menü (☰, Menütaste, Tab): Spuren, Untertitel, Ton und Bild wie auf dem Fire TV. */
    fun playerMenu() {
        val u = app.store.user.value
        val vod = playback as? Playback.Vod
        val items = buildList {
            if (player.audioTracks.isNotEmpty()) add(MenuItem("🔊  Tonspur …") { trackMenu(audio = true) })
            add(MenuItem("💬  Untertitel-Spur …") { trackMenu(audio = false) })
            if (vod != null) add(MenuItem("💬  Untertitel aus dem Internet (${languageName(u.subtitleLanguage.ifBlank { "de" })})") { app.loadSubtitles(vod) })
            add(MenuItem("💬  Untertitel früher (−0,25 s)") { shiftSubtitles(-250) })
            add(MenuItem("💬  Untertitel später (+0,25 s)") { shiftSubtitles(250) })
            add(MenuItem("Aa  Untertitel-Größe: ${u.subtitleSize.label}") { pick("Untertitel-Größe", u.subtitleSize, SubtitleSize.entries) { v -> app.updateSettings { it.copy(subtitleSize = v) } } })
            add(MenuItem("Aa  Untertitel-Farbe: ${u.subtitleColor.label}") { pick("Untertitel-Farbe", u.subtitleColor, SubtitleColor.entries) { v -> app.updateSettings { it.copy(subtitleColor = v) } } })
            add(MenuItem("Aa  Untertitel-Hintergrund: ${u.subtitleBackground.label}") { pick("Untertitel-Hintergrund", u.subtitleBackground, SubtitleBackground.entries) { v -> app.updateSettings { it.copy(subtitleBackground = v) } } })
            add(MenuItem("Aa  Untertitel-Position: ${u.subtitlePosition.label}") { pick("Untertitel-Position", u.subtitlePosition, SubtitlePosition.entries) { v -> app.updateSettings { it.copy(subtitlePosition = v) } } })
            add(MenuItem("🎚  Klang: ${u.audioMode.label}") { pick("Klang", u.audioMode, AudioMode.entries) { v -> app.updateSettings { it.copy(audioMode = v) } } })
            add(MenuItem("⏱  Ton-Versatz: ${if (u.audioDelayMs == 0L) "keiner" else "%+.2f s".format(java.util.Locale.GERMAN, u.audioDelayMs / 1000.0)}") {
                menu = { DelayDialog(app, onDismiss = ::close) }
            })
            add(MenuItem("🖼  Bildformat: ${u.pictureFit.label}") { pick("Bildformat", u.pictureFit, PictureFit.entries) { v -> app.updateSettings { it.copy(pictureFit = v) } } })
            add(MenuItem("🖼  Halbbilder: ${u.deinterlace.label}") { pick("Halbbilder (Deinterlacing)", u.deinterlace, Deinterlace.entries) { v -> app.updateSettings { it.copy(deinterlace = v) } } })
            if (DisplayRate.supported) add(MenuItem("🖥  Bildwiederholrate anpassen: ${if (u.matchFrameRate) "an" else "aus"}") { app.updateSettings { it.copy(matchFrameRate = !it.matchFrameRate) } })
            add(MenuItem(if (fullscreen) "⤢  Vollbild verlassen" else "⤢  Vollbild") { toggleFullscreen() })
        }
        menu = { MenuDialog(app, "Menü", items, onDismiss = ::close) }
    }

    // Vollbild von selbst (Einstellung): Esc führt direkt zurück zur Liste; sonst verlässt Esc zuerst das Vollbild.
    BackHandler(app, enabled = fullscreen && menu == null && !app.autoFullscreen) { app.changeFullscreen(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Leiste nach 4 Sekunden ohne Eingabe ausblenden (nicht bei Pause oder Fehler).
    LaunchedEffect(lastInput, player.playing) {
        delay(4_000)
        if (player.playing && player.error == null) osd = false
    }
    // Stand von Filmen regelmäßig merken; am Ende einer Folge die nächste.
    LaunchedEffect(playback) {
        player.onFinished = { if (nextEpisode != null && app.store.user.value.autoNextEpisode) playNext() else app.stopPlayback() }
        while (playback is Playback.Vod) {
            delay(15_000)
            app.savePosition()
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val live = playback is Playback.Live
                val step = if (e.isShiftPressed) 60_000L else 10_000L
                poke()
                when (e.key) {
                    Key.DirectionUp -> { if (live && zapKeys) zap(-1) else player.changeVolume(5); true }
                    Key.DirectionDown -> { if (live && zapKeys) zap(1) else player.changeVolume(-5); true }
                    Key.PageUp, Key.ChannelUp -> { if (live) zap(-1) else player.changeVolume(5); true }
                    Key.PageDown, Key.ChannelDown -> { if (live) zap(1) else player.changeVolume(-5); true }
                    Key.Menu, Key.Tab -> { playerMenu(); true }
                    Key.DirectionLeft, Key.MediaRewind -> { if (!live) seek(-step); true }
                    Key.DirectionRight, Key.MediaFastForward -> { if (!live) seek(step); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        when {
                            introVisible -> skipIntro()
                            !live && osd -> player.togglePause()
                        }
                        true
                    }
                    Key.I -> { if (introVisible) skipIntro(); true }
                    Key.Spacebar, Key.MediaPlayPause -> { player.togglePause(); true }
                    Key.MediaNext -> { if (live) zap(1) else playNext(); true }
                    Key.MediaPrevious -> { if (live) zap(-1); true }
                    Key.F -> { toggleFullscreen(); true }
                    Key.M, Key.VolumeMute -> { player.toggleMute(); true }
                    Key.Plus, Key.NumPadAdd, Key.VolumeUp -> { player.changeVolume(5); true }
                    Key.Minus, Key.NumPadSubtract, Key.VolumeDown -> { player.changeVolume(-5); true }
                    Key.T -> { trackMenu(audio = true); true }
                    Key.U -> { trackMenu(audio = false); true }
                    Key.N -> { playNext(); true }
                    Key.L -> { (playback as? Playback.Vod)?.let { app.loadSubtitles(it) }; true }
                    Key.G -> { shiftSubtitles(-250); true }
                    Key.H -> { shiftSubtitles(250); true }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { poke() }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) player.changeVolume(if (dy < 0) 5 else -5)
                poke()
            }
            .pointerInput(playback) {
                detectTapGestures(
                    onDoubleTap = { toggleFullscreen() },
                    onTap = { if (playback is Playback.Vod && osd) player.togglePause(); poke(); focus.requestFocus() },
                )
            },
    ) {
        val frame = player.frame
        if (frame != null) {
            val scale = when (user.pictureFit) {
                PictureFit.FIT -> ContentScale.Fit
                PictureFit.ZOOM -> ContentScale.Crop
                PictureFit.STRETCH -> ContentScale.FillBounds
            }
            Image(frame, null, Modifier.fillMaxSize(), contentScale = scale, filterQuality = FilterQuality.Low)
        }

        val problem = player.error ?: if (!player.available) "VLC wurde nicht gefunden. Bitte DnA-TV neu installieren." else null
        if (problem != null) {
            Box(Modifier.align(Alignment.Center).background(Palette.surface, RoundedCornerShape(10.dp)).padding(18.dp)) {
                Label(problem, Type.heading, maxLines = 3)
            }
        } else if (player.buffering && frame == null) {
            Box(Modifier.align(Alignment.Center)) { Label("Lädt …", Type.heading) }
        }

        if (osd || problem != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(start = 32.dp, end = 32.dp, top = 48.dp, bottom = 24.dp),
            ) {
                when (playback) {
                    is Playback.Live -> LiveBar(playback, guide[playback.channel.key].orEmpty(), player.volume)
                    is Playback.Vod -> VodBar(playback, player.positionMs, player.durationMs, player.playing, player.volume, nextEpisode != null, app.subtitleSync, ::playNext) { player.seekTo(it) }
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                if (player.audioTracks.size > 1) { TextButton("🔊 Ton", { trackMenu(audio = true) }); Spacer(Modifier.width(8.dp)) }
                if (player.subtitleTracks.size > 1) { TextButton("💬 Untertitel", { trackMenu(audio = false) }); Spacer(Modifier.width(8.dp)) }
                if (playback is Playback.Vod) {
                    val language = app.store.user.value.subtitleLanguage.ifBlank { "de" }.uppercase()
                    TextButton("💬 $language laden", { app.loadSubtitles(playback) })
                    Spacer(Modifier.width(8.dp))
                }
                TextButton("☰ Menü", { playerMenu() })
                Spacer(Modifier.width(8.dp))
                TextButton(if (fullscreen) "⤢ Fenster" else "⤢ Vollbild", { toggleFullscreen() })
                Spacer(Modifier.width(8.dp))
                TextButton("✕", { app.stopPlayback() })
            }
        }
        if (introVisible) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 32.dp, bottom = if (osd) 150.dp else 40.dp)) {
                TextButton("⏭ Intro überspringen (Enter)", { skipIntro() }, primary = true)
            }
        }
        menu?.invoke()
    }
}

@Composable
private fun LiveBar(live: Playback.Live, programmes: List<de.ayutv.desktop.data.Programme>, volume: Int) {
    val now = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val next = programmes.firstOrNull { it.start >= (current?.stop ?: now) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Logo(live.channel.logo, 56)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            val number = live.list.indexOfFirst { it.key == live.channel.key } + 1
            Label("${if (number > 0) "$number  " else ""}${live.channel.name}", Type.title)
            if (current != null) {
                Label("${clock(current.start)} – ${clock(current.stop)}  ${current.title}", Type.heading)
                Spacer(Modifier.height(6.dp))
                Progress((now - current.start).toFloat() / (current.stop - current.start))
            }
            next?.let { Label("Danach ${clock(it.start)}  ${it.title}", Type.small) }
        }
        Spacer(Modifier.width(16.dp))
        Label("🔊 $volume %   ↑/↓ Sender wechseln", Type.small)
    }
}

@Composable
private fun VodBar(
    vod: Playback.Vod,
    position: Long,
    duration: Long,
    playing: Boolean,
    volume: Int,
    hasNext: Boolean,
    subtitleSync: String?,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(vod.title, Type.title, Modifier.weight(1f))
            // Gegen Ende (Abspann) die nächste Folge anbieten.
            if (hasNext && duration > 0 && duration - position < 3 * 60_000) TextButton("⏭ Nächste Folge", onNext, primary = true)
        }
        Spacer(Modifier.height(8.dp))
        SeekBar(position, duration, onSeek)
        Spacer(Modifier.height(4.dp))
        Row {
            Label("${if (playing) "▶" else "⏸"}  ${duration(position)} / ${duration(duration)}", Type.body, Modifier.weight(1f))
            subtitleSync?.let { Label("💬 $it   ", Type.small) }
            Label("🔊 $volume %   ←/→ 10 s (Umschalt: 1 min)", Type.small)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(14.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / width * duration).toLong()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Progress(if (duration > 0) position.toFloat() / duration else 0f)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
