package de.ayutv.desktop.data

import de.ayutv.data.sport.Crests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrestsTest {
    private val openLiga = """[
        {"teamId":7,"teamName":"Borussia Dortmund","shortName":"Dortmund","teamIconUrl":"https://example.org/bvb.png"},
        {"teamId":9,"teamName":"FC Schalke 04","shortName":"Schalke","teamIconUrl":"https://example.org/s04.png"},
        {"teamId":55,"teamName":"Hannover 96","shortName":"Hannover","teamIconUrl":"https://example.org/h96.png"}
    ]"""

    @Test
    fun germanClubsByAnySpelling() {
        val teams = Crests.parseOpenLigaTeams(openLiga)
        assertEquals("https://example.org/bvb.png", Crests.match("Bor. Dortmund", teams))
        assertEquals("https://example.org/bvb.png", Crests.match("Borussia Dortmund", teams))
        assertEquals("https://example.org/s04.png", Crests.match("Schalke 04", teams))
        assertEquals("https://example.org/h96.png", Crests.match("Hannover 96", teams))
        assertNull(Crests.match("Hannover 96 II", teams))
        assertNull(Crests.match("Arsenal", teams))
    }

    @Test
    fun sportsDbFootballOnly() {
        val body = """{"teams":[
            {"strTeam":"Arsenal","strSport":"Soccer","strBadge":"https://r2.example.org/arsenal.png"},
            {"strTeam":"Arsenal Tula","strSport":"Soccer","strBadge":"https://r2.example.org/tula.png"}
        ]}"""
        assertEquals("https://r2.example.org/arsenal.png", Crests.parseSportsDb(body, "Arsenal"))
        assertNull(Crests.parseSportsDb("""{"teams":null}""", "Arsenal"))
        assertNull(Crests.parseSportsDb("""{"teams":[{"strTeam":"Arsenal","strSport":"Basketball","strBadge":"https://x/y.png"}]}""", "Arsenal"))
    }

    @Test
    fun remembersResultsInFile() {
        val file = java.nio.file.Files.createTempFile("crests", ".tsv").toFile()
        file.writeText("borussia dortmund\thttps://example.org/bvb.png\narsenal\t\n")
        val crests = Crests(okhttp3.OkHttpClient(), file)
        assertEquals("https://example.org/bvb.png", crests.cached("Borussia Dortmund"))
        assertNull(crests.cached("Arsenal"))
    }
}
