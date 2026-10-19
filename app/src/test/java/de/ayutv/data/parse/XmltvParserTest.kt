package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.kxml2.io.KXmlParser

class XmltvParserTest {

    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="daserste.de"><display-name>Das Erste HD</display-name><icon src="x"/></channel>
          <programme start="20240101200000 +0100" stop="20240101201500 +0100" channel="daserste.de">
            <title lang="de">Tagesschau</title>
            <desc lang="de">Nachrichten &amp; Wetter</desc>
            <category>News</category>
            <credits><director>Jemand</director></credits>
          </programme>
          <programme start="20240101201500 +0100" stop="20240101214500 +0100" channel="daserste.de">
            <sub-title>ohne Titel</sub-title>
          </programme>
          <programme start="20240101200000 +0000" stop="20240101210000 +0000" channel="unbekannt">
            <title>Wird gefiltert</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesProgrammesAndChannelNames() {
        val parser = XmltvParser(KXmlParser())
        val result = mutableListOf<XmltvProgramme>()
        parser.parse(xml.byteInputStream(), wanted = { id, _, _ -> id == "daserste.de" }) { result += it }

        assertEquals(2, result.size)
        val first = result[0]
        assertEquals("Tagesschau", first.title)
        assertEquals("Nachrichten & Wetter", first.description)
        assertEquals("News", first.category)
        assertEquals(1704135600000L, first.start) // 2024-01-01T19:00:00Z
        assertEquals(15 * 60_000L, first.stop - first.start)
        assertEquals("", result[1].title)
        assertNull(result[1].description)
        assertEquals("daserste.de", parser.channelNames[XmltvParser.normalizeName("das erste")])
    }

    @Test
    fun parsesTimestampsWithAndWithoutOffset() {
        assertEquals(0L, XmltvTime.parse("19700101000000 +0000"))
        assertEquals(0L, XmltvTime.parse("19700101000000"))
        assertEquals(3_600_000L, XmltvTime.parse("19700101000000 -0100"))
        // Ohne Sekunden und mit Doppelpunkt in der Zeitzone (kommt bei vielen Anbietern vor)
        assertEquals(-3_600_000L, XmltvTime.parse("197001010000 +0100"))
        assertEquals(-3_600_000L, XmltvTime.parse("19700101000000 +01:00"))
        assertEquals(-5_400_000L, XmltvTime.parse("19700101000000 +0130"))
        assertEquals(951_782_400_000L, XmltvTime.parse("20000229000000 +0000"))
        assertNull(XmltvTime.parse("kaputt"))
    }
}
