package de.ayutv.data.repo

import de.ayutv.data.db.ChannelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class VariantCompareTest {
    private val rtl = ChannelEntity(key = "1:rtl", playlistId = 1, name = "RTL HD", groupTitle = "DE", logo = null, url = "http://x/live/u/p/1.ts", epgId = null, number = 1)

    private fun outcome(ts: Int, hls: Int?) = VariantCompare.Outcome(
        listOfNotNull(VariantCompare.Candidate(rtl, false, ts), hls?.let { VariantCompare.Candidate(rtl, true, it) }),
        VariantCompare.Candidate(rtl, false, ts),
    )

    @Test
    fun hlsOnlyWhenClearlyBetter() {
        assertEquals(false, outcome(8_000, 9_000).hlsWins)   // nur 12 % mehr
        assertEquals(true, outcome(8_000, 10_000).hlsWins)   // 25 % mehr
        assertEquals(true, outcome(0, 6_000).hlsWins)        // TS kommt nicht an
        assertEquals(false, outcome(8_000, 0).hlsWins)       // HLS kommt nicht an
        assertEquals(false, outcome(8_000, null).hlsWins)    // kein HLS beim Anbieter
        assertEquals(false, outcome(Int.MAX_VALUE, Int.MAX_VALUE).hlsWins) // kein Überlauf
    }
}
