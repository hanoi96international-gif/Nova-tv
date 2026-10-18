package de.ayutv.data.setup

import de.ayutv.BuildConfig
import de.ayutv.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Prüft die eingetragenen Zugänge sofort, damit man nicht erst im Film merkt, dass ein Schlüssel
 * falsch kopiert wurde. Liefert lesbare Zeilen für Fernseher und Handy-Seite.
 */
object KeyCheck {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Line(val ok: Boolean, val text: String)

    suspend fun run(http: OkHttpClient, settings: AppSettings): List<Line> = withContext(Dispatchers.IO) {
        buildList {
            add(openSubtitles(http, settings))
            add(deepl(http, settings))
        }
    }

    private fun openSubtitles(http: OkHttpClient, settings: AppSettings): Line {
        val key = settings.openSubtitlesApiKey
        if (key.isBlank()) return Line(false, "OpenSubtitles: kein API-Key eingetragen")
        return runCatching {
            val base = "https://api.opensubtitles.com/api/v1"
            val user = settings.openSubtitlesUser.trim()
            if (user.isNotEmpty()) {
                val body = buildJsonObject {
                    put("username", user)
                    put("password", settings.openSubtitlesPassword)
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("$base/login").post(body)
                    .header("Api-Key", key).header("User-Agent", AGENT).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when (response.code) {
                        200 -> {
                            val allowed = (json.parseToJsonElement(text) as? JsonObject)?.get("user")
                                ?.let { it as? JsonObject }?.get("allowed_downloads")?.let { (it as? JsonPrimitive)?.longOrNull }
                            Line(true, "OpenSubtitles: angemeldet als $user" + (allowed?.let { " ($it Downloads pro Tag)" } ?: ""))
                        }
                        401 -> Line(false, "OpenSubtitles: Benutzername oder Passwort falsch")
                        403 -> Line(false, "OpenSubtitles: API-Key ungültig")
                        else -> Line(false, "OpenSubtitles: Anmeldung fehlgeschlagen (HTTP ${response.code})")
                    }
                }
            } else {
                val request = Request.Builder().url("$base/infos/formats")
                    .header("Api-Key", key).header("User-Agent", AGENT).header("Accept", "application/json").build()
                http.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> Line(true, "OpenSubtitles: API-Key gültig (ohne Anmeldung nur wenige Downloads pro Tag)")
                        401, 403 -> Line(false, "OpenSubtitles: API-Key ungültig")
                        else -> Line(false, "OpenSubtitles: nicht erreichbar (HTTP ${response.code})")
                    }
                }
            }
        }.getOrElse { Line(false, "OpenSubtitles: keine Verbindung (${it.message})") }
    }

    private fun deepl(http: OkHttpClient, settings: AppSettings): Line {
        val key = settings.deeplKey.trim()
        if (key.isEmpty()) return Line(false, "DeepL: kein API-Key eingetragen (Übersetzung nur über OpenSubtitles)")
        val host = if (key.endsWith(":fx")) "https://api-free.deepl.com" else "https://api.deepl.com"
        return runCatching {
            val request = Request.Builder().url("$host/v2/usage").header("Authorization", "DeepL-Auth-Key $key").build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when (response.code) {
                    200 -> {
                        val usage = json.parseToJsonElement(text) as? JsonObject
                        val used = (usage?.get("character_count") as? JsonPrimitive)?.longOrNull
                        val limit = (usage?.get("character_limit") as? JsonPrimitive)?.longOrNull
                        val rest = if (used != null && limit != null) " – noch ${"%,d".format(limit - used).replace(',', '.')} Zeichen frei" else ""
                        Line(true, "DeepL: API-Key gültig$rest")
                    }
                    403 -> Line(false, "DeepL: API-Key ungültig")
                    456 -> Line(false, "DeepL: Monatskontingent aufgebraucht")
                    else -> Line(false, "DeepL: HTTP ${response.code}")
                }
            }
        }.getOrElse { Line(false, "DeepL: keine Verbindung (${it.message})") }
    }

    private val AGENT = "AyuTV v${BuildConfig.VERSION_NAME}"
}
