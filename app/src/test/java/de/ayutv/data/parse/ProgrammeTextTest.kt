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

    @Test
    fun liveAndSportEvents() {
        fun event(title: String, sub: String?, category: String?, flags: Int = 0) = ProgrammeText.isSportEvent(title, sub, category, flags)
        assertEquals(true, event("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt, tipico Topspiel der Woche, 7. Spieltag", "Fußball"))
        assertEquals(true, event("Live 2. BL: Sonntags-Konferenz, 9. Spieltag", null, null))
        assertEquals(true, event("1. FC Köln - FC Schalke 04", null, "Sport"))
        assertEquals(true, event("Fußball: Bundesliga der Frauen", "8. Spieltag: Hamburger SV – 1. FC Union Berlin", "Fußball"))
        // Keine Spiele: Zusammenfassungen, Magazine, Talks, Wiederholungen, Nicht-Sport
        assertEquals(false, event("Fußball: Bundesliga", "Highlights XXL: 1. FC Köln – FC Schalke 04, 7. Spieltag", "Fußball"))
        assertEquals(false, event("Fußball: 2. Bundesliga, Alle Spiele, alle Tore", "Die Vodafone Highlight-Show, 9. Spieltag", "Fußball"))
        assertEquals(false, event("Bundesliga pur – 1. & 2. Bundesliga", "7. Spieltag, 1. BL & 9. Spieltag, 2. BL", "Fußballmagazin"))
        assertEquals(false, event("Doppelpass Live", null, "Fußballstammtisch"))
        assertEquals(false, event("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt", "Fußball", de.ayutv.data.db.ProgrammeFlags.REPEAT))
        assertEquals(false, event("Tatort", "Der Fluch – Teil 2", "Krimi"))
        assertEquals("Borussia Dortmund – Eintracht Frankfurt", ProgrammeText.pairing("Fußball: Bundesliga", "Borussia Dortmund – Eintracht Frankfurt, 7. Spieltag"))
        // Andere Schreibweisen der Paarung
        assertEquals(true, event("Premier League: Liverpool vs. Chelsea", null, "Football"))
        assertEquals(true, event("Liverpool v Chelsea", null, "Sport"))
        assertEquals(true, event("FC Bayern München gegen 1. FC Magdeburg", null, "Fußball"))
        // Sportsender ohne Sport-Kategorie im TV-Guide: Paarung genügt, Magazine bleiben draußen
        fun onSportChannel(title: String, sub: String?, category: String?) = ProgrammeText.isSportEvent(title, sub, category, 0, sportChannel = true)
        assertEquals(true, onSportChannel("FC Liverpool – FC Chelsea", null, null))
        assertEquals(false, onSportChannel("Sky Sport News", null, "Nachrichten"))
        assertEquals(false, onSportChannel("Highlights: FC Liverpool – FC Chelsea", null, null))
        assertEquals(true, ProgrammeText.isSportChannel("|DE| SKY SPORT PREMIER LEAGUE FHD"))
        assertEquals(true, ProgrammeText.isSportChannel("DAZN 1 HD"))
        assertEquals(false, ProgrammeText.isSportChannel("Das Erste HD"))
        assertEquals(listOf("Liverpool", "Chelsea"), Teams.ofPairing("Liverpool vs. Chelsea"))
    }
}
