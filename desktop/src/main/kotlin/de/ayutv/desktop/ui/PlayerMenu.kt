package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.LANGUAGES
import de.ayutv.desktop.data.PictureFit
import de.ayutv.desktop.data.languageName
import de.ayutv.desktop.data.next
import de.ayutv.desktop.player.DisplayRate
import kotlinx.coroutines.delay
import java.util.Locale

private val MenuSecondary = Color(0xFF9AA7B4)
private val MenuGood = Color(0xFF7FD69B)

/** Verschiebung als Text: „+1,5 s“. */
fun shiftLabel(ms: Long): String = "%+.1f s".format(Locale.GERMAN, ms / 1000.0)

fun delayLabel(ms: Long): String = if (ms == 0L) "keiner" else "%+.2f s".format(Locale.GERMAN, ms / 1000.0)

@Composable
private fun MenuHeader(text: String) {
    Label(text, Type.small.copy(color = Palette.accent, fontWeight = FontWeight.Bold), Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun MenuHint(text: String) {
    Label(text, Type.small.copy(color = MenuSecondary), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 5)
}

/** Zeile im Menü: ● gewählt, ○ nicht gewählt (wie auf dem Fire TV). */
@Composable
private fun OptionRow(label: String, selected: Boolean, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    Selectable(onClick, Modifier.fillMaxWidth(), focusRequester = focusRequester) {
        Label(if (selected) "●" else "○", Type.body.copy(color = if (selected) Palette.accent else MenuSecondary, fontSize = 14.sp))
        Spacer(Modifier.width(12.dp))
        Label(label, Type.body.copy(color = Color.White), maxLines = 2)
    }
}

/** Was das Menü für Dinge braucht, die nur der Player kennt (Stream-Info, Vollbild, Fenster …). */
class MenuActions(
    val nextEpisode: (() -> Unit)?,
    val tech: Boolean,
    val toggleTech: () -> Unit,
    val fullscreen: Boolean,
    val toggleFullscreen: () -> Unit,
    val openSync: () -> Unit,
    val openLanguages: () -> Unit,
    val openDelay: () -> Unit,
    val close: () -> Unit,
)

/**
 * Menü rechts im Bild (☰, Menütaste, CC, Rechtsklick), aufgebaut wie auf dem Fire TV: Favorit und Aufnahme,
 * Untertitel (Spuren, geladene Sprachen, Zeit einstellen, andere Datei, Sprache laden), Untertitel-Aussehen, Ton,
 * Klang, Bild und Schlaf-Timer. Alles gilt sofort.
 */
@Composable
fun PlayerMenuPanel(app: AppState, playback: Playback, actions: MenuActions, modifier: Modifier = Modifier) {
    val player = app.player
    val user by app.store.user.collectAsState()
    val guide by app.store.guide.collectAsState()
    val first = remember { FocusRequester() }
    val live = playback as? Playback.Live
    val vod = playback as? Playback.Vod
    val preferred = user.subtitleLanguage.ifBlank { "de" }
    val shown = app.shownSubtitle

    LazyColumn(
        modifier.fillMaxHeight().width(420.dp).background(Color.Black.copy(alpha = 0.92f)),
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        var firstUsed = false
        fun LazyListScope.row(label: String, selected: Boolean, action: () -> Unit) {
            val isFirst = !firstUsed
            firstUsed = true
            item { OptionRow(label, selected, if (isFirst) first else null, action) }
        }
        if (live != null) {
            val favorite = live.channel.key in user.favorites
            row(if (favorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen", false) {
                app.toast(if (app.store.toggleFavorite(live.channel.key)) "★ ${live.channel.name} zu Favoriten hinzugefügt" else "${live.channel.name} aus Favoriten entfernt")
            }
            guide[live.channel.key]?.let { nowOf(it) }?.let { current ->
                row("⏺ „${current.title}“ aufnehmen", false) { app.record(live.channel, current) }
                if (app.store.archiveUrl(live.channel, current.start, current.stop) != null) {
                    row("⏮ Sendung von Anfang", false) { actions.close(); app.playArchive(live.channel, current) }
                }
            }
            row("◫ Multiview (bis zu 4 Sender gleichzeitig)", false) { actions.close(); app.openMultiview() }
        }
        actions.nextEpisode?.let { next -> row("⏭ Nächste Folge", false) { actions.close(); next() } }

        item { MenuHeader("Untertitel") }
        row("Aus", shown == null && player.subtitleTrack < 0) { app.subtitlesOff() }
        player.subtitleTracks.forEach { (id, name) ->
            row(name, shown == null && player.subtitleTrack == id) { app.selectSubtitleTrack(id) }
        }
        app.loadedSubtitles.keys.toList().forEach { language ->
            row("${languageName(language)} (geladen)", shown == language) { app.showLoaded(language) }
        }
        if (shown != null) {
            item { MenuHeader("Untertitel-Zeit") }
            row("Untertitel-Zeit einstellen … (${shiftLabel(app.subtitleDelayMs)})", false) { actions.openSync() }
            if (vod != null) row("Andere Untertitel-Datei laden", false) { actions.close(); app.otherSubtitleFile(vod) }
            row("Früher (−0,25 s)", false) { app.setSubtitleDelay(app.subtitleDelayMs - 250) }
            row("Später (+0,25 s)", false) { app.setSubtitleDelay(app.subtitleDelayMs + 250) }
            item {
                MenuHint(
                    app.subtitleSync?.let { "Ausrichtung: $it" + if (app.subtitleDelayMs != 0L) ", von Hand nachgestellt: ${shiftLabel(app.subtitleDelayMs)}." else "." }
                        ?: "Verschiebung: ${shiftLabel(app.subtitleDelayMs)}. DnA-TV richtet die Zeiten nach rund 1,5 Minuten Gespräch automatisch am Ton aus.",
                )
            }
        }
        if (vod != null) {
            if (preferred !in app.loadedSubtitles) row("${languageName(preferred)} laden", false) { actions.close(); app.loadSubtitles(vod, preferred) }
            row("Andere Sprache laden / übersetzen …", false) { actions.openLanguages() }
            if (user.openSubtitlesKey.isBlank()) item { MenuHint("Für Filme ganz ohne Untertitel: OpenSubtitles-Schlüssel unter Einstellungen → Untertitel eintragen (oder Sicherung vom Fire TV übernehmen).") }
        }

        item { MenuHeader("Untertitel-Aussehen") }
        row("Größe: ${user.subtitleSize.label}", false) { app.updateSettings { it.copy(subtitleSize = it.subtitleSize.next()) } }
        row("Farbe: ${user.subtitleColor.label}", false) { app.updateSettings { it.copy(subtitleColor = it.subtitleColor.next()) } }
        row("Hintergrund: ${user.subtitleBackground.label}", false) { app.updateSettings { it.copy(subtitleBackground = it.subtitleBackground.next()) } }
        row("Position: ${user.subtitlePosition.label}", false) { app.updateSettings { it.copy(subtitlePosition = it.subtitlePosition.next()) } }

        item { MenuHeader("Ton") }
        if (player.audioTracks.isEmpty()) item { MenuHint("Dieser Stream hat keine (oder noch keine) Tonspur") }
        player.audioTracks.forEach { (id, name) -> row(name, player.audioTrack == id) { player.selectAudio(id) } }
        row("Ton-Versatz … (${delayLabel(user.audioDelayMs)})", false) { actions.openDelay() }

        item { MenuHeader("Klang") }
        AudioMode.entries.forEach { mode -> row(mode.label, user.audioMode == mode) { app.updateSettings { it.copy(audioMode = mode) } } }
        row("Lautstärke angleichen (alle Sender)", user.normalizeVolume) { app.updateSettings { it.copy(normalizeVolume = !it.normalizeVolume) } }
        item { MenuHint("Wirkt, wenn Windows den Ton ausgibt – nicht bei Dolby/DTS, das an Soundbar oder Receiver durchgereicht wird.") }

        item { MenuHeader("Bild") }
        PictureFit.entries.forEach { fit -> row(fit.label, user.pictureFit == fit) { app.updateSettings { it.copy(pictureFit = fit) } } }
        if (DisplayRate.supported) row("Bildwiederholrate anpassen (50 Hz, 24 Hz …)", user.matchFrameRate) {
            app.updateSettings { it.copy(matchFrameRate = !it.matchFrameRate) }
            if (app.store.user.value.matchFrameRate) app.toast("Bildwiederholrate passt sich ab dem nächsten Sender bzw. Film an")
        }
        if (player.gpuVideo) {
            val nextHdr = HDR_MODES[(HDR_MODES.indexOfFirst { it.first == user.hdrMode } + 1).mod(HDR_MODES.size)]
            row("HDR-Anpassung: ${hdrLabel(user.hdrMode)}", false) {
                app.store.updateUser { it.copy(hdrMode = nextHdr.first) }
                app.applyBuffer()
                app.toast("HDR-Anpassung: ${nextHdr.second}")
            }
        }
        val nextDirect = DIRECT_MODES[(DIRECT_MODES.indexOfFirst { it.first == user.directMode } + 1).mod(DIRECT_MODES.size)]
        row("Direktausgabe: ${directLabel(user.directMode)}", false) {
            app.store.updateUser { it.copy(directMode = nextDirect.first) }
            app.applyBuffer()
            app.restartPlayback()
        }
        row("Stream-Info", actions.tech) { actions.toggleTech() }
        row(if (actions.fullscreen) "Vollbild beenden" else "Vollbild", actions.fullscreen) { actions.toggleFullscreen() }

        item { MenuHeader("Schlaf-Timer") }
        val now = System.currentTimeMillis()
        if (app.sleepAt > 0) {
            item { MenuHint("Endet um ${clockText(app.sleepAt)}" + if (app.sleepPc) ", danach schläft der PC" else "") }
            row("Aus", false) { app.setSleepTimer(0) }
        }
        listOf(15, 30, 45, 60, 90, 120).forEach { minutes -> row("In $minutes Minuten", false) { app.setSleepTimer(now + minutes * 60_000L) } }
        val end = if (live != null) guide[live.channel.key]?.let { nowOf(it) }?.stop?.takeIf { it > now }
        else (player.durationMs - player.positionMs).takeIf { player.durationMs > 0 && it > 0 }?.let { now + it }
        if (end != null) row((if (live != null) "Am Ende der Sendung" else "Am Ende des Films / der Folge") + " (${clockText(end)})", false) { app.setSleepTimer(end) }
        if (user.tvMode && de.ayutv.desktop.TvMode.isWindows) {
            row("In 60 Minuten, danach PC schlafen legen", false) { app.setSleepTimer(now + 60 * 60_000L, pc = true) }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Alle Sprachen: lädt Untertitel (OpenSubtitles) oder übersetzt die englischen (DeepL). */
@Composable
fun SubtitleLanguagePanel(app: AppState, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    val first = remember { FocusRequester() }
    LazyColumn(
        modifier.fillMaxHeight().width(420.dp).background(Color.Black.copy(alpha = 0.92f)),
        contentPadding = PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Untertitel in welcher Sprache?") }
        item { MenuHint("Erst eine fertige Datei von OpenSubtitles, sonst die englische mit DeepL übersetzt (mit DeepL-Schlüssel).") }
        itemsIndexed(LANGUAGES) { index, (code, name) ->
            OptionRow(name + if (code in app.loadedSubtitles) " (geladen)" else "", app.shownSubtitle == code, if (index == 0) first else null) { onPick(code) }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Untertitel-Zeit einstellen: Zeile abwarten, OK drücken, sobald sie gesprochen wird (wie auf dem Fire TV). */
@Composable
fun SubtitleSyncBar(app: AppState, index: Int, confirmed: String?, modifier: Modifier = Modifier) {
    val cues = app.subtitleCues
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .border(2.dp, Palette.accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Untertitel ausrichten", Type.body.copy(color = Palette.accent), Modifier.weight(1f))
            Label("jetzt ${shiftLabel(app.subtitleDelayMs)}", Type.body.copy(color = MenuSecondary, fontSize = 14.sp))
        }
        cues.getOrNull(index - 1)?.let { Label(it.second, Type.body.copy(color = MenuSecondary), Modifier.padding(top = 8.dp)) }
        Label(
            cues.getOrNull(index)?.let { "„${it.second}“" } ?: "Keine Zeilen geladen",
            Type.title.copy(color = Color.White, textAlign = TextAlign.Center),
            Modifier.padding(vertical = 6.dp), maxLines = 3,
        )
        cues.getOrNull(index + 1)?.let { Label(it.second, Type.body.copy(color = MenuSecondary)) }
        Label(
            confirmed ?: "OK drücken, sobald diese Zeile gesprochen wird",
            Type.body.copy(color = if (confirmed != null) MenuGood else Color.White, fontSize = 17.sp, textAlign = TextAlign.Center),
            Modifier.padding(top = 10.dp), maxLines = 2,
        )
        Label(
            "◀ ▶ andere Zeile   ·   ▲ früher  ▼ später (½ s)   ·   ⏯ Pause   ·   Zurück: fertig",
            Type.small.copy(color = MenuSecondary), Modifier.padding(top = 6.dp),
        )
    }
}

/** Ton-Versatz in Schritten von 50 ms (gilt sofort, für alle Sender). */
@Composable
fun AudioDelayDialog(app: AppState, onDismiss: () -> Unit) {
    val user by app.store.user.collectAsState()
    fun change(delta: Long) = app.updateSettings { it.copy(audioDelayMs = if (delta == 0L) 0 else (it.audioDelayMs + delta).coerceIn(-5_000, 5_000)) }
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss, width = 480) {
        Column {
            Label("Ton-Versatz", Type.heading)
            Label("Plus = Ton später (wenn er vor dem Bild kommt), Minus = Ton früher. Gilt für alle Sender und Filme.", Type.small, maxLines = 2)
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
