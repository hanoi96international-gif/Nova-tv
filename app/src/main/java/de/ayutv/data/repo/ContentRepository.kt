package de.ayutv.data.repo

import de.ayutv.data.parse.Episodes
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.FavoriteEntity
import de.ayutv.data.db.GroupKind
import de.ayutv.data.db.GroupToggle
import de.ayutv.data.db.HiddenGroupEntity
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.db.VodEntity
import de.ayutv.data.parse.ChannelVariants
import de.ayutv.data.parse.Countries
import kotlinx.coroutines.flow.first
import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.parse.Teams
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.data.parse.headline
import de.ayutv.data.parse.isSportEvent
import de.ayutv.data.reminders.Reminder
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
    private val db: AyuDatabase,
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

    /** Liste, durch die im Player mit hoch/runter gezappt wird; mit [merge] jeder Sender nur in seiner besten Fassung. */
    suspend fun zapList(group: String?, favoritesOnly: Boolean, merge: Boolean = false): List<ChannelEntity> {
        val list = when {
            favoritesOnly -> db.channels().favorites()
            group != null -> db.channels().inGroup(group)
            else -> db.channels().all()
        }
        return if (merge && !favoritesOnly) mergeVariants(list) else list
    }

    /** Jeder Sender einmal, in seiner besten Fassung (SD/HD/4K/RAW/Backup zusammengefasst). */
    suspend fun mergeVariants(channels: List<ChannelEntity>): List<ChannelEntity> = withContext(Dispatchers.Default) {
        ChannelVariants.merge(channels, { it.name }, { it.playlistId })
    }

    /** Andere Fassungen desselben Senders, beste zuerst (für den Wechsel, wenn eine ausfällt). */
    suspend fun variantsOf(channel: ChannelEntity): List<ChannelEntity> = withContext(Dispatchers.Default) {
        if (ChannelVariants.key(channel.name) == null) return@withContext emptyList()
        ChannelVariants.alternatives(channel, db.channels().visibleInPlaylist(channel.playlistId), { it.name }, { it.playlistId }) { a, b -> a.key == b.key }
    }

    /** Ein Spiel oder eine Live-Übertragung mit dem Sender, auf dem es läuft. */
    data class SportEvent(val programme: ProgrammeEntity, val channel: ChannelEntity, val otherChannels: Int)

    /**
     * Sport von jetzt bis [hours] Stunden: nur Spiele und Live-Übertragungen (keine Magazine, Wiederholungen).
     * Läuft dasselbe Spiel auf mehreren Sendern, erscheint es einmal (bevorzugt auf einem Favoriten).
     */
    suspend fun sportEvents(hours: Int = 36, now: Long = System.currentTimeMillis()): List<SportEvent> {
        val programmes = db.programmes().sportBetween(now, now + hours * HOUR, 4_000).filter { it.isSportEvent() }
        if (programmes.isEmpty()) return emptyList()
        // Alle Fassungen je EPG-Kennung (HD, 4K, SD teilen sich oft eine): daraus die beste wählen.
        val channels = programmes.map { it.channelEpgId }.distinct().chunked(SQL_CHUNK)
            .flatMap { db.channels().allByEpgIds(it) }.groupBy { it.epgId }
        val favorites = db.favorites().keys().toHashSet()
        return withContext(Dispatchers.Default) {
            programmes.flatMap { p -> channels[p.channelEpgId].orEmpty().map { p to it } }
                .groupBy { (p, _) -> (p.start / (5 * 60_000L)) to p.headline().lowercase() }
                .values.map { same ->
                    val (p, c) = same.minWith(compareBy({ if (it.second.key in favorites) 0 else 1 }, { ChannelVariants.rank(it.second.name) }))
                    // Weitere Sender, nicht weitere Fassungen desselben Senders.
                    val others = same.map { ChannelVariants.key(it.second.name) ?: it.second.key }.distinct().size - 1
                    SportEvent(p, c, others)
                }
                .sortedWith(compareBy({ it.programme.start }, { it.channel.name }))
        }
    }

    /** Spiele der Vereine in [teams] in den nächsten [days] Tagen, als Erinnerungen. */
    suspend fun teamReminders(teams: Set<String>, days: Int = 7): List<Reminder> {
        if (teams.isEmpty()) return emptyList()
        return sportEvents(hours = days * 24).filter { e ->
            val p = e.programme
            val pairing = ProgrammeText.pairing(p.title, p.subtitle) ?: return@filter false
            p.start > System.currentTimeMillis() && teams.any { Teams.plays(it, pairing, p.title + " " + p.subtitle.orEmpty()) }
        }.map { Reminder(it.channel.key, it.channel.name, it.programme.headline(), it.programme.start) }
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
        if (ids.size > SQL_CHUNK) {
            // Große Listen: einmal alles Laufende holen (Index auf stop) und im Speicher zuordnen.
            val wanted = ids.toHashSet()
            db.programmes().airingAt(at).forEach { if (it.channelEpgId in wanted) result.putIfAbsent(it.channelEpgId, it) }
            return result
        }
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

    /** Archiv-Adresse einer vergangenen Sendung oder null (Sender ohne Archiv, zu alt). */
    suspend fun catchupUrl(channel: ChannelEntity, programme: ProgrammeEntity): String? =
        Catchup.url(db.playlists().byId(channel.playlistId), channel, programme.start, programme.stop)

    suspend fun upcoming(channel: ChannelEntity, limit: Int = 8): List<ProgrammeEntity> {
        val id = channel.epgId?.takeIf(String::isNotEmpty) ?: return emptyList()
        // Nutzen mehrere Playlists dieselben EPG-IDs, käme jede Sendung mehrfach: die eigene Playlist
        // bevorzugen und doppelte Startzeiten entfernen.
        return db.programmes().upcoming(id, System.currentTimeMillis(), limit * 3)
            .sortedWith(compareBy({ it.start }, { if (it.playlistId == channel.playlistId) 0 else 1 }))
            .distinctBy { it.start }
            .take(limit)
    }

    suspend fun search(query: String): SearchResults = coroutineScope {
        val q = query.trim()
        if (q.length < 2) return@coroutineScope SearchResults()
        val now = System.currentTimeMillis()
        // Alle Treffer zeigen (die Reihen laden lazy). Die Grenze schützt nur vor Speicherproblemen
        // bei Ein-Buchstaben-artigen Suchen in Playlists mit Hunderttausenden Einträgen.
        val channels = async { db.channels().search(q, SEARCH_MAX) }
        val programmes = async { db.programmes().search(q, now, SEARCH_MAX) }
        val movies = async { db.vod().search(q, SEARCH_MAX) }
        val series = async { db.series().search(q, SEARCH_MAX) }

        val programmeList = programmes.await().distinctBy { Triple(it.channelEpgId, it.start, it.title) }
        // Zu jeder gefundenen Sendung den passenden Sender (für "Jetzt einschalten").
        val epgIds = programmeList.map { it.channelEpgId }.distinct()
        // In Stücken: Ältere Fire-OS-SQLite erlaubt höchstens 999 Platzhalter pro Abfrage.
        val channelsByEpg = epgIds.chunked(SQL_CHUNK).flatMap { db.channels().byEpgIds(it) }.associateBy { it.epgId }
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

    /**
     * Wo ein Film weiterläuft: ein paar Sekunden vor der letzten Stelle (Wiedereinstieg). Zu Ende
     * oder fast zu Ende geschaut beginnt er von vorn, statt sofort wieder zu enden.
     */
    suspend fun resumePosition(key: String): Long {
        val entry = db.history().byKey(key) ?: return 0L
        val nearEnd = entry.durationMs > 0 &&
            (entry.positionMs >= entry.durationMs * 0.95 || entry.durationMs - entry.positionMs < 120_000)
        return if (nearEnd) 0L else (entry.positionMs - 3_000).coerceAtLeast(0)
    }

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

    /** Ein Land mit seinen Gruppen (Live, Filme, Serien) über alle Playlists. [visible]: mindestens eine Gruppe sichtbar. */
    data class CountryChoice(val code: String, val label: String, val groups: Int, val items: Int, val visible: Boolean)

    private suspend fun allGroups(): List<Triple<Long, GroupKind, GroupToggle>> = db.playlists().all().filter { it.enabled }.flatMap { playlist ->
        GroupKind.entries.flatMap { kind -> groupToggles(playlist.id, kind).first().map { Triple(playlist.id, kind, it) } }
    }

    /** Länder nach Gruppennamen („DE | Sport“, „UK: News“); Gruppen ohne Länderkennung als eigener Eintrag (code ""). */
    suspend fun countryChoices(): List<CountryChoice> = withContext(Dispatchers.Default) {
        allGroups().groupBy { (_, _, g) -> Countries.of(g.name) }.map { (country, groups) ->
            CountryChoice(
                code = country?.code.orEmpty(),
                label = country?.label ?: "Ohne Länderkennung",
                groups = groups.size,
                items = groups.sumOf { it.third.count },
                visible = groups.any { !it.third.hidden },
            )
        }.sortedWith(compareBy({ it.code.isEmpty() }, { it.code !in PREFERRED }, { -it.items }))
    }

    /** Alle Gruppen eines Landes ([code], "" = ohne Länderkennung) ein- oder ausblenden. */
    suspend fun setCountryVisible(code: String, visible: Boolean) {
        allGroups().filter { (_, _, g) -> Countries.of(g.name)?.code.orEmpty() == code }
            .groupBy({ it.first to it.second }, { it.third.name })
            .forEach { (key, names) -> setGroupsHidden(key.first, key.second, names, hidden = !visible) }
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

    data class NextEpisode(val series: SeriesEntity, val episode: XtreamEpisode, val url: String)

    /** Die Folge nach [episodeKey] („<Serie>:e:<Folge>“), über das Staffelende hinweg; null bei der letzten. */
    suspend fun nextEpisode(episodeKey: String): NextEpisode? {
        val seriesKey = Episodes.seriesKeyOf(episodeKey) ?: return null
        val currentId = Episodes.episodeIdOf(episodeKey) ?: return null
        val series = db.series().byKey(seriesKey) ?: return null
        val list = runCatching { episodes(series) }.getOrNull() ?: return null
        val next = Episodes.next(list.map { it.first }, currentId) ?: return null
        val url = list.firstOrNull { it.first.id == next.id }?.second ?: return null
        return NextEpisode(series, next, url)
    }

    private companion object {
        val PREFERRED = setOf("DE", "AT", "CH")
        const val HOUR = 3_600_000L
        const val SQL_CHUNK = 500
        const val SEARCH_MAX = 20_000
    }
}
