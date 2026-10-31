package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.Buffer
import de.ayutv.desktop.data.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerConfigTest {
    @Test
    fun defaultsKeepVlcAsBefore() {
        val options = PlayerConfig.from(UserData()).vlcOptions()
        assertTrue("--avcodec-hw=any" in options)
        assertTrue(options.none { it.startsWith("--audio-filter") || it == "--spdif" })
        assertNull(PlayerConfig.from(UserData()).equalizerBands())
    }

    @Test
    fun runtimeSettingsDoNotRestartVlc() {
        val a = PlayerConfig.from(UserData())
        val b = PlayerConfig.from(UserData(audioDelayMs = 200, buffer = Buffer.STABLE, audioLanguage = "en"))
        assertEquals(a.vlcOptions(), b.vlcOptions())
        assertEquals(8_000, b.networkCachingMs)
        assertEquals("en,eng", b.audioLanguage)
    }

    @Test
    fun soundAndSubtitlesChangeOptions() {
        val night = PlayerConfig.from(UserData(audioMode = AudioMode.NIGHT, normalizeVolume = true)).vlcOptions()
        assertTrue("--audio-filter=compressor,normvol" in night)
        assertNotEquals(PlayerConfig.from(UserData()).vlcOptions(), night)
        assertEquals(10, PlayerConfig.from(UserData(audioMode = AudioMode.DIALOG)).equalizerBands()?.size)
    }

    @Test
    fun autoSubtitlesPickLanguage() {
        assertEquals("", PlayerConfig.from(UserData()).subtitleLanguage)
        assertEquals("de,deu,ger", PlayerConfig.from(UserData(subtitlesAuto = true)).subtitleLanguage)
    }
}
