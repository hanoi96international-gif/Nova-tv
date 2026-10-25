package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Titel aus dem echten EPG (tvspielfilm über epgshare01, Oktober 2026). */
class ProgrammeTextTest {
    @Test
    fun footballPairingComesFirst() {
        assertEquals(
            "Borussia Dortmund – Eintracht Frankfurt (Fußball: Bundesliga, tipico Topspiel der Woche, 7. Spieltag)",
            ProgrammeText.headline("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt, tipico Topspiel der Woche, 7. Spieltag"),
        )
        assertEquals(
            "TSG Hoffenheim – 1. FSV Mainz 05 (Fußball: Bundesliga der Frauen)",
            ProgrammeText.headline("Fußball: Bundesliga der Frauen", "TSG Hoffenheim – 1. FSV Mainz 05"),
        )
        assertEquals("Genk - Standard Liège (Fußball: Jupiler Pro League)", ProgrammeText.headline("Fußball: Jupiler Pro League", "Genk - Standard Liège"))
    }

    @Test
    fun otherSubtitlesFollowTheTitle() {
        assertEquals("Fußball: 2. Bundesliga – Sonntags-Konferenz, 9. Spieltag", ProgrammeText.headline("Fußball: 2. Bundesliga", "Sonntags-Konferenz, 9. Spieltag"))
        assertEquals("Darts: Europameisterschaft – Viertelfinale", ProgrammeText.headline("Darts: Europameisterschaft", "Viertelfinale"))
        assertEquals("Tagesschau", ProgrammeText.headline("Tagesschau", null))
        assertEquals("Tagesschau", ProgrammeText.headline("Tagesschau", " "))
        assertEquals("Folge 3", ProgrammeText.headline("", "Folge 3"))
        // Untertitel wiederholt nur den Titel
        assertEquals("Tatort: Der Fluch", ProgrammeText.headline("Tatort: Der Fluch", "Der Fluch"))
    }

    @Test
    fun descriptionWithoutSourceNote() {
        assertEquals("Die besten Szenen.", ProgrammeText.description("Die besten Szenen. (Senderinfo)."))
        assertNull(ProgrammeText.description("  "))
    }
}
