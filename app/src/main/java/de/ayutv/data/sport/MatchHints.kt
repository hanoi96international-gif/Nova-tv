package de.ayutv.data.sport

import de.ayutv.data.parse.ProgrammeText

/**
 * Spielplan (OpenLigaDB) für die Anzeige im TV-Guide, in der Senderliste und in der Vorschau: Nennt der TV-Guide nur
 * „Live: 2. Bundesliga“, steht dort trotzdem die Partie. Zuordnung über Stadion, Stadt oder Verein im Text, sonst nur,
 * wenn zu dieser Zeit genau ein Spiel der Liga angepfiffen wird, oder per Ausschluss über alle Sender ([learn]: laufen fünf
 * Spiele und nennen vier Sky-Kanäle ihre Partie, gehört die fünfte zum Kanal, der nur „Live: BL“ sagt). Konferenzen,
 * Zusammenfassungen und Wiederholungen nie.
 */
object MatchHints {
    @Volatile var matches: List<LiveScores.Match> = emptyList()
        private set
    private var scores: LiveScores? = null
    /** Zuordnungen über alle Sender (aus [learn]): Sendung → Partie. */
    private val learned = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val NOT_GAME = Regex("""(?i)konferenz|multi-?view|alle spiele|highlights?|zusammenfassung|wiederholung|re-?live|magazin|vorbericht|analyse|talk|countdown""")
    private const val MINUTE = 60_000L

    /** Einmal beim Start: der Anzeige den Spielplan bekannt machen. */
    fun install(liveScores: LiveScores) {
        scores = liveScores
        ProgrammeText.scheduleHint = { title, subtitle, description, start, stop -> pairingFor(title, subtitle, description, start, stop) }
    }

    /** Neuer Spielplan (aus [LiveScores.current]). */
    fun update(list: List<LiveScores.Match>) {
        if (list.isNotEmpty()) matches = list
    }

    /**
     * Sport-Sendungen aller Sender auf einmal zuordnen ([LiveScores.assign], auch per Ausschluss) und merken, damit die
     * Anzeige einer einzelnen Sendung (Senderliste, TV-Guide, Vorschau) die Partie kennt.
     */
    fun learn(shows: List<LiveScores.Show>) {
        val list = matches
        val s = scores ?: return
        if (list.isEmpty() || shows.isEmpty()) return
        val found = s.assign(list, shows)
        val now = System.currentTimeMillis()
        learned.keys.removeIf { k -> (k.substringBefore('|').toLongOrNull() ?: 0L) < now - 12 * 60 * MINUTE }
        for ((i, show) in shows.withIndex()) {
            val match = found[i] ?: continue
            if (show.pairing == null) learned[key(show.title, show.subtitle, show.description, show.stop)] = match.pairing
        }
    }

    private fun key(title: String, subtitle: String?, description: String?, stop: Long) =
        "$stop|${title.trim()}|${subtitle.orEmpty().trim()}|${description.orEmpty().trim().take(60)}"

    /** Partie für die Anzeige: gemerkt aus [learn], sonst [find]. */
    fun pairingFor(title: String, subtitle: String?, description: String?, start: Long, stop: Long): String? =
        learned[key(title, subtitle, description, stop)]?.takeIf { NOT_GAME.find(title + " " + subtitle.orEmpty()) == null }
            ?: find(title, subtitle, description, start, stop)?.pairing

    fun find(title: String, subtitle: String?, description: String?, start: Long, stop: Long): LiveScores.Match? {
        val list = matches
        if (list.isEmpty()) return null
        val head = title + " " + subtitle.orEmpty()
        if (NOT_GAME.containsMatchIn(head)) return null
        val league = LiveScores.leagueOf(head) ?: return null
        scores?.byVenue(list, title, subtitle, description, start, stop)?.let { return it }
        // Ohne Hinweis im Text: nur wenn genau ein Spiel der Liga in dieser Zeit angepfiffen wird.
        return list.filter { it.league == league && it.kickoff in (start - 15 * MINUTE) until stop }.singleOrNull()
    }
}
