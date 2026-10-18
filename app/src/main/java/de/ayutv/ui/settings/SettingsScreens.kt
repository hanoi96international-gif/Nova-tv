package de.ayutv.ui.settings

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import de.ayutv.data.AudioOutput
import de.ayutv.player.AudioDecoders
import de.ayutv.data.BufferProfile
import de.ayutv.data.LiveFormat
import de.ayutv.data.Languages
import de.ayutv.data.TranslationProvider
import de.ayutv.data.SubtitleSize
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.SyncBanner
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.Job
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import de.ayutv.data.setup.KeyCheck
import de.ayutv.data.setup.PhoneSetupServer
import de.ayutv.ui.components.QrCode
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
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
    var pickingLanguage by remember { mutableStateOf(false) }
    var epgReloadJob by remember { mutableStateOf<Job?>(null) }
    // Einrichtung per Handy: Webseite im Heimnetz, solange die Einstellungen offen sind.
    val phoneSetup = remember { PhoneSetupServer(container) }
    val phone by phoneSetup.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        phoneSetup.start()
        onDispose { phoneSetup.stop() }
    }
    var keyReport by remember { mutableStateOf<String?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { container.settings.update(transform) }
    }

    if (pickingLanguage) {
        BackHandler { pickingLanguage = false }
        LanguageList(selected = settings.subtitleLanguage) { code ->
            update { it.copy(subtitleLanguage = code) }
            pickingLanguage = false
        }
        return
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
                onToggle = { container.scope.launch { container.playlists.setEnabled(playlist.id, !playlist.enabled) } },
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
                "Schnelles Dekodieren (experimentell)",
                onOff(settings.asyncDecoding),
                "Steuert den Hardware-Decoder asynchron an. Nur einschalten, wenn 4K ruckelt; schaltet sich bei Problemen selbst ab.",
            ) { update { it.copy(asyncDecoding = !it.asyncDecoding) } }
        }
        item {
            SettingRow(
                "Tonausgabe",
                settings.audioOutput.label,
                "Automatisch: Dolby/DTS gehen an Soundbar oder Receiver. Bei Stille oder Aussetzern auf „Immer im Gerät dekodieren“ stellen.",
            ) {
                update {
                    it.copy(audioOutput = if (it.audioOutput == AudioOutput.AUTO) AudioOutput.PCM else AudioOutput.AUTO)
                }
            }
        }
        item {
            SettingRow(
                "Zusätzliche Tondecoder (FFmpeg)",
                if (AudioDecoders.ffmpegAvailable) "Aktiv" else "Nicht enthalten",
                "Spielt DTS, Dolby TrueHD, Dolby Digital (Plus) u. a. auch ohne AV-Receiver ab." +
                    (AudioDecoders.ffmpegVersion?.let { " FFmpeg $it." } ?: ""),
            ) {}
        }
        item {
            SettingRow(
                "TV-Guide im Menü",
                onOff(settings.showGuide),
                "Das Programm-Raster. Die Programmdaten (Jetzt läuft, Suche, Empfehlungen) bleiben auch ausgeblendet aktiv.",
            ) { update { it.copy(showGuide = !it.showGuide) } }
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
        item {
            val shift = settings.epgShiftHours
            SettingRow(
                "TV-Guide-Zeitverschiebung",
                if (shift == 0) "Keine" else "%+d Std.".format(shift),
                "Falls „Jetzt läuft“ nicht stimmt: Zeiten des Anbieters korrigieren. Der Guide wird danach neu geladen.",
            ) {
                // Zyklus 0, +1 … +12, −12 … −1
                val next = if (shift >= 12) -12 else shift + 1
                scope.launch {
                    container.settings.update { it.copy(epgShiftHours = next) }
                    epgReloadJob?.cancel()
                    epgReloadJob = container.scope.launch {
                        delay(2_500) // mehrere Klicks abwarten, dann einmal neu laden
                        container.playlists.reloadAllEpg()
                    }
                }
            }
        }

        item { SectionTitle("Untertitel (Filme & Serien)", Modifier.padding(top = 20.dp)) }
        item { PhoneSetupCard(phone, keyReport ?: phone.lastSaved) }
        item {
            SettingRow(
                "Zugänge prüfen",
                "",
                "Testet den OpenSubtitles-Zugang sofort.",
            ) {
                keyReport = "Prüfe …"
                scope.launch {
                    keyReport = KeyCheck.run(container.http, container.settings.current())
                        .joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.text }
                }
            }
        }
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
            ) { pickingLanguage = true }
        }
        item {
            SettingRow("Größe", settings.subtitleSize.label, "Gut lesbar aus Sofa-Entfernung: weiße Schrift mit schwarzem Rand.") {
                update { it.copy(subtitleSize = it.subtitleSize.next()) }
            }
        }
        item {
            val builtIn = BuildConfig.OPENSUBTITLES_API_KEY.isNotBlank()
            SettingRow(
                "Untertitel aus dem Internet",
                onOff(settings.onlineSubtitles),
                if (builtIn) "OpenSubtitles ist eingebaut, kein Konto nötig. Sucht Untertitel, wenn ein Film keine passenden hat."
                else "Sucht bei OpenSubtitles, wenn ein Film keine passenden Untertitel hat (API-Key per Handy eintragen).",
            ) { update { it.copy(onlineSubtitles = !it.onlineSubtitles) } }
        }
        item {
            CredentialField(
                current = settings.openSubtitlesKey,
                label = "Eigener OpenSubtitles API-Key (optional, ersetzt den eingebauten)",
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
                "Hat ein Film keine Untertitel in der Wunschsprache, werden vorhandene direkt auf dem Fire TV übersetzt: " +
                    "ohne Konto und ohne Grenze. Pro Sprache wird einmalig ein Modell (ca. 110 MB) geladen.",
            ) { update { it.copy(translationProvider = it.translationProvider.next()) } }
        }
        item {
            var modelInfo by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                modelInfo = withContext(Dispatchers.IO) {
                    val pairs = Translators.onDevice.installedPairs()
                    if (pairs.isEmpty()) "noch keine geladen" else "${pairs.size} · ${Translators.onDevice.installedBytes() / 1_000_000} MB"
                }
            }
            SettingRow(
                "Sprachmodelle auf dem Gerät",
                modelInfo,
                "Auswählen löscht alle geladenen Modelle (werden bei Bedarf neu geladen). Übersetzung: OPUS-MT, Universität Helsinki, CC BY 4.0.",
            ) {
                scope.launch {
                    Translators.onDevice.deleteAll()
                    modelInfo = "noch keine geladen"
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
            Text("DnA-TV ${BuildConfig.VERSION_NAME}", color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }

    BackHandler(enabled = confirmDelete != null) { confirmDelete = null }
}

private fun onOff(value: Boolean) = if (value) "An" else "Aus"

private fun BufferProfile.next(): BufferProfile = BufferProfile.entries[(ordinal + 1) % BufferProfile.entries.size]
private fun TranslationProvider.next(): TranslationProvider = TranslationProvider.entries[(ordinal + 1) % TranslationProvider.entries.size]

@Composable
private fun LanguageList(selected: String, onSelect: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text("Bevorzugte Untertitelsprache", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 12.dp)) }
        items(Languages.all, key = { it.code }) { language ->
            SettingRow(
                language.label,
                if (language.code == selected) "✓" else "",
                if (Translators.onDevice.supports(language.code)) "" else "nur Untertitel aus dem Internet, keine Übersetzung",
                modifier = if (language.code == selected) Modifier.focusRequester(focus) else Modifier,
            ) { onSelect(language.code) }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
    onToggle: () -> Unit,
    onGroups: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Schalter vorne: Welche Playlists aktiv sind, entscheidet der Nutzer selbst.
        OutlinedButton(
            onClick = onToggle,
            colors = if (playlist.enabled) ButtonDefaults.colors(containerColor = AyuColors.AccentSoft) else ButtonDefaults.colors(),
        ) { Text(if (playlist.enabled) "● Aktiv" else "○ Aus") }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (playlist.enabled) Color.Unspecified else AyuColors.TextSecondary,
            )
            val type = if (playlist.type == PlaylistType.XTREAM) "Xtream Codes" else "M3U"
            val synced = if (playlist.lastSync > 0) {
                "aktualisiert " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(playlist.lastSync))
            } else {
                "noch nicht geladen"
            }
            val state = if (playlist.enabled) "" else " · ausgeschaltet, erscheint nirgends"
            Text("$type · $synced$state", color = AyuColors.TextSecondary, fontSize = 13.sp)
        }
        OutlinedButton(onClick = onGroups) { Text("Gruppen") }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(onClick = onRefresh) { Text("Aktualisieren") }
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = onDelete,
            colors = if (confirmingDelete) ButtonDefaults.colors(containerColor = AyuColors.Live) else ButtonDefaults.colors(),
        ) { Text(if (confirmingDelete) "Wirklich löschen?" else "Löschen") }
    }
}

