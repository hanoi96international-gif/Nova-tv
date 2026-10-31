package de.ayutv.desktop.player

import de.ayutv.data.parse.OpenSubtitlesParsing
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SubtitleCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Untertitel für Filme und Folgen: zuerst eine fertige Datei in der Wunschsprache von OpenSubtitles, sonst
 * (mit DeepL-Schlüssel) die englische Datei übersetzen. Ergebnisse bleiben im Datenordner, das zweite Mal geht
 * es ohne Download.
 */
class Subtitles(private val http: OkHttpClient, private val dir: File) {
    /** Gefundene Datei und woher sie stammt. */
    data class Found(val file: File, val source: String)

    suspend fun find(key: String, title: String, language: String, openSubtitlesKey: String, deeplKey: String, progress: (String) -> Unit = {}): Found =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val cached = File(dir, "${safe(key)}.$language.srt")
            if (cached.isFile && cached.length() > 0) return@withContext Found(cached, "gespeichert")
            val apiKey = openSubtitlesKey.trim()
            if (apiKey.isBlank()) throw IOException("Kein OpenSubtitles-Schlüssel. Bitte unter Einstellungen eintragen oder die Sicherung vom Fire TV übernehmen.")

            progress("Suche Untertitel bei OpenSubtitles …")
            val query = queryOf(title)
            search(apiKey, query, language).firstOrNull()?.let { best ->
                cached.writeText(download(apiKey, best.fileId))
                return@withContext Found(cached, "OpenSubtitles")
            }
            if (deeplKey.isBlank() || language == "en") throw IOException("Keine Untertitel in ${language.uppercase()} gefunden.")
            val english = search(apiKey, query, "en").firstOrNull() ?: throw IOException("Keine Untertitel gefunden.")
            progress("Übersetze englische Untertitel mit DeepL …")
            val cues = Srt.parse(download(apiKey, english.fileId))
            val translated = translate(deeplKey, cues.map { it.text }, language)
            cached.writeText(Srt.write(cues.mapIndexed { i, c -> c.copy(text = translated[i]) }))
            Found(cached, "DeepL")
        }

    private data class Query(val name: String, val year: Int?, val season: Int?, val episode: Int?)

    private fun queryOf(title: String): Query {
        OpenSubtitlesParsing.episodeOf(title)?.let { (name, season, episode) -> return Query(name, null, season, episode) }
        val (name, year) = OpenSubtitlesParsing.cleanTitle(title)
        return Query(name, year, null, null)
    }

    private fun search(apiKey: String, q: Query, language: String): List<SubtitleCandidate> {
        val params = sortedMapOf(
            "ai_translated" to "include",
            "languages" to language,
            "machine_translated" to "include",
            "query" to q.name,
        )
        q.year?.let { params["year"] = it.toString() }
        q.season?.let { params["season_number"] = it.toString() }
        q.episode?.let { params["episode_number"] = it.toString() }
        // OpenSubtitles erwartet alphabetisch sortierte Parameter in Kleinbuchstaben (sonst Umleitung).
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v.lowercase()) } }.build()
        http.newCall(api(apiKey).url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("OpenSubtitles: ${OpenSubtitlesParsing.message(body) ?: "HTTP ${response.code}"}")
            val found = OpenSubtitlesParsing.searchResults(body).filter { it.language.equals(language, ignoreCase = true) }
            val wanted = if (q.season != null) found.filter { it.season == null || it.season == q.season && it.episode == q.episode } else found
            return OpenSubtitlesParsing.byQuality(OpenSubtitlesParsing.bestFeature(wanted, q.year, wantMovie = q.season == null))
        }
    }

    private fun download(apiKey: String, fileId: Long): String {
        val payload = buildJsonObject { put("file_id", fileId) }.toString()
        val link = http.newCall(api(apiKey).url("$API/download").post(payload.toRequestBody(JSON)).build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw IOException("OpenSubtitles: ${OpenSubtitlesParsing.message(body) ?: "Download nicht möglich (HTTP ${response.code})"}")
            OpenSubtitlesParsing.downloadLink(body) ?: throw IOException("OpenSubtitles: keine Download-Adresse")
        }
        return http.newCall(Request.Builder().url(link).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
            response.body.string()
        }
    }

    /** Zeilen mit DeepL übersetzen (in Blöcken, damit eine Anfrage nicht zu groß wird). */
    private fun translate(key: String, texts: List<String>, language: String): List<String> {
        val base = if (key.trim().endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        val out = ArrayList<String>(texts.size)
        for (chunk in texts.chunked(DEEPL_BATCH)) {
            val payload = buildJsonObject {
                put("text", JsonArray(chunk.map { JsonPrimitive(it) }))
                put("target_lang", language.uppercase())
                put("source_lang", "EN")
                put("preserve_formatting", true)
            }.toString()
            val request = Request.Builder().url("$base/v2/translate").post(payload.toRequestBody(JSON))
                .header("Authorization", "DeepL-Auth-Key ${key.trim()}").build()
            http.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) throw IOException(
                    when (response.code) {
                        403 -> "DeepL lehnt den Schlüssel ab."
                        456 -> "DeepL: Monatskontingent aufgebraucht."
                        else -> "DeepL: HTTP ${response.code}"
                    },
                )
                val lines = Json.parseToJsonElement(body).jsonObject["translations"]?.jsonArray?.map { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }.orEmpty()
                if (lines.size != chunk.size) throw IOException("DeepL: unvollständige Antwort")
                out += lines
            }
        }
        return out
    }

    private fun api(apiKey: String) = Request.Builder()
        .header("Api-Key", apiKey)
        .header("User-Agent", "DnATV-Windows v1")
        .header("Accept", "application/json")

    private fun safe(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    companion object {
        private const val API = "https://api.opensubtitles.com/api/v1"
        private const val DEEPL_BATCH = 50
        private val JSON = "application/json".toMediaType()
    }
}
