package de.novatv.data.repo

import android.util.Log
import de.novatv.BuildConfig
import de.novatv.data.AppSettings
import de.novatv.data.parse.MovieHash
import de.novatv.data.parse.OpenSubtitlesParsing
import de.novatv.data.parse.SubtitleCandidate
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
 * Lädt Untertitel von OpenSubtitles.com, wenn ein Film keine eingebettete Spur in der
 * gewünschten Sprache hat.
 *
 * Ablauf: 1. Zwischenspeicher. 2. Suche per Datei-Hash (nur je 64 KiB vom Anfang und Ende
 * werden per HTTP-Range geladen). Ein Treffer passt exakt und zeitgenau zu dieser Datei.
 * 3. Rückfall: Suche nach bereinigtem Titel und Jahr. Ergebnisse werden dauerhaft gespeichert,
 * damit jeder Film höchstens einmal das Download-Kontingent belastet.
 */
class SubtitleRepository(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenFor: String? = null

    data class Result(val file: File, val hashMatch: Boolean)

    fun cached(key: String, language: String): File? =
        cacheFile(key, language).takeIf { it.isFile && it.length() > 0 }

    suspend fun find(key: String, videoUrl: String, title: String, language: String, settings: AppSettings): Result? =
        withContext(Dispatchers.IO) {
            cached(key, language)?.let { return@withContext Result(it, hashMatch = true) }
            val apiKey = settings.openSubtitlesKey.trim().ifEmpty { return@withContext null }

            val hash = runCatching { movieHash(videoUrl, settings.userAgent) }
                .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
                .getOrNull()

            var candidates: List<SubtitleCandidate> = emptyList()
            if (hash != null) {
                candidates = search(apiKey, mapOf("moviehash" to hash, "languages" to language)).filter { it.hashMatch }
            }
            if (candidates.isEmpty()) {
                val (cleanTitle, year) = OpenSubtitlesParsing.cleanTitle(title)
                if (cleanTitle.length < 2) return@withContext null
                val params = buildMap {
                    put("query", cleanTitle)
                    put("languages", language)
                    if (year != null) put("year", year.toString())
                }
                candidates = search(apiKey, params)
            }
            val best = candidates.firstOrNull() ?: return@withContext null

            val link = downloadLink(apiKey, best.fileId, settings) ?: return@withContext null
            val target = cacheFile(key, language)
            val temp = File(target.parentFile, target.name + ".part")
            http.newCall(Request.Builder().url(link).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (!temp.renameTo(target)) throw IOException("Untertitel konnten nicht gespeichert werden")
            Result(target, best.hashMatch)
        }

    /** Größe per Range-Anfrage ermitteln, dann Anfang und Ende laden. Ohne Range-Support kein Hash. */
    private fun movieHash(url: String, userAgent: String): String? {
        val (size, head) = range(url, userAgent, 0, MovieHash.CHUNK - 1) ?: return null
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
        .header("User-Agent", "NovaTV v${BuildConfig.VERSION_NAME}")
        .header("Accept", "application/json")

    private fun cacheFile(key: String, language: String): File {
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120)
        return File(dir, "$safe.$language.srt")
    }

    private companion object {
        const val TAG = "Subtitles"
        const val API = "https://api.opensubtitles.com/api/v1"
        val JSON = "application/json".toMediaType()
    }
}
