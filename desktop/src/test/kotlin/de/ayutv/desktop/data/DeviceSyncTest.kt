package de.ayutv.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeviceSyncTest {
    @Test
    fun takesNewerFromFireTvAndRemapsPlaylistIds() {
        val store = Store(Files.createTempDirectory("dna-devsync").toFile())
        store.savePlaylist(Playlist(1, "X", PlaylistType.XTREAM, "http://anbieter.tv:8080", username = "max"))
        store.setContent(1, Store.Content(
            channels = listOf(Channel("1:c:10", 1, "RTL", "DE", url = "u"), Channel("1:c:11", 1, "ZDF", "DE", url = "u")),
            movies = listOf(Movie("1:v:5", 1, "Film", "Kino", url = "m")),
        ))
        store.rememberWatched("1:c:11")
        // Auf dem Fire TV hat dieselbe Playlist die Nummer 7.
        val fireTv = """{"format":"dna-tv-sync",
          "playlists":[{"id":7,"type":"XTREAM","url":"http://anbieter.tv:8080/","username":"max"},{"id":8,"type":"M3U","url":"http://andere"}],
          "favorites":[{"key":"7:c:10","addedAt":1,"position":0},{"key":"8:c:99","addedAt":1,"position":1}],
          "history":[
            {"key":"7:c:10","kind":"LIVE","title":"RTL","url":"x","watchedAt":${System.currentTimeMillis() + 60_000}},
            {"key":"7:v:5","kind":"VOD","title":"Film","url":"x","positionMs":120000,"durationMs":5400000,"watchedAt":${System.currentTimeMillis() + 60_000}},
            {"key":"7:c:11","kind":"LIVE","title":"ZDF","url":"x","watchedAt":1}
          ]}"""
        val result = DeviceSync(store, okhttp3.OkHttpClient()).import(fireTv)
        assertEquals(1, result.favorites)
        assertEquals(2, result.history)
        val u = store.user.value
        assertEquals(listOf("1:c:10"), u.favorites)
        assertEquals(listOf("1:c:10", "1:c:11"), u.recent)
        assertEquals(120_000L, u.positions["1:v:5"])
        // Eigener Stand geht im gleichen Format zurück.
        val out = DeviceSync(store, okhttp3.OkHttpClient()).export()
        assertTrue(out.contains("\"format\":\"dna-tv-sync\"") && out.contains("\"1:v:5\"") && out.contains("\"kind\":\"LIVE\""))
    }
}
