package de.ayutv.desktop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Protokoll im Datenordner (`%APPDATA%\DnA-TV\logs\dna-tv.log`): Start, Fehler, Abstürze, Stream-Probleme.
 * Wird bei 2 MB umbenannt (eine ältere Datei bleibt). Nichts Vertrauliches: Adressen mit Zugangsdaten werden gekürzt.
 */
object Log {
    private const val MAX_BYTES = 2_000_000L
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    @Volatile private var file: File? = null

    val current: File? get() = file

    fun init(dataDir: File) {
        val dir = File(dataDir, "logs").apply { mkdirs() }
        val target = File(dir, "dna-tv.log")
        if (target.length() > MAX_BYTES) {
            val old = File(dir, "dna-tv.1.log")
            old.delete()
            target.renameTo(old)
        }
        file = File(dir, "dna-tv.log")
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> error("Absturz in ${thread.name}", error) }
        info("Start ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
    }

    fun info(message: String) = write("INFO", message, null)
    fun warn(message: String, error: Throwable? = null) = write("WARN", message, error)
    fun error(message: String, error: Throwable? = null) = write("FEHLER", message, error)

    @Synchronized
    private fun write(level: String, message: String, error: Throwable?) {
        val target = file ?: return
        val line = buildString {
            append(LocalDateTime.now().format(TIME)).append(' ').append(level).append(' ').append(redact(message))
            if (error != null) {
                append('\n')
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                append(redact(trace.lines().take(40).joinToString("\n")))
            }
            append('\n')
        }
        runCatching { target.appendText(line) }
    }

    /** Zugangsdaten aus Adressen entfernen (username=…, password=…, /live/<user>/<pass>/). */
    fun redact(text: String): String = text
        .replace(Regex("""(?i)(username|password|pass|user)=[^&\s]+"""), "$1=***")
        .replace(Regex("""/(live|movie|series)/[^/\s]+/[^/\s]+/"""), "/$1/***/***/")
}
