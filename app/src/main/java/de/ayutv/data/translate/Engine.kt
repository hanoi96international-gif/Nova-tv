package de.ayutv.data.translate

import android.os.Build
import android.os.Process
import java.io.File

/**
 * Womit Übersetzer und Texterkennung rechnen:
 * - ONNX Runtime (nativ, schnell), auf 64-Bit-Geräten;
 * - DnA-Rechenkern (reines Kotlin, kann nicht nativ abstürzen, braucht kaum Arbeitsspeicher), auf
 *   32-Bit-ARM (Fire TV Stick 4K: dort stürzt ONNX Runtime ab) und dort, wo ONNX Runtime in dieser
 *   Version schon einmal abgestürzt ist (mit der nächsten Version wird es wieder versucht).
 */
object Engine {
    enum class Kind(val label: String) {
        ORT("ONNX Runtime"),
        KERN("DnA-Rechenkern (ohne native Bibliothek)"),
    }

    private const val OVERRIDE = "mt_engine"
    private const val ORT_CRASHED = "ort_crashed"

    fun select(baseDir: File): Kind {
        when (runCatching { File(baseDir, OVERRIDE).readText().trim() }.getOrNull()) {
            "kern" -> return Kind.KERN
            "ort" -> return Kind.ORT
        }
        val crashed = File(baseDir, ORT_CRASHED)
        if (crashed.isFile) {
            // Mit jeder neuen Version einmal wieder das schnelle ONNX Runtime versuchen (der Absturz kann
            // behoben sein); stürzt es wieder ab, schreibt markOrtCrash die neue Version hinein.
            val version = runCatching { crashed.readText().trim() }.getOrNull()
            if (version == de.ayutv.BuildConfig.VERSION_NAME || version.isNullOrEmpty()) return Kind.KERN
            crashed.delete()
        }
        val is64 = if (Build.VERSION.SDK_INT >= 23) Process.is64Bit() else Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        return if (is64) Kind.ORT else Kind.KERN
    }

    /** ONNX Runtime ist hier nativ abgestürzt: ab jetzt immer mit dem Rechenkern. */
    fun markOrtCrash(baseDir: File) {
        runCatching { File(baseDir, ORT_CRASHED).writeText(de.ayutv.BuildConfig.VERSION_NAME) }
    }
}
