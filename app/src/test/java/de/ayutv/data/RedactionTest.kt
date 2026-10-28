package de.ayutv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactionTest {
    @Test
    fun credentialsDisappearServersStay() {
        val log = "Öffne http://max:geheim@iptv.example.com:8080/live/maxuser/pw1234/1234.ts?token=abc nach 3 s"
        assertEquals("Öffne http://iptv.example.com:8080/… nach 3 s", Redaction.apply(log))
        assertEquals("player_api password=*** username=***", Redaction.apply("player_api password=geheim username=max"))
        assertEquals("Api-Key: ***", Redaction.apply("Api-Key: s3cr3tValue"))
        assertEquals("Mail an ***@***", Redaction.apply("Mail an someone@example.com"))
        val id = "Schlüssel " + "a1b2c3d4e5".repeat(4)
        assertFalse(Redaction.apply(id).contains("a1b2c3"))
        // Fehlermeldungen bleiben lesbar
        val error = "E/AyuPlayer: Source error HttpDataSourceException: Response code: 403 at de.ayutv.player.PlayerActivity.onPlayerError"
        assertEquals(error, Redaction.apply(error))
    }
}
