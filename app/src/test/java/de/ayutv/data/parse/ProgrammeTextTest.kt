package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun skyGamesWithTheSameTitleStaySeparate() {
        // Sky: parallele Pokalspiele heißen alle „Live: DFB-Pokal“, die Paarung steht in der Beschreibung.
        val hertha = "Hertha BSC – 1. FC Köln. Die Berliner empfangen im Achtelfinale den Bundesligisten."
        val bayern = "FC Bayern München vs. 1. FC Magdeburg. Der Rekordpokalsieger gegen den Zweitligisten."
        assertEquals("Hertha BSC – 1. FC Köln", ProgrammeText.descriptionPairing(hertha))
        assertEquals("FC Bayern München vs. 1. FC Magdeburg", ProgrammeText.descriptionPairing(bayern))
        assertEquals("Hertha BSC – 1. FC Köln (Live: DFB-Pokal)", ProgrammeText.sportHeadline("Live: DFB-Pokal", null, hertha))
        // Kein Satz als Paarung
        assertNull(ProgrammeText.descriptionPairing("Der FC Bayern tritt heute im Achtelfinale gegen den Zweitligisten aus Magdeburg an."))
        assertNull(ProgrammeText.descriptionPairing("Aus dem Selhurst Park in London, Großbritannien."))

        fun key(title: String, sub: String?, desc: String?, channel: String) = ProgrammeText.sportKey(title, sub, desc, channel)
        // Verschiedene Spiele, gleicher Titel: getrennt
        assertNotEquals(key("Live: DFB-Pokal", null, hertha, "Sky.Sport.3.de"), key("Live: DFB-Pokal", null, bayern, "Sky.Sport.4.de"))
        // Ohne Beschreibung (oder nur Platzhalter) bleibt jeder Sender für sich
        assertNotEquals(key("Live: DFB-Pokal", null, null, "Sky.Sport.3.de"), key("Live: DFB-Pokal", null, null, "Sky.Sport.4.de"))
        assertNotEquals(
            key("Live: DFB-Pokal", null, "Informationen zu diesem Programm folgen in Kürze!", "Sky.Sport.3.de"),
            key("Live: DFB-Pokal", null, "Informationen zu diesem Programm folgen in Kürze!", "Sky.Sport.4.de"),
        )
        // Dasselbe Spiel auf zwei Sendern, anders geschrieben: zusammen
        assertEquals(
            key("Live: DFB-Pokal", null, hertha, "Sky.Sport.3.de"),
            key("Fußball: DFB-Pokal", "Hertha BSC - 1. FC Köln, Achtelfinale", null, "Sky.Sport.Top.Event.de"),
        )
        assertEquals(key("Formel 1: Großer Preis von Mexiko", "Rennen", "Live aus Mexiko-Stadt.", "RTL.de"), key("Formel 1: Großer Preis von Mexiko", "Rennen", "Live aus Mexiko-Stadt.", "Sky.Sport.F1.de"))
        // Spiel nur in der Beschreibung genügt auf Sportsendern; Vorschauen „Es folgt“ zählen nicht
        assertEquals(true, ProgrammeText.isLive("DFB-Pokal", null, null, 0, true, hertha))
        assertEquals(false, ProgrammeText.isSportEvent("Es folgt: LIVE Basketball NBA", null, null, 0, sportChannel = true))
        assertEquals(true, ProgrammeText.isSportEvent("Live PL: Crystal Palace - FC Fulham, 19. Spieltag", null, null, 0, sportChannel = true))
    }

    @Test
    fun conferenceAndMultiviewAreLive() {
        fun dazn(title: String, sub: String?, cat: String?, flags: Int = 0) = ProgrammeText.isSportEvent(title, sub, cat, flags, sportChannel = ProgrammeText.isSportChannel("DAZN 1 HD"))
        // DAZN/Sky: Konferenz ohne Paarung und ohne „Live“ im Titel ist trotzdem live.
        assertEquals(true, dazn("Die Konferenz", "Bundesliga, 12. Spieltag", null))
        // So auf DAZN 1 (Foto): Saison und Spieltag im Titel.
        assertEquals(true, ProgrammeText.isSportEvent("Bundesliga Konferenz 26/27 – 9. Spieltag", null, null, 0, sportChannel = ProgrammeText.isSportChannel("DE: DAZN 1 RAW (KABEL)")))
        assertEquals(true, ProgrammeText.isLive("Bundesliga Konferenz 26/27 – 9. Spieltag", null, null, 0))
        assertEquals("Bundesliga Konferenz – 9. Spieltag", ProgrammeText.eventTitle("Bundesliga Konferenz 26/27 – 9. Spieltag", null, null))
        assertEquals(true, dazn("Bundesliga", "Konferenz am Samstag", "Fußball"))
        assertEquals(true, dazn("UEFA Champions League – Die Konferenz", null, null))
        assertEquals(true, ProgrammeText.isSportEvent("2. Bundesliga Konferenz", null, "Fußball", 0))
        assertEquals(true, dazn("NFL RedZone Multiview", null, null))
        assertEquals(true, ProgrammeText.isSportEvent("Bundesliga ᴸᶦᵛᵉ", "FC Bayern München", null, 0, sportChannel = true))
        // Zusammenfassungen, Wiederholungen und Pressekonferenzen bleiben draußen.
        assertEquals(false, dazn("Highlights der Konferenz", "Bundesliga", null))
        assertEquals(false, dazn("Die Konferenz", "Bundesliga", null, flags = de.ayutv.data.db.ProgrammeFlags.REPEAT))
        assertEquals(false, ProgrammeText.isSportEvent("Pressekonferenz", null, "Nachrichten", 0))
        // Sky Bundesliga gilt als Sportsender.
        assertEquals(true, ProgrammeText.isSportChannel("Sky Bundesliga 1 HD"))
        assertEquals(true, ProgrammeText.isSportEvent("Konferenz", null, null, 0, sportChannel = ProgrammeText.isSportChannel("Sky Bundesliga 1 HD")))
    }

    @Test
    fun sportGroupsKeepDifferentGamesApart() {
        val desc = "Live-Übertragung der 2. Bundesliga."
        // Gleicher Allgemeintext auf zwei verschiedenen Sendern: zwei Einträge
        val a = ProgrammeText.sportGroupKey("Live: 2. BL", null, desc, "sky.bl2.de", "skysportbundesliga2")
        val b = ProgrammeText.sportGroupKey("Live: 2. BL", null, desc, "sky.bl3.de", "skysportbundesliga3")
        assertNotEquals(a, b)
        // Dieselbe Sendung in HD und 4K desselben Senders: ein Eintrag
        assertEquals(a, ProgrammeText.sportGroupKey("Live: 2. BL", null, desc, "sky.bl2.uhd.de", "skysportbundesliga2"))
        // Mit Paarung: über alle Sender ein Eintrag
        assertEquals(
            ProgrammeText.sportGroupKey("Hannover 96 – Dynamo Dresden", null, null, "x", "skysportbundesliga2"),
            ProgrammeText.sportGroupKey("Hannover 96 – Dynamo Dresden", null, null, "y", "skysporttopevent"),
        )
    }

    /** Titel aus dem echten EPG von DAZN und Sky (Januar 2026). */
    @Test
    fun realSportGuideTitles() {
        fun on(channel: String, title: String, category: String? = "Fußball") =
            ProgrammeText.isSportEvent(title, null, category, 0, sportChannel = ProgrammeText.isSportChannel(channel))
        // Einzelspiele, wie DAZN und Sky sie nennen
        assertEquals(true, on("DAZN 1", "Serie A 25/26: Lazio Rom - SSC Neapel"))
        assertEquals(true, on("DAZN 2", "Bundesliga: 1. FC Heidenheim - FC Bayern München", "Bundesliga"))
        assertEquals(true, on("Sky Sport 3", "PL: AFC Bournemouth - Tottenham Hotspur, 21. Spieltag"))  // „Hotspur“ ist kein „pur“
        assertEquals(true, on("Sky Sport Premier League", "PL: West Ham United - Nottingham Forest, 21. Spieltag"))
        assertEquals(true, on("Sportdigital Fussball 2", "A-League (10. Spieltag): Melbourne City - Perth Glory", null))
        // Konferenzen, auch mit „alle Spiele“ im Titel
        assertEquals(true, on("Sky Sport Premier League", "PL: Sonntags-Konferenz, 20. Spieltag"))
        assertEquals(true, on("DAZN 1", "UEFA Champions League 25/26: Die Konferenz - alle Spiele, alle Tore"))
        assertEquals(true, on("DAZN 1", "UEFA Champions League: Konferenz", "Fußball"))
        // Kein Live-Spiel: Rückblicke, Kurzfassungen, Dokus, Vereinssender, Serien mit Bindestrich
        assertEquals(false, on("Sky Sport Bundesliga", "2. BL: Hannover 96 - Hinrunde 25-26"))
        assertEquals(false, on("Sky Sport Premier League", "PL: 90in30: MCI - CHE, 20. Spieltag"))
        assertEquals(false, on("Sky Sport Bundesliga", "BL: Mythos Borussia - Eine Legende wird 125, ep. 1"))
        assertEquals(false, on("Sky Sport Bundesliga", "BL: Klassiker der Woche: SGE - BVB (17/18)"))
        assertEquals(false, on("Sky Sport Premier League", "PL: Classic Match: FC Fulham - FC Chelsea"))
        assertEquals(false, on("Sky Sport Premier League", "PL: Fergie - Rise of a Dynasty"))
        assertEquals(false, on("Sportdigital Fußball", "LFC TV - FA Cup"))
        assertEquals(false, on("Sport 1 plus", "Hausmeister Krause - Ordnung muss sein", null))
        assertEquals(false, on("DAZN 2", "NFL Domestic: NFL ENDZN - Woche 18", null))
        assertEquals(false, on("DAZN 1", "Doppelpack Europa - Highlights der Serie A und Ligue 1 2025/26: 18./17. Spieltag", "Sport"))
        assertEquals(false, on("Sky Sport Premier League", "PL: Highlights Samstag, 20. Spieltag"))
    }

    /** Wie auf dem Foto vom Fernseher (November 2026): verschiedene Anbieter, verschiedene Titel für dasselbe Spiel. */
    @Test
    fun sportTitlesAreUniform() {
        assertEquals("Bundesliga", ProgrammeText.eventTitle("Live: BL", null, "Aus der Red Bull Arena, Deutschland."))
        assertEquals("1. FSV Mainz 05 – FC Bayern München", ProgrammeText.eventTitle("Live: BL", "1. FSV Mainz 05 - FC Bayern München", null))
        assertEquals("RB Leipzig – VfL Wolfsburg", ProgrammeText.eventTitle("Live: BL", null, null, pairing = "RB Leipzig - VfL Wolfsburg"))
        assertEquals("Lazio Rom – SSC Neapel", ProgrammeText.eventTitle("Serie A 25/26: Lazio Rom - SSC Neapel", null, null))
        assertEquals("2. Bundesliga – Konferenz", ProgrammeText.eventTitle("Live: 2. BL – Konferenz", null, null))
        assertEquals("Formel 1 - MSC Cruises Grand Prix von Sao Paulo – 3. Freies Training",
            ProgrammeText.eventTitle("Formel 1 - MSC Cruises Grand Prix von Sao Paulo", "3. Freies Training", null))
        assertEquals("Bundesliga", ProgrammeText.competition("Live: BL"))
        assertEquals("Premier League", ProgrammeText.competition("PL: AFC Bournemouth - Tottenham Hotspur, 21. Spieltag"))
        assertEquals("Serie A", ProgrammeText.competition("Serie A 25/26: Lazio Rom - SSC Neapel"))
        assertEquals("Bundesliga", ProgrammeText.competition("Fußball: Bundesliga"))
    }

    @Test
    fun sameMatchFromTwoGuidesOnce() {
        assertEquals(true, ProgrammeText.samePairing("1. FSV Mainz 05 – FC Bayern München", "Mainz 05 - Bayern München"))
        assertEquals(false, ProgrammeText.samePairing("1. FSV Mainz 05 – FC Bayern München", "Borussia Dortmund – SV 07 Elversberg"))
        data class E(val start: Long, val pairing: String?, val channels: List<String>)
        val hour = 3_600_000L
        val merged = ProgrammeText.mergeSameMatch(
            listOf(
                E(0, "1. FSV Mainz 05 – FC Bayern München", listOf("Sky Sport Bundesliga UHD")),
                E(15 * 60_000L, "Mainz 05 - Bayern München", listOf("Sky Go Bundesliga 1 4K")),   // anderer Anbieter, 15 min später
                E(5 * 60_000L, null, listOf("Sky Sport Bundesliga 2")),                           // ohne Paarung: bleibt
                E(5 * hour, "Mainz 05 - Bayern München", listOf("Sky Sport Bundesliga 1")),       // Wiederholung: entfällt
            ),
            { it.start }, { it.pairing },
        ) { a, b -> a.copy(channels = a.channels + b.channels) }
        assertEquals(2, merged.size)
        assertEquals(listOf("Sky Sport Bundesliga UHD", "Sky Go Bundesliga 1 4K"), merged[0].channels)
    }
}
