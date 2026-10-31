package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.data.net.ProxyCheck
import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.AudioOutput
import de.ayutv.desktop.data.Buffer
import de.ayutv.desktop.data.Deinterlace
import de.ayutv.desktop.data.LANGUAGES
import de.ayutv.desktop.data.LiveFormat
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.PictureFit
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.PlaylistType
import de.ayutv.desktop.data.ProxySettings
import de.ayutv.desktop.data.SubtitleBackground
import de.ayutv.desktop.data.SubtitleColor
import de.ayutv.desktop.data.SubtitlePosition
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.Theme
import de.ayutv.desktop.data.Updater
import de.ayutv.desktop.data.UserData
import de.ayutv.desktop.data.config
import de.ayutv.desktop.data.languageName
import de.ayutv.desktop.player.DisplayRate
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/** Bereiche der Einstellungen (wie auf dem Fire TV). */
private enum class Page(val title: String, val description: String, val icon: String) {
    PLAYLISTS("Playlists", "Anbieter hinzufügen, ein- und ausschalten, vom Fire TV übernehmen", "☰"),
    PLAYBACK("Wiedergabe", "Bild, Bildwiederholrate, Puffer und Ton", "▶"),
    SUBTITLES("Untertitel", "Sprache, Aussehen, Untertitel aus dem Internet, Übersetzung", "💬"),
    PROXY("Proxy", "Eigene IP nur für DnA-TV, z. B. aus einem anderen Land", "🌐"),
    DISPLAY("Bedienung & TV-Guide", "Design, Umschalten, Programmführer, Handy", "📺"),
    DATA("Daten & Info", "Version, Updates, Verlauf, Speicher", "ⓘ"),
}

/** Einstellungen: links die Bereiche, rechts die Einstellungen dazu. */
@Composable
fun SettingsScreen(app: AppState) {
    var page by remember { mutableStateOf(if (app.store.playlists.value.isEmpty()) Page.PLAYLISTS else Page.PLAYBACK) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    val show: ((@Composable () -> Unit)?) -> Unit = { dialog = it }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(250.dp).fillMaxHeight().background(Palette.surface.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp)) {
            Label("Einstellungen", Type.title, Modifier.padding(start = 8.dp, top = 4.dp, bottom = 16.dp))
            Page.entries.forEach { entry -> PageButton(entry.icon, entry.title, page == entry) { page = entry } }
            Spacer(Modifier.weight(1f))
            Label("DnA-TV ${Updater.version}", Type.small, Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.width(20.dp))
        key(page) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(end = 12.dp, bottom = 40.dp)) {
                item {
                    Column(Modifier.padding(bottom = 6.dp)) {
                        Label(page.title, Type.title)
                        Label(page.description, Type.small)
                    }
                }
                when (page) {
                    Page.PLAYLISTS -> item { PlaylistsPage(app, show) }
                    Page.PLAYBACK -> item { PlaybackPage(app, show) }
                    Page.SUBTITLES -> item { SubtitlesPage(app, show) }
                    Page.PROXY -> item { ProxySection(app, app.store.playlists.collectAsState().value) }
                    Page.DISPLAY -> item { DisplayPage(app, show) }
                    Page.DATA -> item { DataPage(app, show) }
                }
            }
        }
    }
    dialog?.invoke()
}

