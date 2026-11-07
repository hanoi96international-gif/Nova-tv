package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.UserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerOptionsTest {
    @Test
    fun defaultsChangeNothing() {
        // Grundeinstellung: VLC startet genau wie bisher (kein Neuaufbau, keine neuen Optionen).
        assertEquals(emptyList<String>(), PlayerOptions.vlc(UserData()))
        assertNull(PlayerOptions.equalizer(UserData()))
    }

    @Test
    fun soundAndSubtitles() {
        val options = PlayerOptions.vlc(UserData(audioMode = AudioMode.NIGHT, normalizeVolume = true, subtitleSize = SubtitleSize.LARGE))
        assertTrue("--audio-filter=compressor,normvol" in options)
        assertTrue("--sub-text-scale=130" in options)
        assertEquals(10, PlayerOptions.equalizer(UserData(audioMode = AudioMode.DIALOG))?.size)
    }
}
