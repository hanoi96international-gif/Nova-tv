package de.ayutv.data

/** Für Fehlerberichte: Zugangsdaten unkenntlich machen, Server und Fehlermeldungen bleiben lesbar. */
object Redaction {
    private val URL = Regex("""(?i)\b(https?|rtmp|rtsp|udp)://(?:[^\s/@"'<>]+@)?([^\s/:?"'<>]+)(:\d+)?[^\s"'<>]*""")
    private val SECRET = Regex("""(?i)\b(password|passwort|pass|pwd|username|user|token|api[-_]?key|apikey|key|auth|authorization)(["']?\s*[=:]\s*["']?)[^\s&"',;]+""")
    private val LONG_ID = Regex("""\b[A-Za-z0-9_\-]{28,}\b""")
    private val MAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")

    /** Zugangsdaten unkenntlich machen: Adressen nur mit Server, keine Passwörter, Schlüssel oder E-Mail-Adressen. */
    fun apply(text: String): String = text
        .replace(URL) { m -> "${m.groupValues[1]}://${m.groupValues[2]}${m.groupValues[3]}/…" }
        .replace(SECRET) { m -> "${m.groupValues[1]}${m.groupValues[2]}***" }
        .replace(MAIL, "***@***")
        .replace(LONG_ID, "***")
}
