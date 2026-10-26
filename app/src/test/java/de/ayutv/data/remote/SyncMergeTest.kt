package de.ayutv.data.remote

import de.ayutv.data.remote.SyncMerge.PlaylistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMergeTest {
    @Test
    fun playlistsMatchByProviderAndUser() {
        val remote = listOf(PlaylistRef(1, "XTREAM", "http://a.tv/", "max"), PlaylistRef(2, "M3U", "http://b/list.m3u", null), PlaylistRef(3, "XTREAM", "http://a.tv", "eva"))
        val local = listOf(PlaylistRef(7, "M3U", "http://B/list.m3u", null), PlaylistRef(9, "XTREAM", "http://a.tv", "max"))
        assertEquals(mapOf(1L to 9L, 2L to 7L), SyncMerge.idMap(remote, local))
    }

    @Test
    fun keysAndNewerEntries() {
        assertEquals("9:s:5:e:77", SyncMerge.remapKey("1:s:5:e:77", mapOf(1L to 9L)))
        assertNull(SyncMerge.remapKey("catchup:1:c", mapOf(1L to 9L)))
        val incoming = listOf("a" to 10L, "b" to 5L, "c" to 1L)
        val result = SyncMerge.newer(mapOf("a" to 20L, "b" to 4L), incoming, { it.first }, { it.second })
        assertEquals(listOf("b" to 5L, "c" to 1L), result)
    }
}
