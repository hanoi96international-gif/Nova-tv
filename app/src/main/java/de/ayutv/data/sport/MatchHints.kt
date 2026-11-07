package de.ayutv.data.sport

import de.ayutv.data.parse.ProgrammeText

/**
 * Spielplan (OpenLigaDB) für die Anzeige im TV-Guide, in der Senderliste und in der Vorschau: Nennt der TV-Guide nur
 * „Live: 2. Bundesliga“, steht dort trotzdem die Partie. Zuordnung über Stadion, Stadt oder Verein im Text, sonst nur,
 * wenn zu dieser Zeit genau ein Spiel der Liga angepfiffen wird. Konferenzen, Zusammenfassungen und Wiederholungen nie.
 */
object MatchHints {
    @Volatile var matches: List<LiveScores.Match> = emptyList()
        private set
    private var scores: LiveScores? = null

    private val NOT_GAME = Regex("""(?i)konferenz|multi-?view|alle spiele|highlights?|zusammenfassung|wiederholung|re-?live|magazin|vorbericht|analyse|talk|countdown""")
    private const val MINUTE = 60_000L

    /** Einmal beim Start: der Anzeige den Spielplan bekannt machen. */
    fun install(liveScores: LiveScores) {
        scores = liveScores
        ProgrammeText.scheduleHint = { title, subtitle, description, start, stop -> find(title, subtitle, description, start, stop)?.pairing }
    }

    /** Neuer Spielplan (aus [LiveScores.current]). */
    fun update(list: List<LiveScores.Match>) {
        if (list.isNotEmpty()) matches = list
    }

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