@Composable
private fun PlaylistsPage(app: AppState, show: ((@Composable () -> Unit)?) -> Unit) {
    val playlists by app.store.playlists.collectAsState()
    val content by app.store.content.collectAsState()
    val guideCount = app.store.guide.collectAsState().value.size
    Column {
        if (playlists.isEmpty()) Note("Noch keine Playlist. Am einfachsten: „Vom Fire TV übernehmen“ – dann ist alles wie auf dem Fernseher.")
        playlists.forEach { p ->
            val c = content[p.id]
            val counts = c?.let { "${it.channels.size} Sender · ${it.movies.size} Filme · ${it.series.size} Serien" } ?: "noch nicht geladen"
            val synced = if (p.lastSync > 0) " · geladen ${DATE_TIME.format(Instant.ofEpochMilli(p.lastSync))}" else ""
            val missingPassword = p.type == PlaylistType.XTREAM && p.password.isNullOrEmpty()
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(p.name, Type.heading)
                        Label("${if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U"} · $counts$synced", Type.small)
                        if (missingPassword) Label("⚠ Passwort fehlt – bitte unter „Passwort eintragen“", Type.small.copy(color = Palette.live))
                    }
                    Selectable({ togglePlaylist(app, p) }) { Switch(p.enabled); Spacer(Modifier.width(8.dp)); Label(if (p.enabled) "Aktiv" else "Aus", Type.small) }
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(if (missingPassword) "Passwort eintragen" else "Bearbeiten", { show { PlaylistDialog(app, p, onDismiss = { show(null) }) } }, primary = missingPassword)
                    Spacer(Modifier.width(6.dp))
                    TextButton("⟳ Neu laden", { app.scope.launch { runCatching { app.sync.syncPlaylist(p.id); app.sync.syncGuide() }.onSuccess { app.toast("${p.name} geladen") }.onFailure { app.toast("${p.name}: ${it.message}") } } })
                    Spacer(Modifier.width(6.dp))
                    TextButton("Löschen", {
                        show { ConfirmDialog(app, "Playlist „${p.name}“ löschen?", "Löschen", { app.store.deletePlaylist(p.id) }, { show(null) }) }
                    })
                }
            }
        }
        Row(Modifier.padding(vertical = 8.dp)) {
            TextButton("＋ Playlist hinzufügen", { show { PlaylistDialog(app, Playlist(app.store.nextPlaylistId(), "", PlaylistType.XTREAM, ""), onDismiss = { show(null) }) } }, primary = true)
            Spacer(Modifier.width(8.dp))
            TextButton("⟳ Alle neu laden", { app.syncNow() })
        }
        SettingGroup("Vom Fire TV")
        SettingRow(
            "Sicherung vom Fire TV übernehmen",
            description = "Playlists mit Passwörtern, Favoriten, eigene Gruppen, Reihenfolgen, Proxy, Schlüssel und passende Einstellungen.",
        ) { show { ImportDialog(app, onDismiss = { show(null) }) } }
        SettingGroup("TV-Guide")
        SettingRow(
            "TV-Guide neu laden",
            "$guideCount Sender",
            "Vom Anbieter und freies EPG für Sender ohne Programm. Eigene XMLTV-Adresse je Playlist unter „Bearbeiten“.",
        ) { app.scope.launch { runCatching { app.sync.syncGuide() }.onSuccess { app.toast("TV-Guide geladen") }.onFailure { app.toast("TV-Guide: ${it.message}") } } }
    }
}

private fun togglePlaylist(app: AppState, p: Playlist) {
    app.store.savePlaylist(p.copy(enabled = !p.enabled))
    app.applyProxy()
    if (!p.enabled && app.store.content.value[p.id] == null) {
        app.scope.launch { runCatching { app.sync.syncPlaylist(p.id) }.onFailure { app.toast("${p.name}: ${it.message}") } }
    }
}

