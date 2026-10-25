package de.ayutv.data.parse

import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.ProgrammeFlags

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

    private val SPORT = Regex(
        """(?i)fu(ß|ss)ball|sport|bundesliga|champions league|europa league|conference league|dfb-pokal|handball|basketball|""" +
            """eishockey|tennis|formel 1|formel1|motorsport|motogp|darts|boxen|golf|nfl|nba|nhl|radsport|biathlon|ski |volleyball|""" +
            """snooker|wrestling|ufc|mma|leichtathletik|\bBL\b|konferenz"""
    )
    private val LIVE_WORD = Regex("""\b(Live|LIVE|live)\b""")
    private val NOT_LIVE = Regex(
        """(?i)highlights?|zusammenfassung|alle spiele|alle tore|rückblick|re-?live|classics|kompakt|countdown|magazin|""" +
            """spieltags-sprint|wiederholung|aufzeichnung|reportage|doku|analyse|talk|stammtisch|pur\b|sportschau club"""
    )

    /** Sportsendung (nach Kategorie oder Titel). */
    fun isSport(title: String, subtitle: String?, category: String?): Boolean =
        SPORT.containsMatchIn(category.orEmpty()) || SPORT.containsMatchIn(title) || SPORT.containsMatchIn(subtitle.orEmpty())

    /** Paarung „A – B“ im Untertitel oder Titel. */
    fun pairing(title: String, subtitle: String?): String? =
        listOfNotNull(subtitle, title).map { it.substringBefore(',').substringAfter(": ").trim() }
            .firstOrNull { PAIRING.containsMatchIn(it) && !it.contains("Spieltag") }

    /**
     * Läuft live: ausdrücklich so markiert, „Live“ im Titel, oder ein Spiel mit Paarung, das keine
     * Wiederholung, Zusammenfassung oder Vorberichterstattung ist.
     */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int): Boolean {
        if (flags and ProgrammeFlags.REPEAT != 0) return false
        if (flags and ProgrammeFlags.LIVE != 0) return true
        val text = title + " " + subtitle.orEmpty()
        if (NOT_LIVE.containsMatchIn(text) || NOT_LIVE.containsMatchIn(category.orEmpty())) return false
        if (LIVE_WORD.containsMatchIn(text)) return true
        return isSport(title, subtitle, category) && pairing(title, subtitle) != null
    }

    /** Für die Sport-Übersicht: ein Spiel oder eine Live-Übertragung (keine Magazine, Talks, Wiederholungen). */
    fun isSportEvent(title: String, subtitle: String?, category: String?, flags: Int): Boolean =
        isSport(title, subtitle, category) && isLive(title, subtitle, category, flags)

    /** Beschreibung ohne angehängtes „(Senderinfo).“ der Programmzeitschriften. */
    fun description(text: String?): String? = text?.replace(NOISE, "")?.trim()?.takeIf { it.isNotEmpty() }
}

fun ProgrammeEntity.headline(): String = ProgrammeText.headline(title, subtitle)
fun ProgrammeEntity.isLive(): Boolean = ProgrammeText.isLive(title, subtitle, category, flags)
fun ProgrammeEntity.isSportEvent(): Boolean = ProgrammeText.isSportEvent(title, subtitle, category, flags)

/** Titel mit „LIVE“-Kennzeichen davor, wenn die Sendung live läuft. */
fun ProgrammeEntity.headlineWithBadge(): String = (if (isLive()) "🔴 LIVE  " else "") + headline()
