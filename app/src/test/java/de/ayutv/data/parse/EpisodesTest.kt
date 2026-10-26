package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodesTest {
    private fun ep(id: String, season: Int, episode: Int) = XtreamEpisode(id, season, episode, "Folge $episode", "mkv", null, null, 0)

    @Test
    fun keysRoundTrip() {
        val key = Episodes.key("3:s:77", "1234")
        assertEquals("3:s:77", Episodes.seriesKeyOf(key))
        assertEquals("1234", Episodes.episodeIdOf(key))
        assertNull(Episodes.seriesKeyOf("3:v:99"))
        assertNull(Episodes.episodeIdOf("3:v:99"))
    }

    @Test
    fun nextEpisodeAcrossSeasons() {
        val list = listOf(ep("c", 2, 1), ep("a", 1, 1), ep("b", 1, 2))
        assertEquals("b", Episodes.next(list, "a")?.id)
        assertEquals("c", Episodes.next(list, "b")?.id)
        assertNull(Episodes.next(list, "c"))
        assertNull(Episodes.next(list, "x"))
    }

    @Test
    fun introButtonWithoutLearnedIntro() {
        assertFalse(IntroSkip.visible(2_000, null))
        assertTrue(IntroSkip.visible(60_000, null))
        assertFalse(IntroSkip.visible(6 * 60_000L, null))
        assertEquals(60_000 + IntroSkip.DEFAULT_SKIP_MS, IntroSkip.target(60_000, null))
    }

    @Test
    fun learnedIntroIsUsed() {
        val intro = IntroSkip.learn(120_000, 175_000)!!
        assertEquals(55_000L, intro.lengthMs)
        assertTrue(IntroSkip.visible(130_000, intro))
        assertFalse(IntroSkip.visible(200_000, intro))
        // Mitten im Intro: an sein Ende
        assertEquals(175_000L, IntroSkip.target(130_000, intro))
        // Intro beginnt in dieser Folge früher: gelernte Länge ab jetzt
        assertEquals(100_000L + 55_000, IntroSkip.target(100_000, intro))
        // Unplausibel kurz oder lang: nicht lernen
        assertNull(IntroSkip.learn(100_000, 103_000))
        assertNull(IntroSkip.learn(0, 10 * 60_000))
    }
}
