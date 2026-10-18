package de.ayutv.data.repo

import android.util.Log
import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import de.ayutv.data.Languages
import de.ayutv.data.parse.MovieHash
import de.ayutv.data.parse.OpenSubtitlesParsing
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SubtitleCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Besorgt Untertitel in jeder Sprache.
 *
 * Ablauf: 1. Zwischenspeicher. 2. OpenSubtitles in der Zielsprache: erst per Datei-Hash (nur je
 * 64 KiB vom Anfang und Ende werden per HTTP-Range geladen, ein Treffer passt zeitgenau),
 * dann nach bereinigtem Titel und Jahr. Von Menschen übersetzte Untertitel sind immer besser
 * als Maschinenübersetzung. 3. Gibt es keine: Untertitel in einer anderen Sprache (bevorzugt
 * Englisch) laden und mit DeepL/LibreTranslate übersetzen. Alles wird dauerhaft gespeichert.
 */
class SubtitleRepository(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenFor: String? = null
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    enum class Source { CACHE, HASH, TITLE, TRANSLATED }

    data class Result(val file: File, val source: Source, val translator: String? = null)

    fun cached(key: String, language: String): File? =
        cacheFile(key, language).takeIf { it.isFile && it.length() > 0 }

    suspend fun find(
        key: String,
        videoUrl: String,
        title: String,
        language: String,
        settings: AppSettings,
        progress: (String) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        cached(key, language)?.let { return@withContext Result(it, Source.CACHE) }

        if (settings.onlineSubtitlesEnabled) {
            progress("Suche Untertitel (${Languages.label(language)}) …")
            download(key, videoUrl, title, language, settings)?.let { return@withContext it }
        }

        val translator = Translators.from(http, settings) ?: return@withContext null
        if (!translator.supports(language)) throw IOException("${translator.name} unterstützt ${Languages.label(language)} nicht")

        // Quelle: vorhandene Datei einer anderen Sprache, sonst online Englisch, sonst irgendeine Sprache.
        var source: File? = cachedAny(key, except = language)
        if (source == null && settings.onlineSubtitlesEnabled) {
            progress("Lade Vorlage für die Übersetzung …")
            if (language != "en") source = download(key, videoUrl, title, "en", settings)?.file
            if (source == null) source = download(key, videoUrl, title, null, settings)?.file
        }
        if (source == null) return@withContext null

        val cues = Srt.parse(source.readText())
        if (cues.isEmpty()) return@withContext null
        val texts = cues.map { Srt.plainText(it.text) }
        val translated = ArrayList<String>(texts.size)
        texts.chunked(TRANSLATE_BATCH).forEachIndexed { index, chunk ->
            progress("Übersetze mit ${translator.name} … ${(index * TRANSLATE_BATCH * 100) / texts.size} %")
            translated += translator.translate(chunk, language)
        }
        val target = cacheFile(key, language)
        writeAtomically(target, Srt.write(cues.mapIndexed { i, cue -> cue.copy(text = translated.getOrElse(i) { cue.text }) }))
        Result(target, Source.TRANSLATED, translator.name)
    }

    /** Lädt von OpenSubtitles in [language] (null = beliebige Sprache) und speichert unter der echten Sprache. */
    private suspend fun download(key: String, videoUrl: String, title: String, language: String?, settings: AppSettings): Result? {
        val apiKey = settings.openSubtitlesKey.trim().ifEmpty { return null }
        // ConcurrentHashMap erlaubt kein null: "" steht für "kein Hash möglich".
        val hash = hashCache.getOrPut(videoUrl) {
            runCatching { movieHash(videoUrl, settings.userAgent) }
                .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
                .getOrNull() ?: ""
        }.ifEmpty { null }

        var source = Source.HASH
        var candidates: List<SubtitleCandidate> = emptyList()
        if (hash != null) {
            candidates = search(apiKey, buildMap {
                put("moviehash", hash)
                if (language != null) put("languages", language)
            }).filter { it.hashMatch }
        }
        if (candidates.isEmpty()) {
            source = Source.TITLE
            val (cleanTitle, year) = OpenSubtitlesParsing.cleanTitle(title)
            if (cleanTitle.length < 2) return null
            candidates = search(apiKey, buildMap {
                put("query", cleanTitle)
                if (language != null) put("languages", language)
                if (year != null) put("year", year.toString())
            })
        }
        val best = candidates.firstOrNull() ?: return null
        val link = downloadLink(apiKey, best.fileId, settings) ?: return null
        val actualLanguage = language ?: best.language.ifEmpty { "xx" }
        val target = cacheFile(key, actualLanguage)
        http.newCall(Request.Builder().url(link).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
            writeAtomically(target, response.body.string())
        }
        return Result(target, source)
    }

    /** Alle gespeicherten Untertitel eines Films, nach Sprache. Werden beim Start gleich mitgegeben. */
    fun cachedAll(key: String): Map<String, File> {
        val prefix = safeName(key) + "."
        return File(cacheDir, "subtitles").listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name.endsWith(".srt") && it.length() > 0 }
            .associateBy { it.name.removePrefix(prefix).removeSuffix(".srt") }
    }

    private fun cachedAny(key: String, except: String): File? {
        val prefix = safeName(key) + "."
        return File(cacheDir, "subtitles").listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".srt") && !it.name.endsWith(".$except.srt") && it.length() > 0 }
            ?.sortedByDescending { it.name.endsWith(".en.srt") }
            ?.firstOrNull()
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File(target.parentFile, target.name + ".part")
        temp.writeText(content)
        if (!temp.renameTo(target)) throw IOException("Untertitel konnten nicht gespeichert werden")
    }

    /** Größe per Range-Anfrage ermitteln, dann Anfang und Ende laden. Ohne Range-Support kein Hash. */
    private fun movieHash(url: String, userAgent: String): String? {
        val (size, head) = range(url, userAgent, 0L, MovieHash.CHUNK - 1L) ?: return null
        if (size < MovieHash.CHUNK * 2) return null
        val (_, tail) = range(url, userAgent, size - MovieHash.CHUNK, size - 1) ?: return null
        return MovieHash.compute(size, head, tail)
    }

    private fun range(url: String, userAgent: String, from: Long, to: Long): Pair<Long, ByteArray>? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$from-$to")
            .build()
        http.newCall(request).execute().use { response ->
            // Ignoriert der Server Range (200), würde der ganze Film geladen werden, also abbrechen.
            if (response.code != 206) return null
            val total = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: return null
            val bytes = response.body.source().use { it.readByteArray() }
            if (bytes.size < (to - from + 1)) return null
            return total to bytes
        }
    }

    private fun search(apiKey: String, params: Map<String, String>): List<SubtitleCandidate> {
        // OpenSubtitles erwartet alphabetisch sortierte Parameter in Kleinbuchstaben (sonst Redirect).
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply {
            params.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v.lowercase()) }
        }.build()
        http.newCall(apiRequest(apiKey).url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                Log.w(TAG, "Suche fehlgeschlagen: HTTP ${response.code} ${OpenSubtitlesParsing.message(body)}")
                return emptyList()
            }
            return OpenSubtitlesParsing.searchResults(body)
        }
    }

    private suspend fun downloadLink(apiKey: String, fileId: Long, settings: AppSettings): String? {
        val bearer = loginToken(apiKey, settings)
        val payload = buildJsonObject { put("file_id", fileId) }.toString()
        val request = apiRequest(apiKey)
            .url("$API/download")
            .post(payload.toRequestBody(JSON))
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw IOException(OpenSubtitlesParsing.message(body) ?: "Download nicht möglich (HTTP ${response.code})")
            }
            return OpenSubtitlesParsing.downloadLink(body)
        }
    }

    /** Mit Konto gibt es ein höheres Tageskontingent. Ohne Konto wird anonym geladen. */
    private suspend fun loginToken(apiKey: String, settings: AppSettings): String? = tokenLock.withLock {
        val user = settings.openSubtitlesUser.trim()
        if (user.isEmpty() || settings.openSubtitlesPassword.isEmpty()) return null
        if (tokenFor == user && token != null) return token
        val payload = buildJsonObject {
            put("username", user)
            put("password", settings.openSubtitlesPassword)
        }.toString()
        val request = apiRequest(apiKey).url("$API/login").post(payload.toRequestBody(JSON)).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                OpenSubtitlesParsing.loginToken(response.body.string()).takeIf { response.isSuccessful }
            }
        }.getOrNull().also {
            token = it
            tokenFor = user
        }
    }

    private fun apiRequest(apiKey: String) = Request.Builder()
        .header("Api-Key", apiKey)
        .header("User-Agent", "AyuTV v${BuildConfig.VERSION_NAME}")
        .header("Accept", "application/json")

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    private fun cacheFile(key: String, language: String): File {
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        return File(dir, "${safeName(key)}.$language.srt")
    }

    private companion object {
        const val TAG = "Subtitles"
        const val API = "https://api.opensubtitles.com/api/v1"
        const val TRANSLATE_BATCH = 200
        val JSON = "application/json".toMediaType()
    }
}
