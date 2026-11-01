package de.ayutv.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.repo.Recommendation
import de.ayutv.player.PlayerActivity
import de.ayutv.data.record.Recording
import de.ayutv.player.VodRequest
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelCard
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.PosterCard
import de.ayutv.ui.components.SectionTitle
import de.ayutv.ui.components.SyncBanner
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpenSeries: (SeriesEntity) -> Unit) {
    val container = LocalContainer.current
    val content = container.content
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val continueWatching by content.continueWatching.collectAsStateWithLifecycle(emptyList())
    val recentLive by content.recentLive.collectAsStateWithLifecycle(emptyList())
    val favorites by content.favoriteChannels.collectAsStateWithLifecycle(emptyList())
    val recentMovies by content.recentMovies.collectAsStateWithLifecycle(emptyList())
    val recentSeries by content.recentSeries.collectAsStateWithLifecycle(emptyList())
    val watchMovies by content.watchlistMovies.collectAsStateWithLifecycle(emptyList())
    val watchSeries by content.watchlistSeries.collectAsStateWithLifecycle(emptyList())
    val sync by container.playlists.state.collectAsStateWithLifecycle()
    val minute by rememberMinuteTicker()
    val recordings by container.recordings.all.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var pickingProfile by remember { mutableStateOf(false) }
    if (pickingProfile) {
        ProfilePicker(onClose = { pickingProfile = false })
        return
    }

    var recentChannels by remember { mutableStateOf<List<ChannelEntity>>(emptyList()) }
    var recommendations by remember { mutableStateOf<List<Recommendation>>(emptyList()) }
    var nowMap by remember { mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()) }

    LaunchedEffect(recentLive) {
        recentChannels = recentLive.mapNotNull { content.channel(it.key) }
        recommendations = content.recommendations()
    }
    LaunchedEffect(recentChannels, favorites, recommendations, minute) {
        nowMap = content.nowPlaying(recentChannels + favorites + recommendations.flatMap { it.channels })
    }

    val nothingYet = continueWatching.isEmpty() && recentChannels.isEmpty() && favorites.isEmpty() &&
        recentMovies.isEmpty() && recentSeries.isEmpty() && watchMovies.isEmpty() && watchSeries.isEmpty()

    // „Jetzt“-Kachel oben: das, womit man am wahrscheinlichsten weitermacht (fest, folgt nicht dem Fokus – sonst
    // würde die ganze Seite bei jeder Bewegung neu aufgebaut).
    val spotlight = remember(continueWatching, recentChannels, favorites, nowMap) {
        continueWatching.firstOrNull()?.let { e ->
            Spotlight(
                label = "WEITER SCHAUEN", title = e.title, subtitle = e.subtitle, image = e.image,
                progress = if (e.durationMs > 0) e.positionMs.toFloat() / e.durationMs else null,
                play = { PlayerActivity.startVod(context, VodRequest(e.key, e.kind, e.title, e.subtitle, e.image, e.url)) },
            )
        } ?: (recentChannels.firstOrNull() ?: favorites.firstOrNull())?.let { c ->
            val now = c.epgId?.let { nowMap[it] }
            Spotlight(
                label = "LIVE · ${c.name}", title = now?.title ?: c.name,
                subtitle = now?.let { "${formatTime(it.start)} – ${formatTime(it.stop)}" } ?: c.groupTitle,
                image = now?.icon?.takeIf { it.isNotBlank() }, logo = c.logo,
                progress = now?.let { p -> ((System.currentTimeMillis() - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f) },
                play = { PlayerActivity.startLive(context, c.key, c.groupTitle) },
            )
        }
    }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item(key = "header", contentType = "header") {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(minute), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        java.text.SimpleDateFormat("EEEE, d. MMMM", java.util.Locale.GERMAN).format(java.util.Date(minute)),
                        style = MaterialTheme.typography.bodyMedium, color = AyuColors.TextSecondary,
                    )
                    SyncBanner(sync)
                    de.ayutv.ui.components.UpdateBanner(Modifier.padding(top = 8.dp))
                }
                ProfileChip { pickingProfile = true }
                Spacer(Modifier.width(16.dp))
                Text(formatTime(minute), style = MaterialTheme.typography.headlineSmall, color = AyuColors.TextSecondary)
            }
        }

        spotlight?.let { spot ->
            item(key = "spotlight", contentType = "spotlight") { SpotlightCard(spot) }
        }

        if (nothingYet) {
            item(key = "empty", contentType = "hint") {
                EmptyHint(
                    "Alles bereit",
                    "Öffne Live TV oder Filme. Was du schaust, merkt sich DnA-TV und schlägt dir hier Passendes vor.",
                )
            }
        }

        if (continueWatching.isNotEmpty()) {
            item(key = "continue", contentType = "posterRow") {
                PosterRow("Weiter schauen") {
                    items(continueWatching, key = { it.key }) { entry ->
                        PosterCard(
                            title = entry.title,
                            image = entry.image,
                            subtitle = entry.subtitle,
                            progress = if (entry.durationMs > 0) entry.positionMs.toFloat() / entry.durationMs else null,
                            onClick = {
                                PlayerActivity.startVod(
                                    context,
                                    VodRequest(entry.key, entry.kind, entry.title, entry.subtitle, entry.image, entry.url),
                                )
                            },
                        )
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "favorites", contentType = "channelRow") {
                ChannelRow(
                    "Favoriten",
                    favorites,
                    nowMap,
                    onPlay = { PlayerActivity.startLive(context, it.key, group = null, favoritesOnly = true) },
                    onLongClick = { channel -> scope.launch { content.toggleFavorite(channel.key) } },
                )
            }
        }

        if (recentChannels.isNotEmpty()) {
            item(key = "recent", contentType = "channelRow") {
                ChannelRow("Zuletzt gesehen", recentChannels, nowMap, onPlay = { PlayerActivity.startLive(context, it.key, it.groupTitle) })
            }
        }

        if (watchMovies.isNotEmpty() || watchSeries.isNotEmpty()) {
            item(key = "watchlist", contentType = "posterRow") {
                // Merkliste: gemerkte Filme und Serien (☆ Merken auf der Detailseite; lange OK entfernt).
                PosterRow("☆ Meine Liste") {
                    items(watchMovies, key = { "m-" + it.key }) { movie ->
                        PosterCard(
                            title = movie.name,
                            image = movie.poster,
                            subtitle = "Film" + (movie.rating?.let { " · ★ $it" } ?: ""),
                            onClick = {
                                PlayerActivity.startVod(context, VodRequest(movie.key, HistoryKind.VOD, movie.name, movie.category, movie.poster, movie.url))
                            },
                            onLongClick = { scope.launch { content.toggleFavorite(movie.key) } },
                        )
                    }
                    items(watchSeries, key = { "s-" + it.key }) { series ->
                        PosterCard(
                            title = series.name,
                            image = series.cover,
                            subtitle = "Serie",
                            onClick = { onOpenSeries(series) },
                            onLongClick = { scope.launch { content.toggleFavorite(series.key) } },
                        )
                    }
                }
            }
        }

        if (recordings.isNotEmpty()) {
            item(key = "recordings", contentType = "posterRow") {
                PosterRow("⏺ Aufnahmen & Downloads") {
                    items(recordings, key = { it.id }) { r ->
                        PosterCard(
                            title = r.title,
                            image = r.image,
                            subtitle = recordingStatus(r),
                            progress = if (r.totalBytes > 0 && r.state == Recording.State.RUNNING) r.bytes.toFloat() / r.totalBytes else null,
                            onClick = {
                                if (r.state == Recording.State.SCHEDULED || r.bytes == 0L && r.state != Recording.State.DONE) {
                                    android.widget.Toast.makeText(context, recordingStatus(r), android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    val key = r.sourceKey ?: "rec:${r.id}"
                                    PlayerActivity.startVod(
                                        context,
                                        VodRequest(
                                            key,
                                            if (de.ayutv.data.parse.Episodes.seriesKeyOf(key) != null) HistoryKind.EPISODE else HistoryKind.VOD,
                                            r.title,
                                            r.subtitle,
                                            r.image,
                                            android.net.Uri.fromFile(container.recordings.file(r)).toString(),
                                        ),
                                    )
                                }
                            },
                            onLongClick = {
                                // Zweimal lange drücken: löschen (einmal könnte ein Versehen sein).
                                if (pendingDelete == r.id) {
                                    pendingDelete = null
                                    scope.launch { container.recordings.delete(r) }
                                } else {
                                    pendingDelete = r.id
                                    android.widget.Toast.makeText(context, "Nochmal lange drücken: „${r.title}“ löschen", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }
            }
        }

        recommendations.forEach { rec ->
            item(key = "rec-${rec.title}", contentType = "channelRow") {
                ChannelRow(rec.title, rec.channels, nowMap, onPlay = { PlayerActivity.startLive(context, it.key, it.groupTitle) })
            }
        }

        if (recentMovies.isNotEmpty()) {
            item(key = "movies", contentType = "posterRow") {
                PosterRow("Neue Filme") {
                    items(recentMovies, key = { it.key }) { movie ->
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

        if (recentSeries.isNotEmpty()) {
            item(key = "series", contentType = "posterRow") {
                PosterRow("Neue Serien") {
                    items(recentSeries, key = { it.key }) { series ->
                        PosterCard(title = series.name, image = series.cover, onClick = { onOpenSeries(series) })
                    }
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PosterRow(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
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

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ChannelRow(
    title: String,
    channels: List<ChannelEntity>,
    nowMap: Map<String, ProgrammeEntity>,
    onPlay: (ChannelEntity) -> Unit,
    onLongClick: ((ChannelEntity) -> Unit)? = null,
) {
    Column {
        SectionTitle(title)
        LazyRow(
            modifier = Modifier.focusRestorer(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        ) {
            items(channels, key = { it.key }) { channel ->
                val now = channel.epgId?.let { nowMap[it] }
                ChannelCard(
                    channel = channel,
                    now = now,
                    onClick = { onPlay(channel) },
                    onLongClick = onLongClick?.let { callback -> { callback(channel) } },
                )
            }
        }
    }
}

/** Stand einer Aufnahme / eines Downloads in wenigen Worten. */
private fun recordingStatus(r: Recording): String {
    fun size(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "${bytes / 1_000_000} MB"
    val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
    return when (r.state) {
        Recording.State.SCHEDULED -> if (r.kind == Recording.Kind.RECORDING) "Geplant: ${time.format(java.util.Date(r.start))}" else "Wartet …"
        Recording.State.RUNNING -> if (r.kind == Recording.Kind.RECORDING) "⏺ Läuft · ${size(r.bytes)}"
            else if (r.totalBytes > 0) "⬇ ${r.bytes * 100 / r.totalBytes} %" else "⬇ ${size(r.bytes)}"
        Recording.State.DONE -> (if (r.kind == Recording.Kind.RECORDING) "${r.subtitle.orEmpty()} · " else "⬇ ") + size(r.bytes)
        Recording.State.FAILED -> "✗ ${r.error ?: "Fehlgeschlagen"}"
    }
}

/** Inhalt der großen „Jetzt“-Kachel. */
private class Spotlight(
    val label: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float?,
    val logo: String? = null,
    val play: () -> Unit,
)

private fun greeting(now: Long): String = when (java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..10 -> "Guten Morgen"
    in 11..17 -> "Hallo"
    in 18..22 -> "Guten Abend"
    else -> "Gute Nacht"
}

/**
 * Große Kachel oben: Bild (Poster, Sendungsbild oder Senderlogo) links, Titel, Zeit und Fortschritt rechts. Ein
 * einziges Bild in fester Größe, keine Überblendung: kostet beim Scrollen nichts.
 */
@Composable
private fun SpotlightCard(spot: Spotlight) {
    val context = coil3.compose.LocalPlatformContext.current
    androidx.tv.material3.Card(
        onClick = spot.play,
        modifier = Modifier.fillMaxWidth().height(210.dp),
        colors = androidx.tv.material3.CardDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
        border = androidx.tv.material3.CardDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, AyuColors.Accent)),
        ),
        scale = androidx.tv.material3.CardDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxHeight().width(374.dp).background(AyuColors.SurfaceRaised),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                val image = spot.image ?: spot.logo
                if (image != null) {
                    val request = remember(image) {
                        coil3.request.ImageRequest.Builder(context).data(image).size(640, 360).memoryCacheKey("spot:$image").build()
                    }
                    coil3.compose.AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = if (spot.image != null) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = if (spot.image != null) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(40.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
                Text(spot.label, color = AyuColors.Accent, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                Text(spot.title, style = MaterialTheme.typography.headlineSmall, color = AyuColors.TextPrimary, maxLines = 2)
                spot.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = AyuColors.TextSecondary, maxLines = 1)
                }
                spot.progress?.let {
                    Spacer(Modifier.height(12.dp))
                    de.ayutv.ui.components.ProgressLine(it, Modifier.width(320.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("OK ▶ abspielen", style = MaterialTheme.typography.bodySmall, color = AyuColors.TextSecondary)
            }
        }
    }
}
