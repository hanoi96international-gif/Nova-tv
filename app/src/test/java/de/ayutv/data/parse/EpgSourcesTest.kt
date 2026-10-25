package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgSourcesTest {
    @Test
    fun m3uFromXtreamServerGetsItsGuide() {
        assertEquals(
            "http://tv.example.com:8080/xmltv.php?username=max&password=g%26heim",
            EpgSources.xtreamGuideFor("http://tv.example.com:8080/get.php?username=max&password=g%26heim&type=m3u_plus&output=ts"),
        )
        assertEquals(
            "https://example.org/panel/xmltv.php?username=a&password=b",
            EpgSources.xtreamGuideFor("https://example.org/panel/get.php?type=m3u&username=a&password=b"),
        )
    }

    @Test
    fun otherPlaylistsHaveNoDerivedGuide() {
        assertNull(EpgSources.xtreamGuideFor("http://example.com/list.m3u"))
        assertNull(EpgSources.xtreamGuideFor("http://example.com/get.php?type=m3u"))
        assertNull(EpgSources.xtreamGuideFor("/sdcard/Download/liste.m3u"))
    }

    @Test
    fun ownAddressesFirstThenProvider() {
        assertEquals(
            listOf("http://a/epg.xml.gz", "http://b/epg.xml", "http://h/xmltv.php?username=u&password=p"),
            EpgSources.forM3u("http://a/epg.xml.gz, http://b/epg.xml", "http://h/get.php?username=u&password=p"),
        )
        assertEquals(listOf("http://x/xmltv.php"), EpgSources.forXtream(null, "http://x/xmltv.php"))
        assertEquals(listOf("http://own", "http://x/xmltv.php"), EpgSources.forXtream(" http://own\n", "http://x/xmltv.php"))
        assertEquals(listOf("http://x/xmltv.php"), EpgSources.forXtream("http://x/xmltv.php", "http://x/xmltv.php"))
        assertEquals(emptyList<String>(), EpgSources.forM3u("", "http://example.com/list.m3u"))
    }

    @Test
    fun labels() {
        assertEquals("epgshare01 DE", EpgSources.label(EpgSources.PUBLIC[0]))
        assertEquals("tv.example.com", EpgSources.label("http://tv.example.com:8080/xmltv.php?username=a&password=b"))
    }
}
