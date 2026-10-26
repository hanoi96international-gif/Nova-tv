package de.ayutv.data.setup

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/**
 * Sicherung mit Passwort: AES-256-GCM, Schlüssel aus dem Passwort per PBKDF2 (SHA-256, viele Runden).
 * Ohne das Passwort lässt sich die Datei nicht lesen; ein falsches Passwort wird sicher erkannt.
 */
object BackupCrypto {
    data class Sealed(val salt: String, val iv: String, val data: String, val iterations: Int)

    class WrongPassword : IllegalArgumentException("Falsches Passwort für diese Sicherung.")

    private const val ITERATIONS = 120_000

    fun seal(text: String, password: String, iterations: Int = ITERATIONS): Sealed {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Sealed(Base64.encode(salt), Base64.encode(iv), Base64.encode(data), iterations)
    }

    fun open(sealed: Sealed, password: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, Base64.decode(sealed.salt), sealed.iterations), GCMParameterSpec(128, Base64.decode(sealed.iv)))
        return try {
            String(cipher.doFinal(Base64.decode(sealed.data)), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()
        }
    }

    private fun key(password: String, salt: ByteArray, iterations: Int) = SecretKeySpec(pbkdf2(password, salt, iterations), "AES")

    /**
     * PBKDF2-HMAC-SHA256 für 32 Byte (ein Block), selbst gerechnet: Android bietet es erst ab Version 8,
     * Fire OS 6 (Android 7.1) noch nicht. Ergebnis wie RFC 8018.
     */
    fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8).let { if (it.isEmpty()) ByteArray(1) else it }, "HmacSHA256"))
        mac.update(salt)
        var u = mac.doFinal(byteArrayOf(0, 0, 0, 1))
        val result = u.copyOf()
        repeat(iterations - 1) {
            u = mac.doFinal(u)
            for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
        }
        return result
    }
}
