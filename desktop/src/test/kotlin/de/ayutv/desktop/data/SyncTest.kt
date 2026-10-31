package de.ayutv.desktop.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class SyncTest {
    @Test
    fun refreshLoadsOnlyWhatIsDue() = runBlocking {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list.m3u") { exchange ->
            requests.incrementAndGet()
            val body = "#EXTM3U\n#EXTINF:-1 group-title=\"DE | Voll\",DE: RTL HD\nhttp://127.0.0.1/1.ts\n#EXTINF:-1 group-title=\"DE | Voll\",RTL 4K\nhttp://127.0.0.1/2.ts\n".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val store = Store(Files.createTempDirectory("dna-sync").toFile())
            // Ohne freies EPG (kein Internet im Test).
            store.updateUser { it.copy(publicEpg = false) }
            store.savePlaylist(Playlist(1, "M3U", PlaylistType.M3U, "http://127.0.0.1:${server.address.port}/list.m3u"))
            val sync = Sync(store, okhttp3.OkHttpClient())

            assertEquals(emptyList<String>(), sync.refreshDue())
            assertEquals(1, requests.get())
            assertEquals(2, store.channels().size)
            assertEquals(listOf("DE: RTL HD"), store.allVisible().map { it.name })
            assertTrue(store.user.value.guideSyncedAt > 0)

            // Gleich danach ist nichts fällig.
            sync.refreshDue()
            assertEquals(1, requests.get())

            // 13 Stunden später lädt die Playlist neu.
            sync.refreshDue(now = System.currentTimeMillis() + 13 * 3_600_000L)
            assertEquals(2, requests.get())
        } finally {
            server.stop(0)
        }
    }
}
