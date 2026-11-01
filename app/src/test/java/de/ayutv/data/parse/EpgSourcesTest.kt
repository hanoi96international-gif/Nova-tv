package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgSourcesTest {
    @Test
    fun sourcesByCountry() {
        val gr = EpgSources.SOURCES.first { "GR1" in it.url }.url
        assertEquals(false, EpgSources.relevant(gr, listOf("DE: RTL HD", "Sky Sport 1")))
        assertEquals(true, EpgSources.relevant(gr, listOf("DE: RTL HD", "GR: MAGENTA SERIES HD")))
        assertEquals(true, EpgSources.relevant(EpgSources.PUBLIC.first(), listOf("Sky Sport 1")))
        assertEquals(true, EpgSources.relevant("http://anbieter.example/xmltv.php", emptyList()))
        assertEquals("plutotv-de.xml.gz", EpgSources.cacheName("https://i.mjh.nz/PlutoTV/de.xml.gz"))
        assertEquals("samsungtvplus-de.xml.gz", EpgSources.cacheName("https://i.mjh.nz/SamsungTVPlus/de.xml.gz"))
        assertEquals("epg_ripper_DE1.xml.gz", EpgSources.cacheName(EpgSources.PUBLIC.first()))
    }

    @Test
    fun noMagentaOrDynAnymore() {
        assertEquals(false, EpgSources.PUBLIC.any { "magentatv" in it || "dyn.sport" in it })
    }
}
