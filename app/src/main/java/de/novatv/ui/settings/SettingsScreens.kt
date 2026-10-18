package de.novatv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.novatv.BuildConfig
import de.novatv.data.AppSettings
import de.novatv.data.BufferProfile
import de.novatv.data.LiveFormat
import de.novatv.data.Languages
import de.novatv.data.TranslationProvider
import de.novatv.data.SubtitleSize
import de.novatv.data.db.PlaylistEntity
import de.novatv.data.db.PlaylistType
import de.novatv.ui.LocalContainer
import de.novatv.ui.components.SectionTitle
import de.novatv.ui.components.SyncBanner
import de.novatv.ui.components.TvTextField
import de.novatv.ui.theme.NovaColors
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(onAddPlaylist: () -> Unit, onManageGroups: (PlaylistEntity) -> Unit) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val sync by container.playlists.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column {
                Text("Einstellungen", style = MaterialTheme.typography.headlineMedium)
                SyncBanner(sync, Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(16.dp))
                SectionTitle("Playlists")
            }
        }
        items(playlists, key = { it.id }) { playlist ->
            PlaylistRow(
                playlist = playlist,
                confirmingDelete = confirmDelete == playlist.id,
                onGroups = { onManageGroups(playlist) },
                onRefresh = { container.scope.launch { container.playlists.sync(playlist.id) } },
                onDelete = {
                    if (confirmDelete == playlist.id) {
                        confirmDelete = null
                        container.scope.launch { container.playlists.delete(playlist.id) }
                    } else {
                        confirmDelete = playlist.id
                    }
                },
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = onAddPlaylist) { Text("+ Playlist hinzufügen") }
                OutlinedButton(onClick = { container.scope.launch { container.playlists.syncAll(force = true) } }) {
                    Text("Alles aktualisieren")
                }
            }
        }

        item { SectionTitle("Wiedergabe", Modifier.padding(top = 20.dp)) }
        item {
            SettingRow(
                "Puffer",
                settings.bufferProfile.label,
                "Schnell = kürzeste Umschaltzeit. Stabil = für 4K und schwankende Leitungen.",
            ) { update { it.copy(bufferProfile = it.bufferProfile.next()) } }
        }
        item {
            SettingRow(
                "Tunneling-Modus",
                onOff(settings.tunneling),
                "Video geht direkt vom Decoder zum Display. Hilft oft gegen Ruckeln bei 4K auf Fire TV. Bei Tonaussetzern wieder ausschalten.",
            ) { update { it.copy(tunneling = !it.tunneling) } }
        }
        item {
            SettingRow(
                "Bildwiederholrate anpassen",
                onOff(settings.matchFrameRate),
                "Schaltet den Fernseher auf 50 Hz bzw. 23,976 Hz passend zum Video. Beseitigt Ruckeln bei Kameraschwenks.",
            ) { update { it.copy(matchFrameRate = !it.matchFrameRate) } }
        }
        item {
            SettingRow(
                "Live-Format (Xtream)",
                settings.liveFormat.label,
                "MPEG-TS startet schneller. HLS ist robuster, falls der Anbieter TS drosselt.",
            ) { update { it.copy(liveFormat = if (it.liveFormat == LiveFormat.TS) LiveFormat.HLS else LiveFormat.TS) } }
        }
        item {
            SettingRow(
                "Hoch/Runter schaltet um",
                onOff(settings.zapWithUpDown),
                "Aus: Hoch/Runter öffnet stattdessen die Senderliste.",
            ) { update { it.copy(zapWithUpDown = !it.zapWithUpDown) } }
        }
        item {
            UserAgentRow(settings.userAgent) { ua -> update { it.copy(userAgent = ua) } }
        }

        item { SectionTitle("Untertitel (Filme & Serien)", Modifier.padding(top = 20.dp)) }
        item {
            SettingRow(
                "Automatisch einschalten",
                onOff(settings.subtitlesAutoOn),
                "Aus: Untertitel nur bei Bedarf im Film über die Menütaste ☰. An: bevorzugte Sprache startet mit.",
            ) { update { it.copy(subtitlesAutoOn = !it.subtitlesAutoOn) } }
        }
        item {
            SettingRow(
                "Bevorzugte Sprache",
                Languages.label(settings.subtitleLanguage),
                "Wird beim Einschalten zuerst gewählt. Jede andere Sprache geht im Film über ☰ → Andere Sprache.",
            ) { update { it.copy(subtitleLanguage = nextLanguage(it.subtitleLanguage)) } }
        }
        item {
            SettingRow("Größe", settings.subtitleSize.label, "Gut lesbar aus Sofa-Entfernung: weiße Schrift mit schwarzem Rand.") {
                update { it.copy(subtitleSize = it.subtitleSize.next()) }
            }
        }
        item {
            CredentialField(
                current = settings.openSubtitlesKey,
                label = "OpenSubtitles API-Key (kostenlos auf opensubtitles.com → Konto → API-Consumer)",
            ) { key -> update { it.copy(openSubtitlesKey = key) } }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CredentialField(settings.openSubtitlesUser, "Benutzername (optional, mehr Downloads pro Tag)", Modifier.width(320.dp)) { user ->
                    update { it.copy(openSubtitlesUser = user) }
                }
                CredentialField(settings.openSubtitlesPassword, "Passwort (optional)", Modifier.width(260.dp), password = true) { pw ->
                    update { it.copy(openSubtitlesPassword = pw) }
                }
            }
        }

        item {
            SettingRow(
                "Automatische Übersetzung",
                settings.translationProvider.label,
                "Gibt es keine Untertitel in der Wunschsprache, wird eine vorhandene Datei übersetzt. Auch eingebettete Spuren lassen sich live übersetzen.",
            ) { update { it.copy(translationProvider = it.translationProvider.next()) } }
        }
        if (settings.translationProvider == TranslationProvider.DEEPL) {
            item {
                CredentialField(settings.deeplKey, "DeepL API-Key (kostenlos auf deepl.com/pro-api, 500.000 Zeichen/Monat)") { key ->
                    update { it.copy(deeplKey = key) }
                }
            }
        }
        if (settings.translationProvider == TranslationProvider.LIBRE) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CredentialField(settings.libreUrl, "LibreTranslate-Server, z. B. https://libretranslate.com", Modifier.width(380.dp)) { url ->
                        update { it.copy(libreUrl = url) }
                    }
                    CredentialField(settings.libreKey, "API-Key (falls nötig)", Modifier.width(260.dp)) { key ->
                        update { it.copy(libreKey = key) }
                    }
                }
            }
        }

        item { SectionTitle("Daten", Modifier.padding(top = 20.dp)) }
        item {
            SettingRow("Verlauf löschen", "", "Entfernt „Zuletzt gesehen“, „Weiter schauen“ und die Empfehlungen.") {
                scope.launch { container.content.clearHistory() }
            }
        }
        item {
            Text("NovaTV ${BuildConfig.VERSION_NAME}", color = NovaColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }

    BackHandler(enabled = confirmDelete != null) { confirmDelete = null }
}

