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
}
