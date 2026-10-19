package de.ayutv.data.repo

import android.util.Log
import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import de.ayutv.data.Languages
import de.ayutv.data.parse.MovieHash
import de.ayutv.data.parse.OpenSubtitlesParsing
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
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
 * Englisch) als Vorlage laden; übersetzt wird im Player auf dem Gerät. Alles wird dauerhaft gespeichert.
 */
class SubtitleRepository(
    private val http: OkHttpClient,
    private val cacheDir: File,
) {
    private val tokenLock = Mutex()
    private var token: String? = null
    private var tokenFor: String? = null
    private var tokenAt = 0L
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    enum class Source { CACHE, HASH, TITLE, TRANSLATED }

    data class Result(val file: File, val source: Source, val translator: String? = null, val language: String? = null)

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

        null
    }

    /** Lädt von OpenSubtitles in [language] (null = beliebige Sprache) und speichert unter der echten Sprache. */
    private suspend fun download(key: String, videoUrl: String, title: String, language: String?, settings: AppSettings): Result? {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return null }
        // Kein Datei-Hash während der Wiedergabe: Er bräuchte zwei zusätzliche Verbindungen zum
        // IPTV-Anbieter. Bei Zugängen mit nur einer erlaubten Verbindung kann das den laufenden Film
        // abbrechen. Gesucht wird deshalb nach Titel und Jahr.
        val hash: String? = if (HASH_DURING_PLAYBACK) {
            // ConcurrentHashMap erlaubt kein null: "" steht für "kein Hash möglich".
            hashCache.getOrPut(videoUrl) {
                runCatching { movieHash(videoUrl, settings.userAgent) }
                    .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
                    .getOrNull() ?: ""
            }.ifEmpty { null }
        } else {
            null
        }

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
        return Result(target, source, language = actualLanguage)
    }

    /**
     * Vorlage zum Übersetzen nach [target]: bereits gespeicherte Untertitel einer anderen Sprache,
     * sonst online Englisch, sonst irgendeine Sprache. Übersetzt wird danach im Player, Zeile für
     * Zeile vor der aktuellen Stelle, damit man nicht auf den ganzen Film warten muss.
     */
    suspend fun findSource(
        key: String,
        videoUrl: String,
        title: String,
        target: String,
        settings: AppSettings,
        progress: (String) -> Unit = {},
    ): Result? = withContext(Dispatchers.IO) {
        cachedAny(key, except = target)?.let { file ->
            val language = file.name.removeSuffix(".srt").substringAfterLast('.')
            return@withContext Result(file, Source.CACHE, language = language.takeIf { it.length in 2..3 && it != "xx" })
        }
        if (!settings.onlineSubtitlesEnabled) return@withContext null
        progress("Lade Vorlage für die Übersetzung …")
        if (target != "en") download(key, videoUrl, title, "en", settings)?.let { return@withContext it }
        download(key, videoUrl, title, null, settings)
    }

    /** Anzahl der Treffer bei OpenSubtitles für einen Titel (Selbsttest, lädt nichts herunter). */
    suspend fun countResults(title: String, language: String, settings: AppSettings): Int = withContext(Dispatchers.IO) {
        val apiKey = settings.openSubtitlesApiKey.ifEmpty { return@withContext 0 }
        val (cleanTitle, year) = OpenSubtitlesParsing.cleanTitle(title)
        search(apiKey, buildMap {
            put("query", cleanTitle)
            put("languages", language)
            if (year != null) put("year", year.toString())
        }).size
    }

    /** Fertig übersetzte Untertitel speichern: Beim nächsten Mal stehen sie sofort bereit. */
    fun saveTranslation(key: String, language: String, cues: List<SrtCue>): File {
        val target = cacheFile(key, language)
        writeAtomically(target, Srt.write(cues))
        return target
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
            // Nur genau den angefragten Bereich lesen: Ein Server, der trotzdem den ganzen Film schickt,
            // darf den Speicher nicht füllen.
            val length = to - from + 1
            val bytes = response.body.source().use { source ->
                if (!source.request(length)) return null
                source.readByteArray(length)
            }
            return total to bytes
        }
    }

    private fun search(apiKey: String, params: Map<String, String>): List<SubtitleCandidate> {
        // OpenSubtitles erwartet alphabetisch sortierte Parameter in Kleinbuchstaben (sonst Redirect).
        // Auch KI-/maschinell übersetzte Untertitel zulassen: Damit gibt es fast jeden Film in fast
        // jeder Sprache. Von Menschen erstellte werden beim Sortieren trotzdem bevorzugt.
        val all = params + mapOf("ai_translated" to "include", "machine_translated" to "include")
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply {
            all.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v.lowercase()) }
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
        val account = user + "\n" + settings.openSubtitlesPassword
        val now = System.currentTimeMillis()
        // Token gilt rund 24 Stunden. Nach einer gescheiterten Anmeldung nicht bei jedem Aufruf erneut
        // versuchen (OpenSubtitles erlaubt nur wenige Anfragen pro Sekunde).
        if (tokenFor == account && now - tokenAt < TOKEN_TTL) {
            if (token != null) return token
            if (now - tokenAt < LOGIN_RETRY) return null
        }
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
            tokenFor = account
            tokenAt = now
        }
    }

    private fun apiRequest(apiKey: String) = Request.Builder()
        .header("Api-Key", apiKey)
        .header("User-Agent", "DnATV v${BuildConfig.VERSION_NAME}")
        .header("Accept", "application/json")

    private fun safeName(key: String) = key.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(120)

    private fun cacheFile(key: String, language: String): File {
        val dir = File(cacheDir, "subtitles").apply { mkdirs() }
        return File(dir, "${safeName(key)}.$language.srt")
    }

    private companion object {
        const val TAG = "Subtitles"
        const val HASH_DURING_PLAYBACK = false
        const val TOKEN_TTL = 23 * 3_600_000L
        const val LOGIN_RETRY = 10 * 60_000L
        const val API = "https://api.opensubtitles.com/api/v1"
        val JSON = "application/json".toMediaType()
    }
}