private fun onOff(value: Boolean) = if (value) "An" else "Aus"

private fun BufferProfile.next(): BufferProfile = BufferProfile.entries[(ordinal + 1) % BufferProfile.entries.size]
private fun TranslationProvider.next(): TranslationProvider = TranslationProvider.entries[(ordinal + 1) % TranslationProvider.entries.size]

private fun nextLanguage(code: String): String {
    val index = Languages.all.indexOfFirst { it.code == code }
    return Languages.all[(index + 1) % Languages.all.size].code
}
private fun SubtitleSize.next(): SubtitleSize = SubtitleSize.entries[(ordinal + 1) % SubtitleSize.entries.size]

/** Speichert bei jeder Eingabe: Auf Fire TV schließt man die Tastatur oft mit "Zurück" statt "Fertig". */
@Composable
private fun CredentialField(
    current: String,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(0.8f),
    password: Boolean = false,
    onSave: (String) -> Unit,
) {
    // Bis zur ersten Eingabe den gespeicherten Wert zeigen, danach nur noch die eigene Eingabe.
    var edited by remember { mutableStateOf<String?>(null) }
    val value = edited ?: current
    TvTextField(
        value = value,
        onValueChange = {
            edited = it
            onSave(it)
        },
        label = label,
        password = password,
        imeAction = ImeAction.Done,
        modifier = modifier.padding(vertical = 6.dp),
    )
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistEntity,
    confirmingDelete: Boolean,
    onGroups: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium)
            val type = if (playlist.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U"
            val synced = if (playlist.lastSync > 0) {
                "aktualisiert " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(playlist.lastSync))
            } else {
                "noch nicht geladen"
            }
            Text("$type · $synced", color = NovaColors.TextSecondary, fontSize = 13.sp)
        }
        OutlinedButton(onClick = onGroups) { Text("Gruppen") }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(onClick = onRefresh) { Text("Aktualisieren") }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = onDelete,
            colors = if (confirmingDelete) ButtonDefaults.colors(containerColor = NovaColors.Live) else ButtonDefaults.colors(),
        ) { Text(if (confirmingDelete) "Wirklich löschen?" else "Löschen") }
    }
}

