package de.novatv.data.parse

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence
import java.io.InputStream

data class XtreamLive(
    val streamId: String,
    val name: String,
    val icon: String?,
    val epgId: String?,
    val categoryId: String?,
    val number: Int?,
    val archiveDays: Int,
)

data class XtreamVod(
    val streamId: String,
    val name: String,
    val icon: String?,
    val categoryId: String?,
    val rating: String?,
    val added: Long,
    val extension: String,
)

data class XtreamSeries(
    val seriesId: String,
    val name: String,
    val cover: String?,
    val categoryId: String?,
    val plot: String?,
    val rating: String?,
    val added: Long,
)

data class XtreamEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val extension: String,
    val image: String?,
    val plot: String?,
    val durationSeconds: Long,
)

data class XtreamAccount(val status: String?, val expiresAt: Long?, val maxConnections: Int?)

/**
 * Xtream-Panels liefern Zahlen mal als String, mal als Zahl, mal als null. Deshalb wird hier
 * bewusst auf JsonObject-Ebene gelesen statt mit starren Datenklassen.
 * Große Listen werden elementweise gestreamt, damit 100k Filme nicht auf einmal im Speicher landen.
 */
@OptIn(ExperimentalSerializationApi::class)
object XtreamParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun account(input: InputStream): XtreamAccount? {
        val root = json.decodeFromStream<JsonElement>(input) as? JsonObject ?: return null
        val user = root["user_info"] as? JsonObject ?: return null
        if (user.int("auth") == 0) return null
        return XtreamAccount(user.str("status"), user.long("exp_date")?.times(1000), user.int("max_connections"))
    }

    fun categories(input: InputStream): Map<String, String> {
        val root = json.decodeFromStream<JsonElement>(input) as? JsonArray ?: return emptyMap()
        return root.filterIsInstance<JsonObject>().mapNotNull { obj ->
            val id = obj.str("category_id") ?: return@mapNotNull null
            id to (obj.str("category_name") ?: id)
        }.toMap()
    }

    fun liveStreams(input: InputStream, onItem: (XtreamLive) -> Unit) = objects(input) { obj ->
        val id = obj.str("stream_id") ?: return@objects
        onItem(
            XtreamLive(
                streamId = id,
                name = obj.str("name") ?: id,
                icon = obj.str("stream_icon"),
                epgId = obj.str("epg_channel_id"),
                categoryId = obj.str("category_id"),
                number = obj.int("num"),
                archiveDays = if (obj.int("tv_archive") == 1) obj.int("tv_archive_duration") ?: 0 else 0,
            )
        )
    }

    fun vodStreams(input: InputStream, onItem: (XtreamVod) -> Unit) = objects(input) { obj ->
        val id = obj.str("stream_id") ?: return@objects
        onItem(
            XtreamVod(
                streamId = id,
                name = obj.str("name") ?: id,
                icon = obj.str("stream_icon"),
                categoryId = obj.str("category_id"),
                rating = obj.str("rating")?.takeUnless { it == "0" },
                added = obj.long("added") ?: 0L,
                extension = obj.str("container_extension") ?: "mp4",
            )
        )
    }

    fun series(input: InputStream, onItem: (XtreamSeries) -> Unit) = objects(input) { obj ->
        val id = obj.str("series_id") ?: return@objects
        onItem(
            XtreamSeries(
                seriesId = id,
                name = obj.str("name") ?: id,
                cover = obj.str("cover"),
                categoryId = obj.str("category_id"),
                plot = obj.str("plot"),
                rating = obj.str("rating")?.takeUnless { it == "0" },
                added = obj.long("last_modified") ?: 0L,
            )
        )
    }

    fun episodes(input: InputStream): List<XtreamEpisode> {
        val root = json.decodeFromStream<JsonElement>(input) as? JsonObject ?: return emptyList()
        // Je nach Panel ein Objekt {"1": [...]} oder eine Liste [[...]].
        val seasons: List<JsonElement> = when (val episodes = root["episodes"]) {
            is JsonObject -> episodes.values.toList()
            is JsonArray -> episodes.toList()
            else -> emptyList()
        }
        return seasons.flatMap { season -> (season as? JsonArray).orEmpty() }
            .filterIsInstance<JsonObject>()
            .mapNotNull { obj ->
                val id = obj.str("id") ?: return@mapNotNull null
                val info = obj["info"] as? JsonObject
                XtreamEpisode(
                    id = id,
                    season = obj.int("season") ?: 1,
                    episode = obj.int("episode_num") ?: 0,
                    title = obj.str("title") ?: "Episode ${obj.int("episode_num") ?: ""}",
                    extension = obj.str("container_extension") ?: "mp4",
                    image = info?.str("movie_image"),
                    plot = info?.str("plot"),
                    durationSeconds = info?.long("duration_secs") ?: 0L,
                )
            }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    private inline fun objects(input: InputStream, crossinline block: (JsonObject) -> Unit) {
        for (element in json.decodeToSequence(input, JsonElement.serializer(), DecodeSequenceMode.AUTO_DETECT)) {
            when (element) {
                is JsonObject -> block(element)
                is JsonArray -> element.forEach { (it as? JsonObject)?.let(block) }
                else -> Unit
            }
        }
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] as? JsonPrimitive ?: return null
        if (value is JsonNull) return null
        return value.content.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject.long(key: String): Long? = str(key)?.toDoubleOrNull()?.toLong()
    private fun JsonObject.int(key: String): Int? = long(key)?.toInt()
}
