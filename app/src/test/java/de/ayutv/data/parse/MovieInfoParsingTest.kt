package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MovieInfoParsingTest {
    private fun parse(json: String) = XtreamParsing.movieInfo(json.byteInputStream())

    @Test
    fun typicalPanel() {
        val info = parse(
            """{"info":{"plot":"Ein Film.","cast":"A, B","director":"C","genre":"Drama","releasedate":"2019-05-01",
               "duration_secs":"6720","duration":"01:52:00","rating":"7.84","youtube_trailer":"https://www.youtube.com/watch?v=dQw4w9WgXcQ",
               "backdrop_path":["https://img/b.jpg"],"movie_image":"https://img/p.jpg"},"movie_data":{"stream_id":1}}"""
        )!!
        assertEquals("Ein Film.", info.plot)
        assertEquals(2019, info.year)
        assertEquals(112, info.durationMinutes)
        assertEquals("7.8", info.rating)
        assertEquals("dQw4w9WgXcQ", info.trailer)
        assertEquals("https://img/b.jpg", info.backdrop)
    }

    @Test
    fun sparsePanel() {
        val info = parse("""{"info":{"duration":"95 min","rating":"0","youtube_trailer":"","backdrop_path":[]}}""")!!
        assertEquals(95, info.durationMinutes)
        assertNull(info.rating)
        assertNull(info.trailer)
        assertNull(info.backdrop)
        assertNull(parse("""{"info":[]}"""))
    }

    @Test
    fun helpers() {
        assertEquals("dQw4w9WgXcQ", XtreamParsing.youtubeId("dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", XtreamParsing.youtubeId("https://youtu.be/dQw4w9WgXcQ?t=3"))
        assertEquals(125, XtreamParsing.parseMinutes("02:05:10"))
        assertEquals("1", de.ayutv.data.repo.XtreamClient.movieIdOf("http://h:80/movie/u/p/1.mkv"))
    }
}
