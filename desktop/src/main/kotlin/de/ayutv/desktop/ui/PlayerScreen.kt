package de.ayutv.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
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
import androidx.compose.ui.window.WindowState
import de.ayutv.data.parse.Episodes
import de.ayutv.data.parse.IntroSkip
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.IntroMark
import de.ayutv.desktop.data.Programme
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.digit
import de.ayutv.desktop.input.remote
import de.ayutv.desktop.player.StreamInfo
import kotlinx.coroutines.delay

/** Was über dem Bild liegt. */
private enum class Layer { NONE, INFO, CHANNELS }

/**
 * Wiedergabe, bedient wie die Fire-TV-App.
 *
 * Live TV: ↑/↓ (oder Sender ±) schalten um, OK öffnet die Senderliste, ◀ springt zum letzten Sender, ▶ bzw. Info
 * zeigt Programm-Info (zweimal: Stream-Info), ☰ Menü öffnet Ton/Untertitel/Favorit, Zifferntasten wählen einen
 * Sender, ⏯ hält an, Rot setzt einen Favoriten, Blau = letzter Sender.
 * Filme/Serien: OK oder ⏯ hält an, ◀/▶ und ⏪/⏩ spulen (gehalten immer schneller), ↑/↓ zeigen die Leiste
 * (zweimal: Stream-Info), ☰ Ton und Untertitel, ⏭ nächste Folge.
 * Tastatur zusätzlich: F/F11 Vollbild, M Ton aus, +/− Lautstärke, T Tonspur, U Untertitel, L Untertitel laden,
 * G/H Untertitel früher/später. Maus: Bewegen zeigt die Leiste, Klick Pause/Info, Doppelklick Vollbild, Rad Lautstärke.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(app: AppState, playback: Playback, window: WindowState, toggleFullscreen: () -> Unit) {
    val player = app.player
    val store = app.store
    val guide by store.guide.collectAsState()
    val user by store.user.collectAsState()
    val focus = remember { FocusRequester() }
    val live = playback as? Playback.Live
    var layer by remember { mutableStateOf(Layer.INFO) }
    var tech by remember { mutableStateOf(false) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var menu by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var subtitleDelay by remember(playback) { mutableLongStateOf(0L) }
    // Seit wann eine Spul-/Pfeiltaste gehalten wird (für schnelleres Spulen und „nur beim ersten Drücken“).
    var holdKey by remember { mutableStateOf<RemoteButton?>(null) }
    var holdSince by remember { mutableLongStateOf(0L) }
    var info by remember { mutableStateOf<StreamInfo?>(null) }

    fun refocus() = runCatching { focus.requestFocus() }

    fun show(next: Layer = Layer.INFO) {
        lastInput = System.currentTimeMillis()
        layer = next
    }

    fun shiftSubtitles(deltaMs: Long) {
        subtitleDelay += deltaMs
        player.subtitleDelay(subtitleDelay)
        app.toast("Untertitel ${if (subtitleDelay >= 0) "+" else ""}${subtitleDelay / 1000.0} s")
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
    val learned = series?.let { s -> user.intros[s.key]?.let { IntroSkip.Intro(it.startMs, it.lengthMs) } }
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
        show()
        val s = series ?: return
        if (skippedFrom >= 0 && System.currentTimeMillis() - skippedAt < IntroSkip.LEARN_WINDOW_MS) {
            skippedAt = System.currentTimeMillis()
            val target = (player.positionMs + delta).coerceAtLeast(0)
            IntroSkip.learn(skippedFrom, target)?.let { intro ->
                store.updateUser { it.copy(intros = it.intros + (s.key to IntroMark(intro.startMs, intro.lengthMs))) }
            }
        }
    }

    fun toggleFavorite(channel: Channel) {
        app.toast(if (store.toggleFavorite(channel.key)) "★ ${channel.name} zu Favoriten hinzugefügt" else "${channel.name} aus Favoriten entfernt")
    }

    /** ☰-Menü: Tonspuren, Untertitel, Untertitel laden/verschieben, bei Live TV Favorit und Stream-Info. */
    fun openMenu() {
        val items = buildList {
            if (live != null) {
                val fav = live.channel.key in user.favorites
                add(MenuItem(if (fav) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten") { toggleFavorite(live.channel) })
                guide[live.channel.key]?.let { nowOf(it) }?.let { current ->
                    add(MenuItem("⏺ „${current.title}“ aufnehmen") { app.record(live.channel, current) })
                    if (app.store.archiveUrl(live.channel, current.start, current.stop) != null) add(MenuItem("⏮ Sendung von Anfang") { app.playArchive(live.channel, current) })
                }
            }
            player.audioTracks.forEach { (id, name) -> add(MenuItem("🔊 Ton: $name") { player.selectAudio(id) }) }
            player.subtitleTracks.forEach { (id, name) -> add(MenuItem("💬 Untertitel: $name") { player.selectSubtitle(id) }) }
            if (playback is Playback.Vod) {
                val language = user.subtitleLanguage.ifBlank { "de" }.uppercase()
                add(MenuItem("💬 Untertitel ($language) aus dem Internet laden") { app.loadSubtitles(playback) })
                add(MenuItem("💬 Untertitel 0,25 s früher") { shiftSubtitles(-250) })
                add(MenuItem("💬 Untertitel 0,25 s später") { shiftSubtitles(250) })
                if (nextEpisode != null) add(MenuItem("⏭ Nächste Folge") { playNext() })
            }
            add(MenuItem(if (tech) "Stream-Info ausblenden" else "Stream-Info zeigen") { tech = !tech; show() })
            if (player.gpuVideo) {
                val next = HDR_MODES[(HDR_MODES.indexOfFirst { it.first == user.hdrMode } + 1).mod(HDR_MODES.size)]
                add(MenuItem("HDR-Anpassung: ${hdrLabel(user.hdrMode)} → ${next.second}") {
                    store.updateUser { it.copy(hdrMode = next.first) }
                    app.applyBuffer()
                    app.toast("HDR-Anpassung: ${next.second}")
                })
            }
            if (live != null) add(MenuItem("◫ Multiview (bis zu 4 Sender gleichzeitig)") { app.openMultiview() })
            add(MenuItem(if (user.directOutput) "HDR-Bildschirm: Direktausgabe ausschalten" else "HDR-Bildschirm: Direktausgabe (Vollbild, HDR unverändert)") {
                store.updateUser { it.copy(directOutput = !it.directOutput) }
                app.applyBuffer()
                app.restartPlayback()
            })
            add(MenuItem(if (window.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen) "⤢ Vollbild beenden" else "⤢ Vollbild") { toggleFullscreen() })
        }
        menu = {
            MenuDialog(app, if (live != null) live.channel.name else "Ton und Untertitel", items, onDismiss = { menu = null; refocus() })
        }
    }

    BackHandler(app, enabled = menu == null && layer == Layer.CHANNELS) { layer = Layer.NONE; refocus() }
    BackHandler(app, enabled = menu == null && layer != Layer.CHANNELS && tech) { tech = false }
    LaunchedEffect(Unit) { refocus() }
    LaunchedEffect(menu, layer) { if (menu == null && layer != Layer.CHANNELS) refocus() }
    // Direktausgabe (HDR-Bildschirm): Menü, Senderliste und Stream-Info liegen im Hauptfenster, das Vollbild so lange weg.
    LaunchedEffect(menu, layer, tech) { player.coverDirect(menu != null || layer == Layer.CHANNELS || tech) }
    DisposableEffect(Unit) { onDispose { player.coverDirect(true) } }
    // Info nach 5 Sekunden ohne Eingabe ausblenden (nicht bei Pause, Fehler oder offener Stream-Info).
    LaunchedEffect(lastInput, layer, player.playing, tech) {
        if (layer != Layer.INFO || tech) return@LaunchedEffect
        delay(5_000)
        if (player.playing && player.error == null) layer = Layer.NONE
    }
    // Stream-Info jede Sekunde neu lesen, solange sie offen ist.
    LaunchedEffect(tech, playback) {
        while (tech) {
            info = player.streamInfo()
            delay(1_000)
        }
    }
    // Beim Umschalten kurz die Info zeigen.
    LaunchedEffect((playback as? Playback.Live)?.channel?.key) { if (layer != Layer.CHANNELS) show() }
    // Stand von Filmen regelmäßig merken; am Ende einer Folge die nächste.
    LaunchedEffect(playback) {
        player.onFinished = { if (nextEpisode != null) playNext() else app.stopPlayback() }
        while (playback is Playback.Vod) {
            delay(15_000)
            app.savePosition()
        }
    }

    fun seekStep(button: RemoteButton, forward: Boolean, shift: Boolean): Long {
        val held = if (holdKey == button) System.currentTimeMillis() - holdSince else 0L
        val base = if (forward) 30_000L else 10_000L
        return when {
            shift -> 60_000L
            held > 5_000 -> 120_000L
            held > 1_500 -> 60_000L
            else -> base
        } * if (forward) 1 else -1
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                val button = e.remote()
                if (e.type == KeyEventType.KeyUp) {
                    if (button != null && button == holdKey) holdKey = null
                    return@onKeyEvent false
                }
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                // Senderliste offen: dort navigiert der Fokus (die Liste fängt OK selbst ab).
                if (layer == Layer.CHANNELS || menu != null) return@onKeyEvent false
                val first = button != holdKey
                if (first) {
                    holdKey = button
                    holdSince = System.currentTimeMillis()
                }
                lastInput = System.currentTimeMillis()
                e.digit()?.let { d ->
                    if (live != null) { app.typeDigit(d); show() }
                    return@onKeyEvent live != null
                }
                if (button == null) {
                    // Nur Tastatur: PC-Kürzel.
                    when (e.key) {
                        Key.F, Key.F11 -> toggleFullscreen()
                        Key.M -> player.toggleMute()
                        Key.Plus, Key.NumPadAdd, Key.Equals -> { player.changeVolume(5); show() }
                        Key.Minus, Key.NumPadSubtract -> { player.changeVolume(-5); show() }
                        Key.T, Key.U -> openMenu()
                        Key.L -> (playback as? Playback.Vod)?.let { app.loadSubtitles(it) }
                        Key.G -> shiftSubtitles(-250)
                        Key.H -> shiftSubtitles(250)
                        Key.N -> playNext()
                        Key.I -> if (introVisible) skipIntro() else { if (layer == Layer.INFO) tech = !tech; show() }
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                if (live != null) {
                    when (button) {
                        RemoteButton.UP -> if (user.zapWithUpDown) app.zap(+1) else show(Layer.CHANNELS)
                        RemoteButton.DOWN -> if (user.zapWithUpDown) app.zap(-1) else show(Layer.CHANNELS)
                        RemoteButton.CHANNEL_UP, RemoteButton.NEXT -> app.zap(+1)
                        RemoteButton.CHANNEL_DOWN, RemoteButton.PREVIOUS -> app.zap(-1)
                        RemoteButton.OK, RemoteButton.GUIDE -> if (first) show(Layer.CHANNELS)
                        // ◀ springt zum vorherigen Sender (und von dort zurück); ohne vorherigen öffnet es die Liste.
                        RemoteButton.LEFT -> if (first && !app.backZap()) show(Layer.CHANNELS)
                        RemoteButton.RIGHT, RemoteButton.INFO -> if (first) { if (layer == Layer.INFO) tech = !tech; show() }
                        RemoteButton.LAST_CHANNEL, RemoteButton.BLUE -> if (first) app.backZap()
                        RemoteButton.MENU, RemoteButton.CAPTIONS -> if (first) openMenu()
                        RemoteButton.RED -> if (first) { toggleFavorite(live.channel); show() }
                        // Wie auf dem Fire TV: ⏯ hält an (zeitversetzt weiter), ⏩ zurück zu Live, ⏪ Sendung von Anfang (Archiv).
                        RemoteButton.PLAY_PAUSE -> { if (first) app.toggleLivePause(); show() }
                        RemoteButton.PLAY -> { if (player.paused) app.toggleLivePause(); show() }
                        RemoteButton.PAUSE -> { if (!player.paused) app.toggleLivePause(); show() }
                        RemoteButton.STOP -> app.stopPlayback()
                        RemoteButton.FAST_FORWARD -> if (first) { if (!app.backToLive()) show() }
                        RemoteButton.REWIND -> if (first) {
                            val current = guide[live.channel.key]?.let { nowOf(it) }
                            if (current == null || !app.playArchive(live.channel, current)) app.toast("Für diesen Sender gibt es kein Archiv")
                        }
                        else -> return@onKeyEvent false
                    }
                    return@onKeyEvent true
                }
                // Filme und Serien.
                if (first && (button == RemoteButton.OK)) {
                    if (introVisible) { skipIntro(); return@onKeyEvent true }
                }
                when (button) {
                    RemoteButton.OK, RemoteButton.PLAY_PAUSE -> {
                        if (first) {
                            if (layer == Layer.INFO || !player.playing || button == RemoteButton.PLAY_PAUSE) player.togglePause()
                            show()
                        }
                    }
                    RemoteButton.PLAY -> { player.resume(); show() }
                    RemoteButton.PAUSE -> { player.pause(); show() }
                    RemoteButton.STOP -> app.stopPlayback()
                    RemoteButton.LEFT, RemoteButton.REWIND -> seek(seekStep(button, forward = false, shift = e.isShiftPressed))
                    RemoteButton.RIGHT, RemoteButton.FAST_FORWARD -> seek(seekStep(button, forward = true, shift = e.isShiftPressed))
                    RemoteButton.UP, RemoteButton.DOWN, RemoteButton.INFO -> if (first) { if (layer == Layer.INFO) tech = !tech; show() }
                    RemoteButton.MENU, RemoteButton.CAPTIONS -> if (first) openMenu()
                    RemoteButton.NEXT, RemoteButton.CHANNEL_UP -> if (first) { if ((playback as? Playback.Vod)?.archive != null) app.backToLive() else playNext() }
                    else -> return@onKeyEvent false
                }
                true
            }
            .onPointerEvent(PointerEventType.Move) { if (layer == Layer.NONE) show() else { lastInput = System.currentTimeMillis() } }
            .onPointerEvent(PointerEventType.Scroll) { e ->
                if (layer == Layer.CHANNELS) return@onPointerEvent
                val dy = e.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                if (dy != 0f) player.changeVolume(if (dy < 0) 5 else -5)
                show()
            }
            .pointerInput(playback) {
                detectTapGestures(
                    onDoubleTap = { toggleFullscreen() },
                    onTap = {
                        when {
                            layer == Layer.CHANNELS -> layer = Layer.NONE
                            playback is Playback.Vod && layer == Layer.INFO -> player.togglePause()
                            layer == Layer.INFO -> layer = Layer.NONE
                            else -> show()
                        }
                        if (layer != Layer.NONE) lastInput = System.currentTimeMillis()
                        refocus()
                    },
                )
            },
    ) {
        val hasFrame = player.hasFrame
        if (hasFrame) {
            VideoView(player, Modifier.fillMaxSize())
        } else if (live != null && !player.hasVideo) {
            Box(Modifier.align(Alignment.Center)) { Logo(live.channel.logo, 160) }
        }

        val problem = player.error ?: if (!player.available) "VLC wurde nicht gefunden. Bitte DnA-TV neu installieren." else null
        val status = app.playerStatus
        when {
            status != null -> Notice(status, Modifier.align(Alignment.TopCenter).padding(top = 32.dp))
            problem != null -> Box(Modifier.align(Alignment.Center).background(Palette.surface, RoundedCornerShape(12.dp)).padding(20.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(problem, Type.heading, maxLines = 3)
                    Spacer(Modifier.height(6.dp))
                    Label(if (live != null) "↑/↓ anderer Sender · OK Senderliste · Zurück beendet" else "Zurück beendet", Type.small)
                }
            }
            player.buffering && !hasFrame -> Box(Modifier.align(Alignment.Center)) { Label("Lädt …", Type.heading) }
        }
        if (player.paused && layer == Layer.NONE) {
            Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(50)).padding(20.dp)) { Label("⏸", Type.title) }
        }

        // Eingetippte Sendernummer und Ziel beim schnellen Umschalten.
        val typed = app.typedNumber
        if (typed.isNotEmpty()) {
            Box(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xCC000000), RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 10.dp)) {
                Label(typed, Type.display)
            }
        }

        if (tech) {
            TechPanel(info, Modifier.align(Alignment.TopStart).padding(24.dp))
        }

        if (layer == Layer.INFO || problem != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 64.dp, bottom = 28.dp),
            ) {
                when (playback) {
                    is Playback.Live -> {
                        val shown = app.zapTarget ?: playback.channel
                        LiveBar(playback, shown, guide[shown.key].orEmpty(), player.volume, shown.key in user.favorites, player.paused, app.liveDelayMs)
                    }
                    is Playback.Vod -> VodBar(
                        playback, player.positionMs, player.durationMs, player.playing, player.volume, nextEpisode != null, app.subtitleSync,
                        onNext = ::playNext, onSeek = { player.seekTo(it) }, onPause = { player.togglePause() },
                        onBack10 = { seek(-10_000) }, onForward30 = { seek(30_000) },
                    )
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                if (live != null) { TextButton("☰ Senderliste", { show(Layer.CHANNELS) }); Spacer(Modifier.width(8.dp)) }
                TextButton("🔊 Ton / 💬 Untertitel", { openMenu() })
                Spacer(Modifier.width(8.dp))
                TextButton("⤢", { toggleFullscreen() })
                Spacer(Modifier.width(8.dp))
                TextButton("✕", { app.stopPlayback() })
            }
        }
        if (introVisible) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 40.dp, bottom = if (layer == Layer.INFO) 170.dp else 48.dp)) {
                TextButton("⏭ Intro überspringen (OK)", { skipIntro() }, primary = true)
            }
        }
        if (layer == Layer.CHANNELS && live != null) {
            ChannelPanel(app, live, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(460.dp)) { picked, list ->
                if (picked.key != live.channel.key) app.play(Playback.Live(picked, list))
                layer = Layer.INFO
                lastInput = System.currentTimeMillis()
            }
        }
        menu?.invoke()
    }
}

