package de.ayutv.desktop.data

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MatchHintsTest {
    private val hour = 3_600_000L
    private val t0 = 1_800_000_000_000L
    private fun match(id: Long, league: String, a: String, b: String, kickoff: Long, stadium: String? = null) =
        LiveScores.Match(id, league, a, a, b, b, kickoff, 0, 0, false, 0, null, stadium = stadium)

    @Test
    fun pairingFromSubtitleComesFirst() {
        assertEquals(
            "Hannover 96 – FC Schalke 04 (Fußball: 2. Bundesliga)",
            ProgrammeText.display("Fußball: 2. Bundesliga", "Hannover 96 – FC Schalke 04", null, "Sport", t0, t0 + 2 * hour),
        )
    }

    @Test
    fun pairingFromDescription() {
        assertEquals(
            "Hertha BSC – 1. FC Köln (Live: DFB-Pokal)",
            ProgrammeText.display("Live: DFB-Pokal", null, "Hertha BSC – 1. FC Köln. Die Berliner empfangen …", "Sport", t0, t0 + 2 * hour),
        )
    }

    @Test
    fun pairingFromScheduleWhenOnlyOneMatch() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(listOf(
            match(1, "bl2", "Hannover 96", "FC Schalke 04", t0 + 15 * 60_000L),
            match(2, "bl1", "Borussia Dortmund", "SC Freiburg", t0 + 15 * 60_000L),
        ))
        assertEquals(
            "Hannover 96 – FC Schalke 04 (Live: 2. BL)",
            ProgrammeText.display("Live: 2. BL", null, null, "Sport", t0, t0 + 2 * hour),
        )
        // Konferenz: mehrere Spiele, keine Partie.
        assertEquals("Live: 2. BL Konferenz", ProgrammeText.display("Live: 2. BL Konferenz", null, null, "Sport", t0, t0 + 2 * hour))
        // Kein Sport: unverändert.
        assertEquals("Tagesschau", ProgrammeText.display("Tagesschau", null, null, "Nachrichten", t0, t0 + hour))
    }

    @Test
    fun noGuessWhenSeveralMatchesAtOnce() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(listOf(
            match(1, "bl2", "Hannover 96", "FC Schalke 04", t0),
            match(2, "bl2", "Hertha BSC", "1. FC Köln", t0),
        ))
        assertNull(MatchHints.find("Live: 2. BL", null, null, t0, t0 + 2 * hour))
        // Mit Stadion in der Beschreibung: eindeutig.
        MatchHints.update(listOf(
            match(1, "bl2", "Hannover 96", "FC Schalke 04", t0, stadium = "Heinz-von-Heiden-Arena"),
            match(2, "bl2", "Hertha BSC", "1. FC Köln", t0, stadium = "Olympiastadion"),
        ))
        assertEquals(1L, MatchHints.find("Live: 2. BL", null, "Aus der Heinz-von-Heiden-Arena in Hannover.", t0, t0 + 2 * hour)?.id)
    }
}
