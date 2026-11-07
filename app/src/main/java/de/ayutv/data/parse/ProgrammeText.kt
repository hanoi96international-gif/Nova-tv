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
    private val PAIRING = Regex("""\S\s+(?:[–—-]|vs\.?|v\.?|gegen|@)\s+\S""")
    /** Sender, auf denen eine Paarung oder „Live“ für ein Spiel genügt, auch ohne Sport-Kategorie im TV-Guide. */
    private val SPORT_CHANNEL = Regex("""(?i)sport|dazn|servus|magenta|eleven|laola|bein|espn|\bran\b|motorvision|arena|bundesliga|\bliga\b|premier league|champions league|fu(ß|ss)ball|football""")
    private val NOISE = Regex("""\s*\(Senderinfo\)\.?\s*$""")

    /**
     * Spiel aus dem Spielplan für Sport-Sendungen, deren TV-Guide keine Paarung nennt („Live: 2. Bundesliga“). Von der
     * App gesetzt ([de.ayutv.data.sport.MatchHints]); ohne Spielplan: null.
     */
    @Volatile var scheduleHint: ((title: String, subtitle: String?, description: String?, start: Long, stop: Long) -> String?)? = null

    /**
     * Titel für TV-Guide, Senderliste und Vorschau: wie [headline], bei Sport kommt die Partie nach vorn – aus dem
     * Untertitel, dem Anfang der Beschreibung oder dem Spielplan („Hannover 96 – Schalke 04 (Live: 2. Bundesliga)“).
     */
    fun display(title: String, subtitle: String?, description: String?, category: String?, start: Long, stop: Long): String {
        val base = headline(title, subtitle)
        if (pairing(title, subtitle) != null || !isSport(title, subtitle, category)) return base
        val match = descriptionPairing(description) ?: runCatching { scheduleHint?.invoke(title, subtitle, description, start, stop) }.getOrNull() ?: return base
        return "$match ($base)"
    }

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
    private val LIVE_FORMAT = Regex("""(?i)(?<!presse)konferenz|multi-?view|multi-?match|einzelspiel(e)? live|matchday live|alle spiele live""")
    /**
     * Sicher keine Live-Übertragung: Zusammenfassungen, Wiederholungen, Kurzfassungen („90in30“), Rückblicke, Dokus und
     * Magazine. Wortgrenzen beachten: „Tottenham Hotspur“ ist kein „pur“, „Stalker“ kein „Talk“.
     */
    private val NOT_LIVE = Regex(
        """(?i)highlights?|zusammenfassung|rückblick|re-?live|classics?\b|classic match|klassiker der woche|kompakt|""" +
            """countdown|magazin|spieltags-sprint|wiederholung|aufzeichnung|reportage|\bdoku|\banalyse\b|\btalk\b|stammtisch|""" +
            """\bpur\b|sportschau club|es folgt|sendepause|90in30|\brewind\b|\breport\b|kids club|torparade|\bbest of\b|""" +
            """\b(hin|rück)runde\b|\bfeatures?\b|\bdecoded\b|\boriginals?\b|\bstories\b|\bep\.\s*\d|\bfolge \d|\bpart \d|""" +
            """\bmythos\b|\blegenden?\b|the rise|highlight-show|\breview\b|netbusters|\btop \d+|match of the week"""
    )
    /** Für eine Konferenz zählen „alle Spiele, alle Tore“ nicht als Zusammenfassung (die Konferenz zeigt sie live). */
    private val NOT_LIVE_SOFT = Regex("""(?i)alle spiele|\balle tore\b""")

    /** Sportsendung (nach Kategorie oder Titel). */
    fun isSport(title: String, subtitle: String?, category: String?): Boolean =
        SPORT.containsMatchIn(category.orEmpty()) || SPORT.containsMatchIn(title) || SPORT.containsMatchIn(subtitle.orEmpty())

    /** Paarung „A – B“ im Untertitel oder Titel. */
    fun pairing(title: String, subtitle: String?): String? =
        listOfNotNull(subtitle, title).map { it.substringBefore(',').substringAfter(": ").trim() }
            .firstOrNull { PAIRING.containsMatchIn(it) && !it.contains("Spieltag") && plausible(it) }

    /**
     * Sehen beide Seiten wie Mannschaften oder Sportler aus? Aussortiert werden Serien und Shows mit Bindestrich im
     * Titel („Hausmeister Krause - Ordnung muss sein“, „Fergie - Rise of a Dynasty“), Rückblicke
     * („Hannover 96 - Hinrunde 25-26“), Vereinssender („LFC TV - FA Cup“) und Wochenangaben („NFL ENDZN - Woche 18“).
     */
    private fun plausible(pairing: String): Boolean {
        val sides = Teams.ofPairing(pairing)
        if (sides.size != 2) return true
        return sides.none { side ->
            val words = side.split(' ').filter { it.isNotBlank() }
            NOT_A_SIDE.containsMatchIn(side) || words.size > 6 || LIVE_FORMAT.containsMatchIn(side) || side.lowercase() in COMPETITIONS ||
                words.count { w -> w.first().isLowerCase() && w.lowercase() !in PARTICLES } >= 2
        }
    }
    private val NOT_A_SIDE = Regex("""(?i)^(woche|runde|spieltag|folge|teil|part|ep\.?)\b|\b\d{2}[-/]\d{2}$|\btv$|\bhi\w*runde\b|\brückrunde\b""")
    /** Kleingeschriebene Wörter, die in Vereinsnamen vorkommen („Stade de Reims“, „Atlético de Madrid“, „Brighton and Hove“). */
    private val PARTICLES = setOf("de", "del", "da", "do", "dos", "das", "di", "la", "le", "les", "van", "von", "der", "den", "am", "an", "al", "el", "and", "y", "e", "und")

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
        val conference = LIVE_FORMAT.containsMatchIn(text)
        if (!conference && NOT_LIVE_SOFT.containsMatchIn(text)) return false
        if (LIVE_WORD.containsMatchIn(text)) return true
        if ((sportChannel || isSport(title, subtitle, category)) && conference) return true
        return (sportChannel || isSport(title, subtitle, category)) && pairing(title, subtitle) != null
    }

    /** Wie [isLive], die Paarung darf auch am Anfang der Beschreibung stehen. */
    fun isLive(title: String, subtitle: String?, category: String?, flags: Int, sportChannel: Boolean, description: String?): Boolean =
        isLive(title, subtitle, category, flags, sportChannel) ||
            (flags and REPEAT == 0 && !NOT_LIVE.containsMatchIn(title + " " + subtitle.orEmpty()) && !NOT_LIVE_SOFT.containsMatchIn(title + " " + subtitle.orEmpty()) &&
                (sportChannel || isSport(title, subtitle, category)) && descriptionPairing(description) != null)

    /**
     * Dasselbe Spiel nur einmal: Gleiche Paarung (Schreibweise egal, „FC Bayern München“ = „Bayern“) innerhalb von zwei
     * Stunden wird zusammengelegt ([merge], z. B. weitere Sender), auch wenn die TV-Guides verschiedener Anbieter eine
     * andere Anfangszeit nennen. Später ist es eine Wiederholung (DAZN kennzeichnet sie nicht) und entfällt.
     * [items] nach Beginn sortiert; [pairing] null = ohne Paarung (bleibt für sich).
     */
    fun <T> mergeSameMatch(items: List<T>, start: (T) -> Long, pairing: (T) -> String?, merge: (first: T, other: T) -> T): List<T> {
        val out = ArrayList<T>()
        val pairings = ArrayList<String?>()
        for (item in items) {
            val p = pairing(item)
            if (p == null) { out += item; pairings += null; continue }
            val same = out.indices.filter { j -> pairings[j]?.let { samePairing(it, p) } == true }
            val at = start(item)
            val now = same.firstOrNull { j -> kotlin.math.abs(at - start(out[j])) < REPLAY_AFTER_MS }
            when {
                now != null -> out[now] = merge(out[now], item)
                same.isNotEmpty() -> Unit // Wiederholung
                else -> { out += item; pairings += p }
            }
        }
        return out
    }
    private const val REPLAY_AFTER_MS = 2 * 3_600_000L

    /** Dieselbe Paarung? Je Seite ein gemeinsames Vereinswort („Mainz 05“ = „1. FSV Mainz 05“). */
    fun samePairing(a: String, b: String): Boolean {
        val x = Teams.ofPairing(a)
        val y = Teams.ofPairing(b)
        if (x.size != 2 || y.size != 2) return simple(a) == simple(b)
        fun same(p: String, q: String): Boolean {
            val kp = Teams.keywords(p)
            val kq = Teams.keywords(q)
            return if (kp.isNotEmpty() && kq.isNotEmpty()) kp.any { it in kq } else simple(p) == simple(q)
        }
        return same(x[0], y[0]) && same(x[1], y[1])
    }

    /**
     * Wettbewerb aus dem Titel, einheitlich ausgeschrieben: „Live: BL“ → „Bundesliga“, „PL: …“ → „Premier League“,
     * „Serie A 25/26: Lazio – Neapel“ → „Serie A“. Ohne Doppelpunkt der Titel ohne „Live“.
     */
    fun competition(title: String): String {
        val plain = title.replace(LIVE_PREFIX, "").trim()
        val head = (if (": " in plain) plain.substringBefore(": ") else plain).replace(SEASON, "").trim()
        return expand(head).ifEmpty { plain }
    }

    /**
     * Einheitlicher Titel für die Sport-Übersicht: die Paarung („Mainz 05 – Bayern München“), sonst der Wettbewerb
     * ausgeschrieben, ohne „Live:“ („Live: BL“ → „Bundesliga“, „Live: 2. BL – Konferenz“ → „2. Bundesliga – Konferenz“).
     */
    fun eventTitle(title: String, subtitle: String?, description: String?, pairing: String? = null): String {
        (pairing ?: pairing(title, subtitle, description))?.let { p ->
            val sides = Teams.ofPairing(p)
            return if (sides.size == 2) "${sides[0]} – ${sides[1]}" else p
        }
        val plain = headline(title, subtitle).replace(LIVE_PREFIX, "").trim()
        val head = plain.substringBefore(": ").substringBefore(" – ").substringBefore(" - ")
        val rest = plain.removePrefix(head)
        return (expand(head.replace(SEASON, "").trim()) + rest).trim().ifEmpty { plain }
    }

    private fun expand(head: String): String = COMPETITIONS[head.lowercase()] ?: head
    private val LIVE_PREFIX = Regex("""(?i)^\s*(🔴\s*)?(live\s*:?|ᴸᶦᵛᵉ|ᴸᴵⱽᴱ)\s*|^\s*fu(ß|ss)ball\s*:\s*""")
    private val SEASON = Regex("""\b(20)?\d{2}/\d{2}\b""")
    private val COMPETITIONS = mapOf(
        "bl" to "Bundesliga", "1. bl" to "Bundesliga", "buli" to "Bundesliga", "2. bl" to "2. Bundesliga", "2. buli" to "2. Bundesliga",
        "3. bl" to "3. Liga", "pl" to "Premier League", "epl" to "Premier League", "cl" to "Champions League", "ucl" to "Champions League",
        "uefa cl" to "Champions League", "el" to "Europa League", "uel" to "Europa League", "ecl" to "Conference League",
        "uecl" to "Conference League", "dfb" to "DFB-Pokal", "efl" to "EFL Championship", "wm" to "Weltmeisterschaft", "em" to "Europameisterschaft",
    )

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
