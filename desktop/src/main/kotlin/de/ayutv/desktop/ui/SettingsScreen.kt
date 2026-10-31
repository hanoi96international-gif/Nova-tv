package de.ayutv.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ayutv.data.net.ProxyCheck
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.ProxySettings
import de.ayutv.desktop.data.config
import de.ayutv.desktop.data.PlaylistType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault())

/** Playlists, Sicherung vom Fire TV, TV-Guide, Schlüssel für Untertitel und Übersetzung. */
@Composable
fun SettingsScreen(app: AppState) {
    val playlists by app.store.playlists.collectAsState()
    val user by app.store.user.collectAsState()
    val content by app.store.content.collectAsState()
    var editing by remember { mutableStateOf<Playlist?>(null) }
    var importing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        item { Label("Einstellungen", Type.title, Modifier.padding(bottom = 12.dp)) }

        item { Section("Playlists") }
        items(playlists, key = { it.id }) { p ->
            val c = content[p.id]
            val counts = c?.let { "${it.channels.size} Sender · ${it.movies.size} Filme · ${it.series.size} Serien" } ?: "noch nicht geladen"
            val synced = if (p.lastSync > 0) " · geladen ${DATE_TIME.format(Instant.ofEpochMilli(p.lastSync))}" else ""
            ListRow(
                title = (if (p.enabled) "" else "⏸ ") + p.name,
                subtitle = "${if (p.type == PlaylistType.XTREAM) "Xtream" else "M3U"} · $counts$synced",
                onClick = { editing = p },
                onMenu = {
                    dialog = {
                        MenuDialog(app, p.name, listOf(
                            MenuItem("Bearbeiten") { editing = p },
                            MenuItem("Jetzt neu laden") { app.scope.launch { runCatching { app.sync.syncPlaylist(p.id) }.onFailure { app.toast("${p.name}: ${it.message}") } } },
                            MenuItem(if (p.enabled) "Ausschalten" else "Einschalten") { app.store.savePlaylist(p.copy(enabled = !p.enabled)) },
                            MenuItem("Löschen") {
                                dialog = { ConfirmDialog(app, "Playlist „${p.name}“ löschen?", "Löschen", { app.store.deletePlaylist(p.id) }, { dialog = null }) }
                            },
                        ), onDismiss = { if (dialog != null) dialog = null })
                    }
                },
            )
        }
        item {
            Row(Modifier.padding(vertical = 8.dp)) {
                TextButton("＋ Playlist hinzufügen", { editing = Playlist(app.store.nextPlaylistId(), "", PlaylistType.XTREAM, "") }, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("⟳ Alle neu laden", { app.syncNow() })
            }
        }

        item { Section("Vom Fire TV übernehmen") }
        item {
            Label("Playlists, Favoriten, eigene Gruppen, Reihenfolgen und Schlüssel aus der Fire-TV-App übernehmen.", Type.small, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TextButton("Sicherung übernehmen …", { importing = true })
        }

        item { Section("TV-Guide") }
        item {
            val guideCount = app.store.guide.collectAsState().value.size
            Label("Programm für $guideCount Sender geladen. Eigene Adresse (XMLTV) je Playlist unter „Bearbeiten“.", Type.small, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            TextButton("TV-Guide neu laden", { app.scope.launch { runCatching { app.sync.syncGuide() }.onFailure { app.toast("TV-Guide: ${it.message}") } } })
        }

        item { Section("Proxy") }
        item { ProxySection(app, playlists) }

        item { Section("Untertitel und Übersetzung") }
        item {
            Column(Modifier.width(520.dp)) {
                Field("Eigener OpenSubtitles-Schlüssel (optional, sonst der der App)", user.openSubtitlesKey, { v -> app.store.updateUser { it.copy(openSubtitlesKey = v.trim()) } }, secret = true)
                Field("DeepL-Schlüssel", user.deeplKey, { v -> app.store.updateUser { it.copy(deeplKey = v.trim()) } }, secret = true)
                Field("Untertitel-Sprache (z. B. de)", user.subtitleLanguage, { v -> app.store.updateUser { it.copy(subtitleLanguage = v.trim().lowercase().take(5)) } })
            }
        }

        item { Section("Handy als Fernbedienung") }
        item {
            val address = app.remoteAddress
            Label(
                if (address != null) "Auf dem Handy im Browser öffnen (gleiches WLAN): $address"
                else "Steuert DnA-TV vom Handy aus: Pfeile, OK, Zurück, Lautstärke, Sender suchen. Keine App nötig.",
                if (address != null) Type.heading else Type.small, maxLines = 3,
            )
            Spacer(Modifier.height(6.dp))
            TextButton(if (address != null) "Ausschalten" else "Einschalten", { app.setPhoneRemote(address == null) })
        }

        item { Section("Bild") }
        item {
            ListRow(
                (if (user.fullscreenPlayback) "☑" else "☐") + "  Sender und Filme im Vollbild starten",
                onClick = { app.store.updateUser { it.copy(fullscreenPlayback = !it.fullscreenPlayback) } },
                subtitle = "Vollbild jederzeit: F11, Doppelklick ins Bild oder „⛶ Vollbild“ links unten. Esc verlässt das Vollbild.",
            )
        }

        item { Section("Bedienung") }
        item {
            Label(
                "Maus: Klick wählt, Rechtsklick öffnet das Menü, Doppelklick im Bild = Vollbild, Mausrad = Lautstärke.\n" +
                    "Tastatur / Bluetooth-Fernbedienung: Pfeiltasten, Enter = OK (lange drücken = Menü), Esc = zurück, F11 = Vollbild.\n" +
                    "Im Bild: ↑/↓ Sender, ←/→ spulen, Leertaste Pause, T Tonspur, U Untertitel, L Untertitel laden, G/H Untertitel früher/später, M Ton aus.",
                Type.small, maxLines = 6,
            )
            Spacer(Modifier.height(8.dp))
            Label("Daten: ${app.store.dir.absolutePath}", Type.small)
            Spacer(Modifier.height(40.dp))
        }
    }

    editing?.let { p -> PlaylistDialog(app, p, onDismiss = { editing = null }) }
    if (importing) ImportDialog(app, onDismiss = { importing = false })
    dialog?.invoke()
}

@Composable
private fun Section(title: String) {
    Label(title, Type.heading, Modifier.padding(top = 18.dp, bottom = 6.dp))
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
                    "Mit Sicherungs-Passwort werden auch die Playlist-Passwörter übernommen.",
                Type.small, maxLines = 4,
            )
            Field("Adresse vom Fire TV (z. B. http://192.168.178.40:8095/123456)", address, { address = it })
            Field("Sicherungs-Passwort", password, { password = it }, secret = true)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(if (busy) "Lädt …" else "Vom Fire TV laden", {
                    if (busy || address.isBlank()) return@TextButton
                    busy = true
                    app.scope.launch { done(runCatching { app.backup.fromFireTv(app.sync.http, address, password) }) }
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