@Composable
private fun PlaybackPage(app: AppState, show: ((@Composable () -> Unit)?) -> Unit) {
    val user by app.store.user.collectAsState()
    Column {
        app.player.configProblem?.let { Note(it, warning = true) }
        SettingGroup("Bild")
        val rateNow = remember { if (DisplayRate.supported) DisplayRate.current() else 0 }
        ToggleRow(
            "Bildwiederholrate anpassen", user.matchFrameRate,
            "Empfohlen: An. Deutsches TV läuft mit 50 Hz, Filme mit 23,976 Hz – der Bildschirm läuft sonst meist mit 60 Hz, " +
                "das ruckelt bei Schwenks. Beim Umschalten ist das Bild 1–2 s schwarz (nur wenn sich die Rate ändert). " +
                "Die Rate jedes Senders und Films wird gemerkt; beim Beenden des Players kommt die alte Rate zurück." +
                if (DisplayRate.supported) " Bildschirm jetzt: $rateNow Hz." else " Nur unter Windows.",
            enabled = DisplayRate.supported,
        ) { on -> app.updateSettings { it.copy(matchFrameRate = on) } }
        ChoiceRow(
            app, "Halbbilder (Deinterlacing)", user.deinterlace, Deinterlace.entries,
            "Deutsches TV sendet in Halbbildern (1080i, 576i). „Immer“ setzt sie zu 50 ganzen Bildern pro Sekunde zusammen – am flüssigsten.", show,
        ) { v -> app.updateSettings { it.copy(deinterlace = v) } }
        ChoiceRow(app, "Bildformat", user.pictureFit, PictureFit.entries, "Füllen schneidet schwarze Ränder ab, Strecken verzerrt.", show) { v ->
            app.updateSettings { it.copy(pictureFit = v) }
        }
        ToggleRow(
            "Hardware-Dekodierung", user.hardwareDecoding,
            "Die Grafikkarte entschlüsselt das Video (weniger Last, flüssiges 4K/HEVC). Ausschalten, falls Bildfehler oder grüne Flächen auftreten.",
        ) { on -> app.updateSettings { it.copy(hardwareDecoding = on) } }
        ToggleRow("Sender und Filme im Vollbild starten", user.fullscreenPlayback, "Im Player jederzeit: Doppelklick, F oder F11. Esc verlässt das Vollbild.") { on ->
            app.updateSettings { it.copy(fullscreenPlayback = on) }
        }

        SettingGroup("Stream")
        ChoiceRow(app, "Puffer", user.buffer, Buffer.entries, "Schnell = kürzeste Umschaltzeit. Stabil = für 4K und schwankende Leitungen.", show) { v ->
            app.updateSettings { it.copy(buffer = v) }
        }
        ChoiceRow(app, "Live-Format (Xtream)", user.liveFormat, LiveFormat.entries, "MPEG-TS startet schneller. HLS ist robuster, falls der Anbieter TS drosselt.", show) { v ->
            app.updateSettings { it.copy(liveFormat = v) }
        }
        var agent by remember { mutableStateOf(user.userAgent) }
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Field("User-Agent (leer = Standard; manche Anbieter verlangen einen bestimmten)", agent, { v -> agent = v; app.store.updateUser { it.copy(userAgent = v.trim()) } })
        }

        SettingGroup("Ton")
        ChoiceRow(
            app, "Tonausgabe", user.audioOutput, AudioOutput.entries,
            "Automatisch: Windows mischt den Ton. Durchreichen: Dolby/DTS gehen unverändert an Soundbar oder Receiver (in Windows muss die Ausgabe das können).", show,
        ) { v -> app.updateSettings { it.copy(audioOutput = v) } }
        ChoiceRow(app, "Klang", user.audioMode, AudioMode.entries, "Nachtmodus: laute Stellen leiser, leise lauter. Dialoge verstärken: Sprache lauter als Musik und Effekte.", show) { v ->
            app.updateSettings { it.copy(audioMode = v) }
        }
        ToggleRow("Lautstärke angleichen", user.normalizeVolume, "Laute Werbung und leise Sender kommen auf ähnliche Lautstärke.") { on ->
            app.updateSettings { it.copy(normalizeVolume = on) }
        }
        SettingRow(
            "Ton-Versatz (Lippensynchron)", delayLabel(user.audioDelayMs),
            "Kommt der Ton zu früh oder zu spät (z. B. mit Soundbar)? Hier verschieben; gilt sofort und für alle Sender.",
        ) { show { DelayDialog(app, onDismiss = { show(null) }) } }
        val languages = listOf("" to "Wie im Stream") + LANGUAGES
        SettingRow("Bevorzugte Tonsprache", languages.firstOrNull { it.first == user.audioLanguage }?.second ?: user.audioLanguage, "Hat ein Film mehrere Tonspuren, startet diese.") {
            show {
                MenuDialog(app, "Bevorzugte Tonsprache", languages.map { (code, name) ->
                    MenuItem((if (code == user.audioLanguage) "✓  " else "     ") + name) { app.updateSettings { it.copy(audioLanguage = code) } }
                }, onDismiss = { show(null) })
            }
        }

        SettingGroup("Serien")
        ToggleRow("Nächste Folge automatisch", user.autoNextEpisode, "Am Ende einer Folge startet gleich die nächste.") { on -> app.updateSettings { it.copy(autoNextEpisode = on) } }
        ToggleRow("Intro automatisch überspringen", user.autoSkipIntro, "Sobald die App das Intro einer Serie kennt (einmal mit „Intro überspringen“ gelernt), springt sie selbst darüber.") { on ->
            app.updateSettings { it.copy(autoSkipIntro = on) }
        }
    }
}

private fun delayLabel(ms: Long) = if (ms == 0L) "Keiner" else "%+.2f s".format(java.util.Locale.GERMAN, ms / 1000.0)

