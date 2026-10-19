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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    @Volatile
    private var blockedUntil = 0L
    private val hashCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    enum class Source { CACHE, HASH, TITLE, TRANSLATED }

    data class Result(val file: File, val source: Source, val translator: String? = null, val language: String? = null)

    /** Gemerkte zeitliche Lage geladener Untertitel: automatisch erkannt ([ratio], [offsetMs]) plus von Hand ([manualMs]). */
    data class SavedTiming(val ratio: Double, val offsetMs: Long, val manualMs: Long)

    fun timing(key: String, language: String): SavedTiming? = runCatching {
        val parts = timingFile(key, language).takeIf { it.isFile }?.readText()?.trim()?.split(' ') ?: return null
        SavedTiming(parts[0].toDouble(), parts[1].toLong(), parts[2].toLong())
    }.getOrNull()

    fun saveTiming(key: String, language: String, timing: SavedTiming) {
        runCatching { timingFile(key, language).writeText(String.format(Locale.ROOT, "%.6f %d %d", timing.ratio, timing.offsetMs, timing.manualMs)) }
    }

    /**
     * Datei-Hash vor dem Start des Films berechnen (zwei kurze Anfragen nacheinander, solange noch
     * keine Wiedergabe-Verbindung offen ist). Während der Wiedergabe wird nie zusätzlich verbunden.
     */
    suspend fun prepareHash(videoUrl: String, userAgent: String) = withContext(Dispatchers.IO) {
        if (hashCache.containsKey(videoUrl)) return@withContext
        val quick = http.newBuilder().callTimeout(1500, TimeUnit.MILLISECONDS).build()
        hashCache[videoUrl] = runCatching { movieHash(quick, videoUrl, userAgent) }
            .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
            .getOrNull() ?: ""
    }

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
        // Vor dem Start berechnet (prepareHash): Treffer passen zeitgenau zu genau dieser Datei.
        val hash: String? = hashCache[videoUrl]?.ifEmpty { null } ?: if (HASH_DURING_PLAYBACK) {
            // ConcurrentHashMap erlaubt kein null: "" steht für "kein Hash möglich".
            hashCache.getOrPut(videoUrl) {
                runCatching { movieHash(http, videoUrl, settings.userAgent) }
                    .onFailure { Log.i(TAG, "Kein Datei-Hash möglich: ${it.message}") }
                    .getOrNull() ?: ""
            }.ifEmpty { null }
        } else {
            null
        }

        // Früher als falsch erkannte Dateien (passten nicht zum Film) nie wieder nehmen.
        val bad = language?.let { badFileIds(key, it) }.orEmpty()
        var source = Source.HASH
        var candidates: List<SubtitleCandidate> = emptyList()
        if (hash != null) {
            candidates = search(apiKey, buildMap {
                put("moviehash", hash)
                if (language != null) put("languages", language)
            }).filter { it.hashMatch && it.fileId !in bad }
        }
        if (candidates.isEmpty()) {
            source = Source.TITLE
            // Genau dieser Film statt Titelsuche: TMDB-Kennung und Originaltitel vom Xtream-Anbieter.
            // "Harry Potter und der Halbblutprinz" findet sonst irgendeinen Harry-Potter-Teil.
            val info = runCatching { xtreamInfo(videoUrl, settings.userAgent) }.getOrNull()
            info?.tmdbId?.let { tmdb ->
                candidates = search(apiKey, buildMap {
                    put("tmdb_id", tmdb)
                    if (language != null) put("languages", language)
                }).filter { it.fileId !in bad }
            }
            if (candidates.isEmpty()) {
                val (cleanTitle, year) = OpenSubtitlesParsing.cleanTitle(info?.originalTitle ?: title)
                if (cleanTitle.length < 2) return null
                candidates = search(apiKey, buildMap {
                    put("query", cleanTitle)
                    if (language != null) put("languages", language)
                    (year ?: info?.year)?.let { put("year", it.toString()) }
                }).filter { it.fileId !in bad }
            }
        }
        val best = candidates.firstOrNull() ?: return null
        val link = downloadLink(apiKey, best.fileId, settings) ?: return null
        val actualLanguage = language ?: best.language.ifEmpty { "xx" }
        val target = cacheFile(key, actualLanguage)
        http.newCall(Request.Builder().url(link).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Untertitel-Download: HTTP ${response.code}")
            writeAtomically(target, response.body.string())
        }
        runCatching { sourceFile(key, actualLanguage).writeText(best.fileId.toString()) }
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

    /**
     * Die gespeicherten Untertitel passen nicht zum Film (andere Fassung oder ganz anderer Film):
     * löschen und die Datei für diesen Film sperren, damit die nächste Suche einen anderen Treffer nimmt.
     */
    fun markWrong(key: String, language: String) {
        sourceFile(key, language).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let { fileId ->
            runCatching { badFile(key, language).appendText("$fileId\n") }
        }
        listOf(cacheFile(key, language), sourceFile(key, language), timingFile(key, language)).forEach { it.delete() }
    }

    private fun badFileIds(key: String, language: String): Set<Long> =
        badFile(key, language).takeIf { it.isFile }?.readLines()?.mapNotNull { it.trim().toLongOrNull() }?.toSet().orEmpty()

    /** TMDB-Kennung, Originaltitel und Jahr eines Xtream-Films (player_api get_vod_info). */
    private data class MovieInfo(val tmdbId: String?, val originalTitle: String?, val year: Int?)

    private fun xtreamInfo(videoUrl: String, userAgent: String): MovieInfo? {
        val match = XTREAM_MOVIE.find(videoUrl) ?: return null
        val (base, user, pass, id) = match.destructured
        val url = "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", user)
            .addQueryParameter("password", pass)
            .addQueryParameter("action", "get_vod_info")
            .addQueryParameter("vod_id", id)
            .build()
        val quick = http.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
        val body = quick.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body.string()
        }
        val info = (Json.parseToJsonElement(body) as? JsonObject)?.get("info") as? JsonObject ?: return null
        fun text(vararg names: String) = names.firstNotNullOfOrNull { name ->
            (info[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
        }
        val tmdb = text("tmdb_id", "tmdb")?.takeIf { it.all(Char::isDigit) }
        val year = text("releasedate", "release_date", "year")?.let { Regex("""(19|20)\d\d""").find(it)?.value?.toInt() }
        return MovieInfo(tmdb, text("o_name", "original_name", "name"), year).also { Log.i(TAG, "Film beim Anbieter: $it") }
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
    private fun movieHash(client: OkHttpClient, url: String, userAgent: String): String? {
        val (size, head) = range(client, url, userAgent, 0L, MovieHash.CHUNK - 1L) ?: return null
        if (size < MovieHash.CHUNK * 2) return null
        val (_, tail) = range(client, url, userAgent, size - MovieHash.CHUNK, size - 1) ?: return null
        return MovieHash.compute(size, head, tail)
    }

    private fun range(client: OkHttpClient, url: String, userAgent: String, from: Long, to: Long): Pair<Long, ByteArray>? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Range", "bytes=$from-$to")
            .build()
        client.newCall(request).execute().use { response ->
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
        // Nach „zu viele Anfragen“ (429) kurz Pause, statt das Limit weiter zu reizen.
        if (System.currentTimeMillis() < blockedUntil) return emptyList()
        val all = params + mapOf("ai_translated" to "include", "machine_translated" to "include")
        val url = "$API/subtitles".toHttpUrl().newBuilder().apply {
            all.toSortedMap().forEach { (k, v) -> addQueryParameter(k, v.lowercase()) }
        }.build()
        http.newCall(apiRequest(apiKey).url(url).get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300) ?: 10
                    blockedUntil = System.currentTimeMillis() + seconds * 1000
                }
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

    private fun sourceFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".src")

    private fun badFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".bad")

    private fun timingFile(key: String, language: String): File = File(cacheFile(key, language).path.removeSuffix(".srt") + ".sync")

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
        val XTREAM_MOVIE = Regex("""^(https?://[^/]+)/movie/([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""")
        val JSON = "application/json".toMediaType()
    }
}
