package de.ayutv.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Neue Versionen von GitHub holen und das Setup starten (Einstellungen und Playlists bleiben). */
object Updater {
    private const val LATEST_URL = "https://api.github.com/repos/hanoi96international-gif/Nova-tv/releases/latest"
    private const val SETUP = "DnA-TV-Windows-Setup.exe"

    /** Version dieser App (wie die Fire-TV-App). */
    val version: String = runCatching {
        Updater::class.java.getResourceAsStream("/version.txt")!!.use { it.readBytes().decodeToString().trim() }
    }.getOrDefault("?")

    data class Release(val version: String, val setupUrl: String?)

    suspend fun latest(http: OkHttpClient): Release = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(LATEST_URL).header("Accept", "application/vnd.github+json").header("User-Agent", "DnA-TV").build()
        val text = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw IOException("GitHub antwortet mit HTTP ${r.code}")
            r.body.string()
        }
        val root = Json.parseToJsonElement(text).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.removePrefix("v") ?: throw IOException("Keine Version gefunden")
        val setup = root["assets"]?.jsonArray?.firstNotNullOfOrNull { a ->
            val o = a.jsonObject
            o["browser_download_url"]?.jsonPrimitive?.contentOrNull?.takeIf { o["name"]?.jsonPrimitive?.contentOrNull == SETUP }
        }
        Release(tag, setup)
    }

    /** [candidate] ist neuer als [current] (Zahlen einzeln verglichen: 0.40.10 > 0.40.9). */
    fun newer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Setup herunterladen ([progress] in Prozent) und starten. Danach muss sich die App beenden. */
    suspend fun download(http: OkHttpClient, release: Release, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val url = release.setupUrl ?: throw IOException("Im Release fehlt das Windows-Setup")
        val target = File(System.getProperty("java.io.tmpdir"), "DnA-TV-Setup-${release.version}.exe")
        http.newCall(Request.Builder().url(url).header("User-Agent", "DnA-TV").build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download: HTTP ${r.code}")
            val total = r.body.contentLength()
            val part = File(target.path + ".part")
            r.body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != last) { last = percent; progress(percent) }
                        }
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Setup konnte nicht gespeichert werden")
        }
        target
    }

    fun launch(setup: File) {
        ProcessBuilder(setup.absolutePath).start()
    }
}
