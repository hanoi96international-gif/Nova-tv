package de.ayutv.desktop

import java.io.File
import java.time.LocalDateTime

/**
 * Abstürze mitschreiben (im Datenordner, „absturz.log“), damit sich die Ursache finden lässt. Abstürze in VLC oder
 * der Grafik landen als „hs_err_…“-Datei im selben Ordner (siehe jvmArgs in build.gradle.kts).
 */
object CrashLog {
    private var dir: File? = null
    private var written = 0

    fun install(dir: File) {
        this.dir = dir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            write(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Fehler mitschreiben (höchstens 50 je Programmstart, damit eine Endlosschleife die Platte nicht füllt). */
    @Synchronized
    fun write(where: String, error: Throwable) {
        val target = dir ?: return
        if (written++ >= 50) return
        runCatching {
            target.mkdirs()
            File(target, "absturz.log").appendText("${LocalDateTime.now()} [$where]\n${error.stackTraceToString()}\n")
        }
    }
}
