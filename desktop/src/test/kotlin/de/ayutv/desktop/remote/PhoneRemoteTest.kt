package de.ayutv.desktop.remote

import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.Playlist
import de.ayutv.desktop.data.PlaylistType
import de.ayutv.desktop.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

class PhoneRemoteTest {
    private fun get(url: String, method: String = "GET"): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.requestMethod = method
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        return code to body
    }

    @Test
    fun servesPageOnlyWithPinAndFindsChannels() {
        val store = Store(Files.createTempDirectory("dna-remote").toFile())
        store.savePlaylist(Playlist(1, "P", PlaylistType.XTREAM, "http://x"))
        store.setContent(1, Store.Content(listOf(Channel("1:c:1", 1, "Das Erste HD", "DE", url = "u"), Channel("1:c:2", 1, "ZDF", "DE", url = "u"))))
        var played: String? = null
        val remote = PhoneRemote(store, de.ayutv.desktop.input.RemoteInput()) { played = it }
        remote.start()
        try {
            val base = "http://127.0.0.1:${PhoneRemote.PORT}"
            assertEquals(403, get("$base/0000x").first)
            val (code, page) = get("$base/${remote.pin()}")
            assertEquals(200, code)
            assertTrue(page.contains("Fernbedienung"))
            val (_, json) = get("$base/${remote.pin()}/channels?q=erste")
            assertTrue(json.contains("1:c:1") && !json.contains("1:c:2"))
            assertEquals(204, get("$base/${remote.pin()}/play?key=1%3Ac%3A2", "POST").first)
            Thread.sleep(300)
            javax.swing.SwingUtilities.invokeAndWait {}
            assertEquals("1:c:2", played)
            assertEquals(400, get("$base/${remote.pin()}/key?k=nix", "POST").first)
        } finally {
            remote.stop()
        }
    }
}
