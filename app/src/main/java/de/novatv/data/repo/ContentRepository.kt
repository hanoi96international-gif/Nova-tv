package de.novatv.data.repo

import de.novatv.data.db.ChannelEntity
import de.novatv.data.db.FavoriteEntity
import de.novatv.data.db.GroupKind
import de.novatv.data.db.GroupToggle
import de.novatv.data.db.HiddenGroupEntity
import de.novatv.data.db.HistoryEntity
import de.novatv.data.db.HistoryKind
import de.novatv.data.db.NovaDatabase
import de.novatv.data.db.PlaylistType
import de.novatv.data.db.ProgrammeEntity
import de.novatv.data.db.SeriesEntity
import de.novatv.data.db.VodEntity
import de.novatv.data.parse.XtreamEpisode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

data class SearchResults(
    val channels: List<ChannelEntity> = emptyList(),
    val programmes: List<Pair<ProgrammeEntity, ChannelEntity?>> = emptyList(),
    val movies: List<VodEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty get() = channels.isEmpty() && programmes.isEmpty() && movies.isEmpty() && series.isEmpty()
}

data class Recommendation(val title: String, val channels: List<ChannelEntity>)

class ContentRepository(
    private val db: NovaDatabase,
    private val http: OkHttpClient,
) {
    val groups = db.channels().observeGroups()
    val favoriteChannels = db.channels().observeFavorites()
    val favoriteKeys = db.favorites().observeKeys()
    val channelCount = db.channels().observeCount()
    val vodCategories = db.vod().observeCategories()
    val seriesCategories = db.series().observeCategories()
    val continueWatching = db.history().observeContinueWatching(20)
    val recentLive = db.history().observeRecentLive(20)
    val recentMovies = db.vod().observeRecent(30)
    val recentSeries = db.series().observeRecent(30)

    fun channelsIn(group: String?) = if (group == null) db.channels().observeAll() else db.channels().observeInGroup(group)
    fun moviesIn(category: String) = db.vod().observeInCategory(category)
    fun seriesIn(category: String) = db.series().observeInCategory(category)

    suspend fun channel(key: String) = db.channels().byKey(key)
    suspend fun channelByNumber(number: Int) = db.channels().byNumber(number)

    /** Liste, durch die im Player mit hoch/runter gezappt wird. */
    suspend fun zapList(group: String?, favoritesOnly: Boolean): List<ChannelEntity> = when {
        favoritesOnly -> db.channels().favorites()
        group != null -> db.channels().inGroup(group)
        else -> db.channels().all()
    }

    suspend fun toggleFavorite(key: String): Boolean {
        val favorite = db.favorites().isFavorite(key)
        if (favorite) db.favorites().remove(key) else db.favorites().add(FavoriteEntity(key, System.currentTimeMillis()))
        return !favorite
    }

    /** Aktuelle Sendung je EPG-ID. */
    suspend fun nowPlaying(channels: List<ChannelEntity>, at: Long = System.currentTimeMillis()): Map<String, ProgrammeEntity> {
        val ids = channels.mapNotNull { it.epgId?.takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        val result = HashMap<String, ProgrammeEntity>()
        ids.chunked(SQL_CHUNK).forEach { chunk ->
            db.programmes().inRange(chunk, at, at + 1).forEach { result.putIfAbsent(it.channelEpgId, it) }
        }
        return result
    }

    suspend fun programmesInRange(channels: List<ChannelEntity>, from: Long, to: Long): Map<String, List<ProgrammeEntity>> {
        val ids = channels.mapNotNull { it.epgId?.takeIf(String::isNotEmpty) }.distinct()
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(SQL_CHUNK).flatMap { db.programmes().inRange(it, from, to) }.groupBy { it.channelEpgId }
    }

    suspend fun upcoming(channel: ChannelEntity, limit: Int = 8): List<ProgrammeEntity> {
        val id = channel.epgId?.takeIf(String::isNotEmpty) ?: return emptyList()
        return db.programmes().upcoming(id, System.currentTimeMillis(), limit)
    }

    suspend fun search(query: String): SearchResults = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope SearchResults()
        val now = System.currentTimeMillis()
        val channels = async { db.channels().search(q, 40) }
        val programmes = async { db.programmes().search(q, now, 40) }
        val movies = async { db.vod().search(q, 40) }
        val series = async { db.series().search(q, 40) }

        val programmeList = programmes.await()
        // Zu jeder gefundenen Sendung den passenden Sender (für "Jetzt einschalten").
        val epgIds = programmeList.map { it.channelEpgId }.distinct()
        val channelsByEpg = if (epgIds.isEmpty()) emptyMap() else db.channels().byEpgIds(epgIds).associateBy { it.epgId }
        SearchResults(
            channels = channels.await(),
            programmes = programmeList.map { it to channelsByEpg[it.channelEpgId] },
            movies = movies.await(),
            series = series.await(),
        )
    }

    /**
     * Empfehlungen ohne Cloud: Gruppen, die der Nutzer am häufigsten schaut, liefern weitere
     * Sender, die er noch nicht kennt. Dazu kommt, was dort gerade läuft.
     */
    suspend fun recommendations(): List<Recommendation> {
        val groups = db.history().topLiveGroups(3)
        if (groups.isEmpty()) return emptyList()
        val seen = db.history().recentLiveKeys(200)
        return groups.mapNotNull { group ->
            val channels = db.channels().inGroupsExcluding(listOf(group), seen, 20)
            channels.takeIf { it.isNotEmpty() }?.let { Recommendation("Mehr aus $group", it) }
        }
    }

    suspend fun recordLive(channel: ChannelEntity) {
        val existing = db.history().byKey(channel.key)
        db.history().upsert(
            HistoryEntity(
                key = channel.key,
                kind = HistoryKind.LIVE,
                title = channel.name,
                subtitle = channel.groupTitle,
                image = channel.logo,
                url = channel.url,
                groupTitle = channel.groupTitle,
                playCount = (existing?.playCount ?: 0) + 1,
                watchedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun recordProgress(entry: HistoryEntity) {
        val existing = db.history().byKey(entry.key)
        db.history().upsert(entry.copy(playCount = existing?.playCount ?: 1))
    }

    suspend fun resumePosition(key: String): Long = db.history().byKey(key)?.positionMs ?: 0L

    suspend fun clearHistory() = db.history().clear()

    // ---------------------------------------------------------------- Gruppen ein-/ausblenden

    fun groupToggles(playlistId: Long, kind: GroupKind): Flow<List<GroupToggle>> = when (kind) {
        GroupKind.LIVE -> db.hiddenGroups().observeLive(playlistId)
        GroupKind.VOD -> db.hiddenGroups().observeVod(playlistId)
        GroupKind.SERIES -> db.hiddenGroups().observeSeries(playlistId)
    }

    val hiddenGroupCount = db.hiddenGroups().observeHiddenCount()

    suspend fun setGroupsHidden(playlistId: Long, kind: GroupKind, names: List<String>, hidden: Boolean) {
        names.chunked(SQL_CHUNK).forEach { chunk ->
            if (hidden) {
                db.hiddenGroups().hide(chunk.map { HiddenGroupEntity(playlistId, kind, it) })
            } else {
                db.hiddenGroups().show(playlistId, kind, chunk)
            }
        }
    }

    /** Schnellaktion aus den Listen: Gruppe in allen Playlists ausblenden, die sie enthalten. */
    suspend fun hideGroupEverywhere(kind: GroupKind, name: String) = when (kind) {
        GroupKind.LIVE -> db.hiddenGroups().hideLiveEverywhere(name)
        GroupKind.VOD -> db.hiddenGroups().hideVodEverywhere(name)
        GroupKind.SERIES -> db.hiddenGroups().hideSeriesEverywhere(name)
    }

    suspend fun episodes(series: SeriesEntity): List<Pair<XtreamEpisode, String>> = withContext(Dispatchers.IO) {
        val playlist = db.playlists().byId(series.playlistId) ?: return@withContext emptyList()
        if (playlist.type != PlaylistType.XTREAM) return@withContext emptyList()
        val client = XtreamClient.of(http, playlist)
        client.episodes(series.seriesId).map { it to client.episodeUrl(it.id, it.extension) }
    }

    private companion object {
        const val SQL_CHUNK = 500
    }
}
