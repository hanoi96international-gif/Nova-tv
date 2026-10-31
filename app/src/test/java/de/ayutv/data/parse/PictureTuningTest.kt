package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureTuningTest {
    @Test
    fun recognisesContent() {
        assertEquals(PictureTuning.Content.FOOTBALL, PictureTuning.classify("DE: SKY SPORT BUNDESLIGA 1 HD", "Borussia Dortmund – FC Bayern München", "Fußball: Bundesliga", "Sport"))
        assertEquals(PictureTuning.Content.FOOTBALL, PictureTuning.classify("DAZN 1 HD", "Live: UEFA Champions League", null, "Fußball"))
        assertEquals(PictureTuning.Content.SPORT, PictureTuning.classify("Eurosport 1 HD", "Tennis: ATP Finals", null, "Sport"))
        assertEquals(PictureTuning.Content.SPORT, PictureTuning.classify("Dyn Volleyball", "Volleyball: Dresdner SC – VfB Suhl", null, "Sport"))
        assertEquals(PictureTuning.Content.SPORT, PictureTuning.classify("Dyn Handball", "Handball-Bundesliga: TBV Lemgo Lippe – SC Magdeburg", null, "Sport"))
        assertEquals(PictureTuning.Content.FOOTBALL, PictureTuning.classify("DE: SKY SPORT TOP EVENT", null))
        assertEquals(PictureTuning.Content.FILM, PictureTuning.classify("Sky Cinema Action HD", "John Wick", null, "Spielfilm"))
        assertEquals(PictureTuning.Content.FILM, PictureTuning.classify("Sky Cinema Premiere HD"))
        assertEquals(PictureTuning.Content.SERIES, PictureTuning.classify("Warner TV Serie", "The Big Bang Theory", "Folge 3", "Serie"))
        assertEquals(PictureTuning.Content.NEWS, PictureTuning.classify("Das Erste HD", "Tagesschau", null, "Nachrichten"))
        assertEquals(PictureTuning.Content.FILM, PictureTuning.classify("Inception", vodMovie = true))
        assertEquals(PictureTuning.Content.SERIES, PictureTuning.classify("Dark S01E02", vodEpisode = true))
    }

    @Test
    fun profilesAndLimits() {
        assertNull(PictureTuning.profile(PictureTuning.Content.SPORT, PictureTuning.Mode.OFF))
        val football = PictureTuning.profile(PictureTuning.Content.FOOTBALL, PictureTuning.Mode.AUTO)!!
        val film = PictureTuning.profile(PictureTuning.Content.FILM, PictureTuning.Mode.AUTO)!!
        assertTrue(football.sharpen > film.sharpen && football.saturation > film.saturation)
        assertEquals(0f, film.saturation)
        val strong = PictureTuning.profile(PictureTuning.Content.FOOTBALL, PictureTuning.Mode.STRONG)!!
        assertTrue(strong.sharpen > football.sharpen)
        assertTrue(PictureTuning.eligible(1080, hdr = false))
        assertTrue(PictureTuning.eligible(0, hdr = false))
        assertFalse(PictureTuning.eligible(2160, hdr = false))
        assertFalse(PictureTuning.eligible(1080, hdr = true))
        assertEquals(PictureTuning.Mode.AUTO, PictureTuning.Mode.of(null))
        assertEquals(PictureTuning.Mode.STRONG, PictureTuning.Mode.of("STRONG"))
    }
}
