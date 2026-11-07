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

    /** Sky Bundesliga am Samstag wie auf dem Foto: 381, 383, 384 mit Partie, 382 nur „Live: BL“, 385/386 Multiview. */
    @Test
    fun skyBundesligaChannelsAllGetTheirMatch() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        val kick = t0 + 15 * 60_000L
        MatchHints.update(listOf(
            match(1, "bl1", "1. FSV Mainz 05", "FC Bayern München", kick),
            match(2, "bl1", "Borussia Dortmund", "SV 07 Elversberg", kick),
            match(3, "bl1", "SC Paderborn 07", "Eintracht Frankfurt", kick),
            match(4, "bl1", "Hamburger SV", "1. FC Köln", kick),
        ))
        // Ohne Spielort bei OpenLigaDB: das Stadion des Heimvereins aus der eigenen Liste genügt.
        assertEquals(1L, MatchHints.find("Live: BL", null, "Aus der MEWA Arena in Mainz, Rheinland-Pfalz, Deutschland.", t0, t0 + 2 * hour)?.id)
        assertEquals(4L, MatchHints.find("Live: BL", null, "Aus dem Volksparkstadion in Hamburg.", t0, t0 + 2 * hour)?.id)
        // Ohne jeden Hinweis: per Ausschluss, weil die anderen drei Spiele schon Sendern gehören.
        val stop = t0 + 2 * hour + 30 * 60_000L
        MatchHints.learn(listOf(
            LiveScores.Show("Live: BL", null, "1. FSV Mainz 05 – FC Bayern München. Das Topspiel.", t0, stop, "1. FSV Mainz 05 – FC Bayern München"),
            LiveScores.Show("Live: BL", null, null, t0, stop, null),
            LiveScores.Show("Live: BL", "Borussia Dortmund – SV 07 Elversberg", null, t0, stop, "Borussia Dortmund – SV 07 Elversberg"),
            LiveScores.Show("Live: BL", "SC Paderborn 07 – Eintracht Frankfurt", null, t0, stop, "SC Paderborn 07 – Eintracht Frankfurt"),
            LiveScores.Show("BL: Multiview", null, null, t0, stop, null),
        ))
        assertEquals("Hamburger SV – 1. FC Köln (Live: BL)", ProgrammeText.display("Live: BL", null, null, "Sport", t0, stop))
        // Multiview zeigt mehrere Spiele: keine Partie.
        assertEquals("BL: Multiview", ProgrammeText.display("BL: Multiview", null, null, "Sport", t0, stop))
    }
}
