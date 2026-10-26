package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSubtitlesParsingTest {

    @Test
    fun vipAccountsUseTheirOwnServer() {
        assertEquals("https://vip-api.opensubtitles.com/api/v1", OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"vip-api.opensubtitles.com","status":200}"""))
        assertEquals("https://api.opensubtitles.com/api/v1", OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"api.opensubtitles.com"}"""))
        // Nur Adressen von OpenSubtitles: ein Token geht nie an einen fremden Server.
        assertEquals(null, OpenSubtitlesParsing.loginApi("""{"token":"x","base_url":"evil.example.com"}"""))
        assertEquals(null, OpenSubtitlesParsing.loginApi("""{"token":"x"}"""))
    }

    @Test
    fun rejectedTokenIsRecognised() {
        assertEquals(true, OpenSubtitlesParsing.isTokenProblem(401, "{}"))
        assertEquals(true, OpenSubtitlesParsing.isTokenProblem(403, """{"message":"invalid token"}"""))
        assertEquals(false, OpenSubtitlesParsing.isTokenProblem(406, """{"message":"You have downloaded your allowed 5 subtitles"}"""))
    }

    @Test
    fun hashOfZeroBytesIsFileSize() {
        val zero = ByteArray(MovieHash.CHUNK)
        assertEquals("0000000000020000", MovieHash.compute(131_072L, zero, zero))
    }

    @Test
    fun hashSumsLittleEndianWordsWithOverflow() {
        val head = ByteArray(MovieHash.CHUNK)
        head[0] = 0x01 // erstes Wort = 1
        val tail = ByteArray(MovieHash.CHUNK) { 0xFF.toByte() } // 8192 Wörter à -1
        // size + 1 + 8192 * (-1) als vorzeichenloses 64-Bit-Wort
        val expected = (1_000_000L + 1 - 8192).let { "%016x".format(it) }
        assertEquals(expected, MovieHash.compute(1_000_000L, head, tail))
    }

    @Test
    fun titleSearchPicksTheRelevantFilmNotTheMostPopular() {
        // Wie bei einer Titelsuche: zuerst der gesuchte Film, dahinter ein beliebterer Film mit ähnlichem Namen.
        val body = """{"data":[
          {"attributes":{"language":"id","download_count":50,"feature_details":{"feature_id":1,"year":2009,"feature_type":"Movie"},"files":[{"file_id":11}]}},
          {"attributes":{"language":"id","download_count":90000,"feature_details":{"feature_id":2,"year":2001,"feature_type":"Movie"},"files":[{"file_id":22}]}},
          {"attributes":{"language":"id","download_count":300,"feature_details":{"feature_id":1,"year":2009,"feature_type":"Movie"},"files":[{"file_id":12}]}}
        ]}"""
        val found = OpenSubtitlesParsing.searchResults(body)
        assertEquals(listOf(12L, 11L), OpenSubtitlesParsing.bestFeature(found, 2009).map { it.fileId })
        assertEquals(listOf(12L, 11L), OpenSubtitlesParsing.bestFeature(found, null).map { it.fileId })
        // Jahr passt zu keinem Treffer: lieber nichts als ein falscher Film.
        assertEquals(emptyList<Long>(), OpenSubtitlesParsing.bestFeature(found, 1985).map { it.fileId })
    }

    @Test
    fun readsTmdbIdOfFeature() {
        val body = """{"data":[{"attributes":{"language":"es","feature_details":{"tmdb_id":767,"title":"Harry Potter"},
            "files":[{"file_id":1}]}},{"attributes":{"language":"es","feature_details":{"tmdb_id":9999},"files":[{"file_id":2}]}}]}"""
        assertEquals(listOf(767L, 9999L), OpenSubtitlesParsing.searchResults(body).map { it.tmdbId })
    }

    @Test
    fun ranksHashMatchesFirstAndSkipsBrokenEntries() {
        val body = """
            {"total_count":3,"data":[
              {"id":"1","attributes":{"language":"en","download_count":9000,"hearing_impaired":false,"moviehash_match":false,
                "release":"Popular.Release","files":[{"file_id":111,"file_name":"a.srt"}]}},
              {"id":"2","attributes":{"language":"en","download_count":10,"moviehash_match":true,"hearing_impaired":true,
                "files":[{"file_id":222,"file_name":"b.srt"}]}},
              {"id":"3","attributes":{"language":"en","files":[]}}
            ]}
        """.trimIndent()
        val results = OpenSubtitlesParsing.searchResults(body)
        assertEquals(listOf(222L, 111L), results.map { it.fileId })
        assertEquals("b.srt", results[0].release)
    }

    @Test
    fun readsDownloadLinkAndToken() {
        assertEquals("https://dl.example/x.srt", OpenSubtitlesParsing.downloadLink("""{"link":"https://dl.example/x.srt","remaining":4}"""))
        assertEquals("abc", OpenSubtitlesParsing.loginToken("""{"token":"abc","status":200}"""))
        assertNull(OpenSubtitlesParsing.downloadLink("""{"message":"quota"}"""))
    }

    @Test
    fun cleansIptvTitles() {
        assertEquals("Inception" to 2010, OpenSubtitlesParsing.cleanTitle("DE - Inception (2010) [4K]"))
        assertEquals("The Matrix" to 1999, OpenSubtitlesParsing.cleanTitle("The.Matrix.1999.2160p.HDR"))
        assertEquals("Oppenheimer" to null, OpenSubtitlesParsing.cleanTitle("EN | Oppenheimer UHD"))
        // So nennt ein Anbieter seine 4K-Filme: Der Vorsatz darf nicht in die Suche geraten.
        assertEquals("Harry Potter und der Halbblutprinz" to null, OpenSubtitlesParsing.cleanTitle("4K-DE - Harry Potter und der Halbblutprinz"))
        assertEquals("Dune" to 2021, OpenSubtitlesParsing.cleanTitle("[DE] |MULTI| Dune (2021)"))
    }

    @Test
    fun episodesFromTitles() {
        assertEquals(Triple("Dark", 1, 2), OpenSubtitlesParsing.episodeOf("Dark – S1 E2"))
        assertEquals(Triple("Breaking Bad", 3, 10), OpenSubtitlesParsing.episodeOf("DE - Breaking Bad S03E10"))
        assertEquals(Triple("Friends", 2, 5), OpenSubtitlesParsing.episodeOf("Friends 2x05"))
        assertNull(OpenSubtitlesParsing.episodeOf("Dune (2021)"))
    }

    @Test
    fun frameRateAndLength() {
        val a = SubtitleCandidate(1, "de", "a", hashMatch = false, downloads = 900, hearingImpaired = false, fps = 25.0)
        val b = SubtitleCandidate(2, "de", "b", hashMatch = false, downloads = 100, hearingImpaired = false, fps = 23.976)
        val c = SubtitleCandidate(3, "de", "c", hashMatch = true, downloads = 1, hearingImpaired = false, fps = 25.0)
        // Hash-Treffer bleibt vorn
        assertEquals(listOf(3L, 1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(c, a, b), 23.976f).map { it.fileId })
        // Selten geladene Datei mit passender Bildrate verdrängt die oft geladene nicht (meist falsche Fassung)
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b), 23.976f).map { it.fileId })
        // Fast gleich oft geladen: die mit passender Bildrate
        val b2 = b.copy(downloads = 600)
        assertEquals(listOf(2L, 1L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b2), 23.976f).map { it.fileId })
        // Maschinell übersetzt zählt nicht als gleich gut
        val b3 = b2.copy(translated = true)
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b3), 23.976f).map { it.fileId })
        assertEquals(listOf(1L, 2L), OpenSubtitlesParsing.preferFrameRate(listOf(a, b), null).map { it.fileId })

        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHallo\n\n2\n01:52:10,250 --> 01:52:12,040\nEnde\n"
        assertEquals(((1 * 60 + 52) * 60 + 12) * 1000L + 40, OpenSubtitlesParsing.lastCueEndMs(srt))
        // 115-Minuten-Film: Datei bis 112 min passt, bis 60 min (andere Fassung/Film) oder 140 min nicht
        assertEquals(true, OpenSubtitlesParsing.plausibleLength(112 * 60_000L, 115 * 60_000L))
        assertEquals(false, OpenSubtitlesParsing.plausibleLength(60 * 60_000L, 115 * 60_000L))
        assertEquals(false, OpenSubtitlesParsing.plausibleLength(140 * 60_000L, 115 * 60_000L))
    }
}