/** Ton-Versatz in Schritten von 50 ms (gilt sofort). */
@Composable
fun DelayDialog(app: AppState, onDismiss: () -> Unit) {
    val user by app.store.user.collectAsState()
    fun change(delta: Long) = app.updateSettings { it.copy(audioDelayMs = if (delta == 0L) 0 else (it.audioDelayMs + delta).coerceIn(-5_000, 5_000)) }
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss, width = 480) {
        Column {
            Label("Ton-Versatz", Type.heading)
            Label("Plus = Ton später (wenn er vor dem Bild kommt), Minus = Ton früher.", Type.small, maxLines = 2)
            Spacer(Modifier.height(12.dp))
            Label(delayLabel(user.audioDelayMs), Type.title)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("−0,25 s", { change(-250) })
                Spacer(Modifier.width(6.dp))
                TextButton("−0,05 s", { change(-50) }, focusRequester = first)
                Spacer(Modifier.width(6.dp))
                TextButton("+0,05 s", { change(50) })
                Spacer(Modifier.width(6.dp))
                TextButton("+0,25 s", { change(250) })
            }
            Spacer(Modifier.height(10.dp))
            Row {
                TextButton("Zurücksetzen", { change(0) })
                Spacer(Modifier.width(6.dp))
                TextButton("Fertig", onDismiss, primary = true)
            }
        }
    }
    FocusOnShow(first)
}

@Composable
private fun SubtitlesPage(app: AppState, show: ((@Composable () -> Unit)?) -> Unit) {
    val user by app.store.user.collectAsState()
    Column {
        SettingGroup("Sprache")
        ToggleRow(
            "Automatisch einschalten", user.subtitlesAuto,
            "Aus: Untertitel nur bei Bedarf im Player (U oder 💬). An: eingebaute Untertitel in der Sprache starten mit; " +
                "fehlen sie bei Filmen, holt die App welche aus dem Internet.",
        ) { on -> app.updateSettings { it.copy(subtitlesAuto = on) } }
        SettingRow("Bevorzugte Sprache", languageName(user.subtitleLanguage.ifBlank { "de" }), "Für eingebaute Untertitel und die Suche im Internet.") {
            show {
                MenuDialog(app, "Untertitel-Sprache", LANGUAGES.map { (code, name) ->
                    MenuItem((if (code == user.subtitleLanguage) "✓  " else "     ") + name) { app.updateSettings { it.copy(subtitleLanguage = code) } }
                }, onDismiss = { show(null) })
            }
        }

        SettingGroup("Aussehen")
        SubtitlePreview(user)
        ChoiceRow(app, "Größe", user.subtitleSize, SubtitleSize.entries, "Gut lesbar aus Sofa-Entfernung.", show) { v -> app.updateSettings { it.copy(subtitleSize = v) } }
        ChoiceRow(app, "Schriftfarbe", user.subtitleColor, SubtitleColor.entries, "Gelb ist auf hellen Bildern besser lesbar.", show) { v -> app.updateSettings { it.copy(subtitleColor = v) } }
        ChoiceRow(app, "Hintergrund", user.subtitleBackground, SubtitleBackground.entries, "Rand, Schatten oder ein dunkler Kasten hinter der Schrift.", show) { v ->
            app.updateSettings { it.copy(subtitleBackground = v) }
        }
        ChoiceRow(app, "Position", user.subtitlePosition, SubtitlePosition.entries, "Etwas höher, wenn der Bildschirm den unteren Rand abschneidet.", show) { v ->
            app.updateSettings { it.copy(subtitlePosition = v) }
        }

        SettingGroup("Aus dem Internet")
        ToggleRow(
            "Am Ton ausrichten", user.subtitleAlign,
            "Untertitel aus dem Internet passen oft nicht genau. Die App vergleicht die Sprechpausen mit den Einblendungen " +
                "und schiebt sie nach rund 1,5 Minuten Gespräch passend (auch bei anderer Bildrate). Wird je Film gemerkt.",
        ) { on -> app.updateSettings { it.copy(subtitleAlign = on) } }
        var osKey by remember { mutableStateOf(user.openSubtitlesKey) }
        var osCheck by remember { mutableStateOf<String?>(null) }
        KeyCard(
            "OpenSubtitles", "Eingebaut, kein Konto nötig. Eigener API-Schlüssel (optional) ersetzt den eingebauten.",
            "Eigener API-Schlüssel (optional)", osKey, { v -> osKey = v; app.store.updateUser { it.copy(openSubtitlesKey = v.trim()) } },
            osCheck, "Zugang prüfen",
        ) {
            osCheck = "Prüfe …"
            app.scope.launch { osCheck = runCatching { app.subtitles.checkOpenSubtitles(app.store.user.value.openSubtitlesKey) }.getOrElse { "✗ ${it.message}" } }
        }
        ToggleRow(
            "Automatische Übersetzung (DeepL)", user.translateSubtitles,
            "Gibt es keine Untertitel in der Sprache, werden die englischen übersetzt. Braucht einen DeepL-Schlüssel " +
                "(DeepL API Free: 500.000 Zeichen im Monat kostenlos, deepl.com → API-Konto).",
        ) { on -> app.updateSettings { it.copy(translateSubtitles = on) } }
        var deepl by remember { mutableStateOf(user.deeplKey) }
        var deeplCheck by remember { mutableStateOf<String?>(null) }
        KeyCard(
            "DeepL", "Der Schlüssel kommt auch mit der Sicherung vom Fire TV.",
            "DeepL-API-Schlüssel", deepl, { v -> deepl = v; app.store.updateUser { it.copy(deeplKey = v.trim()) } },
            deeplCheck, "DeepL prüfen",
        ) {
            deeplCheck = "Prüfe …"
            app.scope.launch { deeplCheck = runCatching { app.subtitles.deeplUsage(app.store.user.value.deeplKey) }.getOrElse { "✗ ${it.message}" } }
        }
    }
}

