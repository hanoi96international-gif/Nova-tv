package de.ayutv.data.translate

import ai.onnxruntime.OrtEnvironment
import android.os.Process
import android.util.Log
import de.ayutv.data.Languages
import de.ayutv.data.repo.Translator
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Übersetzung direkt auf dem Fire TV mit OPUS-MT (Universität Helsinki, Lizenz CC BY 4.0):
 * kein Konto, kein Schlüssel, keine Zeichengrenze, nach dem ersten Laden auch offline.
 * Pro Sprachrichtung wird einmalig ein Modell (ca. 110 MB) geladen. Fehlt eine direkte Richtung,
 * geht es über Englisch oder über das Mehrsprachen-Modell mit Sprachkennung (">>pol<<").
 */
class OnDeviceTranslator(baseDir: File, private val http: OkHttpClient) : Translator {
    override val name = "DnA-Übersetzer (auf dem Gerät)"

    /** Fortschritt beim einmaligen Laden eines Sprachmodells (für die Anzeige im Player). */
    @Volatile
    var onProgress: (String) -> Unit = {}

    private val dir = File(baseDir, "mt")
    private val env by lazy { OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } } }
    private val loaded = LinkedHashMap<String, OpusMtModel>()
    private val lock = Mutex()
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Etwas weniger Vorrang als die Wiedergabe: Ton und Bild gehen immer vor.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_LESS_FAVORABLE)
            runnable.run()
        }, "dna-translate")
    }.asCoroutineDispatcher()

    data class Hop(val pair: String, val token: String? = null)

    override fun supports(language: String) = route("en", language.take(2).lowercase()) != null

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = withContext(dispatcher) {
        val to = Languages.normalize(target) ?: target.take(2).lowercase()
        // Unbekannte Vorlage: Untertitel sind fast immer Englisch. MKV nennt Deutsch "ger".
        val from = Languages.normalize(source) ?: "en"
        val hops = route(from, to) ?: throw IOException("${Languages.label(from)} → ${Languages.label(to)} geht auf dem Gerät nicht")
        val models = hops.map { it to model(it.pair) }
        texts.map { text ->
            ensureActive()
            translateText(text, models)
        }
    }

    private fun translateText(text: String, models: List<Pair<Hop, OpusMtModel>>): String {
        val lines = text.replace(TAGS, "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || models.isEmpty()) return text
        // Dialog ("- Hallo.\n- Hi.") Zeile für Zeile, sonst den ganzen Satz über Zeilenumbrüche hinweg.
        val dialog = lines.size > 1 && lines.all { it.startsWith("-") || it.startsWith("–") }
        val segments = if (dialog) lines.map { it.trimStart('-', '–', ' ') } else listOf(lines.joinToString(" "))
        val translated = segments.map { segment ->
            if (segment.none(Char::isLetter)) {
                segment
            } else {
                models.fold(segment) { current, (hop, model) ->
                    val token = hop.token?.let { model.tokenizer.id(it) }
                    model.translate(current, token).ifBlank { current }
                }
            }
        }
        return if (dialog) translated.joinToString("\n") { "- $it" } else translated.single()
    }

    /** Lädt (einmalig herunter und) in den Speicher. Höchstens zwei Modelle gleichzeitig (Brücke über Englisch). */
    private suspend fun model(pair: String): OpusMtModel = lock.withLock {
        loaded.remove(pair)?.let { model ->
            loaded[pair] = model
            return@withLock model
        }
        val folder = File(dir, pair)
        if (!isInstalled(folder)) download(pair, folder)
        while (loaded.size >= 2) {
            val eldest = loaded.keys.first()
            loaded.remove(eldest)?.close()
        }
        onProgress("Starte Übersetzer ${describe(pair)} …")
        val model = try {
            OpusMtModel(folder, env, THREADS)
        } catch (e: Exception) {
            // Beschädigte Datei (z. B. Strom weg beim Laden): beim nächsten Mal neu herunterladen.
            folder.deleteRecursively()
            throw IOException("Übersetzungsmodell defekt, wird neu geladen: ${e.message}", e)
        }
        loaded[pair] = model
        model
    }

    private fun isInstalled(folder: File) = FILES.keys.all { File(folder, it).length() > 0 }

    private fun download(pair: String, folder: File) {
        if (dir.apply { mkdirs() }.usableSpace < MIN_FREE_BYTES) throw IOException("Zu wenig Speicher für das Übersetzungsmodell (ca. 120 MB nötig)")
        folder.mkdirs()
        val label = describe(pair)
        var done = 0L
        val total = TOTAL_BYTES_ESTIMATE
        for ((local, remote) in FILES) {
            val target = File(folder, local)
            if (target.length() > 0) continue
            val part = File(folder, "$local.part")
            // Nach einem WLAN-Abbruch dort weitermachen, wo der Download aufgehört hat.
            val have = part.length()
            val request = Request.Builder().url("$BASE/opus-mt-$pair/resolve/main/$remote")
                .apply { if (have > 0) header("Range", "bytes=$have-") }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Übersetzungsmodell nicht erreichbar (HTTP ${response.code})")
                val append = response.code == 206
                if (append) done += have
                // Erwartete Gesamtgröße, um abgebrochene oder verstümmelte Downloads zu erkennen.
                val expected = response.body.contentLength().takeIf { it > 0 }?.let { if (append) it + have else it }
                var lastPercent = -1
                response.body.byteStream().use { input ->
                    java.io.FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            done += n
                            val percent = (done * 100 / total).toInt().coerceAtMost(99)
                            if (percent != lastPercent && percent % 5 == 0) {
                                lastPercent = percent
                                onProgress("Lade Übersetzer $label: $percent % (einmalig ca. ${total / 1_000_000} MB)")
                            }
                        }
                    }
                }
                // Verbindung mittendrin abgerissen: Teil behalten (wird beim nächsten Mal fortgesetzt), aber nicht verwenden.
                if (expected != null && part.length() != expected) {
                    throw IOException("Download des Übersetzungsmodells unvollständig, bitte erneut versuchen")
                }
            }
            if (!part.renameTo(target)) throw IOException("Übersetzungsmodell konnte nicht gespeichert werden")
        }
        Log.i(TAG, "Modell $pair geladen: ${folder.walk().sumOf { it.length() } / 1_000_000} MB")
    }

    /** Belegter Speicher aller geladenen Sprachmodelle. */
    fun installedBytes(): Long = dir.walk().filter { it.isFile }.sumOf { it.length() }

    fun installedPairs(): List<String> = dir.listFiles().orEmpty().filter { isInstalled(it) }.map { it.name }.sorted()

    /** Auf dem Übersetzer-Thread: Modelle werden nie freigegeben, während gerade mit ihnen gerechnet wird. */
    suspend fun deleteAll() = withContext(dispatcher) {
        lock.withLock {
            loaded.values.forEach { it.close() }
            loaded.clear()
            dir.deleteRecursively()
        }
    }

    /** Speicher freigeben (Player geschlossen oder System knapp an Speicher). Modelle bleiben auf der Platte. */
    suspend fun release() = withContext(dispatcher) {
        lock.withLock {
            loaded.values.forEach { it.close() }
            loaded.clear()
        }
    }

    private fun describe(pair: String): String {
        val (a, b) = pair.split('-', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        fun name(code: String) = when (code) {
            "mul" -> "Mehrsprachig"
            "ROMANCE" -> "Romanisch"
            else -> Languages.label(code)
        }
        return "${name(a)} → ${name(b)}"
    }

    companion object {
        private const val TAG = "OnDeviceTranslator"
        private const val BASE = "https://huggingface.co/Xenova"
        private const val THREADS = 2
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
        private const val TOTAL_BYTES_ESTIMATE = 115_000_000L
        private val TAGS = Regex("""<[^>]+>|\{\\[^}]*\}""")

        /** Lokaler Dateiname → Datei im Modell-Repository (int8-quantisiert, Decoder mit KV-Cache). */
        private val FILES = linkedMapOf(
            OpusMtModel.CONFIG to "config.json",
            OpusMtModel.TOKENIZER to "tokenizer.json",
            OpusMtModel.ENCODER to "onnx/encoder_model_quantized.onnx",
            OpusMtModel.DECODER to "onnx/decoder_model_merged_quantized.onnx",
        )

        /** Direkte Sprachrichtungen (OPUS-MT als ONNX). */
        private val DIRECT = setOf(
            "af-en", "ar-en", "cs-en", "da-de", "da-en", "de-en", "de-es", "de-fr", "en-af", "en-ar", "en-cs", "en-da",
            "en-de", "en-es", "en-fi", "en-fr", "en-hi", "en-hu", "en-id", "en-it", "en-nl", "en-ro", "en-ru", "en-sv",
            "en-uk", "en-vi", "en-zh", "es-de", "es-en", "es-fr", "es-it", "es-ru", "et-en", "fi-de", "fi-en", "fr-de",
            "fr-en", "fr-es", "fr-ro", "fr-ru", "hi-en", "hu-en", "id-en", "it-en", "it-es", "it-fr", "ja-en", "ko-en",
            "nl-en", "nl-fr", "no-de", "pl-en", "ro-fr", "ru-en", "ru-es", "ru-fr", "ru-uk", "sv-en", "th-en", "tr-en",
            "uk-en", "uk-ru", "vi-en", "zh-en",
        )

        /** Romanische Zielsprachen im Modell en-ROMANCE. */
        private val ROMANCE = mapOf("pt" to ">>pt<<", "ca" to ">>ca<<", "gl" to ">>gl<<")

        /** Weitere Zielsprachen über das Mehrsprachen-Modell en-mul (Kennung nach ISO 639-3). */
        private val MUL = mapOf(
            "pl" to "pol", "tr" to "tur", "el" to "ell", "sl" to "slv", "bg" to "bul", "hr" to "hrv", "sr" to "srp_Latn",
            "bs" to "bos_Latn", "sq" to "sqi", "no" to "nob", "et" to "est", "lv" to "lav", "lt" to "lit", "he" to "heb",
            "fa" to "pes", "ku" to "kur_Latn", "ur" to "urd", "th" to "tha", "ja" to "jpn",
        ).mapValues { ">>${it.value}<<" }

        private val ROMANCE_SOURCES = setOf("pt", "ca", "gl")

        fun route(from: String, to: String): List<Hop>? {
            if (from == to) return emptyList()
            if ("$from-$to" in DIRECT) return listOf(Hop("$from-$to"))
            if (from != "en" && to != "en") {
                val first = route(from, "en") ?: return null
                val second = route("en", to) ?: return null
                return first + second
            }
            if (from == "en") {
                ROMANCE[to]?.let { return listOf(Hop("en-ROMANCE", it)) }
                MUL[to]?.let { return listOf(Hop("en-mul", it)) }
                return null
            }
            // Ziel Englisch ohne direktes Modell
            return listOf(Hop(if (from in ROMANCE_SOURCES) "ROMANCE-en" else "mul-en"))
        }
    }
}
