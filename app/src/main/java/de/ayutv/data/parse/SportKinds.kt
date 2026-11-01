package de.ayutv.data.parse

/**
 * Welche Sportart eine Sendung ist, für die Filter im Bereich Sport. Andere Sportarten werden zuerst geprüft: Viele
 * haben selbst eine „Bundesliga“ oder einen „Pokal“ (Handball-Bundesliga, Volleyball Bundesliga, Basketball BBL),
 * die früher als Fußball zählten. Fußball nur mit eindeutigem Hinweis (Fußball, Bundesliga ohne andere Sportart,
 * bekannte Ligen und Wettbewerbe) oder wenn der Spielplan das Spiel als Fußballspiel kennt.
 */
object SportKinds {
    enum class Kind(val label: String) {
        FOOTBALL("Fußball"), HANDBALL("Handball"), BASKETBALL("Basketball"), ICE_HOCKEY("Eishockey"),
        VOLLEYBALL("Volleyball"), TENNIS("Tennis"), MOTORSPORT("Motorsport"), DARTS("Darts"), US_SPORTS("US-Sport"),
        WINTER("Wintersport"), COMBAT("Kampfsport"), CYCLING("Radsport"), GOLF("Golf"), SNOOKER("Snooker"),
        HOCKEY("Hockey"), OTHER("Weitere"),
    }

    private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Reihenfolge zählt: Spezielleres zuerst (Eishockey vor Hockey, US-Sport vor Fußball wegen „Football“). */
    private val RULES: List<Pair<Kind, Regex>> = listOf(
        Kind.ICE_HOCKEY to rx("""eishockey|ice hockey|\bdel\b|\bnhl\b|\bkhl\b|eisbären|adler mannheim|kölner haie"""),
        Kind.HANDBALL to rx("""handball|\bhbl\b|\behf\b|füchse berlin|thw kiel|sg flensburg|mt melsungen|rhein-neckar löwen|hsg |\bthsv\b"""),
        Kind.BASKETBALL to rx("""basketball|\bbbl\b|\bnba\b|euroleague|eurocup|baskets|seawolves|towers hamburg|alba berlin|telekom baskets|\bmbc\b"""),
        Kind.VOLLEYBALL to rx("""volleyball|\bvbl\b|volleys|beach-?volley"""),
        Kind.US_SPORTS to rx("""\bnfl\b|american football|super bowl|\bmlb\b|baseball|\bncaa\b|college football|thursday night|sunday night football|\bat (bills|bears|broncos|steelers|chiefs|patriots|ravens|packers|cowboys|eagles)\b"""),
        Kind.TENNIS to rx("""tennis|\batp\b|\bwta\b|wimbledon|roland garros|us open|australian open|davis cup|billie jean king"""),
        Kind.MOTORSPORT to rx("""formel ?1|formula ?1|\bf1\b|motogp|moto ?gp|\bdtm\b|nascar|indycar|rallye|rally|motorsport|grand prix|superbike|\bwec\b|le mans|formel e|formula e"""),
        Kind.DARTS to rx("""darts|\bpdc\b"""),
        Kind.SNOOKER to rx("""snooker"""),
        Kind.WINTER to rx("""biathlon|ski alpin|skispringen|langlauf|rodeln|bob\b|eiskunstlauf|eisschnelllauf|snowboard|skeleton|nordische kombination|skate canada"""),
        Kind.COMBAT to rx("""boxen|boxing|\bufc\b|\bmma\b|wrestling|\bwwe\b|kickboxen|judo|ringen"""),
        Kind.CYCLING to rx("""radsport|tour de france|giro d|vuelta|cyclocross|radrennen|bahnrad"""),
        Kind.GOLF to rx("""\bgolf\b|pga tour|ryder cup|dp world tour"""),
        Kind.HOCKEY to rx("""\bhockey\b|feldhockey|hallenhockey|hockey-bundesliga|\bhc\b \(|\(d\) -|\(h\) -"""),
    )
    private val FOOTBALL = rx(
        """fu(ß|ss)ball|soccer|\bfootball\b|bundesliga|2\. ?bl\b|3\. ?liga|\bbl\b|dfb-?pokal|champions league|europa league|""" +
            """conference league|premier league|la ?liga|serie a|ligue 1|eredivisie|süper lig|super lig|regionalliga|""" +
            """oberliga|frauen-bundesliga|em-quali|wm-quali|länderspiel|nations league|club world cup|\bfifa\b|\buefa\b"""
    )
    /** Vereinskürzel, die meist Fußball heißen – aber nicht bei Dyn (Handball, Basketball, Volleyball, Hockey). */
    private val CLUBS = rx("""\bfc\b|\bsv\b|\bvfb\b|\bvfl\b|borussia|eintracht|bayern münchen|fc bayern|\bu1[79]\b|\bu2[13]\b""")
    private val NOT_FOOTBALL_CHANNEL = rx("""\bdyn\b|staige|handball|basketball|volleyball""")

    /**
     * Sportart einer Sendung. [footballMatch]: der Spielplan (OpenLigaDB, nur Fußball) kennt das Spiel. Ohne
     * eindeutigen Hinweis [Kind.OTHER].
     */
    fun kind(title: String, subtitle: String?, category: String?, description: String?, channel: String = "", footballMatch: Boolean = false): Kind {
        if (footballMatch) return Kind.FOOTBALL
        val main = listOfNotNull(title, subtitle, category).joinToString(" ")
        // Erst Titel, Untertitel, Kategorie (eindeutig), dann die Beschreibung, zuletzt der Sendername.
        for (text in listOf(main, description.orEmpty(), channel)) {
            if (text.isBlank()) continue
            RULES.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.let { return it.first }
            if (FOOTBALL.containsMatchIn(text)) return Kind.FOOTBALL
            if (text !== channel && CLUBS.containsMatchIn(text) && !NOT_FOOTBALL_CHANNEL.containsMatchIn(channel)) return Kind.FOOTBALL
        }
        return Kind.OTHER
    }
}
