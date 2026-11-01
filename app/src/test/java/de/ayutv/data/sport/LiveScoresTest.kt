package de.ayutv.data.sport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScoresTest {
    // Gekürzt aus https://api.openligadb.de/getmatchdata/bl1
    private val body = """
        [{"matchID":72001,"matchDateTimeUTC":"2026-10-31T14:30:00Z","leagueShortcut":"bl1",
          "team1":{"teamId":7,"teamName":"Borussia Dortmund","shortName":"Dortmund"},
          "team2":{"teamId":91,"teamName":"Eintracht Frankfurt","shortName":"Frankfurt"},
          "matchIsFinished":false,"matchResults":[],
          "goals":[{"goalID":1,"scoreTeam1":1,"scoreTeam2":0,"matchMinute":12,"goalGetterName":"Guirassy"},
                   {"goalID":3,"scoreTeam1":2,"scoreTeam2":1,"matchMinute":67,"goalGetterName":"Adeyemi"},
                   {"goalID":2,"scoreTeam1":1,"scoreTeam2":1,"matchMinute":40,"goalGetterName":"Marmoush"}]},
         {"matchID":72002,"matchDateTimeUTC":"2026-10-30T19:30:00Z",
          "team1":{"teamName":"FC Bayern München","shortName":"Bayern"},
          "team2":{"teamName":"1. FC Köln","shortName":"Köln"},
          "matchIsFinished":true,
          "matchResults":[{"resultTypeID":1,"pointsTeam1":1,"pointsTeam2":0},{"resultTypeID":2,"pointsTeam1":3,"pointsTeam2":0}],
          "goals":[]},
         {"broken":true}]
    """.trimIndent()

    @Test
    fun parsesScoresAndScorers() {
        val matches = LiveScores.parse(body, "bl1")
        assertEquals(2, matches.size)
        val bvb = matches[0]
        assertEquals("2:1", bvb.score)
        assertEquals(3, bvb.goalCount)
        assertEquals("Adeyemi 67'", bvb.lastGoal)
        assertFalse(bvb.finished)
        assertEquals("3:0", matches[1].score)
        assertTrue(matches[1].finished)
    }

    @Test
    fun minuteAndMatchingToTvPairings() {
        val bvb = LiveScores.parse(body, "bl1")[0]
        val kickoff = bvb.kickoff
        assertEquals("30'", bvb.minute(kickoff + 30 * 60_000))
        assertEquals("HZ", bvb.minute(kickoff + 52 * 60_000))
        assertEquals("70'", bvb.minute(kickoff + 85 * 60_000))
        assertNull(bvb.minute(kickoff - 60_000))
        assertTrue(bvb.matches("Borussia Dortmund – Eintracht Frankfurt"))
        assertTrue(bvb.matches("Fußball: Bundesliga: Dortmund - Frankfurt"))
        assertFalse(bvb.matches("Borussia Dortmund – FC Bayern München"))
        val bayern = LiveScores.parse(body, "bl1")[1]
        assertTrue(bayern.matches("FC Bayern – 1. FC Köln"))
        val scores = LiveScores(okhttp3.OkHttpClient())
        assertNotNull(scores.find(listOf(bvb), "Dortmund – Frankfurt", kickoff - 30 * 60_000))
        assertNull(scores.find(listOf(bvb), "Dortmund – Frankfurt", kickoff + 2 * 86_400_000L))
        // Frauenspiel derselben Vereine passt nicht
        assertFalse(bvb.matches("Borussia Dortmund Frauen – Eintracht Frankfurt Frauen"))
    }

    /** Sky nennt Einzelspiele oft nur „Live: 2. BL“ und in der Beschreibung das Stadion. */
    @Test
    fun findsMatchByVenue() {
        val kickoff = 1_761_850_800_000L // 18:30
        fun m(id: Long, t1: String, t2: String, city: String?, stadium: String?, league: String = "bl2") = LiveScores.Match(
            id, league, t1, t1, t2, t2, kickoff, 0, 0, false, 0, null, city, stadium,
        )
        val matches = listOf(
            m(1, "Arminia Bielefeld", "SC Paderborn 07", "Bielefeld", "SchücoArena"),
            m(2, "Hertha BSC", "1. FC Kaiserslautern", "Berlin", "Olympiastadion"),
            m(3, "Hannover 96", "Karlsruher SC", "Hannover", "Heinz von Heiden Arena"),
            m(4, "FC Bayern München", "Borussia Dortmund", "München", "Allianz Arena", league = "bl1"),
        )
        val start = kickoff - 30 * 60_000L
        val stop = kickoff + 2 * 3_600_000L
        val scores = LiveScores(okhttp3.OkHttpClient())
        assertEquals(1L, scores.byVenue(matches, "Live: 2. BL", null, "Aus der Schüco-Arena in Bielefeld.", start, stop)?.id)
        assertEquals(3L, scores.byVenue(matches, "Live: 2. BL", null, "Aus der Heinz von Heiden Arena.", start, stop)?.id)
        // Nur nach Verein, ohne Stadion
        assertEquals(2L, scores.byVenue(matches, "Live: 2. BL", null, "Der 1. FC Kaiserslautern gastiert in Berlin.", start, stop)?.id)
        // Konferenz: mehrere Spiele, keine Zuordnung
        assertNull(scores.byVenue(matches, "Live: 2. BL: Freitags-Konferenz – 10. Spieltag", null, "Aus Bielefeld und Berlin.", start, stop))
        // Andere Liga: nicht das Zweitligaspiel
        assertNull(scores.byVenue(matches, "Live: Bundesliga", null, "Aus der Schüco-Arena in Bielefeld.", start, stop))
        // Kein Hinweis im Text: keins
        assertNull(scores.byVenue(matches, "Live: 2. BL", null, "Spannung pur am Freitagabend.", start, stop))
        assertEquals("bl2", LiveScores.leagueOf("Live: 2. BL"))
        assertEquals("bl3", LiveScores.leagueOf("3. Liga: Spieltag"))
        assertEquals("bl1", LiveScores.leagueOf("Bundesliga live"))
    }

    /** Foto vom 1.11.: drei Zweitliga-Spiele um 13:30, eines nur mit „Aus dem Millerntor-Stadion“ ohne Ort im Spielplan. */
    @Test
    fun remainingMatchByElimination() {
        val kickoff = 1_761_999_000_000L
        fun m(id: Long, t1: String, t2: String, league: String = "bl2") = LiveScores.Match(id, league, t1, t1, t2, t2, kickoff, 0, 0, false, 0, null)
        val hannover = m(1, "Hannover 96", "Dynamo Dresden")
        val fuerth = m(2, "SpVgg Greuther Fürth", "Holstein Kiel")
        val third = m(3, "FC St. Pauli", "Hertha BSC")
        val bl1 = m(4, "FC Bayern München", "Borussia Dortmund", league = "bl1")
        val matches = listOf(hannover, fuerth, third, bl1)
        val scores = LiveScores(okhttp3.OkHttpClient())
        val start = kickoff - 30 * 60_000L
        val stop = kickoff + 2 * 3_600_000L
        val open = listOf(
            LiveScores.Open("Live: 2. BL", null, start, stop),
            LiveScores.Open("Live: 2. BL: Sonntags-Konferenz – 10. Spieltag", null, start, stop),
        )
        // Hannover und Fürth sind über ihre Paarung zugeordnet: das übrige Zweitliga-Spiel gehört zur offenen Sendung.
        assertEquals(mapOf(0 to third), scores.byElimination(matches, open, setOf(hannover, fuerth)))
        // Zwei offene Einzelspiel-Sendungen, zwei übrige Spiele: nicht raten
        val two = listOf(LiveScores.Open("Live: 2. BL", null, start, stop), LiveScores.Open("Live: 2. BL", "Topspiel", start, stop))
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, two, setOf(hannover)))
        // Zwei offene Sendungen, ein übriges Spiel: auch nicht (beide könnten es sein)
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, two, setOf(hannover, fuerth)))
        // Ohne Liga im Titel: nichts
        assertEquals(emptyMap<Int, LiveScores.Match>(), scores.byElimination(matches, listOf(LiveScores.Open("Fußball live", null, start, stop)), setOf(hannover, fuerth)))
    }
}
