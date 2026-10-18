package de.novatv.data.repo

import de.novatv.data.LiveFormat
import de.novatv.data.db.PlaylistEntity
import de.novatv.data.parse.XtreamAccount
import de.novatv.data.parse.XtreamEpisode
import de.novatv.data.parse.XtreamLive
import de.novatv.data.parse.XtreamParsing
import de.novatv.data.parse.XtreamSeries
import de.novatv.data.parse.XtreamVod
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

class XtreamClient(
    private val http: OkHttpClient,
    server: String,
    private val username: String,
    private val password: String,
) {
    val base: String = normalizeServer(server)

    fun account(): XtreamAccount? = get(apiUrl(null), XtreamParsing::account)
    fun liveCategories(): Map<String, String> = get(apiUrl("get_live_categories"), XtreamParsing::categories)
    fun vodCategories(): Map<String, String> = get(apiUrl("get_vod_categories"), XtreamParsing::categories)
    fun seriesCategories(): Map<String, String> = get(apiUrl("get_series_categories"), XtreamParsing::categories)

    fun liveStreams(onItem: (XtreamLive) -> Unit) = get(apiUrl("get_live_streams")) { XtreamParsing.liveStreams(it, onItem) }
    fun vodStreams(onItem: (XtreamVod) -> Unit) = get(apiUrl("get_vod_streams")) { XtreamParsing.vodStreams(it, onItem) }
    fun series(onItem: (XtreamSeries) -> Unit) = get(apiUrl("get_series")) { XtreamParsing.series(it, onItem) }

    fun episodes(seriesId: String): List<XtreamEpisode> =
        get(apiUrl("get_series_info", "series_id" to seriesId), XtreamParsing::episodes)

    val epgUrl: String get() = "$base/xmltv.php?username=${enc(username)}&password=${enc(password)}"

    fun liveUrl(streamId: String, format: LiveFormat) = "$base/live/${enc(username)}/${enc(password)}/$streamId.${format.extension}"
    fun vodUrl(streamId: String, extension: String) = "$base/movie/${enc(username)}/${enc(password)}/$streamId.$extension"
    fun episodeUrl(episodeId: String, extension: String) = "$base/series/${enc(username)}/${enc(password)}/$episodeId.$extension"

    private fun apiUrl(action: String?, vararg extra: Pair<String, String>): String {
        val builder = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", username)
            .addQueryParameter("password", password)
        if (action != null) builder.addQueryParameter("action", action)
        extra.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    private fun <T> get(url: String, read: (InputStream) -> T): T {
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server antwortet mit HTTP ${response.code}")
            val body = response.body ?: throw IOException("Leere Antwort vom Server")
            return body.byteStream().use(read)
        }
    }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        fun normalizeServer(server: String): String {
            var s = server.trim().trimEnd('/')
            if (!s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) s = "http://$s"
            return s.removeSuffix("/player_api.php").removeSuffix("/get.php")
        }

        fun of(http: OkHttpClient, playlist: PlaylistEntity) =
            XtreamClient(http, playlist.url, playlist.username.orEmpty(), playlist.password.orEmpty())
    }
}
