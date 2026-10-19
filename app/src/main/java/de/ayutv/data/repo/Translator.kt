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

    /**
     * Übersetzer und Texterkennung im eigenen Prozess (für den Player): Speichermangel oder ein
     * Absturz im Modell beendet nur die Übersetzung, nie den Film.
     */
    lateinit var remote: de.ayutv.data.translate.RemoteTranslator

    /** Übersetzer gemäß Einstellung, oder null, wenn die Übersetzung ausgeschaltet ist. */
    fun from(settings: AppSettings): Translator? = when (settings.translationProvider) {
        TranslationProvider.ON_DEVICE -> remote
        TranslationProvider.NONE -> null
    }
}
