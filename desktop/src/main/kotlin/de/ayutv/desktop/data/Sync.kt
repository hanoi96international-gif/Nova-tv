package de.ayutv.desktop.data

import de.ayutv.data.parse.EpgMatcher
import de.ayutv.data.parse.EpgSources
import de.ayutv.data.parse.M3uParser
import de.ayutv.data.parse.XmltvParser
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.data.parse.XtreamParsing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Playlists laden (M3U und Xtream) und den TV-Guide (XMLTV) einlesen. Dieselben Leser wie in der Fire-TV-App,
 * deshalb gleiche Sender, Gruppen und Schlüssel.
 */
class Sync(private val store: Store, val http: OkHttpClient = Network.http) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<String?>(null)
    /** Was gerade geladen wird (null = nichts). */
    val status: StateFlow<String?> = _status.asStateFlow()

    suspend fun syncAll() {
        for (p in store.playlists.value.filter { it.enabled }) runCatching { syncPlaylist(p.id) }
        runCatching { syncGuide() }
    }

    suspend fun syncPlaylist(id: Long) = mutex.withLock {
        val playlist = store.playlists.value.firstOrNull { it.id == id } ?: return@withLock
        try {
            _status.value = "${playlist.name} wird geladen …"
            val (content, epg) = withContext(Dispatchers.IO) {
                when (playlist.type) {
                    PlaylistType.M3U -> importM3u(playlist)
                    PlaylistType.XTREAM -> importXtream(playlist)
                }
            }
            store.setContent(playlist.id, content)
            store.savePlaylist(playlist.copy(lastSync = System.currentTimeMillis(), epgUrl = playlist.epgUrl ?: epg))
        } finally {
            _status.value = null
        }
    }

    private fun importM3u(playlist: Playlist): Pair<Store.Content, String?> {
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        var position = 0
        val header = open(playlist.url).use { input ->
            M3uParser.parse(input.bufferedReader()) { entry ->
                position++
                if (entry.isVod) {
                    movies += Movie("${playlist.id}:v:${entry.url}", playlist.id, entry.name, entry.groupTitle.ifEmpty { "Filme" }, entry.logo, url = entry.url)
                } else {
                    channels += Channel(
                        key = "${playlist.id}:c:${entry.tvgId ?: entry.name}:${entry.url}",
                        playlistId = playlist.id,
                        name = entry.name,
                        group = entry.groupTitle.ifEmpty { "Ohne Gruppe" },
                        logo = entry.logo,
                        url = entry.url,
                        epgId = entry.tvgId,
                        number = entry.number ?: position,
                        catchupDays = entry.catchupDays,
                    )
                }
            }
        }
        return Store.Content(channels, movies) to header.epgUrls.joinToString(",").ifEmpty { null }
    }

    private fun importXtream(playlist: Playlist): Pair<Store.Content, String?> {
        val api = Xtream(playlist)
        val liveGroups = api.get(api.action("get_live_categories"), XtreamParsing::categories)
        val vodGroups = runCatching { api.get(api.action("get_vod_categories"), XtreamParsing::categories) }.getOrDefault(emptyMap())
        val seriesGroups = runCatching { api.get(api.action("get_series_categories"), XtreamParsing::categories) }.getOrDefault(emptyMap())
        val channels = ArrayList<Channel>()
        var position = 0
        api.get(api.action("get_live_streams")) { input ->
            XtreamParsing.liveStreams(input) { live ->
                position++
                channels += Channel(
                    key = "${playlist.id}:c:${live.streamId}",
                    playlistId = playlist.id,
                    name = live.name,
                    group = liveGroups[live.categoryId] ?: "Ohne Gruppe",
                    logo = live.icon,
                    url = api.live(live.streamId),
                    epgId = live.epgId,
                    number = live.number ?: position,
                    catchupDays = live.archiveDays,
                )
            }
        }
        val movies = ArrayList<Movie>()
        runCatching {
            api.get(api.action("get_vod_streams")) { input ->
                XtreamParsing.vodStreams(input) { item ->
                    movies += Movie(
                        "${playlist.id}:v:${item.streamId}", playlist.id, item.name, vodGroups[item.categoryId] ?: "Filme",
                        item.icon, item.rating, api.movie(item.streamId, item.extension), item.added,
                    )
                }
            }
        }
        val series = ArrayList<Series>()
        runCatching {
            api.get(api.action("get_series")) { input ->
                XtreamParsing.series(input) { item ->
                    series += Series(
                        "${playlist.id}:s:${item.seriesId}", playlist.id, item.seriesId, item.name, seriesGroups[item.categoryId] ?: "Serien",
                        item.cover, item.plot, item.rating, item.added,
                    )
                }
            }
        }
        return Store.Content(channels, movies, series) to null
    }

    /** Folgen einer Serie (Xtream), mit abspielbarer Adresse. */
    suspend fun episodes(series: Series): List<Pair<XtreamEpisode, String>> = withContext(Dispatchers.IO) {
        val playlist = store.playlists.value.firstOrNull { it.id == series.playlistId } ?: return@withContext emptyList()
        if (playlist.type != PlaylistType.XTREAM) return@withContext emptyList()
        val api = Xtream(playlist)
        api.get(api.action("get_series_info", "series_id" to series.seriesId), XtreamParsing::episodes)
            .sortedWith(compareBy({ it.season }, { it.episode }))
            .map { it to api.episode(it.id, it.extension) }
    }

    /**
     * TV-Guide aller Playlists einlesen: eigene Adressen und die des Anbieters, danach das freie EPG für Sender ohne
     * Programm. Nur die nächsten 7 Tage (und der heutige Rückblick) der Sender, die es in den Playlists gibt.
     */
    suspend fun syncGuide() = mutex.withLock {
        try {
            val result = withContext(Dispatchers.IO) {
                val channels = store.channels()
                val guide = HashMap<String, MutableList<Programme>>()
                val now = System.currentTimeMillis()
                val from = now - DAY
                val until = now + 7 * DAY
                val sources = store.playlists.value.filter { it.enabled }.flatMap { p ->
                    when (p.type) {
                        PlaylistType.XTREAM -> EpgSources.forXtream(p.epgUrl, Xtream(p).guide())
                        PlaylistType.M3U -> EpgSources.forM3u(p.epgUrl, p.url)
                    }
                }.distinct() + EpgSources.PUBLIC
                for (url in sources) {
                    val missing = channels.filter { it.key !in guide }
                    if (missing.isEmpty()) break
                    _status.value = "TV-Guide: ${EpgSources.label(url)} …"
                    runCatching { readGuide(url, missing, from, until, guide) }
                }
                guide.mapValues { (_, list) -> list.sortedBy { it.start }.distinctBy { it.start } }
            }
            store.setGuide(result)
        } finally {
            _status.value = null
        }
    }

    private fun readGuide(url: String, channels: List<Channel>, from: Long, until: Long, guide: MutableMap<String, MutableList<Programme>>) {
        open(url).use { raw ->
            val input = if (url.endsWith(".gz")) GZIPInputStream(raw) else raw
            val parser = XmltvParser(org.kxml2.io.KXmlParser())
            // Die Sender stehen im XMLTV vor den Sendungen: Bei der ersten Sendung einmal zuordnen.
            var byGuideId: Map<String, List<String>>? = null
            parser.parse(input, wanted = { id, start, stop ->
                val map = byGuideId ?: run {
                    val matcher = EpgMatcher()
                    parser.channels.forEach { (gid, names) -> matcher.addGuideChannel(gid, names) }
                    channels.mapNotNull { c -> matcher.match(c.epgId, c.name)?.let { it to c.key } }
                        .groupBy({ it.first }, { it.second })
                        .also { byGuideId = it }
                }
                stop > from && start < until && id in map
            }) { p ->
                val keys = byGuideId?.get(p.channelId).orEmpty()
                val programme = Programme(p.start, p.stop, p.title, p.subtitle, p.description, p.category, p.flags)
                for (key in keys) guide.getOrPut(key) { ArrayList() } += programme
            }
        }
    }

    private fun open(url: String): InputStream {
        val response = http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("Server antwortet mit HTTP ${response.code}")
        }
        return response.body.byteStream()
    }

    /** Xtream-Zugang: Adressen wie in der Fire-TV-App. */
    inner class Xtream(playlist: Playlist) {
        private val base = playlist.url.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
            .removeSuffix("/player_api.php").removeSuffix("/get.php")
        private val user = playlist.username.orEmpty()
        private val pass = playlist.password.orEmpty()

        fun action(action: String, vararg extra: Pair<String, String>): String {
            val builder = "$base/player_api.php".toHttpUrl().newBuilder()
                .addQueryParameter("username", user).addQueryParameter("password", pass)
                .addQueryParameter("action", action)
            extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
            return builder.build().toString()
        }

        fun live(id: String) = "$base/live/${enc(user)}/${enc(pass)}/$id.ts"
        fun movie(id: String, extension: String) = "$base/movie/${enc(user)}/${enc(pass)}/$id.$extension"
        fun episode(id: String, extension: String) = "$base/series/${enc(user)}/${enc(pass)}/$id.$extension"
        fun guide() = "$base/xmltv.php?username=${enc(user)}&password=${enc(pass)}"

        fun <T> get(url: String, read: (InputStream) -> T): T = open(url).use(read)

        private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    companion object {
        const val USER_AGENT = "DnA-TV/Windows"
        private const val DAY = 24 * 3_600_000L
    }
}
