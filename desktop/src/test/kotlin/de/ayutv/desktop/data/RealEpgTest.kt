package de.ayutv.desktop.data

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.EspnScores
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Echte Daten vom 7.11.2026 (9. Spieltag): TV-Guide von epgshare01 (Sky, DAZN), Spielplan von OpenLigaDB, Scoreboards von
 * ESPN. OpenLigaDB nennt keinen Spielort; Sky nennt im TV-Guide nur „Live: BL“ und das Stadion.
 */
class RealEpgTest {
    private fun time(xmltv: String) = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT).parse(xmltv)!!.time
    private fun resource(name: String) = javaClass.getResource("/real/$name")!!.readText()

    @Before
    fun schedule() {
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(
            LiveScores.parse(resource("openligadb-bl1-2026-11-07.json"), "bl1") +
                EspnScores.parse(resource("espn-nhl-2026-11-07.json"), "nhl") +
                EspnScores.parse(resource("espn-eng1-2026-11-07.json"), "eng.1")
        )
    }

    private fun show(title: String, desc: String?, start: String, stop: String, subtitle: String? = null, category: String = "Soccer") =
        ProgrammeText.display(title, subtitle, desc, category, time(start), time(stop))

    @Test
    fun skyBundesligaSaturdayEveryChannelGetsItsMatch() {
        assertEquals(
            "1. FSV Mainz 05 – FC Bayern München (Live: BL)",
            show("Live: BL", "Aus der MEWA Arena in Mainz, Rheinland-Pfalz, Deutschland.", "20261107141500 +0000", "20261107164500 +0000"),
        )
        // Kanal 2 auf dem Foto: nur „Red Bull Arena“, keine Stadt
        assertEquals(
            "RB Leipzig – FC Augsburg (Live: BL)",
            show("Live: BL", "Aus der Red Bull Arena, Deutschland.", "20261107141500 +0000", "20261107163000 +0000"),
        )
        assertEquals(
            "Borussia Dortmund – SV 07 Elversberg (Live: BL)",
            show("Live: BL", "Aus dem Signal Iduna Park, Dortmund, Deutschland.", "20261107141500 +0000", "20261107164500 +0000"),
        )
        assertEquals(
            "SC Paderborn 07 – Eintracht Frankfurt (Live: BL)",
            show("Live: BL", "Aus der Home Deluxe Arena in Paderborn, Deutschland.", "20261107141500 +0000", "20261107170000 +0000"),
        )
        // Multiview: mehrere Spiele, keine Partie
        assertEquals("BL: Multiview", show("BL: Multiview", "Innovativ, individuell, interaktiv: das neue Bundesliga-Erlebnis am Samstagnachmittag bei Sky Sport", "20261107140000 +0000", "20261107170000 +0000"))
    }

    @Test
    fun nhlAndPremierLeagueGetTheirMatch() {
        assertEquals(
            "Washington Capitals – Florida Panthers (Live: NHL)",
            show("Live: NHL", "Aus der Capital One Arena in Washington, D.C., USA.", "20261107173000 +0000", "20261107223000 +0000", category = "Sports"),
        )
        assertEquals(
            "Fulham – Newcastle United (Live: PL)",
            show("Live: PL", "Aus dem Craven Cottage in London, England.", "20261107145000 +0000", "20261107173000 +0000"),
        )
        assertEquals(
            "Leeds United – Tottenham Hotspur (Live: PL)",
            show("Live: PL", "Aus dem Elland Road Stadium in Leeds, England, Vereinigtes Königreich.", "20261107122000 +0000", "20261107143500 +0000"),
        )
    }

    @Test
    fun daznCallsEverythingAMagazineButConferenceAndMatchesAreLive() {
        val dazn = ProgrammeText.isSportChannel("DE: DAZN 1 RAW (KABEL)")
        assertTrue(ProgrammeText.isSportEvent("Bundesliga Konferenz 26/27", "9. Spieltag", "Fußballmagazin", 0, dazn))
        assertTrue(ProgrammeText.isLive("Bundesliga Konferenz 26/27", "9. Spieltag", "Magazin", 0))
        assertTrue(ProgrammeText.isSportEvent("Frauen Bundesliga 26/27", "Bayer 04 Leverkusen - Hamburger SV", "Magazin", 0, dazn))
        // Frauen: nicht mit dem Spielplan der Männer verwechseln
        assertEquals(null, LiveScores.leagueOf("Frauen Bundesliga 26/27"))
        // Echte Magazine bleiben draußen
        assertEquals(false, ProgrammeText.isSportEvent("Bundesliga Highlights", "9. Spieltag", "Fußballmagazin", 0, dazn))
    }

    @Test
    fun austrianBundesligaIsNotTheGermanOne() {
        assertEquals("aut.1", LiveScores.leagueOf("Live: Admiral BL"))
        assertEquals("aut.1", LiveScores.leagueOf("Live: Admiral BL: Sonntags-Konferenz"))
        assertEquals("bl2", LiveScores.leagueOf("Live: 2. BL"))
    }

    @Test
    fun daznReplayOfFridayGameIsRecognised() {
        val scores = LiveScores(okhttp3.OkHttpClient())
        val matches = LiveScores.parse(resource("openligadb-bl1-2026-11-07.json"), "bl1")
        // DAZN 1, Samstag 12:05 UTC: das Freitagsspiel (19:30 UTC) noch einmal, ohne Kennzeichnung
        assertTrue(scores.isReplay(matches, "Hamburger SV - Borussia M'gladbach", time("20261107120500 +0000")))
        // Am Freitagabend selbst: live
        assertEquals(false, scores.isReplay(matches, "Hamburger SV - Borussia M'gladbach", time("20261106192000 +0000")))
        // Samstag 14:30: Dortmund spielt wirklich
        assertEquals(false, scores.isReplay(matches, "Borussia Dortmund – SV 07 Elversberg", time("20261107141500 +0000")))
    }
}
