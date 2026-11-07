package de.ayutv.desktop.data

import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.sport.EspnScores
import de.ayutv.data.sport.LiveScores
import de.ayutv.data.sport.MatchHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EspnScoresTest {
    private val body = """
        {"events":[
          {"id":"401","date":"2026-11-08T00:00Z","competitions":[{
            "venue":{"fullName":"Scotiabank Arena","address":{"city":"Toronto"}},
            "status":{"type":{"state":"in","completed":false,"shortDetail":"2nd 12:34"}},
            "competitors":[
              {"homeAway":"away","score":"1","team":{"displayName":"Boston Bruins","shortDisplayName":"Bruins","logo":"https://a.espncdn.com/bos.png"}},
              {"homeAway":"home","score":"2","team":{"displayName":"Toronto Maple Leafs","shortDisplayName":"Maple Leafs","logo":"https://a.espncdn.com/tor.png"}}
            ]}]},
          {"id":"402","date":"2026-11-08T00:30Z","competitions":[{
            "status":{"type":{"state":"pre","completed":false,"shortDetail":"7:30 PM"}},
            "competitors":[
              {"homeAway":"home","score":"0","team":{"displayName":"New York Rangers","shortDisplayName":"Rangers"}},
              {"homeAway":"away","score":"0","team":{"displayName":"Montreal Canadiens","shortDisplayName":"Canadiens"}}
            ]}]}
        ]}
    """.trimIndent()

    @Test
    fun parsesNhlScoreboard() {
        val list = EspnScores.parse(body, "nhl")
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("Toronto Maple Leafs – Boston Bruins", first.pairing)
        assertEquals("2:1", first.score)
        assertEquals("Scotiabank Arena", first.stadium)
        assertEquals("https://a.espncdn.com/tor.png", first.icon1)
        assertTrue(first.live(0))
        assertEquals("2nd 12:34", first.minute(0))
        assertFalse(first.football)
        assertFalse(list[1].live(Long.MAX_VALUE / 2))
    }

    @Test
    fun liveNhlGetsThePairing() {
        val list = EspnScores.parse(body, "nhl")
        MatchHints.install(LiveScores(okhttp3.OkHttpClient()))
        MatchHints.update(list)
        val start = list[0].kickoff - 10 * 60_000L
        val stop = start + 3 * 3_600_000L
        // Teams in der Beschreibung
        assertEquals(
            "Toronto Maple Leafs – Boston Bruins (Live: NHL)",
            ProgrammeText.display("Live: NHL", null, "Die Maple Leafs empfangen die Boston Bruins.", "Sport", start, stop),
        )
        // Arena in der Beschreibung
        assertEquals(
            "New York Rangers – Montreal Canadiens (Live: NHL)",
            ProgrammeText.display("Live: NHL", null, "Aus dem Madison Square Garden in New York: Rangers empfangen Montreal.", "Sport", start + 30 * 60_000L, stop),
        )
        assertEquals("nhl", LiveScores.leagueOf("Eishockey: NHL"))
        assertEquals("eng.1", LiveScores.leagueOf("Live: PL"))
        assertEquals("bl2", LiveScores.leagueOf("Live: 2. BL"))
    }
}
