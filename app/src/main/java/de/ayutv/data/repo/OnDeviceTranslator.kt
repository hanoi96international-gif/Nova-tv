package de.ayutv.data.repo

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Übersetzung direkt auf dem Gerät mit Googles ML Kit: kostenlos, ohne Konto und ohne Schlüssel.
 * Pro Sprache wird einmalig ein Sprachpaket (ca. 30 MB) geladen, danach läuft alles offline und
 * schnell genug, um Untertitel Zeile für Zeile live zu übersetzen.
 */
class OnDeviceTranslator : Translator {
    override val name = "Übersetzung auf dem Gerät"

    private val clients = HashMap<Pair<String, String>, com.google.mlkit.nl.translate.Translator>()
    private val ready = HashSet<Pair<String, String>>()

    /** Meldet "Sprachpaket wird geladen", damit der Nutzer weiß, warum es beim ersten Mal dauert. */
    @Volatile
    var onModelDownload: (String) -> Unit = {}
    private val lock = Mutex()
    private val languageId by lazy { LanguageIdentification.getClient() }

    override fun supports(language: String) = TranslateLanguage.fromLanguageTag(language) != null

    override suspend fun translate(texts: List<String>, target: String): List<String> = translate(texts, target, null)

    override suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = withContext(Dispatchers.Default) {
        if (texts.isEmpty()) return@withContext emptyList()
        val to = TranslateLanguage.fromLanguageTag(target) ?: throw IOException("Sprache $target wird nicht unterstützt")
        val from = source?.let(TranslateLanguage::fromLanguageTag) ?: detect(texts) ?: TranslateLanguage.ENGLISH
        if (from == to) return@withContext texts
        val client = client(from, to)
        texts.map { text -> if (text.isBlank()) text else client.translate(text).await() }
    }

    /** Sprache der Vorlage erkennen, falls die Spur keine (oder "und") angibt. */
    private suspend fun detect(texts: List<String>): String? {
        val sample = texts.asSequence().filter { it.length > 3 }.take(20).joinToString(" ").take(1000)
        if (sample.isBlank()) return null
        val code = runCatching { languageId.identifyLanguage(sample).await() }.getOrNull()
        return code?.takeIf { it != "und" }?.let(TranslateLanguage::fromLanguageTag)
    }

    private suspend fun client(from: String, to: String) = lock.withLock {
        val pair = from to to
        val client = clients.getOrPut(pair) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
        }
        if (pair !in ready) {
            val manager = RemoteModelManager.getInstance()
            val missing = listOf(from, to).filter { lang ->
                lang != TranslateLanguage.ENGLISH &&
                    !manager.isModelDownloaded(TranslateRemoteModel.Builder(lang).build()).await()
            }
            if (missing.isNotEmpty()) onModelDownload(missing.joinToString(", "))
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            ready += pair
        }
        client
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