/** Beispielzeile im eingestellten Aussehen. */
@Composable
private fun SubtitlePreview(user: UserData) {
    val color = Color(0xFF000000 or user.subtitleColor.rgb.toLong())
    val size = (22 * user.subtitleSize.percent / 100).sp
    val shadow = when (user.subtitleBackground) {
        SubtitleBackground.OUTLINE -> Shadow(Color.Black, Offset(1.5f, 1.5f), 3f)
        SubtitleBackground.SHADOW -> Shadow(Color.Black, Offset(4f, 4f), 6f)
        SubtitleBackground.BOX -> null
    }
    val bottom = when (user.subtitlePosition) {
        SubtitlePosition.LOW -> 6.dp
        SubtitlePosition.NORMAL -> 16.dp
        SubtitlePosition.HIGH -> 40.dp
    }
    Box(
        Modifier.fillMaxWidth().height(150.dp).padding(vertical = 4.dp)
            .background(Brush.linearGradient(listOf(Color(0xFF2B4B6F), Color(0xFF6B8E5A), Color(0xFFC9B37E))), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val style = TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Medium, shadow = shadow)
        Box(
            Modifier.padding(bottom = bottom)
                .background(if (user.subtitleBackground == SubtitleBackground.BOX) Color(0xAA000000) else Color.Transparent, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Label("So sehen die Untertitel aus.", style) }
    }
}

/** Schlüssel eingeben und prüfen. */
@Composable
private fun KeyCard(
    title: String,
    description: String,
    label: String,
    value: String,
    onChange: (String) -> Unit,
    result: String?,
    checkLabel: String,
    onCheck: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.surface, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Label(title, Type.body)
        Label(description, Type.small, maxLines = 3)
        Row(verticalAlignment = Alignment.Bottom) {
            Field(label, value, onChange, Modifier.weight(1f), secret = true)
            Spacer(Modifier.width(8.dp))
            TextButton(checkLabel, onCheck, Modifier.padding(bottom = 4.dp))
        }
        result?.let { Label(it, Type.small.copy(color = if (it.startsWith("✗")) Palette.live else Palette.accent), maxLines = 3) }
    }
}

