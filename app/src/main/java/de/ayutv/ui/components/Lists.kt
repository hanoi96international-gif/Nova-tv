package de.ayutv.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.repo.SyncState
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.delay

/** Zeile in Senderlisten (Live-TV-Ansicht und Senderliste im Player). */
@Composable
fun ChannelListItem(
    channel: ChannelEntity,
    now: ProgrammeEntity?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onFocused: (() -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft.copy(alpha = 0.45f) else Color.Transparent,
            focusedContainerColor = AyuColors.AccentSoft,
        ),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${channel.number}", color = AyuColors.TextSecondary, fontSize = 14.sp, modifier = Modifier.width(48.dp))
            ChannelLogo(channel.logo, channel.name, Modifier.size(width = 56.dp, height = 36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.name,
                        color = AyuColors.TextPrimary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (favorite) Text("  ★", color = AyuColors.Accent, fontSize = 14.sp)
                }
                if (now != null) {
                    Text(now.headlineWithBadge(), color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    ProgressLine(now.progress())
                }
            }
        }
    }
}

/** [action]: kein Gruppeneintrag, sondern ein Knopf (z. B. „Neue Gruppe“): Fokus wählt nichts aus, OK löst [onAction] aus. */
data class CategoryEntry(val id: String?, val label: String, val count: Int?, val removable: Boolean = true, val action: Boolean = false)

/**
 * Linke Kategorienspalte. Die Auswahl folgt dem Fokus (wie bei TiviMate), leicht verzögert,
 * damit schnelles Durchscrollen keine Datenbankabfragen für jede Zwischenstation auslöst.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CategoryColumn(
    entries: List<CategoryEntry>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = if (isPortrait) 170.dp else 260.dp,
    /** Langes Drücken auf eine echte Gruppe (nicht "Alle"/"Favoriten"), z. B. zum Ausblenden. */
    onLongClick: ((String) -> Unit)? = null,
    onAction: ((String) -> Unit)? = null,
) {
    var focusedId by remember { mutableStateOf<Any?>(Unset) }
    LaunchedEffect(focusedId) {
        val id = focusedId
        if (id === Unset) return@LaunchedEffect
        delay(250)
        onSelect(id as String?)
    }
    LazyColumn(
        modifier = modifier.width(width).fillMaxHeight().focusRestorer(),
        contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(entries, key = { it.id ?: "\u0000all" }) { entry ->
            val id = entry.id
            Surface(
                onClick = { if (entry.action && id != null) onAction?.invoke(id) else onSelect(entry.id) },
                onLongClick = if (onLongClick != null && id != null && entry.removable) ({ onLongClick(id) }) else null,
                modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused && !entry.action) focusedId = entry.id },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (entry.id == selected) AyuColors.SurfaceRaised else Color.Transparent,
                    focusedContainerColor = AyuColors.AccentSoft,
                ),
                border = AyuFocus.border,
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.label,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (entry.id == selected) AyuColors.Accent else AyuColors.TextPrimary,
                        fontSize = 15.sp,
                    )
                    entry.count?.let { Text("$it", color = AyuColors.TextSecondary, fontSize = 13.sp) }
                }
            }
        }
    }
}

private object Unset

@Composable
fun SyncBanner(state: SyncState, modifier: Modifier = Modifier) {
    val (text, color) = when (state) {
        SyncState.Idle -> return
        is SyncState.Running -> state.message to AyuColors.Accent
        is SyncState.Failed -> state.message to AyuColors.Live
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Aktualisiert sich jede Minute, damit Fortschrittsbalken und "Jetzt läuft" aktuell bleiben. */
@Composable
fun rememberMinuteTicker(): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(60_000 - System.currentTimeMillis() % 60_000)
        value = System.currentTimeMillis()
    }
}

/** Kurzer Hinweis am unteren Bildrand, z. B. nach dem Ausblenden einer Gruppe. */
@Composable
fun NoticeBar(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.BottomCenter) {
        Text(
            text,
            color = AyuColors.TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier
                .background(AyuColors.SurfaceRaised, RoundedCornerShape(10.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}
