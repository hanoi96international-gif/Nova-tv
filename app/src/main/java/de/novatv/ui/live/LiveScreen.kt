package de.novatv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.novatv.data.db.ChannelEntity
import de.novatv.data.db.ProgrammeEntity
import de.novatv.player.PlayerActivity
import de.novatv.ui.LocalContainer
import de.novatv.ui.components.CategoryColumn
import de.novatv.ui.components.CategoryEntry
import de.novatv.ui.components.ChannelListItem
import de.novatv.ui.components.ChannelLogo
import de.novatv.ui.components.EmptyHint
import de.novatv.ui.components.ProgressLine
import de.novatv.ui.components.formatTime
import de.novatv.ui.components.progress
import de.novatv.ui.components.rememberMinuteTicker
import de.novatv.ui.theme.NovaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val FAVORITES_GROUP = "\u0000favoriten"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen() {
    val content = LocalContainer.current.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val groups by content.groups.collectAsStateWithLifecycle(emptyList())
    val favoriteKeys by content.favoriteKeys.collectAsStateWithLifecycle(emptyList())
    val total by content.channelCount.collectAsStateWithLifecycle(0)
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val minute by rememberMinuteTicker()

    val channelsFlow = remember(selectedGroup) {
        if (selectedGroup == FAVORITES_GROUP) content.favoriteChannels else content.channelsIn(selectedGroup)
    }
    val channels by channelsFlow.collectAsStateWithLifecycle(emptyList())
    val favoriteSet = remember(favoriteKeys) { favoriteKeys.toHashSet() }

    var nowMap by remember { mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()) }
    LaunchedEffect(channels, minute) { nowMap = content.nowPlaying(channels) }

    var focused by remember { mutableStateOf<ChannelEntity?>(null) }
    var upcoming by remember { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    LaunchedEffect(focused) {
        val channel = focused ?: return@LaunchedEffect
        delay(150)
        upcoming = content.upcoming(channel, 6)
    }

    val entries = remember(groups, total, favoriteKeys.size) {
        buildList {
            add(CategoryEntry(FAVORITES_GROUP, "★ Favoriten", favoriteKeys.size))
            add(CategoryEntry(null, "Alle Sender", total))
            groups.forEach { add(CategoryEntry(it.name, it.name, it.count)) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(entries, selectedGroup, onSelect = { selectedGroup = it })

        if (channels.isEmpty()) {
            EmptyHint(
                if (selectedGroup == FAVORITES_GROUP) "Noch keine Favoriten" else "Keine Sender",
                if (selectedGroup == FAVORITES_GROUP) "Halte OK auf einem Sender gedrückt, um ihn zu den Favoriten hinzuzufügen." else "",
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels, key = { it.key }) { channel ->
                    ChannelListItem(
                        channel = channel,
                        now = channel.epgId?.let { nowMap[it] },
                        favorite = channel.key in favoriteSet,
                        onFocused = { focused = channel },
                        onClick = {
                            val favoritesOnly = selectedGroup == FAVORITES_GROUP
                            PlayerActivity.startLive(context, channel.key, selectedGroup.takeUnless { favoritesOnly }, favoritesOnly)
                        },
                        onLongClick = { scope.launch { content.toggleFavorite(channel.key) } },
                    )
                }
            }
        }

        ChannelDetails(focused, upcoming, Modifier.width(330.dp).fillMaxHeight())
    }
}

@Composable
private fun ChannelDetails(channel: ChannelEntity?, programmes: List<ProgrammeEntity>, modifier: Modifier) {
    Column(modifier.background(NovaColors.Surface).padding(24.dp)) {
        if (channel == null) return@Column
        ChannelLogo(channel.logo, channel.name, Modifier.size(width = 120.dp, height = 72.dp))
        Spacer(Modifier.height(12.dp))
        Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = NovaColors.TextSecondary)
        Spacer(Modifier.height(20.dp))
        if (programmes.isEmpty()) {
            Text("Keine Programminformationen", color = NovaColors.TextSecondary, fontSize = 14.sp)
        }
        programmes.forEachIndexed { index, p ->
            Text(
                "${formatTime(p.start)} – ${formatTime(p.stop)}",
                color = if (index == 0) NovaColors.Accent else NovaColors.TextSecondary,
                fontSize = 12.sp,
            )
            Text(
                p.title,
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (index == 0) {
                Spacer(Modifier.height(4.dp))
                ProgressLine(p.progress())
                p.description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = NovaColors.TextSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("OK abspielen  ·  OK halten = Favorit", fontSize = 12.sp, color = NovaColors.TextSecondary)
    }
}