@Composable
private fun SettingRow(title: String, value: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                if (description.isNotEmpty()) Text(description, color = AyuColors.TextSecondary, fontSize = 12.sp)
            }
            if (value.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Text(value, color = AyuColors.Accent, fontSize = 15.sp)
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
                    url = if (type == PlaylistType.XTREAM) de.ayutv.data.repo.XtreamClient.normalizeServer(trimmedUrl) else trimmedUrl,
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
            "DnA-TV ist ein Player: Du brauchst einen Zugang von deinem IPTV-Anbieter.",
            color = AyuColors.TextSecondary,
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
        error?.let { Text(it, color = AyuColors.Live) }
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

@Composable
private fun PhoneSetupCard(state: PhoneSetupServer.State, report: String?) {
    Row(
        Modifier.fillMaxWidth().background(AyuColors.Surface, RoundedCornerShape(12.dp)).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.url?.let { QrCode(it, size = 150.dp) }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text("Einrichtung per Handy", style = MaterialTheme.typography.titleMedium)
            Text(
                "QR-Code mit dem Handy scannen (gleiches WLAN) und dort Playlist-Adressen oder den OpenSubtitles-Zugang " +
                    "bequem einfügen statt mit der Fernbedienung zu tippen.",
                color = AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            state.url?.let { Text(it, color = AyuColors.Accent, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp)) }
            state.error?.let { Text(it, color = AyuColors.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            report?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}
