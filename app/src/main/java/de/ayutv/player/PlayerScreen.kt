@file:OptIn(UnstableApi::class)

package de.ayutv.player

import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import kotlin.math.abs
import de.ayutv.ui.components.isTouchDevice
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import de.ayutv.data.AspectMode
import de.ayutv.data.AudioMode
import de.ayutv.data.SubtitleBackground
import de.ayutv.data.SubtitlePosition
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
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
                        subtitleView?.apply {
                            setApplyEmbeddedFontSizes(false)
                            setBottomPaddingFraction(0.07f)
                        }
                        this.player = player
                    }
                },
                update = {
                    it.player = player
                    // Zwei-Finger-Zoom (Handy): Bild füllt den Bildschirm, Ränder werden abgeschnitten.
                    it.resizeMode = when (activity.aspect) {
                        AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    it.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * activity.subtitleScale)
                    // Untertitel für den Fernseher: Farbe, Rand/Schatten/Kasten aus den Einstellungen; eingebettete Stile zählen nicht.
                    it.subtitleView?.setApplyEmbeddedStyles(false)
                    it.subtitleView?.setStyle(captionStyle(activity.subtitleLook))
                    // Eigene Einblendung (Übersetzung, geladene Datei): Die Spur des Players läuft nur als Taktgeber mit.
                    it.subtitleView?.visibility = if (activity.ownSubtitles && !activity.originalVisible) View.INVISIBLE else View.VISIBLE
                    // Untertitel über die Steuerleiste anheben, solange sie sichtbar ist.
                    val barVisible = activity.overlay == Overlay.CONTROLS || activity.overlay == Overlay.INFO
                    it.subtitleView?.setBottomPaddingFraction(if (barVisible) 0.3f else activity.subtitleLook.position.bottomFraction)
                },
                // Nebeneinander (Multiview): Hauptbild links, zweiter Sender rechts, gleich groß.
                modifier = if (activity.pipPlayer != null && activity.pipSplit) Modifier.fillMaxHeight().fillMaxWidth(0.5f).align(Alignment.CenterStart)
                else Modifier.fillMaxSize(),
            )
        }

        if (activity.fatalError) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        activity.pipPlayer?.let { pip ->
            if (activity.pipSplit) SplitWindow(pip, activity.pipChannel?.name.orEmpty(), Modifier.align(Alignment.CenterEnd))
            else PipWindow(pip, activity.pipChannel?.name.orEmpty(), Modifier.align(Alignment.BottomEnd))
        }

        if (activity.syncPanel && activity.overlay == Overlay.NONE) {
            SyncBar(activity, Modifier.align(Alignment.BottomCenter))
        }

        activity.dueReminder?.let { reminder ->
            Column(
                Modifier.align(Alignment.TopEnd).padding(top = 32.dp, end = 32.dp).width(460.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .border(2.dp, AyuColors.Accent, RoundedCornerShape(12.dp))
                    .padding(16.dp),
            ) {
                Text("⏰ Gleich auf ${reminder.channelName}", color = AyuColors.Accent, fontSize = 15.sp)
                Text(reminder.title, color = Color.White, fontSize = 18.sp, maxLines = 2, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "OK umschalten" + (if (activity.isLive) "  ·  ⏩ als kleines Bild" else "") + "  ·  Zurück ausblenden",
                    color = AyuColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        activity.goalNotice?.let { notice ->
            Text(
                notice,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
                    .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                    .border(2.dp, AyuColors.Live, RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp),
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }

        // Bild-in-Bild: nur das Bild, keine Leisten und Hinweise.
        if (!activity.inPip) {

        val touch = isTouchDevice
        if (touch) {
            // Tippen blendet die Leiste ein/aus, doppelt tippen links/rechts spult (Filme).
            // Wischen: rechts Lautstärke, links Helligkeit, waagerecht spulen, Live in der Mitte hoch/runter umschalten.
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(activity.isLive) {
                        detectTapGestures(
                            onTap = { activity.onScreenTap() },
                            onDoubleTap = if (activity.isLive) null else { offset: Offset ->
                                activity.pressKey(if (offset.x < size.width / 2) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
                            },
                        )
                    }
                    .pointerInput(activity.isLive) { playerGestures(activity) }
            )
        }

        if (activity.buffering && activity.message == null) {
            Text(
                "Lädt …",
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).padding(16.dp),
                color = Color.White,
            )
        }

        activity.gestureInfo?.let {
            Text(
                it,
                modifier = Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 14.dp),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
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

        if (!activity.isLive && activity.overlay != Overlay.CONTROLS) {
            val press = Modifier.pointerInput(Unit) { detectTapGestures { activity.pressKey(KeyEvent.KEYCODE_DPAD_CENTER) } }
            when {
                activity.nextCardVisible -> activity.nextEpisode?.let { next ->
                    EpisodeCard(
                        title = "Nächste Folge: S${next.episode.season} E${next.episode.episode}  ${next.episode.title}",
                        hint = if (activity.nextCountdown >= 0) "Startet in ${activity.nextCountdown} s  ·  OK jetzt  ·  andere Taste: warten"
                            else "OK: jetzt starten",
                        modifier = Modifier.align(Alignment.BottomEnd).then(press),
                    )
                }
                activity.introVisible -> EpisodeCard(activity.introLabel, if (touch) "Tippen" else "OK drücken", Modifier.align(Alignment.BottomEnd).then(press))
            }
        }

        // Vorlauf der Übersetzung: kurz angehalten, bis die nächste Minute übersetzt ist.
        activity.prerollProgress?.let { progress ->
            val press = Modifier.pointerInput(Unit) { detectTapGestures { activity.endPreroll() } }
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 560.dp).then(press)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
            ) {
                Text("Untertitel werden übersetzt … ${(progress * 100).toInt()} %", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress)
                Spacer(Modifier.height(8.dp))
                Text(if (touch) "Tippen: sofort weiterschauen" else "OK: sofort weiterschauen", color = AyuColors.Accent, fontSize = 14.sp)
            }
        }

        // Knöpfe statt Fernbedienung, solange die Leiste zu sehen ist.
        if (touch && (activity.overlay == Overlay.INFO && activity.isLive || activity.overlay == Overlay.CONTROLS && !activity.isLive)) {
            TouchButtons(activity, Modifier.align(Alignment.TopEnd))
        }

        if (activity.ownSubtitles) {
            activity.translatedCue?.let { cue ->
                // Über die Steuerleiste anheben, solange sie sichtbar ist (wie beim Player).
                val barVisible = activity.overlay == Overlay.CONTROLS || activity.overlay == Overlay.INFO || activity.syncPanel
                val look = activity.subtitleLook
                val bottom = when (look.position) {
                    SubtitlePosition.LOW -> 20.dp
                    SubtitlePosition.NORMAL -> 40.dp
                    SubtitlePosition.HIGH -> 80.dp
                }
                Text(
                    cue,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 64.dp, end = 64.dp, bottom = if (barVisible) 200.dp else bottom)
                        .then(
                            if (look.background == SubtitleBackground.BOX) {
                                Modifier.background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 2.dp)
                            } else Modifier
                        ),
                    color = Color(look.color.argb),
                    fontSize = (22 * activity.subtitleScale).sp,
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        shadow = when (look.background) {
                            SubtitleBackground.BOX -> null
                            SubtitleBackground.OUTLINE -> Shadow(Color.Black, Offset(0f, 0f), blurRadius = 4f)
                            SubtitleBackground.SHADOW -> Shadow(Color.Black, Offset(3f, 3f), blurRadius = 6f)
                        },
                    ),
                )
            }
        }

        activity.subtitleStatus?.let { status ->
            Text(
                status,
                // Rechts steht bei offenem Menü die Spurenliste: dann links oben, sonst überdecken sich beide.
                modifier = Modifier.align(if (activity.overlay == Overlay.TRACKS) Alignment.TopStart else Alignment.TopEnd).padding(28.dp)
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
    }

    // Overlays blenden sich nach kurzer Zeit aus. Jede Taste setzt den Timer zurück (overlayToken).
    LaunchedEffect(activity.overlayToken, activity.overlay) {
        val timeout = when (activity.overlay) {
            Overlay.NONE -> return@LaunchedEffect
            // Angehaltenes Live-TV: Leiste stehen lassen, damit man sieht, wie weit es zurückliegt.
            Overlay.INFO -> if (activity.timeshifted && activity.player?.playWhenReady == false) Long.MAX_VALUE
                else if (activity.showTech) 10_000L else 5_000L
            // Angehalten: Leiste bleibt. Nicht isPlaying nehmen: beim Laden ist das auch false, dann
            // bliebe die Leiste nach dem Start eines Films für immer stehen (und OK hielte ihn an).
            Overlay.CONTROLS -> if (activity.player?.playWhenReady == false) Long.MAX_VALUE else 4_000L
            // Beim Zuordnen des TV-Guides (Suchen, Blättern in langen Listen) nicht von selbst schließen.
            Overlay.TRACKS -> if (activity.epgPicker || activity.pipChoices != null) Long.MAX_VALUE else 15_000L
            Overlay.CHANNELS -> 15_000L
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
    val stats = activity.stats
    val badges = remember(stats.height, stats.frameRate, stats.hdr, stats.audioCodec, stats.audioChannels) {
        de.ayutv.data.parse.StreamBadges.video(stats.height, stats.frameRate, stats.hdr) +
            listOfNotNull(de.ayutv.data.parse.StreamBadges.audio(stats.audioCodec, stats.audioChannels))
    }
    val headline = remember(now) { now?.headlineWithBadge() }
    val description = remember(now) { now?.let { de.ayutv.data.parse.ProgrammeText.description(it.description) } }
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 72.dp, bottom = 28.dp)
    ) {
        // Kopf: Logo, Nummer und Name, Kennzeichen (was der Sender gerade wirklich liefert), rechts die Uhrzeit.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 112.dp, height = 68.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}", style = MaterialTheme.typography.titleLarge, color = AyuColors.Accent)
                    Spacer(Modifier.width(10.dp))
                    Text(channel.name, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (activity.isFavorite) Text("  ★", color = AyuColors.Accent, fontSize = 22.sp)
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary, maxLines = 1)
                    badges.forEach { Badge(it) }
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatTime(System.currentTimeMillis()), style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text(
                    java.text.SimpleDateFormat("EEE, d. MMM", Locale.GERMAN).format(java.util.Date()),
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (now != null) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("JETZT", color = AyuColors.Live, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                        Text("${formatTime(now.start)} – ${formatTime(now.stop)}", color = AyuColors.TextSecondary, fontSize = 14.sp)
                        de.ayutv.data.parse.StreamBadges.remaining(now.stop)?.let {
                            Text("  ·  $it", color = AyuColors.TextSecondary, fontSize = 14.sp)
                        }
                    }
                    Text(headline.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    ProgressLine(now.progress())
                    description?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (next != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DANACH", color = AyuColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text("${formatTime(next.start)}  ${next.headline()}", color = AyuColors.TextSecondary, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // Bild zur Sendung (wenn der TV-Guide eins hat), rechts daneben.
                if (!now.icon.isNullOrBlank()) {
                    Spacer(Modifier.width(24.dp))
                    de.ayutv.ui.components.ProgrammeImage(now.icon, Modifier.width(208.dp).height(117.dp))
                }
            }
        } else {
            Text("Keine Programminformationen", color = AyuColors.TextSecondary)
        }
        if (activity.timeshifted || activity.rewindableMs > 0) {
            Spacer(Modifier.height(12.dp))
            TimeshiftLine(activity)
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats, activity.bufferedMs, activity.cushionActive, activity.dropInfo, activity.routeLabel())
        }
        activity.syncBadge?.let { badge ->
            Spacer(Modifier.height(8.dp))
            Text(badge, color = Color(0xFF7FD69B), fontSize = 14.sp, maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (activity.timeshifted) "OK Pause/Weiter   ◀ −10 s   ▶ +30 s (bis zum Ende = Live)   ▲▼ Umschalten   ☰ Ton/Untertitel"
            else "▲▼ Umschalten   OK Senderliste   " + (activity.previousChannel?.let { "◀ zurück zu ${it.name}   " } ?: "") +
                "▶ Stream-Info   ⏯ Pause   ⏪ Zurückspulen   ☰ Ton/Untertitel/Favorit",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

private enum class Swipe { NONE, VOLUME, BRIGHTNESS, SEEK, ZAP, PINCH }

/**
 * Wischgesten wie in Handy-Videoplayern. Erst ab einer kleinen Strecke entschieden (sonst ist es ein Tippen),
 * danach bleibt es bei dieser Geste, bis der Finger losgelassen wird.
 */
private suspend fun PointerInputScope.playerGestures(activity: PlayerActivity) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        var mode = Swipe.NONE
        var last = start
        var pinchStart = 0f
        val slop = viewConfiguration.touchSlop * 2
        while (true) {
            val event = awaitPointerEvent()
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size >= 2) {
                val distance = (pressed[0].position - pressed[1].position).getDistance()
                if (mode != Swipe.PINCH) {
                    mode = Swipe.PINCH
                    pinchStart = distance
                } else if (pinchStart > 0f) {
                    if (distance / pinchStart > 1.25f) activity.zoomGesture(true)
                    if (distance / pinchStart < 0.8f) activity.zoomGesture(false)
                }
                event.changes.forEach { it.consume() }
                continue
            }
            if (mode == Swipe.PINCH) continue
            val change = pressed.first()
            val pos = change.position
            if (mode == Swipe.NONE) {
                val dx = pos.x - start.x
                val dy = pos.y - start.y
                if (abs(dx) < slop && abs(dy) < slop) continue
                mode = when {
                    abs(dx) > abs(dy) -> Swipe.SEEK
                    activity.isLive && start.x in size.width / 3f..size.width * 2 / 3f -> Swipe.ZAP
                    start.x > size.width / 2f -> Swipe.VOLUME
                    else -> Swipe.BRIGHTNESS
                }
                last = pos
            }
            when (mode) {
                Swipe.VOLUME -> activity.adjustVolume((last.y - pos.y) / size.height * 1.5f)
                Swipe.BRIGHTNESS -> activity.adjustBrightness((last.y - pos.y) / size.height * 1.5f)
                Swipe.SEEK -> activity.seekGesture((pos.x - start.x) / size.width, done = false)
                else -> Unit
            }
            last = pos
            change.consume()
        }
        when (mode) {
            Swipe.SEEK -> activity.seekGesture((last.x - start.x) / size.width, done = true)
            Swipe.ZAP -> if (abs(last.y - start.y) > size.height / 6f) activity.zapGesture(up = last.y < start.y)
            else -> Unit
        }
    }
}

/** Bedienknöpfe für den Touchscreen: dieselben Funktionen wie die Tasten der Fernbedienung. */
@Composable
private fun TouchButtons(activity: PlayerActivity, modifier: Modifier = Modifier) {
    val paused = activity.player?.playWhenReady == false
    Row(
        modifier.padding(16.dp).clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun button(label: String, action: () -> Unit) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(30.dp)).clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        }
        if (activity.isLive) {
            button("▲") { activity.pressKey(KeyEvent.KEYCODE_CHANNEL_UP) }
            button("▼") { activity.pressKey(KeyEvent.KEYCODE_CHANNEL_DOWN) }
            button("☰") { activity.openChannelList() }
            button("⏪") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_REWIND) }
            button(if (paused) "▶" else "⏸") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            button("⏩") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) }
            button(if (activity.isFavorite) "★" else "☆") { activity.pressKey(KeyEvent.KEYCODE_PROG_RED) }
        } else {
            button("⏪") { activity.pressKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            button(if (paused) "▶" else "⏸") { activity.pressKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
            button("⏩") { activity.pressKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        button("CC") { activity.pressKey(KeyEvent.KEYCODE_MENU) }
        if (activity.castAvailable) button(if (activity.castingTo != null) "📺✓" else "📺") { activity.toggleCast() }
        button("✕") { activity.finish() }
    }
}

/** Knopf unten rechts (Intro überspringen, nächste Folge): reagiert auf OK, solange die Steuerleiste zu ist. */
@Composable
private fun EpisodeCard(title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(end = 48.dp, bottom = 56.dp).widthIn(max = 520.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.82f))
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(hint, color = AyuColors.Accent, fontSize = 14.sp)
    }
}

