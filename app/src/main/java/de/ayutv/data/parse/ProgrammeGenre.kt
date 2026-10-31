package de.ayutv.data.parse

/**
 * Art einer Sendung für die Farben im TV-Guide: aus der Kategorie des EPG, sonst aus Titel und Sender. Lieber
 * „Sonstiges“ als falsch geraten.
 */
enum class ProgrammeGenre(val label: String, val argb: Long) {
    SPORT("Sport", 0xFF2EA043),
    FILM("Film", 0xFF8957E5),
    SERIES("Serie", 0xFF3B82F6),
    NEWS("Nachrichten", 0xFFE3A008),
    KIDS("Kinder", 0xFFEC4899),
    DOCU("Doku", 0xFF14B8A6),
    SHOW("Show", 0xFFF97316),
    OTHER("Sonstiges", 0x00000000),
    ;

    companion object {
        private val SPORT_WORDS = Regex("""(?i)\bsport|fußball|fussball|football|bundesliga|champions league|tennis|formel ?1|motorsport|handball|basketball|eishockey|boxen|golf|radsport|ski\b|biathlon|darts|nfl|nba|nhl|ufc""")
        private val FILM_WORDS = Regex("""(?i)spielfilm|\bfilm\b|movie|kino|thriller|komödie|drama|western|horror|science.?fiction|action""")
        private val SERIES_WORDS = Regex("""(?i)\bserie|series|sitcom|soap|krimiserie|staffel|folge\b|episode""")
        private val NEWS_WORDS = Regex("""(?i)nachrichten|news|tagesschau|heute journal|\bheute\b|wetter|politik|aktuell|journal""")
        private val KIDS_WORDS = Regex("""(?i)kinder|kids|zeichentrick|animation|cartoon|jugend|kika|nick|disney|toggo|junior""")
        private val DOCU_WORDS = Regex("""(?i)doku|dokumentation|documentary|reportage|natur|wissen|geschichte|history""")
        private val SHOW_WORDS = Regex("""(?i)\bshow|unterhaltung|entertainment|quiz|talk|musik|music|comedy|reality""")

        /** Art aus [category] (EPG), sonst aus [title] und [channel] (z. B. Sportsender, Kindersender). */
        fun of(title: String, category: String?, channel: String = ""): ProgrammeGenre {
            val c = category.orEmpty()
            if (c.isNotBlank()) {
                // Reihenfolge: genauere Arten zuerst („Kinderfilm“ ist Kinder, „Sportdoku“ ist Sport).
                when {
                    KIDS_WORDS.containsMatchIn(c) -> return KIDS
                    SPORT_WORDS.containsMatchIn(c) -> return SPORT
                    NEWS_WORDS.containsMatchIn(c) -> return NEWS
                    SERIES_WORDS.containsMatchIn(c) -> return SERIES
                    FILM_WORDS.containsMatchIn(c) -> return FILM
                    DOCU_WORDS.containsMatchIn(c) -> return DOCU
                    SHOW_WORDS.containsMatchIn(c) -> return SHOW
                }
            }
            return when {
                SPORT_WORDS.containsMatchIn(channel) -> SPORT
                KIDS_WORDS.containsMatchIn(channel) -> KIDS
                NEWS_WORDS.containsMatchIn(title) || Regex("""(?i)\bnews\b|n-tv|\bntv\b|welt\b""").containsMatchIn(channel) -> NEWS
                else -> OTHER
            }
        }
    }
}
