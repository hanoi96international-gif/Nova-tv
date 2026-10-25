package de.ayutv.ui.guide

import androidx.compose.runtime.produceState
import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
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
import androidx.compose.runtime.key
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
import de.ayutv.ui.components.Surface
import de.ayutv.ui.components.isTouchDevice
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
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.launch
import de.ayutv.ui.live.FAVORITES_GROUP
import de.ayutv.data.repo.Catchup
import de.ayutv.data.reminders.Reminder
import de.ayutv.data.db.PlaylistEntity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope

private const val MINUTE = 60_000L
private const val HALF_HOUR = 30 * MINUTE
private const val WINDOW = 4 * HALF_HOUR
private const val DAY = 24 * 60 * MINUTE
// 1080p-Fernseher haben 960 dp Breite: Navigation + Senderspalte + 2 Stunden à 5 dp/min passen genau.
private val DP_PER_MINUTE = 5.dp
private val CHANNEL_COLUMN = 190.dp
private val ROW_HEIGHT = 58.dp

/**
 * EPG-Raster mit festem Zeitfenster (2 Stunden). Links am ersten bzw. rechts am letzten Eintrag
 * einer Zeile verschiebt das Fenster um 30 Minuten. Geladen werden nur die Sendungen der gewählten Gruppe.
 * Oben steht die Vorschau der markierten Sendung, eine rote Linie zeigt „jetzt“. Vergangene Sendungen
 * von Sendern mit Archiv (↺) lassen sich direkt abspielen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GuideScreen() {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val groups by content.groups.collectAsStateWithLifecycle(emptyList())
    val favorites by content.favoriteChannels.collectAsStateWithLifecycle(emptyList())
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(emptyList())
    var group by rememberSaveable { mutableStateOf<String?>(null) }
    // Ohne eigene Wahl: Favoriten zuerst (wie in Live TV), sonst die erste Gruppe.
    val activeGroup = group ?: if (favorites.isNotEmpty()) FAVORITES_GROUP else groups.firstOrNull()?.name
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val rawChannels by remember(activeGroup) {
        if (activeGroup == FAVORITES_GROUP) content.favoriteChannels else content.channelsIn(activeGroup)
    }.collectAsStateWithLifecycle(emptyList())
    // Jeder Sender eine Zeile, auch wenn der Anbieter ihn in SD, HD und 4K führt.
    val channels by produceState(emptyList<ChannelEntity>(), rawChannels, appSettings?.mergeVariants, activeGroup) {
        value = if (appSettings?.mergeVariants != false && activeGroup != FAVORITES_GROUP) content.mergeVariants(rawChannels) else rawChannels
    }
    val minute by rememberMinuteTicker()
    // Nur die Vorschau liest diesen Zustand: Fokuswechsel im Raster bauen das Raster nicht neu auf.
    val focused = remember { mutableStateOf<Pair<ChannelEntity, ProgrammeEntity>?>(null) }

    var windowStart by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val windowEnd = windowStart + WINDOW
    var programmes by remember { mutableStateOf<Map<String, List<ProgrammeEntity>>>(emptyMap()) }
    LaunchedEffect(channels, windowStart) { programmes = content.programmesInRange(channels, windowStart, windowEnd) }

    // Mit Archiv lässt sich so weit zurückblättern, wie der Anbieter aufzeichnet (höchstens 7 Tage).
    val archiveDays = remember(channels) { channels.maxOfOrNull { it.catchupDays }?.coerceIn(0, 7) ?: 0 }
    val earliest = System.currentTimeMillis() / HALF_HOUR * HALF_HOUR - maxOf(2 * HALF_HOUR, archiveDays * DAY)
    // Liefert false, wenn sich nichts verschieben lässt: Dann geht „links“ normal weiter (zur Navigation).
    val shift: (Int) -> Boolean = { steps ->
        val target = (windowStart + steps * HALF_HOUR).coerceAtLeast(earliest)
        (target != windowStart).also { if (it) windowStart = target }
    }
    val reminders by container.reminders.all.collectAsStateWithLifecycle(emptyList())
    val reminderIds = remember(reminders) { reminders.mapTo(HashSet()) { it.id } }
    val play: (ChannelEntity, ProgrammeEntity?) -> Unit = { channel, programme ->
        val now = System.currentTimeMillis()
        if (programme != null && programme.start > now) {
            // Kommende Sendung: Erinnerung setzen bzw. wieder entfernen.
            scope.launch { container.reminders.toggle(Reminder(channel.key, channel.name, programme.headline(), programme.start)) }
        } else if (programme != null && programme.stop <= now) {
            scope.launch {
                val url = content.catchupUrl(channel, programme)
                if (url != null) PlayerActivity.startCatchup(context, channel, programme, url)
                else PlayerActivity.startLive(context, channel.key, channel.groupTitle)
            }
        } else {
            PlayerActivity.startLive(context, channel.key, channel.groupTitle)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 20.dp)) {
        LazyRow(
            modifier = Modifier.focusRestorer(),
            contentPadding = PaddingValues(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (favorites.isNotEmpty()) {
                item(key = FAVORITES_GROUP) { GroupChip("★ Favoriten", activeGroup == FAVORITES_GROUP) { group = FAVORITES_GROUP } }
            }
            items(groups, key = { it.name }) { g ->
                GroupChip(g.name, g.name == activeGroup) { group = g.name }
            }
        }

        Spacer(Modifier.height(10.dp))
        ProgrammePreview(focused, playlists, reminderIds, minute, Modifier.padding(horizontal = 24.dp))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isTouchDevice) {
                // Ohne Fernbedienung: Zeitfenster mit Knöpfen verschieben (sonst ◀ am ersten, ▶ am letzten Feld).
                Row(Modifier.width(CHANNEL_COLUMN + 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GroupChip("◀ früher", false) { shift(-2) }
                    GroupChip("später ▶", false) { shift(+2) }
                }
            } else {
                Spacer(Modifier.width(CHANNEL_COLUMN + 4.dp))
            }
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

        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().focusRestorer(),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(channels, key = { it.key }, contentType = { "guideRow" }) { channel ->
                    GuideRow(
                        channel = channel,
                        programmes = channel.epgId?.let { programmes[it] }.orEmpty(),
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                        now = minute,
                        reminderIds = reminderIds,
                        onShift = shift,
                        onFocusProgramme = { focused.value = channel to it },
                        onPlay = { programme -> play(channel, programme) },
                    )
                }
            }
            // „Jetzt“-Linie über dem Raster
            val nowOffset = (minute - windowStart) / MINUTE
            if (nowOffset in 0 until WINDOW / MINUTE) {
                Box(
                    Modifier
                        .offset(x = 24.dp + CHANNEL_COLUMN + 4.dp + DP_PER_MINUTE * nowOffset.toInt())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(AyuColors.Live),
                )
            }
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
            focusedContainerColor = AyuColors.Accent,
            focusedContentColor = Color.Black,
        ),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 14.sp, maxLines = 1)
    }
}

/** Titel, Zeit und Beschreibung der markierten Sendung; bei Archiv-Sendungen der Hinweis zum Abspielen. */
@Composable
private fun ProgrammePreview(
    state: State<Pair<ChannelEntity, ProgrammeEntity>?>,
    playlists: List<PlaylistEntity>,
    reminderIds: Set<String>,
    now: Long,
    modifier: Modifier,
) {
    val (channel, programme) = state.value ?: (null to null)
    Row(modifier.fillMaxWidth().height(78.dp)) {
    if (programme != null && !programme.icon.isNullOrBlank()) {
        de.ayutv.ui.components.ProgrammeImage(programme.icon, Modifier.width(139.dp).fillMaxHeight())
        Spacer(Modifier.width(14.dp))
    }
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (channel == null || programme == null) {
            Text("Sendung auswählen: Vorschau erscheint hier", color = AyuColors.TextSecondary, fontSize = 14.sp)
            return@Column
        }
        val past = programme.stop <= now
        val archive = past && Catchup.url(playlists.firstOrNull { it.id == channel.playlistId }, channel, programme.start, programme.stop) != null
        val status = when {
            programme.start <= now && programme.stop > now -> "● JETZT"
            archive -> "↺ OK: aus dem Archiv abspielen"
            past -> "Vorbei"
            "${channel.key}@${programme.start}" in reminderIds -> "🔔 Erinnerung an · OK: entfernen"
            else -> "OK: Erinnerung setzen"
        }
        Text(
            "${channel.name} · ${formatTime(programme.start)} – ${formatTime(programme.stop)} · $status",
            color = if (archive || !past) AyuColors.Accent else AyuColors.TextSecondary,
            fontSize = 13.sp,
            maxLines = 1,
        )
        Text(if (programme.stop > now) programme.headlineWithBadge() else programme.headline(), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        de.ayutv.data.parse.ProgrammeText.description(programme.description)?.let {
            Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    reminderIds: Set<String>,
    onShift: (Int) -> Boolean,
    onFocusProgramme: (ProgrammeEntity) -> Unit,
    onPlay: (ProgrammeEntity?) -> Unit,
) {
    Row(Modifier.height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(CHANNEL_COLUMN).fillMaxHeight().background(AyuColors.Surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 48.dp, height = 32.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                channel.name + if (channel.catchupDays > 0) " ↺" else "",
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(4.dp))

        val cells = remember(programmes, windowStart) { layoutCells(programmes, windowStart, windowEnd) }
        if (cells.isEmpty()) {
            GuideCell("Keine Programmdaten", null, DP_PER_MINUTE * (WINDOW / MINUTE).toInt(), live = true, first = false, last = false, onShift, {}) { onPlay(null) }
        } else {
            val firstIndex = cells.indexOfFirst { it.programme != null }
            val lastIndex = cells.indexOfLast { it.programme != null }
            cells.forEachIndexed { index, cell ->
                val p = cell.programme
                if (p == null) {
                    Spacer(Modifier.width(cell.width))
                } else {
                    // Nach Sendung geschlüsselt: Beim Verschieben des Zeitfensters bleibt der Fokus auf ihr.
                    key(p.id) {
                        val archive = p.stop <= now && channel.catchupDays > 0 && now - p.start <= channel.catchupDays * DAY
                        GuideCell(
                            title = (if (archive) "↺ " else "") + (if ("${channel.key}@${p.start}" in reminderIds) "🔔 " else "") +
                                (if (p.stop > now) p.headlineWithBadge() else p.headline()).ifEmpty { "Ohne Titel" },
                            time = "${formatTime(p.start)} – ${formatTime(p.stop)}",
                            width = cell.width,
                            live = p.start <= now && p.stop > now,
                            first = index == firstIndex,
                            last = index == lastIndex,
                            onShift = onShift,
                            onFocused = { onFocusProgramme(p) },
                            onClick = { onPlay(p) },
                        )
                    }
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
    onShift: (Int) -> Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        // Tippen: erst Vorschau zeigen (wie Fokussieren mit der Fernbedienung), zweites Tippen spielt.
        focusFirst = true,
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    first && event.key == Key.DirectionLeft -> onShift(-1)
                    last && event.key == Key.DirectionRight -> onShift(+1)
                    else -> false
                }
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (live) AyuColors.SurfaceRaised else AyuColors.Surface,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        border = AyuFocus.border,
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
                    Text(time, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 1)
                }
            }
        }
    }
}
