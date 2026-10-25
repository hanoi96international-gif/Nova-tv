package de.ayutv.data.parse

/**
 * Gesprochene Suche: Zahlwörter werden zu Ziffern („DAZN zwei“ → „DAZN 2“). Passt ein Sender oder
 * Titel genau, startet er direkt, statt nur in der Trefferliste zu stehen.
 */
object SpokenQuery {
    private val NUMBERS = mapOf(
        "eins" to "1", "zwei" to "2", "drei" to "3", "vier" to "4", "fünf" to "5", "funf" to "5",
        "sechs" to "6", "sieben" to "7", "acht" to "8", "neun" to "9", "zehn" to "10",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    )
    private val SPACES = Regex("""\s+""")
    private val NOT_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val YEAR = Regex("""\((?:19|20)\d\d\)|\b(?:19|20)\d\d\b""")
    private val PREFIX = Regex("""^(?:\s*[^:|]{1,12}?\s*[:|]+\s*)+""")

    fun normalize(spoken: String): String =
        spoken.trim().split(SPACES).joinToString(" ") { word -> NUMBERS[word.lowercase()] ?: word }

    /** Sender, dessen Namenskern genau dem Gesprochenen entspricht (in der besten Fassung), sonst null. */
    fun <T> channel(spoken: String, candidates: List<T>, name: (T) -> String): T? {
        val key = ChannelVariants.key(normalize(spoken)) ?: return null
        return candidates.filter { ChannelVariants.key(name(it)) == key }.minByOrNull { ChannelVariants.rank(name(it)) }
    }

    /** Film oder Serie mit genau diesem Titel (ohne Jahr, Länder-Vorsatz, Satzzeichen), sonst null. */
    fun <T> title(spoken: String, candidates: List<T>, name: (T) -> String): T? {
        val wanted = simplify(normalize(spoken)).takeIf { it.length >= 2 } ?: return null
        return candidates.firstOrNull { simplify(name(it)) == wanted }
    }

    private fun simplify(title: String): String =
        title.replace(PREFIX, "").replace(YEAR, " ").lowercase().replace(NOT_ALNUM, "")
}