@Composable
private fun DisplayPage(app: AppState, show: ((@Composable () -> Unit)?) -> Unit) {
    val user by app.store.user.collectAsState()
    Column {
        SettingGroup("Aussehen")
        ChoiceRow(
            app, "Design", user.theme, Theme.entries,
            "Nordlicht: Nachthimmel mit Polarlicht in Türkis und Violett. Hannover 96: Stadionabend in Rot und Schwarz. Standard: schlichtes Dunkelblau.", show,
        ) { v -> app.updateSettings { it.copy(theme = v) } }
        ToggleRow("Maximiert starten", user.startMaximized, "Das Fenster öffnet sich über den ganzen Bildschirm (mit Taskleiste).") { on ->
            app.updateSettings { it.copy(startMaximized = on) }
        }
        ToggleRow("Beim Start den letzten Sender abspielen", user.resumeLastChannel, "Wie ein Fernseher: DnA-TV startet direkt mit dem zuletzt geschauten Sender.") { on ->
            app.updateSettings { it.copy(resumeLastChannel = on) }
        }

        SettingGroup("Bedienung")
        ToggleRow("Hoch/Runter schaltet um", user.zapWithUpDown, "Im Live-TV: Pfeil hoch/runter wechselt den Sender. Aus: ändert die Lautstärke (Sender dann mit Bild auf/ab).") { on ->
            app.updateSettings { it.copy(zapWithUpDown = on) }
        }

        SettingGroup("TV-Guide")
        val shift = user.guideShiftHours
        SettingRow("TV-Guide-Zeitverschiebung", if (shift == 0) "Keine" else "%+d Std.".format(shift), "Falls „Jetzt läuft“ nicht stimmt: Zeiten des Anbieters korrigieren. Der Guide wird danach neu geladen.") {
            show {
                MenuDialog(app, "TV-Guide-Zeitverschiebung", (-6..6).map { h ->
                    MenuItem((if (h == shift) "✓  " else "     ") + if (h == 0) "Keine" else "%+d Std.".format(h)) {
                        app.updateSettings { it.copy(guideShiftHours = h) }
                        app.scope.launch { runCatching { app.sync.syncGuide() }.onFailure { app.toast("TV-Guide: ${it.message}") } }
                    }
                }, onDismiss = { show(null) })
            }
        }

        SettingGroup("Handy als Fernbedienung")
        val address = app.remoteAddress
        ToggleRow(
            "Handy als Fernbedienung", address != null,
            if (address != null) "Auf dem Handy im Browser öffnen (gleiches WLAN): $address"
            else "Steuert DnA-TV vom Handy aus: Pfeile, OK, Zurück, Lautstärke, Sender suchen. Keine App nötig.",
        ) { on -> app.setPhoneRemote(on) }

        SettingGroup("Tasten")
        Note(
            "Maus: Klick wählt, Rechtsklick öffnet das Menü, Doppelklick im Bild = Vollbild, Mausrad = Lautstärke.\n" +
                "Tastatur / Bluetooth-Fernbedienung: Pfeiltasten, Enter = OK (lange drücken = Menü), Esc = zurück, F11 = Vollbild.\n" +
                "Im Bild: ↑/↓ Sender, ←/→ spulen, Leertaste Pause, Menütaste oder ☰ = Player-Menü, T Tonspur, U Untertitel, " +
                "L Untertitel laden, G/H Untertitel früher/später, M Ton aus.",
        )
    }
}

