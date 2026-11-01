package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLogTest {
    private fun entry(at: Long, channel: String = "DE: ProSieben", drops: Map<String, Int> = emptyMap(), stalls: Int = 0, route: String = "direkt") =
        WatchLog.Entry(
            at = at, channel = channel, watchedS = 1800, height = 1080, fps = 25f, displayHz = 60f, codec = "hevc",
            avgKbps = 9_800, lowKbps = 2_100, minBufferS = 4, stalls = stalls, stallS = stalls * 3, drops = drops,
            droppedFrames = 0, startMs = 749, errors = 0, route = route, net = "WLAN 5 GHz 433 Mbit/s", worstRssi = -67,
        )

    @Test
    fun roundTripAndLimits() {
        val e = entry(1_000L, channel = "Sky\tSport\n1", drops = mapOf(WatchLog.KIND_SILENT to 3))
        val back = WatchLog.decode(WatchLog.encode(e))!!
        assertEquals("Sky Sport 1", back.channel)
        assertEquals(mapOf(WatchLog.KIND_SILENT to 3), back.drops)
        assertEquals(e.copy(channel = "Sky Sport 1"), back)
        // Älter als 48 h fällt weg, höchstens MAX Einträge
        val now = WatchLog.KEEP_MS + 10_000L
        var lines = listOf(WatchLog.encode(entry(1_000L)))
        repeat(WatchLog.MAX + 5) { lines = WatchLog.append(lines, entry(now - 1_000L + it), now) }
        assertEquals(WatchLog.MAX, lines.size)
        assertTrue(lines.mapNotNull(WatchLog::decode).all { now - it.at <= WatchLog.KEEP_MS })
        // Kaputte Zeilen werden übergangen
        assertEquals(null, WatchLog.decode("kaputt"))
    }

    @Test
    fun reasonsAndSummary() {
        assertEquals(WatchLog.KIND_SILENT, WatchLog.kindOf("Anbieter liefert nichts mehr (Zeitüberschreitung)"))
        assertEquals(WatchLog.KIND_SILENT, WatchLog.kindOf("java.net.SocketTimeoutException: timeout"))
        assertEquals(WatchLog.KIND_CLOSED, WatchLog.kindOf("Anbieter hat die Verbindung beendet"))
        assertEquals(WatchLog.KIND_OTHER, WatchLog.kindOf("Connection reset"))
        val now = 10_000_000L
        val text = WatchLog.summary(
            listOf(
                entry(now - 1000, drops = mapOf(WatchLog.KIND_SILENT to 3), stalls = 1, route = "über Proxy (NL)"),
                entry(now - 2000, channel = "DE: RTL"),
            ),
            now,
        )
        assertTrue(text, text.contains("2 Sender, 1,0 h geschaut"))
        assertTrue(text, text.contains("Neu verbunden: 3 (keine Daten 3)"))
        assertTrue(text, text.contains("Ruckler (Bild hing, Puffer leer): 1"))
        assertTrue(text, text.contains("25/50 fps auf 60 Hz): 2 von 2"))
        assertTrue(text, text.contains("Neu verbunden pro Stunde"))
        assertTrue(text, text.contains("Auffällige Sender"))
        assertEquals("Noch nichts geschaut (gezählt wird ab 30 s je Sender).\n", WatchLog.summary(emptyList(), now))
    }
}
