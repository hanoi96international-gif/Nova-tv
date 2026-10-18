package de.ayutv.data.parse

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * OpenSubtitles-"moviehash": Dateigröße plus die Summe aller 64-Bit-Wörter (little endian)
 * der ersten und letzten 64 KiB. Ein Treffer über den Hash passt exakt zu genau dieser Datei,
 * also auch zeitlich (keine verschobenen Untertitel wie bei einer Suche nach Titel).
 */
object MovieHash {
    const val CHUNK = 64 * 1024

    fun compute(fileSize: Long, head: ByteArray, tail: ByteArray): String {
        require(head.size >= CHUNK && tail.size >= CHUNK) { "Es werden je 64 KiB vom Anfang und Ende benötigt" }
        var hash = fileSize
        hash += sumWords(head)
        hash += sumWords(tail)
        return "%016x".format(hash)
    }

    private fun sumWords(bytes: ByteArray): Long {
        var sum = 0L
        var i = 0
        while (i + 8 <= CHUNK) {
            var word = 0L
            for (b in 7 downTo 0) word = (word shl 8) or (bytes[i + b].toLong() and 0xFF)
            sum += word
            i += 8
        }
        return sum
    }
}

data class SubtitleCandidate(
    val fileId: Long,
    val language: String,
    val release: String,
    val hashMatch: Boolean,
    val downloads: Long,
    val hearingImpaired: Boolean,
)

object OpenSubtitlesParsing {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Sortiert nach Qualität: Hash-Treffer zuerst (synchron zur Datei), dann normale vor
     * Hörgeschädigten-Untertiteln (ohne [Tür knarrt]-Einblendungen), dann Beliebtheit.
     */
    fun searchResults(body: String): List<SubtitleCandidate> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val data = root["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val attributes = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
            val file = (attributes["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val fileId = file.primitive("file_id")?.longOrNull ?: return@mapNotNull null
            SubtitleCandidate(
                fileId = fileId,
                language = attributes.primitive("language")?.contentOrNull.orEmpty(),
                release = attributes.primitive("release")?.contentOrNull ?: file.primitive("file_name")?.contentOrNull.orEmpty(),
                hashMatch = attributes.primitive("moviehash_match")?.booleanOrNull ?: false,
                downloads = attributes.primitive("download_count")?.longOrNull ?: 0L,
                hearingImpaired = attributes.primitive("hearing_impaired")?.booleanOrNull ?: false,
            )
        }.sortedWith(
            compareByDescending<SubtitleCandidate> { it.hashMatch }
                .thenBy { it.hearingImpaired }
                .thenByDescending { it.downloads }
        )
    }

    fun downloadLink(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("link")?.contentOrNull

    fun loginToken(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("token")?.contentOrNull

    fun message(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("message")?.contentOrNull
    }.getOrNull()

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    /**
     * Titel aus IPTV-Listen sind oft "DE - Film Name (2019) [4K]". Für die Suche nach Name
     * werden Präfixe, Qualitätsangaben und Klammern entfernt, das Jahr wird separat erkannt.
     */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        val year = Regex("""\b(19[3-9]\d|20[0-4]\d)\b""").findAll(raw).lastOrNull()?.value?.toInt()
        var title = raw
            .replace(Regex("""^\s*[A-Z]{2,3}\s*[-|:]\s*"""), "")
            .replace(Regex("""\[[^]]*]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|x26[45]|1080p|2160p|720p|HDR(?:10)?|DV|MULTI|DUAL)\b""", RegexOption.IGNORE_CASE), " ")
        if (year != null) title = title.replace(year.toString(), " ")
        title = title.replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
        return title to year
    }
}
