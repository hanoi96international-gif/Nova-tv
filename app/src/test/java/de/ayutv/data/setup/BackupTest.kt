package de.ayutv.data.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupTest {
    @Test
    fun favoriteKeysFollowNewPlaylistNumbers() {
        val ids = mapOf(3L to 1L, 5L to 7L)
        assertEquals("1:c:1234", Backup.remapKey("3:c:1234", ids))
        assertEquals("7:c:DE.RTL:http://x/a:b", Backup.remapKey("5:c:DE.RTL:http://x/a:b", ids))
        assertNull(Backup.remapKey("9:c:1", ids))
        assertNull(Backup.remapKey("kaputt", ids))
    }
}