@Composable
private fun SettingRow(title: String, value: String, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = NovaColors.Surface, focusedContainerColor = NovaColors.AccentSoft),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                Text(description, color = NovaColors.TextSecondary, fontSize = 12.sp)
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Text(value, color = NovaColors.Accent, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun UserAgentRow(current: String, onChange: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    Column(Modifier.padding(vertical = 6.dp)) {
        TvTextField(
            value = value,
            onValueChange = { value = it },
            label = "User-Agent (manche Anbieter verlangen einen bestimmten)",
            imeAction = ImeAction.Done,
            onImeAction = { onChange(value.ifBlank { AppSettings.DEFAULT_USER_AGENT }) },
            modifier = Modifier.fillMaxWidth(0.8f),
        )
    }
}

/** Playlist anlegen: M3U-Link oder Xtream-Zugang. Xtream wird vor dem Speichern geprüft. */
@Composable
fun AddPlaylistScreen(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(PlaylistType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var epgUrl by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val firstField = remember { FocusRequester() }

    if (onCancel != null) BackHandler(onBack = onCancel)

    fun save() {
        error = null
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) {
            error = if (type == PlaylistType.XTREAM) "Bitte die Server-Adresse eingeben." else "Bitte den Playlist-Link eingeben."
            return
        }
        if (type == PlaylistType.XTREAM && (username.isBlank() || password.isBlank())) {
            error = "Bitte Benutzername und Passwort eingeben."
            return
        }
        busy = true
        scope.launch {
            if (type == PlaylistType.XTREAM) {
                val problem = container.playlists.verifyXtream(trimmedUrl, username.trim(), password.trim())
                if (problem != null) {
                    error = problem
                    busy = false
                    return@launch
                }
            }
            val id = container.playlists.add(
                PlaylistEntity(
                    name = name.trim().ifEmpty { if (type == PlaylistType.XTREAM) "Xtream" else "Playlist" },
                    type = type,
                    url = if (type == PlaylistType.XTREAM) de.novatv.data.repo.XtreamClient.normalizeServer(trimmedUrl) else trimmedUrl,
                    epgUrl = epgUrl.trim().ifEmpty { null },
                    username = username.trim().ifEmpty { null },
                    password = password.trim().ifEmpty { null },
                )
            )
            // Laden im App-Scope: läuft weiter, auch wenn dieser Bildschirm schon geschlossen ist.
            container.scope.launch { container.playlists.sync(id) }
            busy = false
            onDone()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 96.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Playlist hinzufügen", style = MaterialTheme.typography.headlineMedium)
        Text(
            "NovaTV ist ein Player: Du brauchst einen Zugang von deinem IPTV-Anbieter.",
            color = NovaColors.TextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeButton("Xtream Codes", type == PlaylistType.XTREAM) { type = PlaylistType.XTREAM }
            TypeButton("M3U-Link", type == PlaylistType.M3U) { type = PlaylistType.M3U }
        }
        TvTextField(name, { name = it }, "Name (optional)", focusRequester = firstField)
        if (type == PlaylistType.XTREAM) {
            TvTextField(url, { url = it }, "Server, z. B. http://anbieter.tv:8080", keyboardType = KeyboardType.Uri)
            TvTextField(username, { username = it }, "Benutzername")
            TvTextField(password, { password = it }, "Passwort", password = true)
        } else {
            TvTextField(url, { url = it }, "Playlist-Link (M3U/M3U8)", keyboardType = KeyboardType.Uri)
        }
        TvTextField(
            epgUrl,
            { epgUrl = it },
            if (type == PlaylistType.XTREAM) "EPG-Link (optional, sonst vom Anbieter)" else "EPG-Link XMLTV (optional, sonst aus der Playlist)",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            onImeAction = { save() },
        )
        error?.let { Text(it, color = NovaColors.Live) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (!busy) save() }) { Text(if (busy) "Wird geprüft …" else "Speichern und laden") }
            if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
}

@Composable
private fun TypeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
}
