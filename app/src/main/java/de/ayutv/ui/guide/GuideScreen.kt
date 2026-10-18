package de.ayutv.ui.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val WINDOW = 4 * HALF_HOUR
// 1080p-Fernseher haben 960 dp Breite: Navigation + Senderspalte + 2 Stunden à 5 dp/min passen genau.
private val DP_PER_MINUTE = 5.dp
private val CHANNEL_COLUMN = 190.dp
private val ROW_HEIGHT = 58.dp

/**
 * EPG-Raster mit festem Zeitfenster (2 Stunden). Links am ersten bzw. rechts am letzten Eintrag
 * einer Zeile verschiebt das Fenster um 30 Minuten. Geladen werden nur die Sendungen der gewählten Gruppe.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen() {
    val content = LocalContainer.current.content
    val context = LocalContext.current
    val groups by content.groups.collectAsStateWithLifecycle(emptyList())
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    val activeGroup = group ?: groups.firstOrNull()?.name
    val channels by remember(activeGroup) { content.channelsIn(activeGroup) }.collectAsStateWithLifecycle(emptyList())
    val minute by rememberMinuteTicker()

    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val windowEnd = windowStart + WINDOW
    var programmes by remember { mutableStateOf<Map<String, List<ProgrammeEntity>>>(emptyMap()) }
    LaunchedEffect(channels, windowStart) { programmes = content.programmesInRange(channels, windowStart, windowEnd) }

    val earliest = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - 2 * HALF_HOUR
    val shift: (Int) -> Unit = { steps ->
        windowStart = (windowStart + steps * HALF_HOUR).coerceAtLeast(earliest)
    }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(groups, key = { it.name }) { g ->
                val selected = g.name == activeGroup
                Surface(
                    onClick = { group = g.name },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
                        focusedContainerColor = AyuColors.Accent,
                        focusedContentColor = Color.Black,
                    ),
                ) {
                    Text(g.name, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.padding(start = 24.dp)) {
            Spacer(Modifier.width(CHANNEL_COLUMN))
            for (i in 0 until 4) {
                Text(
                    formatTime(windowStart + i * HALF_HOUR),
                    modifier = Modifier.width(DP_PER_MINUTE * 30),
                    color = AyuColors.TextSecondary,
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        if (channels.isEmpty()) {
            EmptyHint("Kein TV-Guide", "Für diese Gruppe sind keine Sender vorhanden.")
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().focusRestorer(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(channels, key = { it.key }) { channel ->
                GuideRow(
                    channel = channel,
                    programmes = channel.epgId?.let { programmes[it] }.orEmpty(),
                    windowStart = windowStart,
                    windowEnd = windowEnd,
                    now = minute,
                    onShift = shift,
                    onPlay = { PlayerActivity.startLive(context, channel.key, channel.groupTitle) },
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channel: ChannelEntity,
    programmes: List<ProgrammeEntity>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    onShift: (Int) -> Unit,
    onPlay: () -> Unit,
) {
    Row(Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(CHANNEL_COLUMN).fillMaxHeight().background(AyuColors.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 48.dp, height = 32.dp))
            Spacer(Modifier.width(10.dp))
            Text(channel.name, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(4.dp))

        val cells = remember(programmes, windowStart) { layoutCells(programmes, windowStart, windowEnd) }
        if (cells.isEmpty()) {
            GuideCell("Keine Programmdaten", null, DP_PER_MINUTE * (WINDOW / MINUTE).toInt(), live = true, first = true, last = true, onShift, onPlay)
        } else {
            cells.forEachIndexed { index, cell ->
                if (cell.programme == null) {
                    Spacer(Modifier.width(cell.width))
                } else {
                    val p = cell.programme ?: return@forEachIndexed
                    GuideCell(
                        title = p.title.ifEmpty { "Ohne Titel" },
                        time = "${formatTime(p.start)} – ${formatTime(p.stop)}",
                        width = cell.width,
                        live = p.start <= now && p.stop > now,
                        first = index == cells.indexOfFirst { it.programme != null },
                        last = index == cells.indexOfLast { it.programme != null },
                        onShift = onShift,
                        onClick = onPlay,
                    )
                }
            }
        }
    }
}

private class Cell(val programme: ProgrammeEntity?, val width: Dp)

private fun layoutCells(programmes: List<ProgrammeEntity>, windowStart: Long, windowEnd: Long): List<Cell> {
    val cells = ArrayList<Cell>()
    var cursor = windowStart
    for (p in programmes.sortedBy { it.start }) {
        val start = maxOf(p.start, cursor)
        val end = minOf(p.stop, windowEnd)
        if (end <= start) continue
        if (start > cursor) cells += Cell(null, DP_PER_MINUTE * ((start - cursor) / MINUTE).toInt())
        cells += Cell(p, DP_PER_MINUTE * ((end - start) / MINUTE).toInt().coerceAtLeast(4))
        cursor = end
    }
    return cells
}

@Composable
private fun GuideCell(
    title: String,
    time: String?,
    width: Dp,
    live: Boolean,
    first: Boolean,
    last: Boolean,
    onShift: (Int) -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    first && event.key == Key.DirectionLeft -> { onShift(-1); true }
                    last && event.key == Key.DirectionRight -> { onShift(+1); true }
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) AyuColors.SurfaceRaised else AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Column {
                Text(
                    title,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (live || focused) AyuColors.TextPrimary else AyuColors.TextSecondary,
                )
                if (time != null && width > 90.dp) {
                    Text(time, fontSize = 11.sp, color = AyuColors.TextSecondary, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
