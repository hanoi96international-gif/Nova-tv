package de.ayutv.data

/** Untertitelsprachen. [code] ist ISO 639-1 (OpenSubtitles, Media3, Übersetzer). */
data class Language(val code: String, val label: String)

object Languages {
    val all: List<Language> = listOf(
        Language("de", "Deutsch"),
        Language("en", "Englisch"),
        Language("fr", "Französisch"),
        Language("es", "Spanisch"),
        Language("it", "Italienisch"),
        Language("pt", "Portugiesisch"),
        Language("nl", "Niederländisch"),
        Language("pl", "Polnisch"),
        Language("tr", "Türkisch"),
        Language("ru", "Russisch"),
        Language("uk", "Ukrainisch"),
        Language("ar", "Arabisch"),
        Language("el", "Griechisch"),
        Language("cs", "Tschechisch"),
        Language("sk", "Slowakisch"),
        Language("sl", "Slowenisch"),
        Language("hu", "Ungarisch"),
        Language("ro", "Rumänisch"),
        Language("bg", "Bulgarisch"),
        Language("hr", "Kroatisch"),
        Language("sr", "Serbisch"),
        Language("bs", "Bosnisch"),
        Language("sq", "Albanisch"),
        Language("sv", "Schwedisch"),
        Language("da", "Dänisch"),
        Language("no", "Norwegisch"),
        Language("fi", "Finnisch"),
        Language("et", "Estnisch"),
        Language("lv", "Lettisch"),
        Language("lt", "Litauisch"),
        Language("he", "Hebräisch"),
        Language("fa", "Persisch"),
        Language("ku", "Kurdisch"),
        Language("hi", "Hindi"),
        Language("ur", "Urdu"),
        Language("th", "Thailändisch"),
        Language("vi", "Vietnamesisch"),
        Language("id", "Indonesisch"),
        Language("ja", "Japanisch"),
        Language("ko", "Koreanisch"),
        Language("zh", "Chinesisch"),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String?): Language? = code?.let { byCode[it.lowercase().take(2)] }

    fun label(code: String?): String = of(code)?.label ?: code.orEmpty()
}