@Composable
private fun Notice(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xDD151B23), RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
        Label(text, Type.body, maxLines = 2)
    }
}

@Composable
private fun TechPanel(info: StreamInfo?, modifier: Modifier = Modifier) {
    Column(modifier.width(330.dp).background(Color(0xDD0B0F14), RoundedCornerShape(12.dp)).padding(16.dp)) {
        Label("Stream-Info", Type.heading)
        Spacer(Modifier.height(8.dp))
        if (info == null) {
            Label("Wird gelesen …", Type.small)
        } else {
            info.lines().forEach { (name, value) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Label(name, Type.small, Modifier.width(96.dp))
                    Label(value, Type.body, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Senderliste über dem Bild (OK im Live-TV): der laufende Sender ist ausgewählt, ◀/▶ wechseln die Gruppe,
 * OK schaltet um, ☰ bzw. OK lange drücken setzt einen Favoriten, Zurück schließt.
 */
@Composable
private fun ChannelPanel(app: AppState, live: Playback.Live, modifier: Modifier, onPick: (Channel, List<Channel>) -> Unit) {
    val store = app.store
    val guide by store.guide.collectAsState()
    val user by store.user.collectAsState()
    // Gruppen: die Liste, in der gerade gezappt wird, Favoriten, alle Sender, dann die Gruppen des Anbieters.
    val groups = remember(user) {
        buildList<Pair<String, () -> List<Channel>>> {
            add("Aktuelle Liste" to { live.list })
            if (user.favorites.isNotEmpty()) add("★ Favoriten" to { store.favorites() })
            user.customGroups.forEach { g -> add("▣ ${g.name}" to { store.inCustom(g.id) }) }
            add("Alle Sender" to { store.allVisible() })
            store.liveGroups().forEach { (name, _) -> add(name to { store.inGroup(name) }) }
        }
    }
    var groupIndex by remember { mutableStateOf(0) }
    val (groupName, loader) = groups[groupIndex.coerceIn(0, groups.lastIndex)]
    val channels = remember(groupIndex, user) { loader() }
    val state = rememberLazyListState()
    val current = remember { FocusRequester() }
    val at = channels.indexOfFirst { it.key == live.channel.key }.takeIf { it >= 0 } ?: 0

    LaunchedEffect(groupIndex) {
        state.scrollToItem((at - 4).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { current.requestFocus() }
    }

    fun switchGroup(delta: Int) {
        groupIndex = (groupIndex + delta).mod(groups.size)
    }

    Column(
        modifier.background(Color(0xF00B0F14)).padding(16.dp).onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.remote()) {
                RemoteButton.LEFT -> { switchGroup(-1); true }
                RemoteButton.RIGHT -> { switchGroup(+1); true }
                RemoteButton.CHANNEL_UP -> { switchGroup(+1); true }
                RemoteButton.CHANNEL_DOWN -> { switchGroup(-1); true }
                else -> false
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("◀", { switchGroup(-1) })
            Label(groupName, Type.heading, Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton("▶", { switchGroup(+1) })
        }
        Spacer(Modifier.height(10.dp))
        if (channels.isEmpty()) {
            Label("Keine Sender in dieser Gruppe.", Type.small)
            return@Column
        }
        LazyColumn(state = state) {
            itemsIndexed(channels, key = { _, c -> c.key }) { index, channel ->
                val programme = guide[channel.key]?.let(::nowOf)
                val number = channel.number.takeIf { it > 0 } ?: (index + 1)
                Selectable(
                    onClick = { onPick(channel, channels) },
                    onMenu = { app.toast(if (store.toggleFavorite(channel.key)) "★ ${channel.name} zu Favoriten" else "${channel.name} aus Favoriten entfernt") },
                    selected = channel.key == live.channel.key,
                    focusRequester = if (index == at) current else null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Label("$number", Type.small, Modifier.width(40.dp))
                    Logo(channel.logo, 34)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Label((if (channel.key in user.favorites) "★ " else "") + channel.name, Type.body)
                        if (programme != null) {
                            Label(programme.title, Type.small)
                            Spacer(Modifier.height(3.dp))
                            Progress(programme.fraction())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBar(live: Playback.Live, channel: Channel, programmes: List<Programme>, volume: Int, favorite: Boolean, paused: Boolean, delayMs: Long) {
    val now = System.currentTimeMillis()
    val current = programmes.firstOrNull { it.start <= now && it.stop > now }
    val next = programmes.firstOrNull { it.start >= (current?.stop ?: now) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(channel.logo, 64)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                val index = live.list.indexOfFirst { it.key == channel.key }
                val number = channel.number.takeIf { it > 0 } ?: (index + 1)
                Label("${if (number > 0) "$number  " else ""}${channel.name}${if (favorite) "  ★" else ""}", Type.title)
                if (current != null) {
                    Label("${clock(current.start)} – ${clock(current.stop)}   ${current.title}", Type.heading)
                    Spacer(Modifier.height(6.dp))
                    Progress(current.fraction())
                } else {
                    Label("Kein TV-Guide für diesen Sender", Type.small)
                }
                next?.let { Spacer(Modifier.height(4.dp)); Label("Danach  ${clock(it.start)}   ${it.title}", Type.body, color = Palette.secondary) }
            }
            Spacer(Modifier.width(18.dp))
            Column(horizontalAlignment = Alignment.End) {
                Label(clock(now), Type.title)
                when {
                    paused -> Label("⏸ Angehalten", Type.heading, color = Palette.accent)
                    delayMs > 1_000 -> Label("Zeitversetzt −${duration(delayMs)} · ⏩ Live", Type.small, color = Palette.accent)
                }
                Label("🔊 $volume %", Type.small)
            }
        }
        Spacer(Modifier.height(10.dp))
        Label("↑/↓ umschalten · OK Senderliste · ◀ letzter Sender · ▶ Stream-Info · ☰ Ton/Untertitel · ⏯ Pause · ⏪ von Anfang", Type.small)
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
    onPause: () -> Unit,
    onBack10: () -> Unit,
    onForward30: () -> Unit,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label(vod.title, Type.title, Modifier.weight(1f))
            // Gegen Ende (Abspann) die nächste Folge anbieten.
            if (hasNext && duration > 0 && duration - position < 3 * 60_000) TextButton("⏭ Nächste Folge", onNext, primary = true)
        }
        Spacer(Modifier.height(10.dp))
        SeekBar(position, duration, onSeek)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton("⏪ 10", onBack10)
            Spacer(Modifier.width(6.dp))
            TextButton(if (playing) "⏸" else "▶", onPause, primary = true)
            Spacer(Modifier.width(6.dp))
            TextButton("30 ⏩", onForward30)
            if (hasNext) { Spacer(Modifier.width(6.dp)); TextButton("⏭", onNext) }
            Spacer(Modifier.width(16.dp))
            Label("${duration(position)} / ${duration(duration)}", Type.heading, Modifier.weight(1f))
            subtitleSync?.let { Label("💬 $it   ", Type.small) }
            Label("🔊 $volume %", Type.small)
        }
        Spacer(Modifier.height(6.dp))
        Label("OK Pause · ◀/▶ spulen (halten = schneller) · ↑/↓ Stream-Info · ☰ Ton/Untertitel", Type.small)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SeekBar(position: Long, duration: Long, onSeek: (Long) -> Unit) {
    var width by remember { mutableStateOf(1) }
    Box(
        Modifier.fillMaxWidth().height(16.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / width * duration).toLong()) }
            },
        contentAlignment = Alignment.Center,
    ) {
        Progress(if (duration > 0) position.toFloat() / duration else 0f, height = 6)
    }
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** HDR-Anpassung: Wert und Anzeige. */
val HDR_MODES = listOf("AUTO" to "Automatisch", "PQ" to "HDR10", "HLG" to "HLG", "OFF" to "Aus")
fun hdrLabel(mode: String) = HDR_MODES.firstOrNull { it.first == mode }?.second ?: "Automatisch"

/** Anteil der Sendung, der schon gelaufen ist. */
fun Programme.fraction(now: Long = System.currentTimeMillis()): Float =
    if (stop > start) ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f) else 0f

fun nowOf(list: List<Programme>, at: Long = System.currentTimeMillis()): Programme? = list.firstOrNull { it.start <= at && it.stop > at }

/**
 * Das Videobild. Neue Bilder lösen nur Neuzeichnen aus (nicht den ganzen Player-Bildschirm): [VideoPlayer.frameTick]
 * wird erst beim Zeichnen gelesen. Schneller Weg: YUV-Ebenen + Shader; sonst das RGB-Bild wie früher.
 */
@Composable
fun VideoView(player: de.ayutv.desktop.player.VideoPlayer, modifier: Modifier = Modifier) {
    if (player.gpuVideo) {
        androidx.compose.foundation.Canvas(modifier) {
            player.frameTick // Neuzeichnen bei jedem Bild
            val frame = player.currentFrame() ?: return@Canvas
            drawIntoCanvas { canvas -> frame.draw(canvas.nativeCanvas, size.width, size.height) }
        }
    } else {
        val frame = player.frame ?: return
        Image(frame, null, modifier, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Low)
    }
}
