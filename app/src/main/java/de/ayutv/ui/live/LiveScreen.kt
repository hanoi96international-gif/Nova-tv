package de.ayutv.ui.live

import de.ayutv.ui.components.isPortrait
import androidx.compose.runtime.produceState
import de.ayutv.ui.components.ProgrammeImage
import de.ayutv.data.parse.headlineWithBadge
import de.ayutv.data.parse.headline
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
import androidx.compose.runtime.State
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
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.CategoryColumn
import de.ayutv.ui.components.CategoryEntry
import de.ayutv.ui.components.ChannelListItem
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.NoticeBar
import de.ayutv.ui.components.ProgressLine
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.progress
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.rememberLazyListState

const val FAVORITES_GROUP = "\u0000favoriten"
private const val NOW_PAGE = 30

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveScreen() {
    val content = LocalContainer.current.content
    val appSettings by LocalContainer.current.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val merge = appSettings?.mergeVariants != false
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
    // null = lädt noch (dann kein „Keine Sender“-Aufblitzen), leer = wirklich keine.
    val raw by channelsFlow.collectAsStateWithLifecycle(null)
    // Gleiche Sender in mehreren Qualitäten (SD/HD/4K/RAW/Backup) als ein Eintrag; Favoriten bleiben, wie sie sind.
    val loaded by produceState<List<ChannelEntity>?>(null, raw, merge, selectedGroup, appSettings?.minQuality, appSettings?.preferUhd, appSettings?.hideBrokenChannels) {
        appSettings?.let { de.ayutv.data.parse.ChannelQuality.configure(it.minQuality.height, it.preferUhd, it.hideBrokenChannels) }
        val list = raw
        value = if (list == null) null else if (merge && selectedGroup != FAVORITES_GROUP) content.mergeVariants(list) else list
    }
    val channels = loaded.orEmpty()
    val favoriteSet = remember(favoriteKeys) { favoriteKeys.toHashSet() }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    // Neue Gruppe: oben beginnen (nicht an der Scrollposition der vorigen).
    var shownGroup by rememberSaveable { mutableStateOf(selectedGroup) }
    LaunchedEffect(selectedGroup) {
        if (shownGroup != selectedGroup) {
            shownGroup = selectedGroup
            listState.scrollToItem(0)
        }
    }

    // „Jetzt läuft“ nur für den sichtbaren Ausschnitt (plus Vorlauf) laden: Bei 20.000 Sendern
    // würde sonst jede Minute das ganze Programm aller Sender gelesen.
    var nowMap by remember { mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()) }
    LaunchedEffect(channels, minute) {
        snapshotFlow { listState.firstVisibleItemIndex / NOW_PAGE }
            .distinctUntilChanged()
            .collectLatest { page ->
                val from = ((page - 1) * NOW_PAGE).coerceAtLeast(0)
                val to = ((page + 3) * NOW_PAGE).coerceAtMost(channels.size)
                if (from < to) nowMap = nowMap + content.nowPlaying(channels.subList(from, to))
            }
    }

    // Fokussierter Sender als State-Objekt: nur die Detailspalte liest ihn. So wird beim Scrollen
    // nicht bei jedem Tastendruck der ganze Bildschirm neu berechnet.
    val focusedState = remember { mutableStateOf<ChannelEntity?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }

    val entries = remember(groups, total, favoriteKeys.size) {
        buildList {
            add(CategoryEntry(FAVORITES_GROUP, "★ Favoriten", favoriteKeys.size, removable = false))
            add(CategoryEntry(null, "Alle Sender", total))
            groups.forEach { add(CategoryEntry(it.name, it.name, it.count)) }
        }
    }

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(
            entries,
            selectedGroup,
            onSelect = { selectedGroup = it },
            onLongClick = { name ->
                scope.launch {
                    content.hideGroupEverywhere(GroupKind.LIVE, name)
                    if (selectedGroup == name) selectedGroup = null
                    notice = "„$name“ ausgeblendet. Wieder einblenden: Einstellungen → Playlist → Gruppen"
                }
            },
        )

        if (loaded == null) {
            Spacer(Modifier.weight(1f))
        } else if (channels.isEmpty()) {
            EmptyHint(
                if (selectedGroup == FAVORITES_GROUP) "Noch keine Favoriten" else "Keine Sender",
                if (selectedGroup == FAVORITES_GROUP) "Halte OK auf einem Sender gedrückt, um ihn zu den Favoriten hinzuzufügen." else "",
                Modifier.weight(1f),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
                contentPadding = PaddingValues(vertical = 24.dp, horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(channels, key = { it.key }, contentType = { "channel" }) { channel ->
                    ChannelListItem(
                        channel = channel,
                        now = channel.epgId?.let { nowMap[it] },
                        favorite = channel.key in favoriteSet,
                        onFocused = { focusedState.value = channel },
                        onClick = {
                            val favoritesOnly = selectedGroup == FAVORITES_GROUP
                            PlayerActivity.startLive(context, channel.key, selectedGroup.takeUnless { favoritesOnly }, favoritesOnly)
                        },
                        onLongClick = {
                            // In der Favoriten-Gruppe verschwindet der Sender: vorher den Fokus weitergeben.
                            if (selectedGroup == FAVORITES_GROUP && !focusManager.moveFocus(FocusDirection.Down)) {
                                focusManager.moveFocus(FocusDirection.Up)
                            }
                            scope.launch { content.toggleFavorite(channel.key) }
                        },
                    )
                }
            }
        }

        // Hochformat: kein Platz für die Detailspalte (Sendungsinfo steht in der Senderzeile).
        if (!isPortrait) ChannelDetails(focusedState, Modifier.width(290.dp).fillMaxHeight())
    }
    notice?.let { NoticeBar(it) }
}

