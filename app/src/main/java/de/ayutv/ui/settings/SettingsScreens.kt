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
import androidx.compose.runtime.produceState
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
import de.ayutv.data.repo.PlaylistRepository
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.SyncBanner
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.Job
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import de.ayutv.data.setup.KeyCheck
import de.ayutv.data.setup.PhoneSetupServer
import de.ayutv.ui.components.QrCode
import de.ayutv.data.repo.Translators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.tv.material3.Border
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.key
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalComposeUiApi::class)
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
    var page by rememberSaveable { mutableStateOf(SettingsPage.PLAYLISTS) }
    // Bereich erst nach kurzem Verweilen wechseln: Beim schnellen Durchscrollen wird nicht jede Seite aufgebaut.
    var pendingPage by remember { mutableStateOf<SettingsPage?>(null) }
    LaunchedEffect(pendingPage) {
        val target = pendingPage ?: return@LaunchedEffect
        delay(200)
        page = target
    }
    val pageFocus = remember { SettingsPage.entries.associateWith { FocusRequester() } }

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

    Row(Modifier.fillMaxSize()) {
        // Links die Bereiche: Fokus genügt zum Umschalten, rechts stehen die Einstellungen dazu.
        Column(
            // focusRestorer: Von rechts zurück landet der Fokus auf dem gewählten Bereich, nicht auf dem
            // gerade daneben liegenden (der sonst sofort umschalten würde).
            // fallback: Beim ersten Betreten (noch nichts gemerkt) auf den gewählten Bereich, nicht auf den nächstgelegenen.
            Modifier.width(250.dp).fillMaxHeight().focusRestorer(pageFocus.getValue(page))
                .background(AyuColors.Surface.copy(alpha = 0.5f)).padding(vertical = 32.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Einstellungen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 10.dp, bottom = 18.dp))
            SettingsPage.entries.forEach { entry ->
                PageButton(
                    entry,
                    selected = page == entry,
                    focusRequester = pageFocus.getValue(entry),
                    onFocus = { pendingPage = entry },
                    onClick = { pendingPage = null; page = entry },
                )
            }
            Spacer(Modifier.weight(1f))
            Text("DnA-TV ${BuildConfig.VERSION_NAME}", color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 10.dp))
        }
        // Jeder Bereich mit eigenem Listenzustand: beginnt oben statt an der Scrollposition des vorigen.
        key(page) { LazyColumn(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title-${page.name}") {
                Column(Modifier.padding(bottom = 8.dp)) {
                    Text(page.title, style = MaterialTheme.typography.headlineMedium)
                    Text(page.description, color = AyuColors.TextSecondary, fontSize = 14.sp)
                    SyncBanner(sync, Modifier.padding(top = 4.dp))
                }
            }
            when (page) {
                SettingsPage.PLAYLISTS -> {
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

                    item { PhoneSetupCard(phone, keyReport ?: phone.lastSaved) }
                }
                SettingsPage.PLAYBACK -> {
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
                            "Bild und Ton laufen direkt in der Hardware synchron: am Fire TV Stick 4K / 4K Max flüssigeres 4K und exakte Lippensynchronität. " +
                                "Empfohlen für 4K. Ausschalten, falls Ton aussetzt (manche Soundbars) – das Puffer-Polster gegen Hänger ist dabei inaktiv.",
                        ) { update { it.copy(tunneling = !it.tunneling) } }
                    }
                    item {
                        SettingRow(
                            "Bildwiederholrate anpassen",
                            onOff(settings.matchFrameRate),
                            "Empfohlen: An. Deutsches TV läuft mit 50 Hz, Filme mit 23,976 Hz – der Fire TV gibt sonst 60 Hz aus, das ruckelt bei " +
                                "Schwenks. Beim Umschalten ist das Bild 1–2 s schwarz (nur wenn sich die Rate ändert). Fire TV: zusätzlich unter " +
                                "Einstellungen → Display & Audio → Display „Originalbildrate anpassen“ einschalten.",
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
                        UserAgentRow(settings.userAgent) { ua -> update { it.copy(userAgent = ua) } }
                    }

                }
                SettingsPage.SUBTITLES -> {
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
                        var testResult by remember { mutableStateOf("Prüft Texterkennung und Übersetzung auf diesem Gerät") }
                        SettingRow(
                            "Übersetzer testen",
                            "",
                            testResult,
                        ) {
                            testResult = "Läuft … (beim ersten Mal werden die Modelle geladen)"
                            scope.launch {
                                testResult = de.ayutv.data.translate.TranslatorSelfTest.run(settings.subtitleLanguage)
                                // Übersetzer-Prozess wieder beenden: Speicher frei für die Wiedergabe.
                                Translators.remote.release()
                            }
                        }
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

                }
                SettingsPage.DISPLAY -> {
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
                        SettingRow(
                            "TV-Guide aus dem Internet",
                            when {
                                !settings.internetGuide -> "Aus"
                                settings.preferInternetGuide -> "Bevorzugen"
                                else -> "Ergänzen"
                            },
                            "Freies EPG für Deutschland, Österreich und die Schweiz (epgshare01), höchstens einmal am Tag geladen. " +
                                "Ergänzen: nur für Sender, die beim Anbieter fehlen (oft Sky, DAZN). Bevorzugen: zuerst aus dem Internet " +
                                "(meist genauer, z. B. mit Fußball-Paarungen), der Anbieter füllt den Rest. Der Guide wird danach neu geladen.",
                        ) {
                            scope.launch {
                                // Ergänzen → Bevorzugen → Aus → Ergänzen
                                container.settings.update {
                                    when {
                                        !it.internetGuide -> it.copy(internetGuide = true, preferInternetGuide = false)
                                        it.preferInternetGuide -> it.copy(internetGuide = false, preferInternetGuide = false)
                                        else -> it.copy(preferInternetGuide = true)
                                    }
                                }
                                epgReloadJob?.cancel()
                                epgReloadJob = container.scope.launch {
                                    delay(2_500)
                                    container.playlists.reloadAllEpg()
                                }
                            }
                        }
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

                }
                SettingsPage.PARENTAL -> {
                    item { ParentalSettings() }
                }
                SettingsPage.DATA -> {
                    item {
                        SettingRow("Verlauf löschen", "", "Entfernt „Zuletzt gesehen“, „Weiter schauen“ und die Empfehlungen.") {
                            scope.launch { container.content.clearHistory() }
                        }
                    }
                    item {
                        SettingRow("Suchverlauf löschen", "", "Entfernt die „Letzten Suchen“ in der Suche.") {
                            scope.launch { container.settings.clearSearchHistory() }
                        }
                    }
                    item {
                        Text(
                            "DnA-TV ${BuildConfig.VERSION_NAME} · Übersetzung: OPUS-MT (Universität Helsinki, CC BY 4.0) · " +
                                "Untertitel: OpenSubtitles.com · Wiedergabe: AndroidX Media3, FFmpeg",
                            color = AyuColors.TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(top = 20.dp),
                        )
                    }
                }
            }
        } }
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
        ActiveToggle(playlist.enabled, onToggle)
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
            // Wie viele Sender ein Programm haben, woher es kommt und welche keins haben.
            val container = LocalContainer.current
            val guide by produceState<PlaylistRepository.EpgStatus?>(null, playlist.id, playlist.lastEpgSync) {
                value = withContext(Dispatchers.IO) { container.playlists.epgStatus(playlist.id) }
            }
            guide?.let { g ->
                val sources = g.sources.filter { it.second > 0 }.joinToString(", ") { "${it.first}: ${it.second}" }
                Text(
                    "TV-Guide: ${g.withGuide} von ${g.total} Sendern" + (if (sources.isNotEmpty()) " ($sources)" else ""),
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
                if (g.missing.isNotEmpty()) {
                    Text(
                        "Ohne Programm: " + g.missing.take(12).joinToString(", ") + (if (g.total - g.withGuide > 12) " … (ganze Liste: Einrichtung per Handy)" else ""),
                        color = AyuColors.TextSecondary, fontSize = 12.sp, maxLines = 2,
                    )
                }
            }
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
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 16.sp)
                if (description.isNotEmpty()) Text(description, color = AyuColors.TextSecondary, fontSize = 13.sp)
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
            // Bei jeder Änderung speichern: Wer die Tastatur mit „Zurück“ schließt, verliert sonst die Eingabe.
            onValueChange = {
                value = it
                onChange(it.ifBlank { AppSettings.DEFAULT_USER_AGENT })
            },
            label = "User-Agent (manche Anbieter verlangen einen bestimmten)",
            imeAction = ImeAction.Done,
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

    // Zugangsdaten lassen sich auch vom Handy einfügen (QR-Code rechts), statt sie zu tippen.
    val phoneSetup = remember { PhoneSetupServer(container) }
    val phone by phoneSetup.state.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        phoneSetup.start()
        onDispose { phoneSetup.stop() }
    }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Column(
            Modifier.weight(1.4f).fillMaxHeight()
                .background(AyuColors.Surface, RoundedCornerShape(16.dp))
                .verticalScroll(rememberScrollState())
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Playlist hinzufügen", style = MaterialTheme.typography.headlineMedium)
            Text("Zugang deines IPTV-Anbieters eintragen – DnA-TV ist ein reiner Player.", color = AyuColors.TextSecondary, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                TypeCard(
                    "Xtream Codes",
                    "Server, Benutzername und Passwort. Filme, Serien und TV-Guide kommen automatisch.",
                    type == PlaylistType.XTREAM,
                    Modifier.weight(1f),
                ) { type = PlaylistType.XTREAM }
                TypeCard(
                    "M3U-Link",
                    "Eine Adresse (.m3u / .m3u8 / get.php). TV-Guide optional per XMLTV-Link.",
                    type == PlaylistType.M3U,
                    Modifier.weight(1f),
                ) { type = PlaylistType.M3U }
            }
            TvTextField(name, { name = it }, "Name (optional), z. B. „Mein Anbieter“", focusRequester = firstField)
            if (type == PlaylistType.XTREAM) {
                TvTextField(url, { url = it }, "Server, z. B. http://anbieter.tv:8080", keyboardType = KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvTextField(username, { username = it }, "Benutzername", Modifier.weight(1f))
                    TvTextField(password, { password = it }, "Passwort", Modifier.weight(1f), password = true)
                }
            } else {
                TvTextField(url, { url = it }, "Playlist-Link (M3U/M3U8)", keyboardType = KeyboardType.Uri)
            }
            TvTextField(
                epgUrl,
                { epgUrl = it },
                if (type == PlaylistType.XTREAM) "EPG-Link (optional, der des Anbieters gilt immer zusätzlich)" else "EPG-Link XMLTV (optional, sonst automatisch aus der Playlist)",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                onImeAction = { save() },
            )
            error?.let {
                Text(
                    "⚠ $it",
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(AyuColors.Live.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(onClick = { if (!busy) save() }) { Text(if (busy) "Wird geprüft …" else "Speichern und laden") }
                if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxHeight()
                .background(AyuColors.Surface.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bequemer per Handy", style = MaterialTheme.typography.titleLarge)
            Text(
                "QR-Code scannen (gleiches WLAN) und Adresse, Benutzername und Passwort einfach einfügen. " +
                    "Die Playlist erscheint danach sofort hier.",
                color = AyuColors.TextSecondary,
                fontSize = 14.sp,
            )
            phone.url?.let { url ->
                QrCode(url, size = 190.dp, modifier = Modifier.padding(top = 8.dp))
                Text(url, color = AyuColors.Accent, fontSize = 16.sp)
            }
            phone.error?.let { Text(it, color = AyuColors.Live, fontSize = 13.sp) }
            phone.lastSaved?.let { Text(it, fontSize = 13.sp) }
        }
    }

    LaunchedEffect(Unit) { runCatching { firstField.requestFocus() } }
}

@Composable
private fun TypeCard(title: String, description: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft.copy(alpha = 0.55f) else AyuColors.Background.copy(alpha = 0.6f),
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(2.dp, if (selected) AyuColors.Accent else Color.Transparent), shape = RoundedCornerShape(12.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text((if (selected) "● " else "○ ") + title, fontSize = 17.sp, color = Color.White)
            Text(description, fontSize = 13.sp, color = AyuColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
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

/** Bereiche der Einstellungen (linke Spalte). */
private enum class SettingsPage(val title: String, val description: String, val icon: String) {
    PLAYLISTS("Playlists", "Anbieter hinzufügen, ein- und ausschalten, Gruppen wählen", "☰"),
    PLAYBACK("Wiedergabe", "Puffer, Bild, Ton und Stabilität", "▶"),
    SUBTITLES("Untertitel", "Sprachen, Größe, Übersetzung auf dem Gerät", "💬"),
    DISPLAY("Bedienung & TV-Guide", "Umschalten, Programmführer, Zeitverschiebung", "📺"),
    PARENTAL("Kindersicherung", "Gruppen mit PIN sperren", "🔒"),
    DATA("Daten & Info", "Verlauf, Speicher, Version", "ⓘ"),
}

@Composable
private fun PageButton(page: SettingsPage, selected: Boolean, focusRequester: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft.copy(alpha = 0.45f) else Color.Transparent,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(page.icon, fontSize = 16.sp, modifier = Modifier.width(30.dp))
            Text(page.title, fontSize = 16.sp, color = if (selected) Color.White else AyuColors.TextSecondary)
        }
    }
}

/** Gut sichtbarer An/Aus-Schalter: grün = aktiv, grau = aus, im Fokus mit weißem Rahmen. */
@Composable
private fun ActiveToggle(enabled: Boolean, onToggle: () -> Unit) {
    val active = Color(0xFF2E9E5B)
    Surface(
        onClick = onToggle,
        modifier = Modifier.width(120.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (enabled) active else AyuColors.Surface,
            focusedContainerColor = if (enabled) active else AyuColors.SurfaceRaised,
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(20.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            if (enabled) "● Aktiv" else "○ Aus",
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
            fontSize = 15.sp,
        )
    }
}

/** PIN festlegen, entsperren, wieder sperren. Gesperrt wird in Playlists → Gruppen (OK halten). */
@Composable
private fun ParentalSettings() {
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val hasPin by container.parental.hasPin.collectAsStateWithLifecycle(false)
    val unlocked by container.parental.unlocked.collectAsStateWithLifecycle()
    val locked by container.parental.lockedGroups.collectAsStateWithLifecycle(emptySet())
    var pin by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val status = when {
            !hasPin -> "Keine PIN festgelegt – nichts ist gesperrt."
            unlocked -> "🔓 Entsperrt, bis die App verlassen wird. ${locked.size} Gruppe(n) mit Sperre."
            else -> "🔒 Gesperrt: ${locked.size} Gruppe(n) sind ausgeblendet."
        }
        Text(status, fontSize = 17.sp)
        Text(
            "Gesperrte Gruppen verschwinden überall: Listen, TV-Guide, Suche und Umschalten. Sperren: Playlists → Gruppen → " +
                "Gruppe auswählen und OK gedrückt halten. Beim Verlassen der App wird automatisch wieder gesperrt.",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
        )
        TvTextField(
            value = pin,
            onValueChange = { value -> pin = value.filter(Char::isDigit).take(8) },
            label = if (hasPin) "PIN" else "Neue PIN (mindestens 4 Ziffern)",
            password = true,
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Done,
            modifier = Modifier.width(320.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!hasPin) {
                Button(onClick = {
                    scope.launch {
                        info = runCatching { container.parental.setPin(pin) }
                            .fold({ "PIN gespeichert. Jetzt Gruppen sperren (Playlists → Gruppen → OK halten)." }, { it.message })
                        pin = ""
                    }
                }) { Text("PIN festlegen") }
            } else if (!unlocked) {
                Button(onClick = {
                    scope.launch {
                        info = if (container.parental.unlock(pin)) "Entsperrt." else "Falsche PIN."
                        pin = ""
                    }
                }) { Text("Entsperren") }
            } else {
                Button(onClick = { scope.launch { container.parental.lock(); info = "Wieder gesperrt." } }) { Text("Wieder sperren") }
                OutlinedButton(onClick = {
                    scope.launch {
                        info = if (container.parental.removePin(pin)) "PIN entfernt, Kindersicherung aus." else "Zum Entfernen die PIN eingeben."
                        pin = ""
                    }
                }) { Text("PIN entfernen") }
            }
        }
        info?.let { Text(it, color = AyuColors.Accent, fontSize = 14.sp) }
    }
}
