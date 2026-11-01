package de.ayutv.data.parse

/**
 * Titel einer Sendung für die Anzeige. Sport-EPGs nennen im Titel nur den Wettbewerb („Fußball: Bundesliga“),
 * die Paarung steht im Untertitel („Borussia Dortmund – Eintracht Frankfurt, Topspiel, 7. Spieltag“). Dann
 * kommt die Paarung nach vorn, damit sie auch in einzeiligen Listen zu sehen ist; sonst „Titel – Untertitel“.
 */
object ProgrammeText {
    // Gleiche Werte wie de.ayutv.data.db.ProgrammeFlags (ohne Datenbank-Abhängigkeit, auch für die Windows-App).
    private const val REPEAT = 1
    private const val LIVE = 2

    /** „A – B“, „A - B“, „A vs. B“, „A v B“, „A gegen B“. */
    private val PAIRING = Regex("""\S\s+(?:[–—-]|vs\.?|v\.?|gegen)\s+\S""")
    /** Sender, auf denen eine Paarung oder „Live“ für ein Spiel genügt, auch ohne Sport-Kategorie im TV-Guide. */
    private val SPORT_CHANNEL = Regex("""(?i)sport|dazn|servus|magenta|eleven|laola|bein|espn|\bran\b|motorvision|arena|bundesliga|\bliga\b|premier league|champions league|fu(ß|ss)ball|football""")
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
            """snooker|wrestling|ufc|mma|leichtathletik|\bBL\b|konferenz|football|soccer|premier league|serie a|la ?liga|ligue 1|""" +
            """eredivisie|pokal|league cup|\bcup\b"""
    )
    private val LIVE_WORD = Regex("""\b(Live|LIVE|live)\b|ᴸᶦᵛᵉ|ᴸᴵⱽᴱ|🔴""")
    /**
     * Übertragungen ohne Paarung, die trotzdem live sind: Konferenz (DAZN, Sky: alle Spiele im Wechsel), Multiview.
     * Zusammenfassungen davon („Highlights der Konferenz“) fängt [NOT_LIVE] vorher ab.
     */
    private val LIVE_FORMAT = Regex("""(?i)(?<!presse)konferenz|multi-?view|einzelspiel(e)? live|matchday live""")
    private val NOT_LIVE = Regex(
        """(?i)highlights?|zusammenfassung|alle spiele|alle tore|rückblick|re-?live|classics|kompakt|countdown|magazin|""" +
            """spieltags-sprint|wiederholung|aufzeichnung|reportage|doku|analyse|talk|stammtisch|pur\b|sportschau club|es folgt|sendepause"""
    )

    /** Sportsendung (nach Kategorie oder Titel). */
    fun isSport(title: String, subtitle: String?, category: String?): Boolean =
        SPORT.containsMatchIn(category.orEmpty()) || SPORT.containsMatchIn(title) || SPORT.containsMatchIn(subtitle.orEmpty())

    /** Paarung „A – B“ im Untertitel oder Titel. */
    fun pairing(title: String, subtitle: String?): String? =
        listOfNotNull(subtitle, title).map { it.substringBefore(',').substringAfter(": ").trim() }
            .firstOrNull { PAIRING.containsMatchIn(it) && !it.contains("Spieltag") }

    /**
     * Paarung am Anfang der Beschreibung. Sky nennt mehrere Spiele gleichzeitig nur „Live: DFB-Pokal“; welches
     * Spiel es ist, steht im ersten Satz („Hertha BSC – 1. FC Köln. Die Berliner …“).
     */
    fun descriptionPairing(description: String?): String? {
        val first = description?.trim()?.split(SENTENCE_END, limit = 2)?.firstOrNull()?.substringAfter(": ")?.trim() ?: return null
        val pairing = first.substringBefore(',').trim()
        if (pairing.length > 80 || !PAIRING.containsMatchIn(pairing) || pairing.contains("Spieltag")) return null
        // Nur kurze Vereinsnamen, kein Satz wie „Der FC tritt heute gegen Dortmund an“
        val sides = Teams.ofPairing(pairing)
        return pairing.takeIf { sides.size == 2 && sides.all { it.split(' ').size <= 4 } }
    }
    /** Satzende, aber nicht nach „1.“, „FC.“, „vs.“, „St.“ */
    private val SENTENCE_END = Regex("""(?<!\bvs|\bv|\bSt|[0-9A-ZÄÖÜ])\.\s|\n""")

    /** Paarung aus Titel, Untertitel oder (zuletzt) dem Anfang der Beschreibung. */
    fun pairing(title: String, subtitle: String?, description: String?): String? =
        pairing(title, subtitle) ?: descriptionPairing(description)

    /** Für Sport-Listen: Steht die Paarung nur in der Beschreibung, kommt sie nach vorn („A – B (Live: DFB-Pokal)“). */
    fun sportHeadline(title: String, subtitle: String?, description: String?): String {
        val main = headline(title, subtitle)
        if (pairing(title, subtitle) != null) return main
        val fromDescription = descriptionPairing(description) ?: return main
        return "$fromDescription ($main)"
    }

    /**
     * Läuft live: ausdrücklich so markiert, „Live“ im Titel, oder ein Spiel mit Paarung, das keine
     * Wiederholung, Zusammenfassung oder Vorberichterstattung ist.
     */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean = false): Boolean {
        if (flags and REPEAT != 0) return false
        if (flags and LIVE != 0) return true
        val text = title + " " + subtitle.orEmpty()
        if (NOT_LIVE.containsMatchIn(text) || NOT_LIVE.containsMatchIn(category.orEmpty())) return false
        if (LIVE_WORD.containsMatchIn(text)) return true
        if ((sportChannel || isSport(title, subtitle, category)) && LIVE_FORMAT.containsMatchIn(text)) return true
        return (sportChannel || isSport(title, subtitle, category)) && pairing(title, subtitle) != null
    }

    /** Wie [isLive], die Paarung darf auch am Anfang der Beschreibung stehen. */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean, description: String?): Boolean =
        isLive(title, subtitle, category, flags, sportChannel) ||
            (flags and REPEAT == 0 && !NOT_LIVE.containsMatchIn(title + " " + subtitle.orEmpty()) &&
                (sportChannel || isSport(title, subtitle, category)) && descriptionPairing(description) != null)

    /** Sportsender nach Namen (Sky Sport, DAZN, Eurosport, Servus TV, MagentaSport …). */
    fun isSportChannel(name: String): Boolean = SPORT_CHANNEL.containsMatchIn(name)

    /**
     * Für die Sport-Übersicht: ein Spiel oder eine Live-Übertragung (keine Magazine, Talks, Wiederholungen).
     * Auf einem Sportsender ([sportChannel]) zählt jede Paarung oder „Live“, auch wenn der TV-Guide keine
     * Sport-Kategorie nennt.
     */
    fun isSportEvent(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean = false): Boolean =
        (sportChannel || isSport(title, subtitle, category)) && isLive(title, subtitle, category, flags, sportChannel)

    /**
     * Woran man dieselbe Übertragung auf verschiedenen Sendern erkennt. Mit Paarung zählt nur die Paarung
     * (Schreibweise egal). Ohne Paarung zählen Titel und Beschreibung; ohne brauchbare Beschreibung bleibt jeder
     * Sender für sich, denn Sky nennt parallele Spiele oft nur „Live: DFB-Pokal“.
     */
    fun sportKey(title: String, subtitle: String?, description: String?, channelEpgId: String): String {
        pairing(title, subtitle, description)?.let { return "p:" + Teams.ofPairing(it).ifEmpty { listOf(it) }.joinToString("|") { side -> simple(side) } }
        val desc = description(description)?.takeUnless { PLACEHOLDER.containsMatchIn(it) }?.let { simple(it).take(120) }
        return "h:" + simple(headline(title, subtitle)) + "|" + (desc?.ifEmpty { null } ?: "@$channelEpgId")
    }
    /**
     * Schlüssel, unter dem Sport-Sendungen zu einem Eintrag zusammengefasst werden. Mit Paarung („Hannover – Dresden“)
     * über alle Sender: dasselbe Spiel. Ohne Paarung („Live: 2. BL“) nur Fassungen desselben Senders ([channelGroup],
     * z. B. „Sky Sport Bundesliga 2“ in HD und 4K): Sonst wurden verschiedene Spiele mit gleichem Allgemeintext auf
     * verschiedenen Sendern zu einem Eintrag, und Spiele fehlten in der Liste.
     */
    fun sportGroupKey(title: String, subtitle: String?, description: String?, channelEpgId: String, channelGroup: String): String {
        val key = sportKey(title, subtitle, description, channelEpgId)
        return if (key.startsWith("p:")) key else "$key|$channelGroup"
    }

    private val PLACEHOLDER = Regex("""(?i)folg(en|t) in k(ü|u)rze|keine (weiteren )?informationen""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private fun simple(text: String) = text.lowercase().replace(NON_WORD, "")

    /** Beschreibung ohne angehängtes „(Senderinfo).“ der Programmzeitschriften. */
    fun description(text: String?): String? = text?.replace(NOISE, "")?.trim()?.takeIf { it.isNotEmpty() }
}
