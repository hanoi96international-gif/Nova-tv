package de.ayutv.data.parse

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelQualityTest {
    private data class Ch(val key: String, val name: String)

    @After
    fun reset() {
        ChannelQuality.clear()
        ChannelQuality.preferUhd = false
        ChannelQuality.minHeight = 0
        ChannelQuality.hideBroken = false
    }

    private fun best(vararg channels: Ch) =
        ChannelVariants.merge(channels.toList(), { it.name }, { 1L }, { ChannelQuality.score(it.key, it.name) }).single().name

    @Test
    fun bestVariantByNameAndMeasurement() {
        assertEquals("DE: RTL FHD", best(Ch("a", "DE: RTL SD"), Ch("b", "DE: RTL HD"), Ch("c", "DE: RTL FHD"), Ch("d", "DE: RTL 4K")))
        // 4K nur, wenn gewünscht
        ChannelQuality.preferUhd = true
        assertEquals("DE: RTL 4K", best(Ch("c", "DE: RTL FHD"), Ch("d", "DE: RTL 4K")))
        ChannelQuality.preferUhd = false
        // Gemessen schlägt Name: „HD“ liefert in Wahrheit 1080p, „FHD“ fällt aus
        ChannelQuality.recordPlayback("b", 1080, 900)
        ChannelQuality.recordCheck("c", 0, 0)
        ChannelQuality.recordCheck("c", 0, 0)
        assertEquals("DE: RTL HD", best(Ch("b", "DE: RTL HD"), Ch("c", "DE: RTL FHD")))
        // Backup nur, wenn sonst nichts
        assertEquals("RTL", best(Ch("x", "RTL"), Ch("y", "RTL (Backup)")))
    }

    @Test
    fun minimumQualityHidesOnlyKnownLowerOnes() {
        ChannelQuality.minHeight = 1080
        assertFalse(ChannelQuality.passes("a", "DE: RTL SD"))
        assertFalse(ChannelQuality.passes("b", "DE: RTL HD"))
        assertTrue(ChannelQuality.passes("c", "DE: RTL FHD"))
        assertTrue(ChannelQuality.passes("u", "DE: RTL")) // unbekannt bleibt sichtbar
        ChannelQuality.recordPlayback("u", 576, 500)
        assertFalse(ChannelQuality.passes("u", "DE: RTL"))
        ChannelQuality.recordPlayback("b", 1080, 500) // „HD“, gemessen 1080p
        assertTrue(ChannelQuality.passes("b", "DE: RTL HD"))
        ChannelQuality.hideBroken = true
        ChannelQuality.minHeight = 0
        ChannelQuality.recordFailure("z"); ChannelQuality.recordFailure("z")
        assertFalse(ChannelQuality.passes("z", "Kaputt"))
    }

    @Test
    fun stabilityRanksWatchedChannelsWithoutDropsFirst() {
        ChannelQuality.recordPlayback("calm", 1080, 800)
        ChannelQuality.recordWatch("calm", 3 * 3_600_000L, 0)
        ChannelQuality.recordPlayback("shaky", 1080, 800)
        ChannelQuality.recordWatch("shaky", 2 * 3_600_000L, 8)
        ChannelQuality.recordCheck("checked", 1080, 1_200) // nur im Check gemessen
        ChannelQuality.recordFailure("dead"); ChannelQuality.recordFailure("dead")
        val order = listOf("shaky", "dead", "checked", "calm", "unknown").filter { ChannelQuality.stability(it) != null }
            .sortedBy { ChannelQuality.stability(it) }
        assertEquals(listOf("calm", "checked", "shaky"), order)
    }

    @Test
    fun savedAndLoaded() {
        val file = java.io.File.createTempFile("quality", ".tsv")
        ChannelQuality.recordPlayback("k1", 720, 1200, now = 5)
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        ChannelQuality.recordWatch("k1", 600_000, 1, now = 6)
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        assertEquals(ChannelQuality.Info(720, 1200, 0, 5, 600_000, 1), ChannelQuality.info("k1"))
        file.delete()
    }

    @Test
    fun sameResolutionHigherBitrateWins() {
        ChannelQuality.preferUhd = true
        // Zwei 4K-Fassungen: die mit mehr Daten (schärfer) gewinnt, eine Full-HD-Fassung mit viel Daten nicht gegen 4K.
        ChannelQuality.recordPlayback("a", 2160, 900)
        ChannelQuality.recordPlayback("b", 2160, 900)
        ChannelQuality.recordPlayback("c", 1080, 900)
        ChannelQuality.recordBitrate("a", 10_000)
        ChannelQuality.recordBitrate("b", 22_000)
        ChannelQuality.recordBitrate("c", 40_000)
        assertEquals("DE: SKY SPORT UHD (EVENT)", best(Ch("a", "DE: SKY SPORT UHD"), Ch("b", "DE: SKY SPORT UHD (EVENT)"), Ch("c", "DE: SKY SPORT FHD")))
        // Gespeichert und wieder geladen.
        val file = java.io.File.createTempFile("quality", ".tsv")
        ChannelQuality.saveIfChanged(file)
        ChannelQuality.clear()
        ChannelQuality.load(file)
        assertEquals(22_000, ChannelQuality.info("b")?.kbps)
        file.delete()
    }
}
