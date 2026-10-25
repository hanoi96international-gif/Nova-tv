package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenQueryTest {
    @Test
    fun numberWords() {
        assertEquals("DAZN 2", SpokenQuery.normalize("DAZN zwei"))
        assertEquals("Sky Bundesliga 1", SpokenQuery.normalize(" Sky  Bundesliga Eins "))
    }

    @Test
    fun spokenChannelPicksBestVariant() {
        val channels = listOf("DE: RTL SD", "DE: RTL HD", "DE: RTL 4K", "DE: RTLZWEI HD", "DE: RTL UP")
        assertEquals("DE: RTL HD", SpokenQuery.channel("RTL", channels) { it })
        assertEquals("DE: RTLZWEI HD", SpokenQuery.channel("RTL zwei", channels) { it })
        assertNull(SpokenQuery.channel("Pro Sieben", channels) { it })
    }

    @Test
    fun spokenTitle() {
        val movies = listOf("DE: Der Pate (1972)", "Der Pate 2", "Fluch der Karibik")
        assertEquals("DE: Der Pate (1972)", SpokenQuery.title("der pate", movies) { it })
        assertEquals("Der Pate 2", SpokenQuery.title("Der Pate zwei", movies) { it })
        assertNull(SpokenQuery.title("Pate", movies) { it })
    }
}
