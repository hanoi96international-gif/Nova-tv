package de.ayutv.desktop.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import de.ayutv.data.parse.Episodes
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Movie
import de.ayutv.desktop.data.Series
import kotlinx.coroutines.delay

/** Ein Eintrag auf der Startseite. */
private sealed interface HomeItem {
    val key: String

    data class Live(val channel: Channel, val list: List<Channel>) : HomeItem { override val key get() = "c:" + channel.key }
    data class Film(val movie: Movie, val progress: Float?) : HomeItem { override val key get() = "m:" + movie.key }
    data class Show(val series: Series, val progress: Float?) : HomeItem { override val key get() = "s:" + series.key }
}

private data class HomeRow(val title: String, val items: List<HomeItem>)

/**
 * Startseite wie auf dem Fire TV: „Weiter schauen“, „Zuletzt gesehen“, Favoriten mit laufendem Programm, „Mehr aus …“
 * für die meistgeschauten Gruppen, neue Filme und Serien. Alles lokal berechnet.
 * Bedienung: ◀/▶ in einer Reihe, ↑/↓ zwischen den Reihen, OK spielt ab, ☰ bzw. Rechtsklick öffnet das Menü.
 */
@Composable
fun HomeScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    val playlists by store.playlists.collectAsState()
    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // Laufendes Programm jede Minute auffrischen.
    var minute by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            minute++
        }
    }

    if (playlists.isEmpty()) {
        Empty("Willkommen bei DnA-TV. Unter „Einstellungen“ eine Playlist hinzufügen oder die Sicherung vom Fire TV übernehmen.")
        return
    }

    val rows = remember(user, content, playlists, guide, minute) {
        buildList {
            val movies = store.movies().associateBy { it.key }
            val series = store.series().associateBy { it.key }
            // Weiter schauen: Filme und Serien mit gemerktem Stand (neueste Einträge zuletzt gespeichert → zuerst).
            val resume = user.positions.entries.reversed().filter { it.value > 0 }.mapNotNull { (key, position) ->
                val fraction = user.durations[key]?.takeIf { it > 0 }?.let { (position.toFloat() / it).coerceIn(0f, 1f) }
                movies[key]?.let { return@mapNotNull HomeItem.Film(it, fraction) }
                Episodes.seriesKeyOf(key)?.let { series[it] }?.let { HomeItem.Show(it, fraction) }
            }.distinctBy { it.key }.take(20)
            if (resume.isNotEmpty()) add(HomeRow("Weiter schauen", resume))

            val visible = store.allVisible()
            val recent = store.recentChannels().take(20)
            if (recent.isNotEmpty()) add(HomeRow("Zuletzt gesehen", recent.map { HomeItem.Live(it, visible) }))
            val favorites = store.favorites()
            if (favorites.isNotEmpty()) add(HomeRow("★ Favoriten", favorites.map { HomeItem.Live(it, favorites) }))

            // „Mehr aus …“: die zwei Gruppen, aus denen am meisten geschaut wurde.
            val shown = (recent + favorites).map { it.key }.toSet()
            recent.groupingBy { it.group }.eachCount().entries.sortedByDescending { it.value }.take(2).forEach { (group, _) ->
                val list = store.inGroup(group)
                val more = list.filter { it.key !in shown }.take(20)
                if (more.isNotEmpty()) add(HomeRow("Mehr aus $group", more.map { HomeItem.Live(it, list) }))
            }
            if (recent.isEmpty() && favorites.isEmpty()) {
                val first = visible.take(20)
                if (first.isNotEmpty()) add(HomeRow("Live TV", first.map { HomeItem.Live(it, visible) }))
            }

            val newMovies = movies.values.sortedByDescending { it.added }.take(20)
            if (newMovies.isNotEmpty()) add(HomeRow("Neue Filme", newMovies.map { HomeItem.Film(it, null) }))
            val newSeries = series.values.sortedByDescending { it.added }.take(20)
            if (newSeries.isNotEmpty()) add(HomeRow("Neue Serien", newSeries.map { HomeItem.Show(it, null) }))
        }
    }

    fun open(item: HomeItem) = when (item) {
        is HomeItem.Live -> app.play(Playback.Live(item.channel, item.list))
        is HomeItem.Film -> playMovie(app, item.movie)
        is HomeItem.Show -> { app.openSeries = item.series; app.screen = Screen.SERIES }
    }

    fun menu(item: HomeItem) {
        val entries = when (item) {
            is HomeItem.Live -> {
                val fav = item.channel.key in user.favorites
                listOf(
                    MenuItem("▶ Abspielen") { open(item) },
                    MenuItem(if (fav) "★ Aus Favoriten entfernen" else "☆ Zu Favoriten") { store.toggleFavorite(item.channel.key) },
                    MenuItem("Aus „Zuletzt gesehen“ entfernen") { store.updateUser { u -> u.copy(recent = u.recent - item.channel.key) } },
                )
            }
            is HomeItem.Film -> listOf(
                MenuItem("▶ Abspielen") { open(item) },
                MenuItem("Von vorn") { app.play(Playback.Vod(item.movie.key, item.movie.name, item.movie.url, 0)) },
                MenuItem("Aus „Weiter schauen“ entfernen") { store.updateUser { u -> u.copy(positions = u.positions - item.movie.key) } },
            )
            is HomeItem.Show -> listOf(MenuItem("Folgen zeigen") { open(item) })
        }
        dialog = { MenuDialog(app, title(item), entries, onDismiss = { dialog = null }) }
    }

    val first = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader("Start", "OK abspielen · ☰ Menü · ◀ Navigation") }
        if (rows.isEmpty()) item { Label("Noch nichts geschaut. Unter „Live TV“ einen Sender wählen.", Type.body) }
        itemsIndexed(rows, key = { _, r -> r.title }) { rowIndex, row ->
            Label(row.title, Type.heading, Modifier.padding(top = if (rowIndex == 0) 0.dp else 18.dp, bottom = 8.dp))
            LazyRow {
                itemsIndexed(row.items, key = { _, i -> i.key }) { index, item ->
                    when (item) {
                        is HomeItem.Live -> {
                            val now = guide[item.channel.key]?.let { nowOf(it) }
                            Tile(
                                item.channel.name, now?.title ?: item.channel.group, item.channel.logo,
                                onClick = { open(item) }, progress = now?.fraction(), onMenu = { menu(item) },
                                focusRequester = if (rowIndex == 0 && index == 0) first else null,
                            )
                        }
                        is HomeItem.Film -> Tile(
                            item.movie.name, item.movie.category, item.movie.poster, onClick = { open(item) }, progress = item.progress,
                            wide = false, onMenu = { menu(item) }, focusRequester = if (rowIndex == 0 && index == 0) first else null,
                        )
                        is HomeItem.Show -> Tile(
                            item.series.name, item.series.category, item.series.cover, onClick = { open(item) }, progress = item.progress,
                            wide = false, onMenu = { menu(item) }, focusRequester = if (rowIndex == 0 && index == 0) first else null,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                }
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
    if (rows.isNotEmpty()) FocusOnShow(first)
    dialog?.invoke()
}

private fun title(item: HomeItem) = when (item) {
    is HomeItem.Live -> item.channel.name
    is HomeItem.Film -> item.movie.name
    is HomeItem.Show -> item.series.name
}
