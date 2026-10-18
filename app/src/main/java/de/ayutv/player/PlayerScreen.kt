@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.ayutv.data.Languages
import de.ayutv.ui.components.ChannelListItem
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.ProgressLine
import de.ayutv.ui.components.formatDuration
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.progress
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun PlayerScreen(activity: PlayerActivity) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val player = activity.player
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        isFocusable = false
                        setShutterBackgroundColor(AndroidColor.BLACK)
                        // Beim Umschalten das letzte Bild stehen lassen statt schwarz zu blenden.
                        setKeepContentOnPlayerReset(true)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        // Untertitel für den Fernseher: weiße Schrift mit schwarzem Rand, ohne Kasten.
                        subtitleView?.apply {
                            setStyle(
                                CaptionStyleCompat(
                                    AndroidColor.WHITE,
                                    AndroidColor.TRANSPARENT,
                                    AndroidColor.TRANSPARENT,
                                    CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                    AndroidColor.BLACK,
                                    null,
                                )
                            )
                            setApplyEmbeddedFontSizes(false)
                            setBottomPaddingFraction(0.07f)
                        }
                        this.player = player
                    }
                },
                update = {
                    it.player = player
                    it.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * activity.subtitleScale)
                    it.subtitleView?.visibility = if (activity.liveTranslateTarget != null) View.INVISIBLE else View.VISIBLE
                    // Untertitel über die Steuerleiste anheben, solange sie sichtbar ist.
                    val barVisible = activity.overlay == Overlay.CONTROLS || activity.overlay == Overlay.INFO
                    it.subtitleView?.setBottomPaddingFraction(if (barVisible) 0.3f else 0.07f)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (activity.fatalError) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        if (activity.buffering && activity.message == null) {
            Text(
                "Lädt …",
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(16.dp),
                color = Color.White,
            )
        }

        activity.message?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(20.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }

        if (activity.numberInput.isNotEmpty()) {
            Text(
                activity.numberInput,
                modifier = Modifier.align(Alignment.TopEnd).padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.INFO && activity.isLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            LiveInfoBar(activity)
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.CONTROLS && !activity.isLive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VodControls(activity)
        }

        if (activity.liveTranslateTarget != null) {
            activity.translatedCue?.let { cue ->
                Text(
                    cue,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = 40.dp),
                    color = Color.White,
                    fontSize = (22 * activity.subtitleScale).sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(shadow = Shadow(Color.Black, Offset(2f, 2f), blurRadius = 6f)),
                )
            }
        }

        activity.subtitleStatus?.let { status ->
            Text(
                status,
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                color = Color.White,
                fontSize = 14.sp,
            )
            LaunchedEffect(status) {
                // Fehler länger stehen lassen, damit man sie lesen kann.
                delay(if ("nicht" in status || "keine" in status.lowercase()) 12_000 else 5_000)
                activity.clearSubtitleStatus(status)
            }
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.TRACKS,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            TracksOverlay(activity)
        }

        AnimatedVisibility(
            visible = activity.overlay == Overlay.CHANNELS,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
        ) {
            ChannelListOverlay(activity)
        }
    }

    // Overlays blenden sich nach kurzer Zeit aus. Jede Taste setzt den Timer zurück (overlayToken).
    LaunchedEffect(activity.overlayToken, activity.overlay) {
        val timeout = when (activity.overlay) {
            Overlay.NONE -> return@LaunchedEffect
            Overlay.INFO -> if (activity.showTech) 10_000L else 5_000L
            Overlay.CONTROLS -> if (activity.player?.isPlaying == false) Long.MAX_VALUE else 4_000L
            Overlay.CHANNELS, Overlay.TRACKS -> 15_000L
        }
        delay(timeout)
        activity.closeOverlay()
    }
}

private val bottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f)))

