package de.ayutv.data.repo

import de.ayutv.data.AppSettings
import de.ayutv.data.TranslationProvider
import de.ayutv.data.translate.OnDeviceTranslator

/** Maschinelle Übersetzung von Untertitelzeilen. Die Reihenfolge der Ergebnisse entspricht der Eingabe. */
interface Translator {
    val name: String
    fun supports(language: String): Boolean
    suspend fun translate(texts: List<String>, target: String): List<String>

    /** [source]: Sprache der Vorlage, falls bekannt (z. B. von der Untertitelspur). */
    suspend fun translate(texts: List<String>, target: String, source: String?): List<String> = translate(texts, target)
}

object Translators {
    /** Übersetzer auf dem Gerät; einmal pro App (geladene Modelle bleiben im Speicher). */
    lateinit var onDevice: OnDeviceTranslator

    /** Texterkennung für Bild-Untertitel (PGS/DVB), damit auch diese übersetzt werden können. */
    lateinit var ocr: de.ayutv.data.translate.SubtitleOcr

    /** Übersetzer gemäß Einstellung, oder null, wenn die Übersetzung ausgeschaltet ist. */
    fun from(settings: AppSettings): Translator? = when (settings.translationProvider) {
        TranslationProvider.ON_DEVICE -> onDevice
        TranslationProvider.NONE -> null
    }
}
