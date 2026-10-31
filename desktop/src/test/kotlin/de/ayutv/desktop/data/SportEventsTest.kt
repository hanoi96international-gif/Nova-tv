package de.ayutv.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SportEventsTest {
    private val now = 1_800_000_000_000L
    private fun channel(id: Int, name: String) = Channel("1:c:$id", 1, name, "Sport", url = "u", epgId = "e$id")

    @Test
    fun groupsSameMatchAcrossChannelsAndSkipsRepeats() {
        val sky = channel(1, "Sky Sport Bundesliga 1 HD")
        val skyUhd = channel(2, "Sky Sport Bundesliga UHD")
        val zdf = channel(3, "ZDF")
        val game = Programme(now - 600_000, now + 6_000_000, "Fußball: Bundesliga", "Borussia Dortmund – FC Bayern München", "Live aus Dortmund", "Sport")
        val repeat = Programme(now + 7_200_000, now + 9_000_000, "Fußball: Bundesliga", "Werder Bremen – VfL Wolfsburg", null, "Sport", flags = 1)
        val news = Programme(now, now + 1_800_000, "heute journal", null, null, "Nachrichten")
        val events = SportEvents.of(listOf(sky, skyUhd, zdf), mapOf(sky.key to listOf(game, repeat), skyUhd.key to listOf(game), zdf.key to listOf(news)), favorites = emptySet(), now = now)
        assertEquals(1, events.size)
        assertEquals("Borussia Dortmund – FC Bayern München", events.single().pairing)
        assertEquals(listOf("Sky Sport Bundesliga UHD"), events.single().alsoOn.map { it.name })
    }
}
