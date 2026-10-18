package de.ayutv.data.repo

import de.ayutv.data.AppSettings
import de.ayutv.data.Languages
import de.ayutv.data.TranslationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** Maschinelle Übersetzung von Untertitelzeilen. Die Reihenfolge der Ergebnisse entspricht der Eingabe. */
interface Translator {
    val name: String
    fun supports(language: String): Boolean
    suspend fun translate(texts: List<String>, target: String): List<String>

    /** [source]: Sprache der Vorlage, falls bekannt (z. B. von der Untertitelspur). */
    suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = translate(texts, target)
}

object Translators {
    /** Eine gemeinsame Instanz: geladene Sprachpakete und Übersetzer bleiben im Speicher. */
    val onDevice by lazy { OnDeviceTranslator() }

    fun from(http: OkHttpClient, settings: AppSettings): Translator? = when (settings.translationProvider) {
        TranslationProvider.NONE -> null
        TranslationProvider.ON_DEVICE -> onDevice
        TranslationProvider.DEEPL -> settings.deeplKey.trim().takeIf { it.isNotEmpty() }?.let { DeepLTranslator(http, it) }
        TranslationProvider.LIBRE -> settings.libreUrl.trim().takeIf { it.isNotEmpty() }
            ?.let { LibreTranslator(http, it, settings.libreKey.trim()) }
    }
}

private val json = Json { ignoreUnknownKeys = true; isLenient = true }
private val JSON_TYPE = "application/json".toMediaType()

/** DeepL: beste Qualität. Kostenloser Zugang (500.000 Zeichen/Monat) erkennt man an der Endung ":fx". */
class DeepLTranslator(private val http: OkHttpClient, private val key: String) : Translator {
    override val name = "DeepL"
    private val endpoint = if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"

    override fun supports(language: String) = Languages.of(language)?.deepl != null

    override suspend fun translate(texts: List<String>, target: String): List<String> = withContext(Dispatchers.IO) {
        val targetCode = Languages.of(target)?.deepl ?: throw IOException("DeepL unterstützt ${Languages.label(target)} nicht")
        texts.chunked(50).flatMap { chunk ->
            val body = buildJsonObject {
                putJsonArray("text") { chunk.forEach { add(it) } }
                put("target_lang", targetCode)
                put("preserve_formatting", true)
                put("split_sentences", "nonewlines")
            }.toString()
            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "DeepL-Auth-Key $key")
                .post(body.toRequestBody(JSON_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                when (response.code) {
                    200 -> Unit
                    403 -> throw IOException("DeepL: API-Key ungültig")
                    456 -> throw IOException("DeepL: Monatskontingent aufgebraucht")
                    else -> throw IOException("DeepL: HTTP ${response.code}")
                }
                val translations = (json.parseToJsonElement(text) as JsonObject)["translations"] as JsonArray
                translations.map { ((it as JsonObject)["text"] as JsonPrimitive).contentOrNull.orEmpty() }
            }
        }
    }
}

/** LibreTranslate: Open Source, auf eigenem Server oder bei einem Anbieter. Unterstützt viele Sprachen. */
class LibreTranslator(private val http: OkHttpClient, baseUrl: String, private val key: String) : Translator {
    override val name = "LibreTranslate"
    private val endpoint = baseUrl.trimEnd('/').let { if (it.endsWith("/translate")) it else "$it/translate" }

    override fun supports(language: String) = true

    override suspend fun translate(texts: List<String>, target: String): List<String> = withContext(Dispatchers.IO) {
        texts.chunked(40).flatMap { chunk ->
            val body = buildJsonObject {
                putJsonArray("q") { chunk.forEach { add(it) } }
                put("source", "auto")
                put("target", target)
                put("format", "text")
                if (key.isNotEmpty()) put("api_key", key)
            }.toString()
            val request = Request.Builder().url(endpoint).post(body.toRequestBody(JSON_TYPE)).build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw IOException("LibreTranslate: HTTP ${response.code}")
                when (val translated = (json.parseToJsonElement(text) as JsonObject)["translatedText"]) {
                    is JsonArray -> translated.map { (it as JsonPrimitive).contentOrNull.orEmpty() }
                    is JsonPrimitive -> listOf(translated.contentOrNull.orEmpty())
                    else -> throw IOException("LibreTranslate: unerwartete Antwort")
                }
            }
        }
    }
}
