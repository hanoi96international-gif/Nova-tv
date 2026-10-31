package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingAppsTest {
    private fun ids(list: List<StreamingApps.App>) = list.map { it.id }

    @Test
    fun defaultOrderHasAllFour() {
        assertEquals(listOf("netflix", "youtube", "prime", "spotify"), ids(StreamingApps.arrange(emptyList(), emptySet())))
    }

    @Test
    fun savedOrderFirstNewOnesBehindHiddenGone() {
        assertEquals(listOf("spotify", "netflix", "prime"), ids(StreamingApps.arrange(listOf("spotify", "netflix", "unbekannt"), setOf("youtube"))))
    }

    @Test
    fun moveForwardAndBack() {
        val forward = StreamingApps.move(emptyList(), "prime", -1)
        assertEquals(listOf("netflix", "prime", "youtube", "spotify"), forward)
        assertEquals(listOf("netflix", "prime", "youtube", "spotify"), StreamingApps.move(forward, "netflix", -1))
        assertEquals(listOf("netflix", "youtube", "prime", "spotify"), StreamingApps.move(forward, "prime", 1))
    }

    @Test
    fun findsAppsByTheirName() {
        val youtube = StreamingApps.ALL.first { it.id == "youtube" }
        val prime = StreamingApps.ALL.first { it.id == "prime" }
        assertEquals(0, StreamingApps.labelMatch(youtube, "YouTube"))
        assertEquals(null, StreamingApps.labelMatch(youtube, "YouTube Kids"))
        assertEquals(null, StreamingApps.labelMatch(youtube, "YouTube Music"))
        assertEquals(null, StreamingApps.labelMatch(youtube, "YouTube TV"))
        assertEquals(0, StreamingApps.labelMatch(prime, "Prime Video"))
        assertEquals(0, StreamingApps.labelMatch(prime, "Amazon Prime Video"))
        assertEquals(null, StreamingApps.labelMatch(prime, "Prime Gaming"))
    }

    @Test
    fun everyAppHasTvPhoneAndWeb() {
        StreamingApps.ALL.forEach { a ->
            assertTrue(a.name, a.tvPackages.isNotEmpty() && a.phonePackages.isNotEmpty() && a.web.startsWith("https://"))
        }
    }
}
