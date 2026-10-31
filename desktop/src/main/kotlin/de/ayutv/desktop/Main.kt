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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.input.remote
import de.ayutv.desktop.ui.AppState
import de.ayutv.desktop.ui.CountryDialog
import de.ayutv.desktop.ui.HomeScreen
import de.ayutv.desktop.ui.clock
import de.ayutv.desktop.ui.GuideScreen
import de.ayutv.desktop.ui.Label
import de.ayutv.desktop.ui.LibraryScreen
import de.ayutv.desktop.ui.ListRow
import de.ayutv.desktop.ui.LiveScreen
import de.ayutv.desktop.ui.Palette
import de.ayutv.desktop.ui.PlayerScreen
import de.ayutv.desktop.ui.Screen
import de.ayutv.desktop.ui.SearchScreen
import de.ayutv.desktop.ui.SettingsScreen
import de.ayutv.desktop.ui.SportScreen
import de.ayutv.desktop.ui.Type
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

fun main(args: Array<String>) {
    VideoPlayer.useBundledVlc()
    if (args.firstOrNull() == "--selftest") return SelfTest.run(args.getOrNull(1))
    val store = Store(Store.defaultDir())
    // Proxy vor der ersten Verbindung festlegen (sonst wartet jede Verbindung kurz auf die Einstellungen).
    Network.apply(store)
    // Fire-TV-Fernbedienung so früh wie möglich lesen (eigener Thread, nur unter Windows).
    val input = RemoteInput().also { it.start() }
    application {
        val window = rememberWindowState(
            size = DpSize(1280.dp, 780.dp),
            placement = if (store.user.value.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating,
        )
        val scope = rememberCoroutineScope()
        val app = remember { AppState(store, Sync(store), scope, input) }
        Window(
            onCloseRequest = { app.close(); exitApplication() },
            state = window,
            title = "DnA-TV",
            icon = remember { appIcon() },
        ) {
            val awt = this.window
            DisposableEffect(awt) {
                // Tasten von Fire-TV- und Handy-Fernbedienung gehen an dieses Fenster, solange es vorn ist.
                app.remote.window = awt
                input.window = awt
                input.foreground = awt.isFocused
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) { input.foreground = true }
                    override fun windowLostFocus(e: WindowEvent?) { input.foreground = false }
                }
                awt.addWindowFocusListener(listener)
                onDispose { awt.removeWindowFocusListener(listener) }
            }
            App(app, window)
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
    val root = remember { FocusRequester() }
    var rootFocused by remember { mutableStateOf(false) }
    val source = app.input.source

    fun toggleFullscreen() {
        val full = window.placement != WindowPlacement.Fullscreen
        window.placement = if (full) WindowPlacement.Fullscreen else WindowPlacement.Floating
        app.store.updateUser { it.copy(fullscreen = full) }
    }

    // Erste Taste der Fire-TV-Fernbedienung: kurz bestätigen, dass sie erkannt wurde.
    LaunchedEffect(source?.deviceName) { if (source != null) app.toast("✓ ${source.label} erkannt") }
    // Beim Start etwas auswählen, damit Pfeiltasten und Fernbedienung sofort wirken.
    LaunchedEffect(Unit) { runCatching { root.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Palette.background)
            .focusRequester(root)
            .onFocusChanged { rootFocused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                // Was kein Element selbst nutzt: Pfeiltasten wechseln den Fokus, Zurück geht eine Ebene zurück,
                // Home zur Startseite, Suche/Einstellungen/Guide direkt.
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (e.key == Key.F11) { toggleFullscreen(); return@onKeyEvent true }
                fun move(direction: FocusDirection): Boolean {
                    // Noch nichts ausgewählt: ins erste Element springen.
                    if (rootFocused) return focus.moveFocus(FocusDirection.Enter) || focus.moveFocus(FocusDirection.Next)
                    return focus.moveFocus(direction)
                }
                when (e.remote()) {
                    RemoteButton.UP -> move(FocusDirection.Up)
                    RemoteButton.DOWN -> move(FocusDirection.Down)
                    RemoteButton.LEFT -> move(FocusDirection.Left)
                    RemoteButton.RIGHT -> move(FocusDirection.Right)
                    RemoteButton.BACK -> app.backToHome()
                    RemoteButton.HOME -> { app.goHome(); true }
                    RemoteButton.SEARCH -> { if (app.playing == null) app.screen = Screen.SEARCH; true }
                    RemoteButton.SETTINGS -> { if (app.playing == null) app.screen = Screen.SETTINGS; true }
                    RemoteButton.GUIDE -> { if (app.playing == null) app.screen = Screen.GUIDE; true }
                    else -> false
                }
            },
    ) {
        val playing = app.playing
        if (playing != null) {
            PlayerScreen(app, playing, window, ::toggleFullscreen)
        } else {
            Row(Modifier.fillMaxSize()) {
                Sidebar(app)
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp)) {
                    when (app.screen) {
                        Screen.HOME -> HomeScreen(app)
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
            if (app.askCountries) CountryDialog(app, first = true, onDismiss = { app.askCountries = false })
        }
        val line = app.message ?: status
        if (line != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(24.dp).background(Palette.raised, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Label(line, Type.body, maxLines = 3)
            }
        }
    }
}

/** Navigation links: immer erreichbar mit ◀, Maus oder Home-Taste. Uhrzeit oben wie auf dem Fernseher. */
@Composable
private fun Sidebar(app: AppState) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000)
        }
    }
    Column(Modifier.width(210.dp).fillMaxHeight().background(Palette.surface).padding(10.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 18.dp), verticalAlignment = Alignment.Bottom) {
            Label("DnA-TV", Type.title, Modifier.weight(1f))
            Label(clock(now), Type.small)
        }
        Screen.entries.forEach { screen ->
            ListRow(
                "${screen.icon}   ${screen.label}",
                onClick = { app.screen = screen },
                selected = app.screen == screen,
            )
            Spacer(Modifier.height(2.dp))
        }
        Spacer(Modifier.weight(1f))
        val source = app.input.source
        Label(
            if (source != null) "🎮 ${source.label} verbunden" else "Fernbedienung, Tastatur oder Maus",
            Type.small, Modifier.padding(start = 12.dp, bottom = 6.dp), maxLines = 2,
        )
    }
}
