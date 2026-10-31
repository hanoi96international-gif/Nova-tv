package de.ayutv.desktop

import java.io.File
import java.time.LocalDateTime

/**
 * Abstürze mitschreiben (im Datenordner, „absturz.log“), damit sich die Ursache finden lässt. Abstürze in VLC oder
 * der Grafik landen als „hs_err_…“-Datei im selben Ordner (siehe jvmArgs in build.gradle.kts).
 */
object CrashLog {
    fun install(dir: File) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                dir.mkdirs()
                File(dir, "absturz.log").appendText("${LocalDateTime.now()} [${thread.name}]\n${error.stackTraceToString()}\n")
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
