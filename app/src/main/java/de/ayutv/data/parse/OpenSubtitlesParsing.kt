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
    /** Von OpenSubtitles per KI oder Maschine übersetzt (statt von Menschen erstellt). */
    val translated: Boolean = false,
    /** TMDB-Kennung des Films, zu dem die Datei gehört (Suche nach tmdb_id liefert manchmal Fremdes). */
    val tmdbId: Long? = null,
    /** Film/Folge, zu der die Datei gehört, und deren Jahr und Art ("Movie", "Episode"). */
    val featureId: Long? = null,
    val year: Int? = null,
    val featureType: String? = null,
    /** Platz in der Antwort von OpenSubtitles (deren Relevanz, vor unserer Sortierung nach Qualität). */
    val rank: Int = 0,
    /** Bildrate, für die die Datei erstellt wurde (falls angegeben). */
    val fps: Double? = null,
    /** Bei Serienfolgen: Staffel und Folge laut OpenSubtitles. */
    val season: Int? = null,
    val episode: Int? = null,
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
        return data.mapIndexedNotNull { index, item ->
            val attributes = (item as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapIndexedNotNull null
            val feature = attributes["feature_details"] as? JsonObject
            val file = (attributes["files"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapIndexedNotNull null
            val fileId = file.primitive("file_id")?.longOrNull ?: return@mapIndexedNotNull null
            SubtitleCandidate(
                fileId = fileId,
                language = attributes.primitive("language")?.contentOrNull.orEmpty(),
                release = attributes.primitive("release")?.contentOrNull ?: file.primitive("file_name")?.contentOrNull.orEmpty(),
                hashMatch = attributes.primitive("moviehash_match")?.booleanOrNull ?: false,
                downloads = attributes.primitive("download_count")?.longOrNull ?: 0L,
                hearingImpaired = attributes.primitive("hearing_impaired")?.booleanOrNull ?: false,
                translated = attributes.primitive("ai_translated")?.booleanOrNull == true ||
                    attributes.primitive("machine_translated")?.booleanOrNull == true,
                tmdbId = feature?.primitive("tmdb_id")?.longOrNull,
                featureId = feature?.primitive("feature_id")?.longOrNull,
                year = feature?.primitive("year")?.longOrNull?.toInt(),
                featureType = feature?.primitive("feature_type")?.contentOrNull,
                rank = index,
                fps = attributes.primitive("fps")?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 1 },
                season = feature?.primitive("season_number")?.longOrNull?.toInt(),
                episode = feature?.primitive("episode_number")?.longOrNull?.toInt(),
            )
        }.sortedWith(QUALITY)
    }

    /**
     * Die Titelsuche liefert Untertitel zu mehreren Filmen, die nur ungefähr passen. Erst den richtigen
     * Film wählen (Reihenfolge von OpenSubtitles = Relevanz; Jahr muss passen, Filme vor Serienfolgen),
     * dann nur dessen Dateien zurückgeben, nach Qualität sortiert. Passt kein Film zum Jahr: nichts.
     */
    fun bestFeature(candidates: List<SubtitleCandidate>, year: Int?, wantMovie: Boolean = true): List<SubtitleCandidate> {
        if (candidates.isEmpty()) return emptyList()
        fun key(c: SubtitleCandidate): Any = c.featureId ?: c.tmdbId ?: "?${c.rank}"
        val features = candidates.groupBy(::key)
        val suitable = features.values.filter { files ->
            val first = files.minBy { it.rank }
            year == null || first.year == null || kotlin.math.abs(first.year - year) <= 1
        }
        val chosen = suitable.minWithOrNull(
            compareBy<List<SubtitleCandidate>> { files -> if (wantMovie && files.first().featureType.equals("Episode", true)) 1 else 0 }
                .thenBy { files -> files.minOf { it.rank } }
        ) ?: return emptyList()
        return chosen.sortedWith(QUALITY)
    }

    private val QUALITY = compareByDescending<SubtitleCandidate> { it.hashMatch }
        .thenBy { it.translated }
        .thenBy { it.hearingImpaired }
        .thenByDescending { it.downloads }

    /**
     * Bei gleicher Güte die Datei mit der Bildrate des Films nach vorn: Sie passt ohne Umrechnung
     * (25 statt 23,976 fps läuft sonst nach einer Stunde 2,5 Minuten auseinander). Hash-Treffer bleiben vorn.
     */
    fun preferFrameRate(candidates: List<SubtitleCandidate>, frameRate: Float?): List<SubtitleCandidate> {
        if (frameRate == null || frameRate <= 1f) return candidates
        fun fits(c: SubtitleCandidate) = when {
            c.fps == null -> 1
            kotlin.math.abs(c.fps - frameRate) < 0.05 -> 0
            else -> 2
        }
        return candidates.sortedWith(compareByDescending<SubtitleCandidate> { it.hashMatch }.thenBy { it.translated }.thenBy(::fits))
    }

    /** „Serie – S1 E2“, „Serie S01E02“, „Serie 1x02“: Name, Staffel, Folge. Sonst null. */
    fun episodeOf(title: String): Triple<String, Int, Int>? {
        val m = EPISODE.find(title) ?: return null
        val season = (m.groupValues[1].ifEmpty { m.groupValues[3] }).toIntOrNull() ?: return null
        val episode = (m.groupValues[2].ifEmpty { m.groupValues[4] }).toIntOrNull() ?: return null
        val name = title.substring(0, m.range.first).trim().trimEnd('-', '–', ':', '|').trim()
        return if (name.length >= 2) Triple(cleanTitle(name).first, season, episode) else null
    }
    private val EPISODE = Regex("""(?i)\bS(\d{1,2})\s*E(\d{1,3})\b|\b(\d{1,2})x(\d{1,3})\b""")

    /** Ende der letzten Einblendung einer SRT-Datei (ms), um zu prüfen, ob sie zur Filmlänge passt. */
    fun lastCueEndMs(srt: String): Long? {
        val m = SRT_TIME.findAll(srt).lastOrNull() ?: return null
        val g = m.groupValues
        return ((g[1].toLong() * 60 + g[2].toLong()) * 60 + g[3].toLong()) * 1000 + g[4].padEnd(3, '0').take(3).toLong()
    }
    private val SRT_TIME = Regex("""-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /**
     * Passt eine Datei, die bis [lastCueMs] reicht, zu einem Film von [durationMs]? Untertitel enden vor dem
     * Abspann, aber nicht nach der Hälfte; länger als der Film (plus Bildraten-Unterschied) geht nicht.
     */
    fun plausibleLength(lastCueMs: Long, durationMs: Long): Boolean {
        if (durationMs < 10 * 60_000L) return true
        return lastCueMs <= durationMs * 1.06 + 60_000 && lastCueMs >= durationMs * 0.55
    }

    fun downloadLink(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("link")?.contentOrNull

    fun loginToken(body: String): String? =
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("token")?.contentOrNull

    fun message(body: String): String? = runCatching {
        (json.parseToJsonElement(body) as? JsonObject)?.primitive("message")?.contentOrNull
    }.getOrNull()

    private fun JsonObject.primitive(key: String): JsonPrimitive? = this[key] as? JsonPrimitive

    private val PREFIX = Regex("""^\s*(?:\[[^\]]*\]|\|[^|]*\||(?:4K|UHD|FHD|HD|SD|[A-Z]{2,3})(?:[-_ ](?:4K|UHD|FHD|HD|SD|[A-Z]{2,3}))*\s*[-|:])\s*""")

    /**
     * Titel aus IPTV-Listen sind oft "DE - Film Name (2019) [4K]". Für die Suche nach Name
     * werden Präfixe, Qualitätsangaben und Klammern entfernt, das Jahr wird separat erkannt.
     */
    fun cleanTitle(raw: String): Pair<String, Int?> {
        val year = Regex("""\b(19[3-9]\d|20[0-4]\d)\b""").findAll(raw).lastOrNull()?.value?.toInt()
        // Vorsätze der Anbieter, auch mehrere: "4K-DE - ", "DE: ", "[DE] ", "|DE| ", "EN | "
        var title = raw
        while (true) {
            val stripped = title.replace(PREFIX, "")
            if (stripped == title) break
            title = stripped
        }
        title = title
            .replace(Regex("""\[[^\]]*\]|\([^)]*\)"""), " ")
            .replace(Regex("""\b(4K|UHD|FHD|HD|SD|HEVC|H\.?26[45]|x26[45]|1080p|2160p|720p|HDR(?:10)?|DV|MULTI|DUAL)\b""", RegexOption.IGNORE_CASE), " ")
        if (year != null) title = title.replace(year.toString(), " ")
        title = title.replace(Regex("""[._]"""), " ").replace(Regex("""\s+"""), " ").trim(' ', '-', '|', ':')
        return title to year
    }
}
