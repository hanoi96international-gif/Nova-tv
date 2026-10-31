package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeGenreTest {
    @Test
    fun fromCategory() {
        assertEquals(ProgrammeGenre.SPORT, ProgrammeGenre.of("Bayern – Dortmund", "Sport, Fußball"))
        assertEquals(ProgrammeGenre.FILM, ProgrammeGenre.of("Inception", "Spielfilm"))
        assertEquals(ProgrammeGenre.SERIES, ProgrammeGenre.of("Tatort", "Krimiserie"))
        assertEquals(ProgrammeGenre.KIDS, ProgrammeGenre.of("Paw Patrol", "Kinderserie"))
        assertEquals(ProgrammeGenre.NEWS, ProgrammeGenre.of("Tagesschau", "Nachrichten"))
        assertEquals(ProgrammeGenre.DOCU, ProgrammeGenre.of("Terra X", "Dokumentation"))
    }

    @Test
    fun fromChannelWhenNoCategory() {
        assertEquals(ProgrammeGenre.SPORT, ProgrammeGenre.of("Live", null, "Sky Sport Bundesliga 1"))
        assertEquals(ProgrammeGenre.NEWS, ProgrammeGenre.of("Am Morgen", null, "n-tv HD"))
        assertEquals(ProgrammeGenre.OTHER, ProgrammeGenre.of("Irgendwas", null, "RTL"))
    }
}
