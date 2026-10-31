package de.ayutv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.ui.AppState
import de.ayutv.desktop.ui.GuideScreen
import de.ayutv.desktop.ui.Label
import de.ayutv.desktop.ui.LibraryScreen
import de.ayutv.desktop.ui.ListRow
import de.ayutv.desktop.ui.LiveScreen
import de.ayutv.desktop.ui.Palette
import de.ayutv.desktop.ui.Playback
import de.ayutv.desktop.ui.PlayerScreen
import de.ayutv.desktop.ui.Screen
import de.ayutv.desktop.ui.SearchScreen
import de.ayutv.desktop.ui.SettingsScreen
import de.ayutv.desktop.ui.SportScreen
import de.ayutv.desktop.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    VideoPlayer.useBundledVlc()
    if (args.firstOrNull() == "--selftest") return SelfTest.run(args.getOrNull(1))
    CrashLog.install(Store.defaultDir())
    application {
        // Ein Fehler beim Zeichnen eines Fensters soll die App nicht beenden (Standard: Fehlerfenster und Ende),
        // sondern nur protokolliert werden; das nächste Bild wird wieder normal gezeichnet.
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides KeepRunning) { Windows() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private val KeepRunning = WindowExceptionHandlerFactory { window ->
    WindowExceptionHandler { error -> CrashLog.write("Fenster ${window.name}", error) }
}

@Composable
private fun ApplicationScope.Windows() {
    val window = rememberWindowState(size = DpSize(1280.dp, 780.dp))
    val scope = rememberCoroutineScope()
    // Fenster sofort zeigen; Daten (Playlists, TV-Guide) und VLC im Hintergrund laden.
    var app by remember { mutableStateOf<AppState?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            withContext(Dispatchers.IO) {
                val store = Store(Store.defaultDir())
                // Proxy vor der ersten Verbindung festlegen (sonst wartet jede Verbindung kurz auf die Einstellungen).
                Network.apply(store)
                AppState(store, Sync(store), scope)
            }
        }.onSuccess { app = it }.onFailure { failure = "DnA-TV konnte nicht starten: ${it.message}" }
    }
    Window(
        onCloseRequest = { app?.close(); exitApplication() },
        state = window,
        title = "DnA-TV",
        icon = remember { appIcon() },
    ) {
        val ready = app
        if (ready == null) {
            Starting(failure)
        } else {
            // Tasten vom Handy gehen an dieses Fenster.
            LaunchedEffect(ready) {
                ready.mainWindow = this@Window.window
                ready.remote.window = this@Window.window
            }
            App(ready, window)
        }
    }
}

@Composable
private fun Starting(failure: String?) {
    Box(Modifier.fillMaxSize().background(Palette.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label("DnA-TV", Type.title)
            Spacer(Modifier.height(8.dp))
            Label(failure ?: "startet …", Type.body, maxLines = 4)
        }
    }
}

private fun appIcon(): BitmapPainter? = runCatching {
    val bytes = object {}.javaClass.getResourceAsStream("/dna-tv.png")!!.use { it.readBytes() }
    BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

@Composable
fun App(app: AppState, window: WindowState) {
    val focus = LocalFocusManager.current
    val status by app.sync.status.collectAsState()

    // Beim Start: Playlists auffrischen, wenn der letzte Abgleich älter als 12 Stunden ist.
    // Vollbild im selben Fenster; danach wieder so wie vorher (normal oder maximiert).
    val before = remember { arrayOf(WindowPlacement.Floating) }
    LaunchedEffect(app.fullscreen) {
        if (app.fullscreen) {
            if (window.placement != WindowPlacement.Fullscreen) before[0] = window.placement
            window.placement = WindowPlacement.Fullscreen
        } else if (window.placement == WindowPlacement.Fullscreen) {
            window.placement = before[0]
        }
    }

    LaunchedEffect(Unit) {
        val user = app.store.user.value
        if (user.startMaximized) window.placement = WindowPlacement.Maximized
        // Wie ein Fernseher: mit dem letzten Sender starten (Einstellung).
        if (user.resumeLastChannel) user.recent.firstNotNullOfOrNull { app.store.channel(it) }?.let { app.play(Playback.Live(it, app.store.allVisible())) }
        val stale = app.store.playlists.value.any { it.enabled && System.currentTimeMillis() - it.lastSync > 12 * 3_600_000L }
        if (stale) app.syncNow()
        else if (app.store.guide.value.isEmpty() && app.store.playlists.value.isNotEmpty()) runCatching { app.sync.syncGuide() }
    }

    Box(
        Modifier.fillMaxSize().background(Palette.background).background(Palette.backdrop).onPreviewKeyEvent { e ->
            // F11 immer, auch während ein Eingabefeld den Fokus hat.
            if (e.key != Key.F11 || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            // Im Player: Vollbild; sonst das Fenster maximieren.
            if (app.playing != null) app.changeFullscreen(!app.fullscreen) else toggleMaximized(window)
            true
        }.onKeyEvent { e ->
            // Was kein Element selbst nutzt: Pfeiltasten wechseln den Fokus, Esc/Zurück geht eine Ebene zurück.
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            when (e.key) {
                Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                Key.DirectionLeft -> focus.moveFocus(FocusDirection.Left)
                Key.DirectionRight -> focus.moveFocus(FocusDirection.Right)
                Key.Escape, Key.Back -> app.back()
                else -> false
            }
        },
    ) {
        val playing = app.playing
        if (playing != null) {
            PlayerScreen(app, playing)
        } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar(app, window)
                Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
                    when (app.screen) {
                        Screen.LIVE -> LiveScreen(app)
                        Screen.GUIDE -> GuideScreen(app)
                        Screen.SPORT -> SportScreen(app)
                        Screen.MOVIES -> LibraryScreen(app, series = false)
                        Screen.SERIES -> LibraryScreen(app, series = true)
                        Screen.SEARCH -> SearchScreen(app)
                        Screen.SETTINGS -> SettingsScreen(app)
                    }
                }
            }
        }
        val line = app.message ?: status
        if (line != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Palette.raised, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Label(line, Type.body, maxLines = 3)
            }
        }
    }
}

@Composable
private fun Sidebar(app: AppState, window: WindowState) {
    val first = remember { FocusRequester() }
    Column(Modifier.width(200.dp).fillMaxHeight().background(Palette.surface.copy(alpha = 0.75f)).padding(10.dp)) {
        Label("DnA-TV", Type.title, Modifier.padding(start = 12.dp, top = 8.dp, bottom = 16.dp))
        Screen.entries.forEachIndexed { index, screen ->
            ListRow(
                "${screen.icon}  ${screen.label}",
                onClick = { app.screen = screen },
                selected = app.screen == screen,
                focusRequester = if (index == 0) first else null,
            )
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.weight(1f))
        val big = window.placement == WindowPlacement.Maximized
        ListRow(if (big) "⛶  Kleiner (F11)" else "⛶  Großes Fenster (F11)", onClick = { toggleMaximized(window) })
    }
}

fun toggleMaximized(window: WindowState) {
    window.placement = if (window.placement == WindowPlacement.Maximized) WindowPlacement.Floating else WindowPlacement.Maximized
}
