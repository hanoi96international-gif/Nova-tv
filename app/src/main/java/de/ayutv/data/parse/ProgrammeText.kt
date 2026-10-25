package de.ayutv.data.parse

import de.ayutv.data.db.ProgrammeEntity

/**
 * Titel einer Sendung für die Anzeige. Sport-EPGs nennen im Titel nur den Wettbewerb („Fußball: Bundesliga“),
 * die Paarung steht im Untertitel („Borussia Dortmund – Eintracht Frankfurt, Topspiel, 7. Spieltag“). Dann
 * kommt die Paarung nach vorn, damit sie auch in einzeiligen Listen zu sehen ist; sonst „Titel – Untertitel“.
 */
object ProgrammeText {
    private val PAIRING = Regex("""\S\s+[–—-]\s+\S""")
    private val NOISE = Regex("""\s*\(Senderinfo\)\.?\s*$""")

    fun headline(title: String, subtitle: String?): String {
        val main = title.trim()
        val sub = subtitle?.trim().orEmpty()
        if (sub.isEmpty() || main.contains(sub, ignoreCase = true)) return main
        if (main.isEmpty()) return sub
        if (sub.contains(main, ignoreCase = true)) return sub
        val first = sub.substringBefore(',').trim()
        if (PAIRING.containsMatchIn(first)) {
            val rest = sub.substringAfter(',', "").trim()
            return "$first ($main" + (if (rest.isNotEmpty()) ", $rest" else "") + ")"
        }
        return "$main – $sub"
    }

    /** Beschreibung ohne angehängtes „(Senderinfo).“ der Programmzeitschriften. */
    fun description(text: String?): String? = text?.replace(NOISE, "")?.trim()?.takeIf { it.isNotEmpty() }
}

fun ProgrammeEntity.headline(): String = ProgrammeText.headline(title, subtitle)
