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
 * Prüft Texterkennung und Übersetzung mit dem DnA-Rechenkern für mehrere Sprachen.
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
        // Je Runde ein frischer Übersetzer-Prozess (Speicher-Höchststand je Runde). Auf 32 Bit rechnet von
        // selbst der DnA-Rechenkern (ONNX Runtime stürzt auf dem echten Fire TV Stick 4K ab).
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        File(app.filesDir, "mt_engine").delete()
        for ((round, language) in listOf("de", "es", "id", "fr").withIndex()) {
            val result = runBlocking { TranslatorSelfTest.run(language) }
            Translators.remote.release()
            log("round $round $language: ${result.replace("\n", " | ")}")
            failures += result.lines().filterNot { "ok" in it || it.startsWith("Speicher") || it.startsWith("Prozessor") || it.startsWith("Rechenkern") }
            if ("Rechenkern: DnA" !in result) failures += "Runde $round: nicht mit dem DnA-Rechenkern gerechnet"
            Thread.sleep(1_000)
            am.runningAppProcesses.orEmpty().filter { it.processName.endsWith(":mt") }.forEach { android.os.Process.killProcess(it.pid) }
            Thread.sleep(1_500)
        }
        org.junit.Assert.assertTrue("Auf 32-Bit-ARM fehlgeschlagen: $failures", failures.isEmpty())
    }
}
