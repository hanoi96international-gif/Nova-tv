package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraGuidesTest {
    @Test
    fun magentaToXmltv() {
        val channels = """{"channellist":[{"contentId":"1","name":"Das Erste HD","extra":{"a":1}},{"contentId":"2","name":"Warner TV Serie (Sky)"},{"contentId":"3","name":"Testkanal"}]}"""
        val day = """{"playbilllist":[
            {"channelid":"1","name":"Tagesschau","starttime":"2026-11-01 19:00:00 UTC+00:00","endtime":"2026-11-01 19:15:00 UTC+00:00","introduce":"Nachrichten & mehr","genres":"Nachrichten,Info","ignored":[1,2]},
            {"channelid":"1","name":"Tagesschau","starttime":"2026-11-01 19:00:00 UTC+00:00","endtime":"2026-11-01 19:15:00 UTC+00:00"},
            {"channelid":"2","name":"Momentan kein Programm","starttime":"2026-11-01 19:00:00 UTC+00:00","endtime":"2026-11-01 20:00:00 UTC+00:00"},
            {"channelid":9,"name":"Unbekannt","starttime":"2026-11-01 19:00:00 UTC+00:00","endtime":"2026-11-01 20:00:00 UTC+00:00"}
        ]}"""
        val xml = ExtraGuides.magentaXmltv(channels, listOf(day, "kaputt"))
        assertTrue(xml, xml.contains("<channel id=\"Das.Erste.HD.de@MagentaTV\"><display-name>Das Erste HD</display-name></channel>"))
        assertTrue(xml, xml.contains("<display-name>Warner TV Serie</display-name>"))
        assertTrue(xml, !xml.contains("Testkanal"))
        assertEquals(1, Regex("<programme ").findAll(xml).count())
        assertTrue(xml, xml.contains("start=\"20261101190000 +0000\""))
        assertTrue(xml, xml.contains("<desc>Nachrichten &amp; mehr</desc><category>Nachrichten</category>"))
        assertTrue(xml, xml.trimEnd().endsWith("</tv>"))
    }

    @Test
    fun dynBlocksOverlappingGames() {
        val h = 3_600_000L
        val xml = ExtraGuides.dynXmltv(
            mapOf(
                "Volleyball" to listOf(
                    ExtraGuides.DynEvent("a", "BR Volleys – Düren", "Bundesliga", 10 * h, 12 * h),
                    ExtraGuides.DynEvent("b", "Lüneburg – Giesen", "Bundesliga", 11 * h, 13 * h),
                    ExtraGuides.DynEvent("c", "Haching – Herrsching", null, 14 * h, 16 * h),
                ),
            ),
        )
        assertEquals(2, Regex("channel=\"Dyn\\.Volleyball\\.de@Dyn\"").findAll(xml).count())
        assertTrue(xml, xml.contains("Volleyball: BR Volleys – Düren · Lüneburg – Giesen"))
        assertTrue(xml, xml.contains("<display-name>Dyn Volleyball</display-name>"))
        // Alle Sportarten zusammen für „Dyn 3“ usw.
        assertTrue(xml, xml.contains("<channel id=\"Dyn.de@Dyn\">"))
        assertEquals(2, Regex("channel=\"Dyn\\.de@Dyn\"").findAll(xml).count())
    }

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
}
