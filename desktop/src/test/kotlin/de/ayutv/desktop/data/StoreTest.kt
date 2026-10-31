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

    private fun named(id: Int, name: String, group: String) =
        Channel("1:c:$id", 1, name, group, url = "http://x/$id.ts", number = id)

    @Test
    fun variantsAreMergedAndBecomeFallbacks() {
        val s = store().withChannels(
            named(1, "DE: RTL HD", "DE | Voll"), named(2, "DE: RTL 4K", "DE | Voll"), named(3, "RTL (Backup)", "DE | Voll"), named(4, "ZDF", "DE | Voll"),
        )
        assertEquals(listOf("DE: RTL HD", "ZDF"), s.allVisible().map { it.name })
        assertEquals(listOf("DE | Voll" to 2), s.liveGroups())
        assertEquals(listOf("DE: RTL 4K", "RTL (Backup)"), s.alternatives(s.allVisible().first()).map { it.name })
        s.updateUser { it.copy(mergeVariants = false) }
        assertEquals(4, s.allVisible().size)
    }

    @Test
    fun movieAndSeriesCategoriesCanBeHidden() {
        val s = store()
        s.savePlaylist(Playlist(1, "Test", PlaylistType.XTREAM, "http://x"))
        s.setContent(1, Store.Content(
            movies = listOf(Movie("1:v:1", 1, "Film A", "Kino", url = "u"), Movie("1:v:2", 1, "Film B", "Kids", url = "u")),
            series = listOf(Series("1:s:1", 1, "1", "Serie", "Doku")),
        ))
        s.hideMovieGroup("Kids", series = false)
        assertEquals(listOf("Film A"), s.movies().map { it.name })
        assertEquals(2, s.allMovies().size)
        s.setGroupsHidden(1, GroupKind.SERIES, listOf("Doku"), hide = true)
        assertTrue(s.series().isEmpty())
        assertEquals(listOf(Triple("Doku", 1, true)), s.groupsOf(1, GroupKind.SERIES))
        s.setGroupsHidden(1, GroupKind.MOVIES, listOf("Kids"), hide = false)
        assertEquals(2, s.movies().size)
    }

    @Test
    fun countryChoiceHidesOtherCountriesButKeepsGroupsWithoutCountry() {
        val s = store().withChannels(channel(1, "DE | Sport"), channel(2, "AT | News"), channel(3, "UK | Movies"), channel(4, "Kinder"))
        val codes = s.countries().map { it.code }.toSet()
        assertEquals(setOf("DE", "AT", "UK"), codes)
        s.keepCountries(setOf("DE", "AT", "CH"))
        assertEquals(listOf("DE | Sport", "AT | News", "Kinder"), s.liveGroups().map { it.first })
        assertTrue(s.user.value.countriesAsked)
        assertEquals(false, s.countries().first { it.code == "UK" }.visible)
        s.keepCountries(codes)
        assertEquals(4, s.liveGroups().size)
    }

    @Test
    fun guideShiftAppliesAndSurvivesReload() {
        val dir = Files.createTempDirectory("dna-store").toFile()
        val s = Store(dir).withChannels(channel(1, "A"))
        val now = System.currentTimeMillis()
        s.setGuide(mapOf("1:c:1" to listOf(Programme(now, now + 3_600_000, "Show"))))
        s.setEpgShift(2)
        assertEquals(now + 2 * 3_600_000, s.guide.value.getValue("1:c:1").first().start)
        val again = Store(dir)
        assertEquals(now + 2 * 3_600_000, again.guide.value.getValue("1:c:1").first().start)
        again.setEpgShift(0)
        assertEquals(now, again.guide.value.getValue("1:c:1").first().start)
    }

    @Test
    fun reorderPutsShownKeysFirst() {
        assertEquals(listOf("c", "a", "b", "d"), Ordering.reorder(listOf("a", "b", "c", "d"), listOf("c", "a", "b")))
        assertEquals(listOf("x", "y"), Ordering.apply(listOf("y", "x"), listOf("x")) { it })
    }
}
