package de.ayutv.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.repo.SearchResults
import de.ayutv.player.PlayerActivity
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelCard
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.TvTextField
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.delay

/** Eine Suche über alles: Sender, laufende und kommende Sendungen, Filme und Serien. */
@Composable
fun SearchScreen(onOpenSeries: (SeriesEntity) -> Unit) {
    val content = LocalContainer.current.content
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(SearchResults()) }
    var searching by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = SearchResults()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        results = content.search(query)
        searching = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item {
            TvTextField(
                value = query,
                onValueChange = { query = it },
                label = "Suche nach Sendern, Sendungen, Filmen und Serien",
                imeAction = ImeAction.Search,
                focusRequester = fieldFocus,
                modifier = Modifier.fillMaxWidth(0.7f),
            )
        }

        if (query.trim().length >= 2 && !searching && results.isEmpty) {
            item { Text("Nichts gefunden für „${query.trim()}“", color = AyuColors.TextSecondary) }
        }

        if (results.channels.isNotEmpty()) {
            item {
                ResultRow("Sender") {
                    items(results.channels, key = { it.key }) { channel ->
                        ChannelCard(channel, now = null, onClick = { PlayerActivity.startLive(context, channel.key, channel.groupTitle) })
                    }
                }
            }
        }

        if (results.programmes.isNotEmpty()) {
            item {
                ResultRow("Im TV") {
                    items(results.programmes, key = { it.first.id }) { (programme, channel) ->
                        ProgrammeResult(programme, channel?.name) {
                            if (channel != null) PlayerActivity.startLive(context, channel.key, channel.groupTitle)
                        }
                    }
                }
            }
        }

        if (results.movies.isNotEmpty()) {
            item {
                ResultRow("Filme") {
                    items(results.movies, key = { it.key }) { movie ->
                        PosterCard(movie.name, movie.poster, onClick = {
                            PlayerActivity.startVod(
                                context,
                                VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url),
                            )
                        })
                    }
                }
            }
        }

        if (results.series.isNotEmpty()) {
            item {
                ResultRow("Serien") {
                    items(results.series, key = { it.key }) { series ->
                        PosterCard(series.name, series.cover, onClick = { onOpenSeries(series) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ResultRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column {
        SectionTitle(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            content = content,
        )
    }
}

@Composable
private fun ProgrammeResult(programme: ProgrammeEntity, channelName: String?, onClick: () -> Unit) {
    val now = System.currentTimeMillis()
    val live = programme.start <= now && programme.stop > now
    Surface(
        onClick = onClick,
        modifier = Modifier.width(260.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                if (live) "● JETZT  bis ${formatTime(programme.stop)}" else "${formatTime(programme.start)} – ${formatTime(programme.stop)}",
                color = if (live) AyuColors.Live else AyuColors.TextSecondary,
                fontSize = 12.sp,
            )
            Text(programme.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channelName ?: programme.channelEpgId, color = AyuColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}
