package de.ayutv.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoreTest {
    private fun store() = Store(Files.createTempDirectory("dna-store").toFile())

    private fun channel(id: Int, group: String, playlist: Long = 1) =
        Channel("$playlist:c:$id", playlist, "Sender $id", group, url = "http://x/$id.ts", number = id)

    private fun Store.withChannels(vararg channels: Channel): Store {
        savePlaylist(Playlist(1, "Test", PlaylistType.XTREAM, "http://x"))
        setContent(1, Store.Content(channels.toList()))
        return this
    }

    @Test
    fun hiddenGroupKeepsChannelsInFavoritesAndCustomGroups() {
        val s = store().withChannels(channel(1, "Sport"), channel(2, "Sport"), channel(3, "News"))
        s.toggleFavorite("1:c:1")
        val group = s.createGroup("Meine", listOf("1:c:2"))
        s.hideGroup("Sport")
        assertEquals(listOf("News"), s.liveGroups().map { it.first })
        assertEquals(listOf("1:c:1"), s.favorites().map { it.key })
        assertEquals(listOf("1:c:2"), s.inCustom(group).map { it.key })
        assertEquals(listOf("1:c:3"), s.allVisible().map { it.key })
    }

    @Test
    fun orderSurvivesReloadAndNewChannelsGoLast() {
        val dir = Files.createTempDirectory("dna-store").toFile()
        val s = Store(dir).withChannels(channel(1, "Sport"), channel(2, "Sport"), channel(3, "Sport"))
        s.saveOrder(GroupRef.Provider("Sport"), listOf("1:c:3", "1:c:1"))
        val again = Store(dir)
        again.setContent(1, Store.Content(listOf(channel(1, "Sport"), channel(2, "Sport"), channel(3, "Sport"), channel(4, "Sport"))))
        assertEquals(listOf("1:c:3", "1:c:1", "1:c:2", "1:c:4"), again.inGroup("Sport").map { it.key })
    }

    @Test
    fun customGroupsAndFavoritesKeepManualOrder() {
        val s = store().withChannels(channel(1, "A"), channel(2, "A"), channel(3, "A"))
        s.addFavorites(listOf("1:c:1", "1:c:2", "1:c:3"))
        s.saveOrder(GroupRef.Favorites, listOf("1:c:3", "1:c:1", "1:c:2"))
        assertEquals(listOf("1:c:3", "1:c:1", "1:c:2"), s.favorites().map { it.key })
        val id = s.createGroup("G")
        assertEquals(2, s.addToGroup(id, listOf("1:c:2", "1:c:1", "1:c:2")))
        s.saveOrder(GroupRef.Custom(id), listOf("1:c:1", "1:c:2"))
        assertEquals(listOf("1:c:1", "1:c:2"), s.inCustom(id).map { it.key })
        s.removeFromGroup(id, listOf("1:c:1"))
        assertEquals(listOf("1:c:2"), s.inCustom(id).map { it.key })
    }

    @Test
    fun disabledPlaylistHidesItsChannels() {
        val s = store().withChannels(channel(1, "A"))
        s.savePlaylist(s.playlists.value.first().copy(enabled = false))
        assertTrue(s.channels().isEmpty())
    }

    @Test
    fun reorderPutsShownKeysFirst() {
        assertEquals(listOf("c", "a", "b", "d"), Ordering.reorder(listOf("a", "b", "c", "d"), listOf("c", "a", "b")))
        assertEquals(listOf("x", "y"), Ordering.apply(listOf("y", "x"), listOf("x")) { it })
    }
}