@Composable
private fun DataPage(app: AppState, show: ((@Composable () -> Unit)?) -> Unit) {
    var update by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var subtitleBytes by remember { mutableStateOf(-1L) }
    LaunchedEffect(Unit) { subtitleBytes = withContext(Dispatchers.IO) { runCatching { app.subtitles.cacheBytes() }.getOrDefault(0L) } }
    Column {
        SettingGroup("Version")
        val newer = release?.takeIf { Updater.newer(it.version, Updater.version) }
        SettingRow(
            "DnA-TV ${Updater.version}",
            when {
                busy -> update ?: "…"
                newer != null -> "Version ${newer.version} installieren"
                else -> "Nach Updates suchen"
            },
            update ?: "Neue Versionen kommen direkt von GitHub. Das Setup ersetzt die alte Version; Playlists und Einstellungen bleiben.",
        ) {
            if (busy) return@SettingRow
            busy = true
            app.scope.launch {
                val http = app.sync.http
                if (newer == null) {
                    update = "Suche …"
                    runCatching { Updater.latest(http) }
                        .onSuccess { r ->
                            release = r
                            update = if (Updater.newer(r.version, Updater.version)) "Version ${r.version} ist da – noch einmal klicken zum Installieren." else "Aktuell (neueste Version ${r.version})."
                        }
                        .onFailure { update = "Suche fehlgeschlagen: ${it.message}" }
                } else {
                    runCatching {
                        val setup = Updater.download(http, newer) { percent -> update = "Lädt … $percent %" }
                        update = "Starte Setup … DnA-TV schließt sich."
                        Updater.launch(setup)
                        delay(800)
                        app.close()
                        kotlin.system.exitProcess(0)
                    }.onFailure { update = "Update fehlgeschlagen: ${it.message}" }
                }
                busy = false
            }
        }

        SettingGroup("Verlauf")
        SettingRow("Verlauf löschen", description = "Entfernt „Zuletzt gesehen“ und „Weiter schauen“ (Wiedergabestände von Filmen und Folgen).") {
            show {
                ConfirmDialog(app, "Verlauf und Wiedergabestände löschen?", "Löschen", {
                    app.store.updateUser { it.copy(recent = emptyList(), positions = emptyMap(), durations = emptyMap()) }
                    app.toast("Verlauf gelöscht")
                }, { show(null) })
            }
        }
        SettingRow("Gemerkte Untertitel-Ausrichtung löschen", "${app.store.user.collectAsState().value.subtitleTimings.size} Filme", "Die Untertitel werden beim nächsten Mal neu am Ton ausgerichtet.") {
            app.store.updateUser { it.copy(subtitleTimings = emptyMap()) }
            app.toast("Ausrichtungen gelöscht")
        }

        SettingGroup("Speicher")
        SettingRow("Gespeicherte Untertitel löschen", if (subtitleBytes < 0) "…" else formatBytes(subtitleBytes), "Untertitel aus dem Internet werden bei Bedarf neu geladen.") {
            app.scope.launch {
                withContext(Dispatchers.IO) { app.subtitles.clearCache() }
                subtitleBytes = 0
                app.toast("Untertitel gelöscht")
            }
        }
        SettingRow("Datenordner öffnen", description = app.store.dir.absolutePath) { openFile(app, app.store.dir) }
        val crash = File(app.store.dir, "absturz.log")
        if (crash.isFile) SettingRow("Absturzprotokoll öffnen", formatBytes(crash.length()), "Bei Problemen diese Datei an den Entwickler schicken.") { openFile(app, crash) }

        SettingGroup("Info")
        Note(
            "DnA-TV ${Updater.version} für Windows · Wiedergabe: VLC ${app.player.vlcVersion ?: "–"} (VideoLAN, LGPL) · " +
                "Untertitel: OpenSubtitles.com · Übersetzung: DeepL · Spielstände: OpenLigaDB",
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(java.util.Locale.GERMAN, bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(java.util.Locale.GERMAN, bytes / 1e6)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes Bytes"
}

private fun openFile(app: AppState, file: File) {
    runCatching { java.awt.Desktop.getDesktop().open(file) }.onFailure { app.toast("Lässt sich nicht öffnen: ${it.message}") }
}

@Composable
private fun PlaylistDialog(app: AppState, original: Playlist, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(original.name) }
    var type by remember { mutableStateOf(original.type) }
    var url by remember { mutableStateOf(original.url) }
    var username by remember { mutableStateOf(original.username.orEmpty()) }
    var password by remember { mutableStateOf(original.password.orEmpty()) }
    var epg by remember { mutableStateOf(original.epgUrl.orEmpty()) }
    val isNew = app.store.playlists.value.none { it.id == original.id }
    Overlay(app, onDismiss, width = 560) {
        Column {
            Label(if (isNew) "Playlist hinzufügen" else "Playlist bearbeiten", Type.heading)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Selectable({ type = PlaylistType.XTREAM }, selected = type == PlaylistType.XTREAM) { Label("Xtream-Zugang") }
                Spacer(Modifier.width(8.dp))
                Selectable({ type = PlaylistType.M3U }, selected = type == PlaylistType.M3U) { Label("M3U-Adresse") }
            }
            Field("Name", name, { name = it })
            Field(if (type == PlaylistType.XTREAM) "Server (z. B. http://anbieter.tv:8080)" else "M3U-Adresse", url, { url = it })
            if (type == PlaylistType.XTREAM) {
                Field("Benutzername", username, { username = it })
                Field("Passwort", password, { password = it }, secret = true)
            }
            Field("TV-Guide-Adresse (optional, XMLTV)", epg, { epg = it })
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("Speichern und laden", {
                    if (url.isBlank()) return@TextButton app.toast("Bitte die Adresse eintragen")
                    val saved = original.copy(
                        name = name.ifBlank { if (type == PlaylistType.XTREAM) "Xtream" else "M3U" }.trim(),
                        type = type,
                        url = url.trim(),
                        username = username.trim().takeIf { type == PlaylistType.XTREAM && it.isNotEmpty() },
                        password = password.takeIf { type == PlaylistType.XTREAM && it.isNotEmpty() },
                        epgUrl = epg.trim().ifEmpty { null },
                    )
                    app.store.savePlaylist(saved)
                    onDismiss()
                    app.scope.launch {
                        runCatching { app.sync.syncPlaylist(saved.id); app.sync.syncGuide() }
                            .onSuccess { app.toast("${saved.name} geladen") }
                            .onFailure { app.toast("${saved.name}: ${it.message}") }
                    }
                }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
}

/** Sicherung übernehmen: direkt vom Fire TV (Adresse aus „Einrichtung per Handy“) oder aus einer Datei. */
@Composable
private fun ImportDialog(app: AppState, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun done(result: Result<de.ayutv.desktop.data.BackupImport.Result>) {
        busy = false
        result.onSuccess { r ->
            onDismiss()
            app.toast(r.summary)
            app.applyProxy()
            app.syncNow()
        }.onFailure { app.toast(it.message ?: "Übernehmen fehlgeschlagen") }
    }

    Overlay(app, onDismiss, width = 600) {
        Column {
            Label("Sicherung vom Fire TV übernehmen", Type.heading)
            Spacer(Modifier.height(6.dp))
            Label(
                "Auf dem Fire TV: Einstellungen → Playlists öffnen (und offen lassen). Im Kasten „Einrichtung per Handy“ steht die " +
                    "Adresse mit Port 8095 und einer Zahl am Ende – die hier eintragen (nicht die der Handy-Fernbedienung). " +
                    "Passwörter von Playlists und Proxy kommen dabei immer mit (verschlüsselt übertragen).",
                Type.small, maxLines = 4,
            )
            Field("Adresse vom Fire TV (z. B. http://192.168.178.40:8095/123456)", address, { address = it })
            Field("Passwort (nur für „Aus Datei“, wenn die Datei eines hat)", password, { password = it }, secret = true)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(if (busy) "Lädt …" else "Vom Fire TV laden", {
                    if (busy || address.isBlank()) return@TextButton
                    busy = true
                    app.scope.launch { done(runCatching { app.backup.fromFireTv(app.sync.http, address) }) }
                }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Aus Datei …", {
                    val file = chooseFile() ?: return@TextButton
                    busy = true
                    app.scope.launch {
                        done(runCatching {
                            val text = withContext(Dispatchers.IO) { file.readText() }
                            app.backup.import(text, password.ifEmpty { null })
                        })
                    }
                })
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
}

private fun chooseFile(): File? {
    val dialog = FileDialog(null as Frame?, "DnA-TV-Sicherung wählen", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

/** Proxy wie auf dem Fire TV: aus, für alles oder nur für ausgewählte Playlists; HTTP oder SOCKS5. */
@Composable
private fun ProxySection(app: AppState, playlists: List<Playlist>) {
    val saved = app.store.user.collectAsState().value.proxy
    var mode by remember(saved) { mutableStateOf(saved.mode) }
    var type by remember(saved) { mutableStateOf(saved.type) }
    var host by remember(saved) { mutableStateOf(saved.host) }
    var port by remember(saved) { mutableStateOf(if (saved.port > 0) saved.port.toString() else "") }
    var user by remember(saved) { mutableStateOf(saved.user) }
    var password by remember(saved) { mutableStateOf(saved.password) }
    var selected by remember(saved) { mutableStateOf(saved.playlists) }
    var testing by remember { mutableStateOf(false) }

    fun current() = ProxySettings(mode, type, host.trim(), port.trim().toIntOrNull() ?: 0, user.trim(), password, selected)

    Column(Modifier.width(560.dp)) {
        Label("Nur für DnA-TV, keine VPN-App nötig. Geräte im Heimnetz (Fire TV, Handy) laufen nie über den Proxy.", Type.small, maxLines = 2)
        Spacer(Modifier.height(6.dp))
        Row {
            listOf("OFF" to "Aus", "ALL" to "Alle Verbindungen", "SELECTED" to "Nur ausgewählte Playlists").forEach { (value, label) ->
                Selectable({ mode = value }, selected = mode == value) { Label(label) }
                Spacer(Modifier.width(6.dp))
            }
        }
        if (mode != "OFF") {
            Row {
                listOf("HTTP", "SOCKS5").forEach { value ->
                    Selectable({ type = value }, selected = type == value) { Label(value) }
                    Spacer(Modifier.width(6.dp))
                }
            }
            Row {
                Field("Server", host, { host = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Field("Port", port, { port = it.filter(Char::isDigit).take(5) }, Modifier.width(110.dp))
            }
            Field("Benutzer (optional)", user, { user = it })
            Field("Passwort (optional)", password, { password = it }, secret = true)
            if (mode == "SELECTED") {
                Label("Über den Proxy:", Type.small, Modifier.padding(top = 6.dp))
                playlists.forEach { p ->
                    val on = p.id in selected
                    ListRow((if (on) "☑ " else "☐ ") + p.name, { selected = if (on) selected - p.id else selected + p.id })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton("Speichern", {
                val next = current()
                if (next.mode != "OFF" && (next.host.isEmpty() || next.port !in 1..65535)) return@TextButton app.toast("Bitte Server und Port eintragen")
                app.store.updateUser { it.copy(proxy = next) }
                app.applyProxy()
                app.toast(if (next.mode == "OFF") "Proxy aus" else "Proxy gespeichert. Gilt ab dem nächsten Sender.")
            }, primary = true)
            Spacer(Modifier.width(8.dp))
            TextButton(if (testing) "Teste …" else "Testen", {
                if (testing) return@TextButton
                testing = true
                app.scope.launch {
                    val result = ProxyCheck.run(Network.http, current().config())
                    testing = false
                    app.toast(result.summary())
                }
            })
        }
    }
}
