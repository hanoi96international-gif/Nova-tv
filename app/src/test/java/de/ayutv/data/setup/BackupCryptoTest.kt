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
}
