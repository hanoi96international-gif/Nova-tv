@file:OptIn(UnstableApi::class)

package de.novatv.player

import android.graphics.Color as AndroidColor
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.novatv.ui.components.ChannelListItem
import de.novatv.ui.components.ChannelLogo
import de.novatv.ui.components.ProgressLine
import de.novatv.ui.components.formatDuration
import de.novatv.ui.components.formatTime
import de.novatv.ui.components.progress
import de.novatv.ui.theme.NovaColors
import kotlinx.coroutines.delay

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
                        this.player = player
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
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
    Column(
        Modifier.fillMaxWidth().background(bottomScrim).padding(start = 48.dp, end = 48.dp, top = 64.dp, bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 96.dp, height = 60.dp))
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${channel.number}  ${channel.name}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    if (activity.isFavorite) Text("  ★", color = NovaColors.Accent, fontSize = 22.sp)
                    activity.stats.resolutionLabel.takeIf { it.isNotEmpty() }?.let { Badge(it) }
                }
                Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
            }
            Text(formatTime(System.currentTimeMillis()), style = MaterialTheme.typography.titleLarge, color = Color.White)
        }
        Spacer(Modifier.height(16.dp))
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${formatTime(now.start)} – ${formatTime(now.stop)}", color = NovaColors.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.width(12.dp))
                Text(now.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            ProgressLine(now.progress())
            now.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = NovaColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (next != null) {
                Spacer(Modifier.height(8.dp))
                Text("Danach ${formatTime(next.start)}  ${next.title}", color = NovaColors.TextSecondary, fontSize = 14.sp, maxLines = 1)
            }
        } else {
            Text("Keine Programminformationen", color = NovaColors.TextSecondary)
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "▲▼ Umschalten   OK Senderliste   ▶ Stream-Info   ☰ Favorit",
            color = NovaColors.TextSecondary.copy(alpha = 0.7f),
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
        }
        Spacer(Modifier.height(16.dp))
        ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
        Spacer(Modifier.height(6.dp))
        Row {
            Text(formatDuration(position), color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            if (duration > 0) {
                Text("noch ${formatDuration(duration - position)}  ·  Ende ${formatTime(System.currentTimeMillis() + duration - position)}", color = NovaColors.TextSecondary, fontSize = 14.sp)
            }
        }
        if (activity.showTech) {
            Spacer(Modifier.height(12.dp))
            TechLine(activity.stats)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            (if (activity.player?.playWhenReady == false) "Pausiert  ·  " else "") + "OK Pause   ◀ −10 s   ▶ +30 s (halten = schneller)   ▲ Stream-Info",
            color = NovaColors.TextSecondary.copy(alpha = 0.7f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun TechLine(stats: StreamStats) {
    val parts = buildList {
        if (stats.width > 0) add("${stats.width}×${stats.height}")
        if (stats.frameRate > 0) add("%.2f fps".format(stats.frameRate))
        if (stats.videoCodec.isNotEmpty()) add(stats.videoCodec)
        if (stats.videoBitrate > 0) add("%.1f Mbit/s".format(stats.videoBitrate / 1_000_000f))
        if (stats.audioCodec.isNotEmpty()) add("${stats.audioCodec} ${stats.audioChannels}ch")
        if (stats.decoder.isNotEmpty()) add(stats.decoder)
        if (stats.bandwidth > 0) add("Leitung %.1f Mbit/s".format(stats.bandwidth / 1_000_000f))
        add("Verworfene Frames: ${stats.droppedFrames}")
        if (stats.startupMs > 0) add("Start in ${stats.startupMs} ms")
    }
    Text(parts.joinToString("  ·  "), color = NovaColors.Accent, fontSize = 13.sp)
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        modifier = Modifier.padding(start = 12.dp).clip(RoundedCornerShape(4.dp)).background(NovaColors.AccentSoft).padding(horizontal = 8.dp, vertical = 2.dp),
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
            activity.current?.groupTitle ?: "Sender",
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
