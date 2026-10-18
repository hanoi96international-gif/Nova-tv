package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private fun parse(text: String): Pair<M3uHeader, List<M3uEntry>> {
        val entries = mutableListOf<M3uEntry>()
        val header = M3uParser.parse(text.reader().buffered()) { entries += it }
        return header to entries
    }

    @Test
    fun parsesAttributesNameAndEpgHeader() {
        val (header, entries) = parse(
            """
            #EXTM3U url-tvg="http://epg.example/a.xml.gz,http://epg.example/b.xml"
            #EXTINF:-1 tvg-id="daserste.de" tvg-name="Das Erste" tvg-logo="http://logo/ard.png" group-title="DE | Vollprogramm" tvg-chno="1",Das Erste HD
            http://host/live/u/p/1.ts
            """.trimIndent()
        )
        assertEquals(listOf("http://epg.example/a.xml.gz", "http://epg.example/b.xml"), header.epgUrls)
        val e = entries.single()
        assertEquals("Das Erste HD", e.name)
        assertEquals("daserste.de", e.tvgId)
        assertEquals("http://logo/ard.png", e.logo)
        assertEquals("DE | Vollprogramm", e.groupTitle)
        assertEquals(1, e.number)
        assertFalse(e.isVod)
    }

    @Test
    fun commaInsideQuotedAttributeDoesNotSplitName() {
        val (_, entries) = parse(
            """
            #EXTM3U
            #EXTINF:-1 group-title="Sport, Fußball",Sky Sport 1
            http://host/1.ts
            """.trimIndent()
        )
        assertEquals("Sky Sport 1", entries.single().name)
        assertEquals("Sport, Fußball", entries.single().groupTitle)
    }

    @Test
    fun handlesExtgrpUserAgentBomAndBlankLines() {
        val (_, entries) = parse(
            "﻿#EXTM3U\r\n\r\n#EXTINF:0,News\r\n#EXTGRP:Nachrichten\r\n#EXTVLCOPT:http-user-agent=Mozilla/5.0\r\n\r\nhttp://host/news.m3u8\r\n"
        )
        val e = entries.single()
        assertEquals("News", e.name)
        assertEquals("Nachrichten", e.groupTitle)
        assertEquals("Mozilla/5.0", e.userAgent)
        assertNull(e.tvgId)
    }

    @Test
    fun detectsVodAndSkipsUrlsWithoutExtinf() {
        val (_, entries) = parse(
            """
            #EXTM3U
            http://orphan/without/info.ts
            #EXTINF:-1 group-title="Filme",Film A
            http://host/movie/u/p/123.mkv
            #EXTINF:-1,Serie S01E01
            http://host/series/u/p/55.mp4?token=1
            """.trimIndent()
        )
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.isVod })
    }
}
