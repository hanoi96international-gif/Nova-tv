package de.ayutv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.ayutv.AppContainer
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.ui.guide.GuideScreen
import de.ayutv.ui.home.HomeScreen
import de.ayutv.ui.live.LiveScreen
import de.ayutv.ui.search.SearchScreen
import de.ayutv.ui.settings.AddPlaylistScreen
import de.ayutv.ui.settings.GroupManagerScreen
import de.ayutv.ui.settings.SettingsScreen
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.vod.MoviesScreen
import de.ayutv.ui.vod.SeriesScreen

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer fehlt") }

private val NAV_COLLAPSED = 72.dp
private val NAV_EXPANDED = 220.dp

enum class Section(val label: String, val icon: ImageVector) {
    HOME("Start", Icons.Filled.Home),
    LIVE("Live TV", Icons.Filled.List),
    GUIDE("TV-Guide", Icons.Filled.DateRange),
    MOVIES("Filme", Icons.Filled.PlayArrow),
    SERIES("Serien", Icons.Filled.Star),
    SEARCH("Suche", Icons.Filled.Search),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

@Composable
fun AyuRoot() {
    val container = LocalContainer.current
    val playlists by container.playlists.playlists.collectAsStateWithLifecycle(initialValue = null)
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val sections = remember(appSettings?.showGuide) {
        Section.entries.filter { it != Section.GUIDE || appSettings?.showGuide != false }
    }
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    var addingPlaylist by remember { mutableStateOf(false) }
    var openSeries by remember { mutableStateOf<SeriesEntity?>(null) }
    var managingGroups by remember { mutableStateOf<PlaylistEntity?>(null) }
    // Nach „Zurück“ verschwindet der fokussierte Inhalt: dann den Fokus gezielt in die Navigation setzen.
    var navTick by remember { mutableIntStateOf(0) }
    // Jeder Bereich behält seinen Zustand (gewählte Gruppe, Scrollposition), auch wenn man zwischendurch wechselt.
    val sectionState = rememberSaveableStateHolder()

    Box(Modifier.fillMaxSize().background(AyuColors.Background)) {
        val list = playlists
        when {
            list == null -> Unit
            list.isEmpty() || addingPlaylist -> AddPlaylistScreen(
                onDone = { addingPlaylist = false; navTick++ },
                onCancel = if (list.isEmpty()) null else ({ addingPlaylist = false; navTick++ }),
            )
            managingGroups != null -> GroupManagerScreen(managingGroups!!, onBack = { managingGroups = null; navTick++ })
            else -> {
                BackHandler(enabled = section != Section.HOME) {
                    section = Section.HOME
                    navTick++
                }
                // Die Navigation liegt über dem Inhalt: Aufklappen verschiebt nichts und löst kein Neu-Layout aus.
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().padding(start = NAV_COLLAPSED)) {
                        sectionState.SaveableStateProvider(section.name) { when (section) {
                            Section.HOME -> HomeScreen(onOpenSeries = { openSeries = it; section = Section.SERIES })
                            Section.LIVE -> LiveScreen()
                            Section.GUIDE -> GuideScreen()
                            Section.MOVIES -> MoviesScreen()
                            Section.SERIES -> SeriesScreen(openSeries = openSeries, onOpenSeries = { openSeries = it })
                            Section.SEARCH -> SearchScreen(onOpenSeries = { openSeries = it; section = Section.SERIES })
                            Section.SETTINGS -> SettingsScreen(
                                onAddPlaylist = { addingPlaylist = true },
                                onManageGroups = { managingGroups = it },
                            )
                        } }
                    }
                    SideNav(sections = sections, selected = section, refocusTick = navTick, onSelect = { section = it })
                }
            }
        }
    }
}

/** Schmale Leiste mit Symbolen, die sich beim Fokussieren mit Beschriftung aufklappt. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun SideNav(sections: List<Section>, selected: Section, refocusTick: Int, onSelect: (Section) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) NAV_EXPANDED else NAV_COLLAPSED, label = "nav")
    val initialFocus = remember { FocusRequester() }

    if (expanded) {
        // Abdunkeln des Inhalts hinter der aufgeklappten Navigation
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
    }
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(AyuColors.Surface)
            .onFocusChanged { expanded = it.hasFocus }
            .focusRestorer()
            .padding(vertical = 24.dp, horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (expanded) "DnA-TV" else "DnA",
            fontWeight = FontWeight.Bold,
            fontSize = if (expanded) 22.sp else 18.sp,
            maxLines = 1,
            color = AyuColors.Accent,
            modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
        )
        sections.forEach { item ->
            val isSelected = item == selected
            Surface(
                onClick = { onSelect(item) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isSelected) Modifier.focusRequester(initialFocus) else Modifier),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isSelected) AyuColors.SurfaceRaised else Color.Transparent,
                    focusedContainerColor = AyuColors.AccentSoft,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        item.icon,
                        contentDescription = item.label,
                        tint = if (isSelected) AyuColors.Accent else AyuColors.TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(item.label, color = AyuColors.TextPrimary, fontSize = 15.sp, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    LaunchedEffect(refocusTick) {
        repeat(5) {
            if (runCatching { initialFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
}
