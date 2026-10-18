package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenSubtitlesParsingTest {

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
    }
}
