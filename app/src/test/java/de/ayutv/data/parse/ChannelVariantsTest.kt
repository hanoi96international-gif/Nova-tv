package de.ayutv.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelVariantsTest {
    private data class Ch(val name: String, val playlist: Long = 1)

    private fun merge(vararg names: String) = ChannelVariants.merge(names.map { Ch(it) }, { it.name }, { it.playlist }).map { it.name }

    @Test
    fun variantsBecomeOneEntryInBestQuality() {
        assertEquals(
            listOf("DE: RTL HD", "DE: VOX FHD", "DE: ZDF"),
            merge("DE: RTL SD", "DE: RTL HD", "DE: VOX 4K", "DE: VOX FHD", "DE: RTL 4K", "PRIME: RTL ᴿᴬᵂ", "DE: ZDF", "DE: RTL (Backup)"),
        )
        assertEquals(listOf("PRIME: 3SAT ᴿᴬᵂ"), merge("DE: 3SAT 4K", "PRIME: 3SAT ᴿᴬᵂ"))
        assertEquals(listOf("ARTE ᴴᴰ"), merge("ARTE ᵁᴴᴰ", "ARTE", "ARTE ᴴᴰ"))
    }

    @Test
    fun differentChannelsStaySeparate() {
        assertEquals(listOf("RTL", "RTL +1", "RTL 4", "RTL Crime"), merge("RTL", "RTL +1", "RTL 4", "RTL Crime"))
        assertEquals(listOf("Sky Sport Bundesliga 1", "Sky Sport Bundesliga 2", "DAZN", "DAZN 1"), merge("Sky Sport Bundesliga 1", "Sky Sport Bundesliga 2", "DAZN", "DAZN 1"))
        // Verschiedene Playlists bleiben getrennt, Namen ohne Inhalt werden nicht zusammengefasst
        assertEquals(2, ChannelVariants.merge(listOf(Ch("RTL", 1), Ch("RTL", 2)), { it.name }, { it.playlist }).size)
        assertEquals(listOf("HD", "4K"), merge("HD", "4K"))
    }

    @Test
    fun alternativesBestFirst() {
        val all = listOf(Ch("DE: RTL 4K"), Ch("DE: RTL HD"), Ch("DE: RTL SD"), Ch("DE: VOX HD"), Ch("RTL (Backup)"))
        val alt = ChannelVariants.alternatives(all[1], all, { it.name }, { it.playlist }, { a, b -> a == b }).map { it.name }
        assertEquals(listOf("DE: RTL 4K", "DE: RTL SD", "RTL (Backup)"), alt)
    }
}