@Composable
private fun LiveInfoBar(activity: PlayerActivity) {
    val channel = activity.current ?: return
    val now = activity.nowNext.firstOrNull()
    val next = activity.nowNext.getOrNull(1)
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 96.dp, height = 60.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    if (activity.isFavorite) Text("  ★", color = AyuColors.Accent, fontSize = 22.sp)
                    activity.stats.resolutionLabel.takeIf { it.isNotEmpty() }?.let { Badge(it) }
                    activity.stats.hdr.takeIf { it.isNotEmpty() }?.let { Badge(it) }
                }
                Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary)
            }
            Text(formatTime(System.currentTimeMillis()), style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(now.start)} – ${formatTime(now.stop)}", color = AyuColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(now.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            ProgressLine(now.progress())
            now.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Text("Danach ${formatTime(next.start)}  ${next.title}", color = AyuColors.TextSecondary, fontSize = 14.sp, maxLines = 1)
            }
        } else {
            Text("Keine Programminformationen", color = AyuColors.TextSecondary)
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "▲▼ Umschalten   OK Senderliste   ▶ Stream-Info   ☰ Ton/Untertitel/Favorit",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun VodControls(activity: PlayerActivity) {
    val duration = activity.durationMs
    val position = activity.positionMs
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(activity.vodTitle, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.weight(1f))
            activity.stats.resolutionLabel.takeIf { it.isNotEmpty() }?.let { Badge(it) }
            activity.stats.hdr.takeIf { it.isNotEmpty() }?.let { Badge(it) }
        }
        Spacer(Modifier.height(16.dp))
        ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(formatDuration(position), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text("noch ${formatDuration(duration - position)}  ·  Ende ${formatTime(System.currentTimeMillis() + duration - position)}", color = AyuColors.TextSecondary, fontSize = 14.sp)
            }
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (activity.player?.playWhenReady == false) "Pausiert  ·  " else "") + "OK Pause   ◀ −10 s   ▶ +30 s (halten = schneller)   ▲ Stream-Info   ☰ Ton/Untertitel",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TechLine(stats: StreamStats) {
    val parts = buildList {
        if (stats.width > 0) add("${stats.width}×${stats.height}")
        if (stats.frameRate > 0) add("%.2f fps".format(stats.frameRate) + if (stats.frameRateMeasured) " (gemessen)" else "")
        if (stats.videoCodec.isNotEmpty()) add(stats.videoCodec)
        if (stats.videoBitrate > 0) add("%.1f Mbit/s".format(stats.videoBitrate / 1_000_000f))
        if (stats.audioCodec.isNotEmpty()) add("${stats.audioCodec} ${stats.audioChannels}ch")
        if (stats.audioDecoder.isNotEmpty()) add("Ton: " + if ("ffmpeg" in stats.audioDecoder.lowercase()) "FFmpeg" else stats.audioDecoder)
        if (stats.decoder.isNotEmpty()) add(stats.decoder)
        if (stats.bandwidth > 0) add("Leitung %.1f Mbit/s".format(stats.bandwidth / 1_000_000f))
        add("Verworfene Frames: ${stats.droppedFrames}")
        if (stats.startupMs > 0) add("Start in ${stats.startupMs} ms")
    }
    Text(parts.joinToString("  ·  "), color = AyuColors.Accent, fontSize = 13.sp)
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(4.dp)).background(AyuColors.AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ChannelListOverlay(activity: PlayerActivity) {
    val channels = activity.channels
    val currentIndex = remember(channels) { channels.indexOfFirst { it.key == activity.current?.key }.coerceAtLeast(0) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 4).coerceAtLeast(0))
    val focusRequester = remember { FocusRequester() }

    Column(
        Modifier.fillMaxHeight().width(460.dp).background(Color.Black.copy(alpha = 0.88f)).padding(vertical = 24.dp)
    ) {
        Text(
            activity.listTitle.ifEmpty { "Sender" },
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
        )
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(channels, key = { _, c -> c.key }) { index, channel ->
                ChannelListItem(
                    channel = channel,
                    now = channel.epgId?.let { activity.nowPlayingMap[it] },
                    selected = channel.key == activity.current?.key,
                    modifier = Modifier.padding(horizontal = 12.dp)
                        .then(if (index == currentIndex) Modifier.focusRequester(focusRequester) else Modifier),
                    onClick = {
                        activity.closeOverlay()
                        if (channel.key != activity.current?.key) activity.playChannel(channel)
                    },
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        // Warten, bis die Zeile komponiert ist, dann den aktuellen Sender fokussieren.
        repeat(5) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Spurmenü: Audiospur, Untertitel (inkl. Laden und Übersetzen in jede Sprache) und bei Live der Favorit. */
@Composable
private fun TracksOverlay(activity: PlayerActivity) {
    if (activity.languagePicker) {
        LanguagePicker(activity)
        return
    }
    val tracks = activity.tracks
    // Ton: auch Spuren zeigen, die das Gerät nicht abspielen kann, damit klar ist, warum es still bleibt.
    val audio = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO, includeUnsupported = true) }
    val text = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
    val firstItem = remember { FocusRequester() }
    val preferred = activity.preferredSubtitleLanguage
    val liveTarget = activity.liveTranslateTarget

    LazyColumn(
        modifier = Modifier.fillMaxHeight().width(420.dp).background(Color.Black.copy(alpha = 0.92f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (activity.isLive) {
            item {
                OptionRow(
                    if (activity.isFavorite) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten hinzufügen",
                    selected = false,
                    modifier = Modifier.focusRequester(firstItem),
                ) { activity.toggleFavorite() }
            }
        }
        item { MenuHeader("Untertitel") }
        item {
            OptionRow(
                "Aus",
                activity.textDisabled || text.none { it.selected },
                if (!activity.isLive) Modifier.focusRequester(firstItem) else Modifier,
            ) { activity.setTextEnabled(false) }
        }
        text.forEach { option ->
            item {
                OptionRow(option.label, option.selected && !activity.textDisabled && liveTarget == null) {
                    activity.selectTrack(option.group, option.index)
                }
            }
        }
        if (!activity.isLive) {
            item { OptionRow("${Languages.label(preferred)} laden", selected = false) { activity.loadSubtitles(preferred) } }
            item { OptionRow("Andere Sprache laden / übersetzen …", selected = false) { activity.openLanguagePicker() } }
        }
        if (activity.translationAvailable && text.any { it.selected } && !activity.textDisabled) {
            if (liveTarget == null) {
                item { OptionRow("Gewählte Spur live übersetzen: ${Languages.label(preferred)}", false) { activity.startLiveTranslation(preferred) } }
            } else {
                item { OptionRow("Live-Übersetzung (${Languages.label(liveTarget)}) beenden", true) { activity.stopLiveTranslation() } }
            }
        }
        if (!activity.isLive && !activity.onlineSubtitlesAvailable && !activity.translationAvailable) {
            item { MenuHint("Für Untertitel aus dem Internet und Übersetzungen: OpenSubtitles bzw. DeepL in den Einstellungen eintragen") }
        }
        item { MenuHeader("Ton") }
        if (audio.isEmpty()) item { MenuHint("Dieser Stream hat keine Tonspur") }
        audio.forEach { option ->
            item {
                OptionRow(option.label, option.selected) {
                    if (option.supported) activity.selectTrack(option.group, option.index)
                    else activity.explainUnsupportedAudio(option.group.getTrackFormat(option.index))
                }
            }
        }
        if (audio.isNotEmpty() && audio.none { it.supported }) {
            item { MenuHint("Keine dieser Tonspuren kann das Gerät wiedergeben. Abhilfe: AV-Receiver/Soundbar per HDMI oder einen anderen Stream wählen.") }
        }
    }

    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Alle Sprachen: lädt menschliche Untertitel (OpenSubtitles) oder übersetzt automatisch. */
@Composable
private fun LanguagePicker(activity: PlayerActivity) {
    val firstItem = remember { FocusRequester() }
    val embeddedSelected = !activity.textDisabled && trackOptions(activity.tracks, C.TRACK_TYPE_TEXT).any { it.selected }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().width(420.dp).background(Color.Black.copy(alpha = 0.92f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 28.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Sprache wählen") }
        item {
            MenuHint(
                "Ayu-TV nimmt eine passende Spur oder lädt Untertitel. Gibt es keine, " +
                    "werden vorhandene Untertitel automatisch auf dem Gerät übersetzt (kostenlos, ohne Konto)."
            )
        }
        Languages.all.forEachIndexed { index, language ->
            item {
                OptionRow(
                    language.label,
                    selected = false,
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                    onLongClick = if (embeddedSelected && activity.translationAvailable) ({ activity.startLiveTranslation(language.code) }) else null,
                ) { activity.loadSubtitles(language.code) }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

private class TrackOption(val group: Tracks.Group, val index: Int, val label: String, val selected: Boolean, val supported: Boolean)

private fun trackOptions(tracks: Tracks, type: Int, includeUnsupported: Boolean = false): List<TrackOption> = buildList {
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            val supported = group.isTrackSupported(i)
            if (!supported && !includeUnsupported) continue
            val label = describe(group.getTrackFormat(i), type, size + 1) + if (supported) "" else " · nicht abspielbar"
            add(TrackOption(group, i, label, group.isTrackSelected(i), supported))
        }
    }
}

private fun describe(format: Format, type: Int, number: Int): String {
    val language = format.language?.takeIf { it.isNotBlank() && it != "und" }?.let {
        Locale.forLanguageTag(it).getDisplayLanguage(Locale.GERMAN).replaceFirstChar(Char::uppercase)
    }
    val parts = mutableListOf(format.label ?: language ?: "Spur $number")
    if (format.label != null && language != null && language !in format.label!!) parts += language
    if (type == C.TRACK_TYPE_AUDIO) {
        when (format.sampleMimeType) {
            MimeTypes.AUDIO_E_AC3_JOC -> parts += "Dolby Atmos"
            MimeTypes.AUDIO_E_AC3 -> parts += "Dolby Digital Plus"
            MimeTypes.AUDIO_AC3 -> parts += "Dolby Digital"
            MimeTypes.AUDIO_TRUEHD -> parts += "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> parts += "DTS"
            MimeTypes.AUDIO_DTS_HD -> parts += "DTS-HD"
            MimeTypes.AUDIO_DTS_EXPRESS -> parts += "DTS Express"
            MimeTypes.AUDIO_AAC -> parts += "AAC"
            MimeTypes.AUDIO_OPUS -> parts += "Opus"
            MimeTypes.AUDIO_FLAC -> parts += "FLAC"
            MimeTypes.AUDIO_MPEG -> parts += "MP3"
        }
        when (format.channelCount) {
            6 -> parts += "5.1"
            8 -> parts += "7.1"
            2 -> parts += "Stereo"
        }
    } else if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) {
        parts += "erzwungen"
    }
    return parts.joinToString(" · ")
}

@Composable
private fun MenuHeader(text: String) {
    Text(text, color = AyuColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun MenuHint(text: String) {
    Text(text, color = AyuColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "●" else "○", color = if (selected) AyuColors.Accent else AyuColors.TextSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(12.dp))
            Text(label, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
