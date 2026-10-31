package de.ayutv.desktop.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(Updater.newer("0.41.0", "0.40.2"))
        assertTrue(Updater.newer("0.40.10", "0.40.9"))
        assertFalse(Updater.newer("0.40.2", "0.40.2"))
        assertFalse(Updater.newer("0.39.9", "0.40.0"))
        assertTrue(Updater.newer("1.0", "0.99.99"))
    }

    @Test
    fun settingsSurviveSaving() {
        val dir = java.nio.file.Files.createTempDirectory("dna-settings").toFile()
        val store = Store(dir)
        store.updateUser { it.copy(theme = Theme.HANNOVER, subtitleSize = SubtitleSize.XL, matchFrameRate = true, audioDelayMs = -150) }
        val again = Store(dir).user.value
        assertTrue(again.theme == Theme.HANNOVER && again.subtitleSize == SubtitleSize.XL && again.matchFrameRate && again.audioDelayMs == -150L)
    }
}
