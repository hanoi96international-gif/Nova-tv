package de.ayutv.data.setup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class BackupCryptoTest {
    @Test
    fun roundTrip() {
        val text = """{"format":"dna-tv-backup","playlists":[{"password":"geheim äöü"}]}"""
        val sealed = BackupCrypto.seal(text, "Sofa-2026", iterations = 2_000)
        assertFalse(sealed.data.contains("geheim"))
        assertEquals(text, BackupCrypto.open(sealed, "Sofa-2026"))
    }

    @Test(expected = BackupCrypto.WrongPassword::class)
    fun wrongPassword() {
        val sealed = BackupCrypto.seal("x", "richtig", iterations = 1_000)
        BackupCrypto.open(sealed, "falsch")
    }

    @Test
    fun pbkdf2MatchesJava() {
        val salt = ByteArray(16) { it.toByte() }
        for (iterations in listOf(1, 2, 1_000)) {
            val expected = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec("passwört".toCharArray(), salt, iterations, 256)).encoded
            assertArrayEquals(expected, BackupCrypto.pbkdf2("passwört", salt, iterations))
        }
    }

    @Test
    fun localChannelRoundTripWithoutPinOnTheWire() {
        val client = LocalChannel("4711")
        val server = LocalChannel.Server { "4711" }
        val wire = client.request("/sync", "{\"favorites\":[\"ZDF\"]}")
        // Weder PIN noch Inhalt im Klartext unterwegs
        org.junit.Assert.assertFalse(wire.contains("4711"))
        org.junit.Assert.assertFalse(wire.contains("favorites"))
        val request = server.open(wire)
        assertEquals("/sync", request.path)
        assertEquals("{\"favorites\":[\"ZDF\"]}", request.body)
        assertEquals("200\nok", client.reply(server.reply(request, "200\nok")))
        // Zweite Nachricht derselben Verbindung (gleicher Schlüssel, neuer IV)
        val again = client.request("/key?k=up", "")
        org.junit.Assert.assertNotEquals(wire.split('.')[2], again.split('.')[2])
        assertEquals("/key?k=up", server.open(again).path)
    }

    @Test
    fun localChannelRejectsWrongPinAndTampering() {
        val wire = LocalChannel("1234").request("/report", "")
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "9999" }.open(wire) }
        val parts = wire.split('.').toMutableList()
        parts[3] = parts[3].reversed()
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "1234" }.open(parts.joinToString(".")) }
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel.Server { "1234" }.open("1234/sync") }
        // Antwort für eine andere Verbindung wird nicht angenommen
        val other = LocalChannel("1234")
        val server = LocalChannel.Server { "1234" }
        val reply = server.reply(server.open(other.request("/info", "")), "200\n{}")
        org.junit.Assert.assertThrows(BackupCrypto.WrongPassword::class.java) { LocalChannel("1234").reply(reply) }
    }
}
