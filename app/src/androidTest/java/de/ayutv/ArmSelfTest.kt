package de.ayutv

import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.ayutv.data.repo.Translators
import de.ayutv.data.translate.TranslatorSelfTest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Wie auf dem Fire TV Stick 4K: App mit 32-Bit-ARM-Bibliotheken (im Emulator per ARM-Übersetzung).
 * Prüft Texterkennung und Übersetzung mehrfach, damit auch die Ersatz-Varianten nach einem Absturz laufen.
 */
@RunWith(AndroidJUnit4::class)
class ArmSelfTest {
    @Test
    fun translatorOnArm32() {
        val app = ApplicationProvider.getApplicationContext<AyuApp>()
        val out = File(app.filesDir, "ci/arm.txt").apply { parentFile?.mkdirs(); writeText("") }
        fun log(text: String) {
            Log.i("AyuArm", text)
            out.appendText(text + "\n")
        }
        log("abis=${Build.SUPPORTED_ABIS.joinToString()} libs=${app.applicationInfo.nativeLibraryDir}")
        for ((round, language) in listOf("de", "es", "id", "es", "fr").withIndex()) {
            val result = runBlocking { TranslatorSelfTest.run(language) }
            Translators.remote.release()
            log("round $round $language: ${result.replace("\n", " | ")}")
            Thread.sleep(2_000)
        }
    }
}
