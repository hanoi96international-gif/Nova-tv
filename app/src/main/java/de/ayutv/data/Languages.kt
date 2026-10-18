package de.ayutv.data

/**
 * Untertitelsprachen. [code] ist ISO 639-1 (OpenSubtitles, Media3, LibreTranslate),
 * [deepl] der Zielcode für DeepL (null = von DeepL nicht unterstützt, dann nur LibreTranslate/OpenSubtitles).
 */
data class Language(val code: String, val label: String, val deepl: String?)

object Languages {
    val all: List<Language> = listOf(
        Language("de", "Deutsch", "DE"),
        Language("en", "Englisch", "EN-GB"),
        Language("fr", "Französisch", "FR"),
        Language("es", "Spanisch", "ES"),
        Language("it", "Italienisch", "IT"),
        Language("pt", "Portugiesisch", "PT-PT"),
        Language("nl", "Niederländisch", "NL"),
        Language("pl", "Polnisch", "PL"),
        Language("tr", "Türkisch", "TR"),
        Language("ru", "Russisch", "RU"),
        Language("uk", "Ukrainisch", "UK"),
        Language("ar", "Arabisch", "AR"),
        Language("el", "Griechisch", "EL"),
        Language("cs", "Tschechisch", "CS"),
        Language("sk", "Slowakisch", "SK"),
        Language("sl", "Slowenisch", "SL"),
        Language("hu", "Ungarisch", "HU"),
        Language("ro", "Rumänisch", "RO"),
        Language("bg", "Bulgarisch", "BG"),
        Language("hr", "Kroatisch", null),
        Language("sr", "Serbisch", null),
        Language("bs", "Bosnisch", null),
        Language("sq", "Albanisch", null),
        Language("sv", "Schwedisch", "SV"),
        Language("da", "Dänisch", "DA"),
        Language("no", "Norwegisch", "NB"),
        Language("fi", "Finnisch", "FI"),
        Language("et", "Estnisch", "ET"),
        Language("lv", "Lettisch", "LV"),
        Language("lt", "Litauisch", "LT"),
        Language("he", "Hebräisch", "HE"),
        Language("fa", "Persisch", null),
        Language("ku", "Kurdisch", null),
        Language("hi", "Hindi", null),
        Language("ur", "Urdu", null),
        Language("th", "Thailändisch", "TH"),
        Language("vi", "Vietnamesisch", "VI"),
        Language("id", "Indonesisch", "ID"),
        Language("ja", "Japanisch", "JA"),
        Language("ko", "Koreanisch", "KO"),
        Language("zh", "Chinesisch", "ZH-HANS"),
    )

    private val byCode = all.associateBy { it.code }

    fun of(code: String?): Language? = code?.let { byCode[it.lowercase().take(2)] }

    fun label(code: String?): String = of(code)?.label ?: code.orEmpty()
}