/** Zeitversatz: wie weit das Bild hinter Live liegt und wie weit sich zurückspulen lässt. */
@Composable
private fun TimeshiftLine(activity: PlayerActivity) {
    val window = activity.rewindableMs
    val behind = activity.behindLiveMs.coerceAtMost(window)
    val paused = activity.timeshifted && activity.player?.playWhenReady == false
    Row(verticalAlignment = Alignment.CenterVertically) {
        val state = when {
            paused -> "⏸ Angehalten"
            activity.timeshifted -> "▶ Zeitversetzt"
            else -> "● Live"
        }
        Text(state, color = if (activity.timeshifted) AyuColors.Accent else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            ProgressLine(if (window > 0) (window - behind).toFloat() / window else 1f)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (activity.timeshifted) "−${formatDuration(behind)} hinter Live" else "${formatDuration(window)} zurückspulbar",
            color = AyuColors.TextSecondary,
            fontSize = 14.sp,
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
        // Touch: auf den Balken tippen springt dorthin (großzügige Trefferfläche um die dünne Linie).
        Box(
            Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                detectTapGestures { offset -> activity.seekToFraction(offset.x / size.width) }
            },
            contentAlignment = Alignment.Center,
        ) {
            ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
        }
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
            TechLine(activity.stats, activity.bufferedMs, activity.cushionActive, activity.dropInfo, activity.routeLabel())
        }
        activity.syncBadge?.let { badge ->
            Spacer(Modifier.height(8.dp))
            Text(badge, color = if (badge.startsWith("✓")) Color(0xFF7FD69B) else Color(0xFFFFC870), fontSize = 14.sp, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (activity.player?.playWhenReady == false) "Pausiert  ·  " else "") + "OK Pause   ◀ −10 s   ▶ +30 s (halten = schneller)   ▲ Stream-Info   ☰ Ton/Untertitel",
            color = AyuColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun TechLine(stats: StreamStats, bufferedMs: Long, cushion: Boolean, drops: String, route: String) {
    val parts = buildList {
        add("Puffer %.1f s".format(bufferedMs / 1000f) + if (cushion) " (wird aufgefüllt)" else "")
        if (stats.width > 0) add("${stats.width}×${stats.height}")
        if (stats.frameRate > 0) add("%.2f fps".format(stats.frameRate) + if (stats.frameRateMeasured) " (gemessen)" else "")
        if (stats.displayHz > 0) add("Fernseher %.2f Hz".format(stats.displayHz))
        if (stats.videoCodec.isNotEmpty()) add(stats.videoCodec)
        if (stats.videoBitrate > 0) add("%.1f Mbit/s".format(stats.videoBitrate / 1_000_000f))
        if (stats.audioCodec.isNotEmpty()) add("${stats.audioCodec} ${stats.audioChannels}ch")
        if (stats.audioDecoder.isNotEmpty()) add("Ton: " + if ("ffmpeg" in stats.audioDecoder.lowercase()) "FFmpeg" else stats.audioDecoder)
        if (stats.decoder.isNotEmpty()) add(stats.decoder)
        if (stats.bandwidth > 0) add("Datenrate des Senders %.1f Mbit/s".format(stats.bandwidth / 1_000_000f))
        add("Verworfene Frames: ${stats.droppedFrames}")
        if (stats.startupMs > 0) add("Start in ${stats.startupMs} ms")
        if (drops.isNotEmpty()) add(drops)
        add(route)
    }
    Text(parts.joinToString("  ·  "), color = AyuColors.Accent, fontSize = 13.sp)
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(4.dp)).background(AyuColors.AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 13.sp,
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
    if (activity.epgPicker) {
        EpgPicker(activity)
        return
    }
    activity.pipChoices?.let { choices ->
        PipPicker(activity, choices)
        return
    }
    val tracks = activity.tracks
    // Ton: auch Spuren zeigen, die das Gerät nicht abspielen kann, damit klar ist, warum es still bleibt.
    val audio = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_AUDIO, includeUnsupported = true) }
    val text = remember(tracks) { trackOptions(tracks, C.TRACK_TYPE_TEXT) }
    val firstItem = remember { FocusRequester() }
    val preferred = activity.preferredSubtitleLanguage
    val liveTarget = activity.liveTranslateTarget
    val external = activity.externalLanguage

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
            item {
                OptionRow("⏺ Diese Sendung aufnehmen", selected = false) { activity.recordCurrent() }
            }
            item {
                OptionRow("📺 Programm falsch? TV-Guide zuordnen …", selected = false) { activity.openEpgPicker() }
            }
            if (activity.pipPlayer == null) item {
                OptionRow("⧉ Zweites Spiel als kleines Bild (Konferenz) …", selected = false) { activity.openPipPicker() }
            } else {
                item { OptionRow("⇄ Bilder tauschen (auch mit ⏩)", selected = false) { activity.swapPip() } }
                item {
                    OptionRow(if (activity.pipSplit) "◱ Zweiten Sender als kleines Bild" else "◫ Nebeneinander (Multiview, gleich groß)", selected = false) { activity.togglePipSplit() }
                }
                item { OptionRow("⧉ Anderes kleines Bild …", selected = false) { activity.openPipPicker() } }
                item { OptionRow("✕ Kleines Bild schließen", selected = false) { activity.closePip() } }
            }
        }
        if (activity.vlcAvailable) item {
            OptionRow("▶ In VLC öffnen (wenn dieser Stream hier hakt)", selected = false) { activity.openInVlc() }
        }
        // Serien: jederzeit zur nächsten Folge (wer das im Abspann tut, bringt DnA-TV bei, wo der Abspann beginnt).
        activity.nextEpisode?.let { next ->
            item {
                OptionRow("⏭ Nächste Folge: S${next.episode.season} E${next.episode.episode}", selected = false) { activity.playNextEpisode() }
            }
        }
        item { MenuHeader("Untertitel") }
        item {
            OptionRow(
                "Aus",
                !activity.ownSubtitles && (activity.textDisabled || text.none { it.selected }),
                if (!activity.isLive) Modifier.focusRequester(firstItem) else Modifier,
            ) { activity.setTextEnabled(false) }
        }
        text.forEach { option ->
            item {
                OptionRow(option.label, option.selected && !activity.textDisabled && !activity.ownSubtitles) {
                    activity.selectTrack(option.group, option.index)
                }
            }
        }
        activity.loadedLanguages.forEach { language ->
            item {
                OptionRow("${Languages.label(language)} (geladen)", external == language) { activity.showLoaded(language) }
            }
        }
        // Immer die Wahl: Datei von OpenSubtitles oder die Spur des Films übersetzen (je Film bzw. Serie gemerkt).
        activity.sourceChoiceLanguage?.let { language ->
            val label = Languages.label(language)
            item { MenuHeader("$label: Quelle") }
            item {
                val fit = activity.matchPercent?.takeIf { external == language }?.let { " · passt $it %" }
                    ?: activity.autoSwitched?.takeIf { liveTarget == language }?.let { " · passte nur $it %" }
                    ?: ""
                OptionRow("OpenSubtitles$fit", external == language) { activity.chooseSubtitleSource(language, translate = false) }
            }
            item {
                OptionRow("Übersetzung der Film-Spur (${activity.translatorName})", liveTarget == language) {
                    activity.chooseSubtitleSource(language, translate = true)
                }
            }
        }
        if (external != null) {
            item { MenuHeader("Untertitel-Zeit") }
            item { OptionRow("Untertitel-Zeit einstellen … (${activity.subtitleShiftLabel})", selected = false) { activity.openSyncPanel() } }
            item {
                val fit = activity.matchPercent?.let { " (diese passt $it %)" }.orEmpty()
                OptionRow("Andere Untertitel-Datei laden$fit", selected = false) { activity.reportWrongSubtitles() }
            }
            item { MenuHint(syncHint(activity)) }
            activity.speechSyncInfo?.let { info -> item { MenuHint("Ausrichtung: $info") } }
            activity.fileTranslationInfo?.let { info -> item { MenuHint(info) } }
        }
        if (!activity.isLive) {
            if (preferred !in activity.loadedLanguages) {
                item { OptionRow("${Languages.label(preferred)} laden", selected = false) { activity.loadSubtitles(preferred) } }
            }
            item { OptionRow("Andere Sprache laden / übersetzen …", selected = false) { activity.openLanguagePicker() } }
        }
        if (activity.translationAvailable && external == null && text.any { it.selected } && !activity.textDisabled) {
            if (liveTarget == null) {
                item { OptionRow("Gewählte Spur live übersetzen: ${Languages.label(preferred)}", false) { activity.startLiveTranslation(preferred) } }
            } else {
                item { OptionRow("Live-Übersetzung (${Languages.label(liveTarget)}) beenden", true) { activity.stopLiveTranslation() } }
            }
        }
        activity.lastOnlineProblem?.let { problem -> item { MenuHint("Internet-Untertitel: $problem") } }
        if (!activity.isLive && !activity.onlineSubtitlesAvailable && !activity.translationAvailable) {
            item { MenuHint("Für Filme ganz ohne Untertitel: OpenSubtitles verbinden (Einstellungen → Einrichtung per Handy)") }
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
        item { MenuHeader("Klang") }
        AudioMode.entries.forEach { mode ->
            item { OptionRow(mode.label, activity.audioMode == mode) { activity.chooseAudioMode(mode) } }
        }
        item { OptionRow("Lautstärke angleichen (alle Sender)", activity.loudnessLevel) { activity.toggleLoudness() } }
        item { MenuHint("Wirkt, wenn der Ton im Gerät dekodiert wird – nicht bei Dolby/DTS, das an Soundbar oder Receiver geht (Einstellungen → Tonausgabe: „Immer im Gerät dekodieren“).") }
        item { MenuHeader("Bild") }
        AspectMode.entries.forEach { mode ->
            item { OptionRow(mode.label, activity.aspect == mode) { activity.chooseAspect(mode) } }
        }
        item { MenuHeader("Schlaf-Timer") }
        if (activity.sleepAt > 0) {
            item { MenuHint("Endet um ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(activity.sleepAt))}") }
            item { OptionRow("Aus", selected = false) { activity.setSleepTimer(0) } }
        }
        listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
            item { OptionRow("In $minutes Minuten", selected = false) { activity.setSleepTimer(minutes) } }
        }
        item {
            OptionRow(if (activity.isLive) "Am Ende der Sendung" else "Am Ende des Films / der Folge", selected = false) {
                activity.setSleepTimer(PlayerActivity.SLEEP_END)
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
                "DnA-TV nimmt eine passende Spur oder lädt Untertitel. Gibt es keine, " +
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

/**
 * TV-Guide von Hand zuordnen: alle Sender der geladenen EPGs, die mit ähnlichem Namen zuerst. Die Wahl gilt
 * dauerhaft für diesen Sender (auch nach dem Neuladen der Playlist).
 */
@Composable
private fun EpgPicker(activity: PlayerActivity) {
    val channel = activity.current ?: return
    var filter by remember { mutableStateOf("") }
    val firstItem = remember { FocusRequester() }
    // Schon nach Ähnlichkeit sortiert (im Hintergrund, PlayerActivity.openEpgPicker); das Suchfeld filtert nur noch.
    val choices = remember(activity.epgChoices, filter) {
        val f = filter.trim().lowercase()
        activity.epgChoices.asSequence().filter { f.isEmpty() || f in it.name.lowercase() || f in it.id.lowercase() }.take(300).toList()
    }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().width(460.dp).background(Color.Black.copy(alpha = 0.94f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Programm für „${channel.name}“") }
        item { MenuHint("Den Sender wählen, dessen Programm stimmt. Gilt dauerhaft, der TV-Guide wird danach neu geladen.") }
        item {
            de.ayutv.ui.components.TvTextField(filter, { filter = it }, "Suchen", modifier = Modifier.fillMaxWidth())
        }
        if (activity.epgOverride != null) item {
            OptionRow("↺ Wieder automatisch zuordnen", selected = false) { activity.assignEpg(null) }
        }
        when {
            activity.epgChoicesLoading -> item { MenuHint("Sender der TV-Guides werden geladen …") }
            activity.epgChoices.isEmpty() -> item { MenuHint("Noch kein TV-Guide geladen.") }
        }
        choices.forEachIndexed { index, c ->
            item(key = c.id) {
                OptionRow(
                    "${c.name}  ·  ${c.source}",
                    selected = c.id == (activity.epgOverride ?: channel.epgId),
                    modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier,
                ) { activity.assignEpg(c.id) }
            }
        }
    }
    LaunchedEffect(choices.isNotEmpty()) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Zweites Bild: laufende Spiele, voriger Sender, Favoriten. */
@Composable
private fun PipPicker(activity: PlayerActivity, choices: List<Pair<de.ayutv.data.db.ChannelEntity, String>>) {
    val firstItem = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier.fillMaxHeight().width(460.dp).background(Color.Black.copy(alpha = 0.94f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 24.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item { MenuHeader("Kleines Bild dazu") }
        item { MenuHint("Läuft ohne Ton unten rechts. ⏩ tauscht die Bilder, ☰ schließt. Braucht eine zweite Verbindung beim Anbieter.") }
        if (choices.isEmpty()) item { MenuHint("Gerade kein Spiel, kein voriger Sender und keine Favoriten.") }
        choices.forEachIndexed { index, (channel, label) ->
            item(key = channel.key) {
                OptionRow(label, selected = false, modifier = if (index == 0) Modifier.focusRequester(firstItem) else Modifier) {
                    activity.startPip(channel)
                }
            }
        }
    }
    LaunchedEffect(choices.isNotEmpty()) {
        repeat(5) {
            if (runCatching { firstItem.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}

/** Das kleine Bild: eigene Fläche über dem Hauptbild (SurfaceView als Medien-Overlay). */
@Composable
private fun PipWindow(player: androidx.media3.exoplayer.ExoPlayer, label: String, modifier: Modifier) {
    Column(modifier.padding(end = 36.dp, bottom = 36.dp).width(420.dp)) {
        androidx.compose.runtime.key(player) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context).apply {
                        setZOrderMediaOverlay(true)
                        player.setVideoSurfaceView(this)
                    }
                },
                onRelease = { view -> player.clearVideoSurfaceView(view) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .border(2.dp, Color.White.copy(alpha = 0.8f)),
            )
        }
        Text(
            "$label  ·  ⏩ tauschen",
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.7f)).padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Multiview: zweiter Sender gleich groß rechts neben dem Hauptbild (ohne Ton; ⏩ tauscht, der Ton folgt dem Hauptbild). */
@Composable
private fun SplitWindow(player: androidx.media3.exoplayer.ExoPlayer, label: String, modifier: Modifier) {
    Box(modifier.fillMaxHeight().fillMaxWidth(0.5f)) {
        androidx.compose.runtime.key(player) {
            AndroidView(
                factory = { context ->
                    android.view.SurfaceView(context).apply {
                        setZOrderMediaOverlay(true)
                        player.setVideoSurfaceView(this)
                    }
                },
                onRelease = { view -> player.clearVideoSurfaceView(view) },
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).align(Alignment.Center),
            )
        }
        Text(
            "$label  ·  ⏩ tauschen",
            color = Color.White,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Ausrichten mit einem Druck: die nächste Zeile groß, OK sobald sie gesprochen wird. */
@Composable
private fun SyncBar(activity: PlayerActivity, modifier: Modifier) {
    val index = activity.syncCandidate
    Column(
        modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 28.dp)
            .background(Color.Black.copy(alpha = 0.88f), RoundedCornerShape(14.dp))
            .border(2.dp, AyuColors.Accent, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Untertitel ausrichten", color = AyuColors.Accent, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text("jetzt ${activity.subtitleShiftLabel}", color = AyuColors.TextSecondary, fontSize = 14.sp)
        }
        activity.syncCue(index - 1)?.let { Text(it, color = AyuColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        Text(
            activity.syncCue(index)?.let { "„$it“" } ?: "Keine Zeilen geladen",
            color = Color.White,
            fontSize = 26.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        activity.syncCue(index + 1)?.let { Text(it, color = AyuColors.TextSecondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(
            activity.syncConfirmed ?: "OK drücken, sobald diese Zeile gesprochen wird",
            color = if (activity.syncConfirmed != null) Color(0xFF7FD69B) else Color.White,
            fontSize = 17.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
        Text(
            "◀ ▶ andere Zeile   ·   ▲ früher  ▼ später (½ s)   ·   ⏯ Pause   ·   Zurück: fertig",
            color = AyuColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Untertitel des Players im gewählten Aussehen. */
private fun captionStyle(look: SubtitleLook): CaptionStyleCompat = CaptionStyleCompat(
    look.color.argb,
    if (look.background == SubtitleBackground.BOX) 0xB3000000.toInt() else AndroidColor.TRANSPARENT,
    AndroidColor.TRANSPARENT,
    when (look.background) {
        SubtitleBackground.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleBackground.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleBackground.BOX -> CaptionStyleCompat.EDGE_TYPE_NONE
    },
    AndroidColor.BLACK,
    null,
)

/** Wie die geladenen Untertitel gerade liegen, in Worten. */
private fun syncHint(activity: PlayerActivity): String {
    val timing = activity.subtitleTiming
    val total = timing.offsetMs + activity.subtitleDelayMs
    val shift = String.format(Locale.GERMAN, "%+.1f s", total / 1000f)
    return when {
        activity.subtitleAutoSynced && activity.subtitleDelayMs == 0L ->
            (if (activity.syncedBySpeech) "Automatisch am gesprochenen Ton ausgerichtet ($shift" else "Automatisch an den eingebetteten Untertiteln ausgerichtet ($shift") +
                (if (timing.ratio != 1.0) ", Bildrate korrigiert" else "") + ")."
        activity.subtitleAutoSynced -> "Automatisch ausgerichtet, von Hand nachgestellt: $shift."
        else -> "Verschiebung: $shift. DnA-TV richtet die Zeiten nach wenigen Minuten automatisch aus (an eingebetteten Untertiteln oder am gesprochenen Ton)."
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
    Text(text, color = AyuColors.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
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