@Composable
private fun ChannelDetails(focusedState: State<ChannelEntity?>, modifier: Modifier) {
    val content = LocalContainer.current.content
    val channel = focusedState.value
    var programmes by remember { mutableStateOf<List<ProgrammeEntity>>(emptyList()) }
    LaunchedEffect(channel) {
        val target = channel ?: return@LaunchedEffect
        delay(150) // schnelles Durchscrollen löst keine Abfragen aus
        programmes = content.upcoming(target, 6)
    }
    Column(modifier.background(AyuColors.Surface).padding(24.dp)) {
        if (channel == null) return@Column
        ChannelLogo(channel.logo, channel.name, Modifier.size(width = 120.dp, height = 72.dp))
        Spacer(Modifier.height(12.dp))
        Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(channel.groupTitle, style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary)
        if (channel.catchupDays > 0) {
            Text(
                "↺ Archiv: ${channel.catchupDays} ${if (channel.catchupDays == 1) "Tag" else "Tage"} – im TV-Guide zurückblättern",
                color = AyuColors.Accent,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (programmes.isEmpty()) {
            Text("Keine Programminformationen", color = AyuColors.TextSecondary, fontSize = 14.sp)
        }
        programmes.forEachIndexed { index, p ->
            Text(
                "${formatTime(p.start)} – ${formatTime(p.stop)}",
                color = if (index == 0) AyuColors.Accent else AyuColors.TextSecondary,
                fontSize = 13.sp,
            )
            Text(
                p.headlineWithBadge(),
                style = if (index == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (index == 0) {
                Spacer(Modifier.height(4.dp))
                ProgressLine(p.progress())
                if (!p.icon.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    ProgrammeImage(p.icon, Modifier.width(242.dp).height(136.dp))
                }
                de.ayutv.data.parse.ProgrammeText.description(p.description)?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("OK abspielen  ·  OK halten = Favorit", fontSize = 13.sp, color = AyuColors.TextSecondary)
        Text("OK halten auf Gruppe = ausblenden", fontSize = 13.sp, color = AyuColors.TextSecondary)
    }
}
