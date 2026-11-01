package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class EventNamesTest {
    // So., 01.11.2026, 15:32 deutscher Zeit (MEZ)
    private val now = ZonedDateTime.of(2026, 11, 1, 14, 32, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun utc(h: Int, m: Int, day: Int = 1) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test
    fun providerFormats() {
        val a = EventNames.parse("NEXT | HAMBURGER SV II VS HANNOVER 96 II | Sun 01 Nov 14:00 CET (DE) | 8K EXCLUSIVE | DE: LEAGUES FOOTBALL PPV 5", now)!!
        assertEquals("HAMBURGER SV II VS HANNOVER 96 II", a.title)
        assertEquals(utc(13, 0), a.start)
        assertEquals(EventNames.State.NEXT, a.state)

        val b = EventNames.parse("Next | FC St. Pauli vs. 1. FC Nürnberg | all | 01-11-2026 | 13:00 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 21", now)!!
        assertEquals("FC St. Pauli vs. 1. FC Nürnberg", b.title)
        assertEquals(utc(13, 0), b.start)

        val c = EventNames.parse("Next | Juventus - Neapel | Serie A | 2026-11-01 | 19:45 (GMT) | 8K EXCLUSIVE | DE: DAZN PPV 1", now)!!
        assertEquals("Juventus - Neapel – Serie A", c.title)
        assertEquals(utc(19, 45), c.start)

        val d = EventNames.parse("NEXT | WEEK 8 | RAVENS AT BILLS | Sun 01 Nov 19:00 CET (DE) | 8K EXCLUSIVE | DE: RTL+ PPV 3", now)!!
        assertEquals("WEEK 8 – RAVENS AT BILLS", d.title)
        assertEquals(utc(18, 0), d.start)

        // Live ohne Uhrzeit: läuft jetzt
        val e = EventNames.parse("Live | Snooker: International Championship, 1. Runde | all | 8K EXCLUSIVE | DE: SOCCER PPV 10", now)!!
        assertEquals("Snooker: International Championship, 1. Runde", e.title)
        assertTrue(e.start <= now && e.stop > now)
        assertEquals(EventNames.State.LIVE, e.state)

        // Live mit uraltem Datum (Name nicht aktualisiert): kein tagelanger Block
        val f = EventNames.parse("LIVE | WEEK 7 | THURSDAY NIGHT: PATRIOTS AT BEARS | Fri 23 Oct 02:10 CEST (DE) | 8K EXCLUSIVE | DE: RTL+ PPV 1", now)!!
        assertTrue(f.stop - f.start < 4 * 3_600_000L)
        assertTrue(f.start <= now && f.stop > now)
    }

    @Test
    fun nothingForEndedIdleOrNormalChannels() {
        assertNull(EventNames.parse("ENDED | ESV BURGAU VS EC ZIRL | Sun 01 Nov 10:25 CET (DE) | 8K EXCLUSIVE | DE: STAIGE PPV 1", now))
        assertNull(EventNames.parse("End | Seoul 2026 - Singles Final - 8 | all | 01-11-2026 | 06:00 (GMT) | 8K EXCLUSIVE | DE: SOCCER PPV 1", now))
        assertNull(EventNames.parse("- NO EVENT STREAMING - | 8K EXCLUSIVE | DE: DYN PPV 39", now))
        assertNull(EventNames.parse("DE: SKY SPORT BUNDESLIGA 1 HD", now))
        assertNull(EventNames.parse("DE| SKY HEVC: DE: SKY CINEMA BEST OF HEVC", now))
        assertTrue(EventNames.isIdle("- NO EVENT STREAMING - | 8K EXCLUSIVE | DE: DYN PPV 39"))
        assertTrue(EventNames.isSeparator("##### SKY MOVIES HD/4K #####"))
        assertTrue(EventNames.isSeparator("### WOW SPORT ᴴᴰ ᴰᴼᴸᴮʸ ᴬᵁᴰᴵᴼ ###"))
        assertFalse(EventNames.isSeparator("DE: RTL ᵁᴴᴰ 3840P"))
        assertFalse(EventNames.isSeparator("- NO EVENT STREAMING - | DE: DYN PPV 39"))
    }

    @Test
    fun eventsAreSportForTheSportScreen() {
        val a = EventNames.parse("LIVE | MÜNCHNER SC (D) - DER CLUB AN DER ALSTER (D) | Sun 01 Nov 11:25 CET (DE) | 8K EXCLUSIVE | DE: DYN PPV 2", now)!!
        assertEquals("MÜNCHNER SC (D) - DER CLUB AN DER ALSTER (D)", a.title)
        assertTrue(ProgrammeText.pairing(a.title, null) != null)
    }

    @Test
    fun uhdVersionsShareTheEventOfTheirSibling() {
        assertEquals("dynppv2", EventNames.baseKey("LIVE | MÜNCHNER SC (D) - DER CLUB AN DER ALSTER (D) | Sun 01 Nov 11:25 CET (DE) | 8K EXCLUSIVE | DE: DYN PPV 2"))
        assertEquals("dynppv2", EventNames.baseKey("DE: DYN PPV 2 4K"))
        assertEquals("dynppv2", EventNames.baseKey("DE: DYN PPV 2 ᵁᴴᴰ"))
        assertEquals("daznppv12", EventNames.baseKey("Next | Juventus - Neapel | Serie A | 2026-11-01 | 19:45 (GMT) | 8K EXCLUSIVE | DE: DAZN PPV 12"))
        // Ohne Nummer kein Event-Sender: nie das Spiel eines anderen übernehmen
        assertNull(EventNames.baseKey("4K: SKY SPORTS ᵁᴴᴰ ³⁸⁴⁰ᴾ (EVENT)"))
        assertNull(EventNames.baseKey("DE: SKY SPORT BUNDESLIGA ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
    }
}
