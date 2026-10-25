package de.ayutv.ui.vod

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.CategoryColumn
import de.ayutv.ui.components.CategoryEntry
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun MoviesScreen() {
    val content = LocalContainer.current.content
    val context = LocalContext.current
    val categories by content.vodCategories.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val active = selected ?: categories.firstOrNull()?.name

    if (categories.isEmpty()) {
        EmptyHint("Keine Filme", "Diese Playlist enthält keine Filme. Xtream-Zugänge laden Filme automatisch.")
        return
    }
    val movies by remember(active) { content.moviesIn(active.orEmpty()) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(
            entries = remember(categories) { categories.map { CategoryEntry(it.name, it.name, it.count) } },
            selected = active,
            onSelect = { selected = it },
            onLongClick = { name ->
                scope.launch { content.hideGroupEverywhere(GroupKind.VOD, name) }
                if (selected == name) selected = null
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(movies, key = { it.key }) { movie ->
                PosterCard(
                    title = movie.name,
                    image = movie.poster,
                    subtitle = movie.rating?.let { "★ $it" },
                    onClick = {
                        PlayerActivity.startVod(
                            context,
                            VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url),
                        )
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SeriesScreen(openSeries: SeriesEntity?, onOpenSeries: (SeriesEntity?) -> Unit) {
    if (openSeries != null) {
        BackHandler { onOpenSeries(null) }
        SeriesDetail(openSeries)
        return
    }

    val content = LocalContainer.current.content
    val categories by content.seriesCategories.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val active = selected ?: categories.firstOrNull()?.name

    if (categories.isEmpty()) {
        EmptyHint("Keine Serien", "Serien werden über Xtream-Zugänge geladen.")
        return
    }
    val series by remember(active) { content.seriesIn(active.orEmpty()) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxSize()) {
        CategoryColumn(
            entries = remember(categories) { categories.map { CategoryEntry(it.name, it.name, it.count) } },
            selected = active,
            onSelect = { selected = it },
            onLongClick = { name ->
                scope.launch { content.hideGroupEverywhere(GroupKind.SERIES, name) }
                if (selected == name) selected = null
            },
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            modifier = Modifier.weight(1f).fillMaxHeight().focusRestorer(),
            contentPadding = PaddingValues(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(series, key = { it.key }) { item ->
                PosterCard(
                    title = item.name,
                    image = item.cover,
                    subtitle = item.rating?.let { "★ $it" },
                    onClick = { onOpenSeries(item) },
                )
            }
        }
    }
}

@Composable
private fun SeriesDetail(series: SeriesEntity) {
    val content = LocalContainer.current.content
    val context = LocalContext.current
    var episodes by remember(series.key) { mutableStateOf<List<Pair<XtreamEpisode, String>>?>(null) }
    var error by remember(series.key) { mutableStateOf<String?>(null) }
    var season by remember(series.key) { mutableIntStateOf(-1) }

    LaunchedEffect(series.key) {
        runCatching { content.episodes(series) }
            .onSuccess { episodes = it }
            .onFailure { error = it.message ?: "Episoden konnten nicht geladen werden" }
    }

    val list = episodes
    val seasons = list?.map { it.first.season }?.distinct().orEmpty()
    val activeSeason = if (season in seasons) season else seasons.firstOrNull() ?: 1

    Row(Modifier.fillMaxSize().padding(32.dp)) {
        Column(Modifier.width(260.dp)) {
            if (!series.cover.isNullOrBlank()) {
                AsyncImage(
                    model = series.cover,
                    contentDescription = series.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 240.dp, height = 360.dp).clip(RoundedCornerShape(12.dp)),
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(series.name, style = MaterialTheme.typography.titleLarge, maxLines = 3)
            series.rating?.let { Text("★ $it", color = AyuColors.Accent) }
            series.plot?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            when {
                error != null -> Text(error.orEmpty(), color = AyuColors.Live)
                list == null -> Text("Episoden werden geladen …", color = AyuColors.TextSecondary)
                list.isEmpty() -> Text("Keine Episoden gefunden.", color = AyuColors.TextSecondary)
                else -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            Surface(
                                onClick = { season = s },
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = if (s == activeSeason) AyuColors.AccentSoft else AyuColors.Surface,
                                    focusedContainerColor = AyuColors.Accent,
                                ),
                            ) {
                                Text("Staffel $s", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(list.filter { it.first.season == activeSeason }, key = { it.first.id }) { (episode, url) ->
                            EpisodeRow(episode) {
                                PlayerActivity.startVod(
                                    context,
                                    VodRequest(
                                        key = de.ayutv.data.parse.Episodes.key(series.key, episode.id),
                                        kind = HistoryKind.EPISODE,
                                        title = de.ayutv.data.parse.Episodes.title(series.name, episode),
                                        subtitle = episode.title,
                                        image = episode.image ?: series.cover,
                                        url = url,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(episode: XtreamEpisode, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!episode.image.isNullOrBlank()) {
                AsyncImage(
                    model = episode.image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(6.dp)),
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("${episode.episode}. ${episode.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                episode.plot?.let { Text(it, fontSize = 13.sp, color = AyuColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (episode.durationSeconds > 0) {
                Text("${episode.durationSeconds / 60} min", fontSize = 13.sp, color = AyuColors.TextSecondary)
            }
        }
    }
}
