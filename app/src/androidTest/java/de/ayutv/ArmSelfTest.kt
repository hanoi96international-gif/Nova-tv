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
        val failures = mutableListOf<String>()
        log("abis=${Build.SUPPORTED_ABIS.joinToString()} libs=${app.applicationInfo.nativeLibraryDir}")
        // Je Runde ein frischer Übersetzer-Prozess, damit der Speicher-Höchststand (VmHWM) pro Einstellung gilt:
        // normal gegen Speichersparmodus (wie auf dem Fire TV Stick neben einem 4K-Film).
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val rounds = listOf("de" to "off", "es" to "off", "es" to "on", "id" to "off", "id" to "on", "fr" to "on")
        for ((round, setup) in rounds.withIndex()) {
            val (language, lowMemory) = setup
            File(app.filesDir, "mt_lowmem").writeText(lowMemory)
            val result = runBlocking { TranslatorSelfTest.run(language) }
            Translators.remote.release()
            log("round $round $language lowmem=$lowMemory: ${result.replace("\n", " | ")}")
            failures += result.lines().filterNot { "ok" in it || it.startsWith("Speicher") }
            Thread.sleep(1_000)
            am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(":mt") }.forEach { android.os.Process.killProcess(it.pid) }
            Thread.sleep(1_500)
        }
        File(app.filesDir, "mt_lowmem").delete()
        org.junit.Assert.assertTrue("Auf 32-Bit-ARM fehlgeschlagen: $failures", failures.isEmpty())
    }
}
