package de.ayutv.data

import android.content.Context
import java.io.File

/**
 * Speicher auf dem Gerät im Blick behalten: Ein Fire TV Stick hat nur wenige GB frei. Läuft er voll, wird das ganze
 * Gerät träge und das System beendet Apps. Übersetzungsmodelle (je Sprache gut 100 MB), Untertitel, übersetzte Zeilen,
 * nächtlich gelesene Untertitel und geladene Updates werden deshalb nach einer Weile ohne Nutzung gelöscht; wird der
 * Platz knapp, früher.
 */
object StorageCleaner {
    data class Part(val label: String, val bytes: Long)

    private const val DAY = 24 * 3_600_000L
    /** Unter so viel freiem Platz wird gründlicher aufgeräumt. */
    private const val LOW_FREE_BYTES = 600L * 1024 * 1024

    /** Markierung „zuletzt benutzt“ in einem Modellordner (der Ordner selbst ändert sich beim Lesen nicht). */
    const val USED_MARK = ".used"

    /** Belegung nach Bereichen, größter zuerst. */
    fun usage(context: Context): List<Part> {
        val files = context.filesDir
        val cache = context.cacheDir
        val parts = listOf(
            Part("Übersetzungsmodelle", size(File(files, "mt"))),
            Part("Texterkennung", size(File(files, "ocr"))),
            Part("Untertitel", size(File(files, "subtitles")) + size(File(files, "subtitle-lines")) + size(File(files, "embedded-lines"))),
            Part("Logos und Poster", size(File(cache, "images"))),
            Part("Zeitversatz", size(File(cache, "timeshift"))),
            Part("Updates", size(File(cache, "update"))),
            Part("Aufnahmen", size(File(context.getExternalFilesDir(null) ?: files, "recordings"))),
            Part("Datenbank", context.databaseList().sumOf { context.getDatabasePath(it).length() }),
        )
        return parts.filter { it.bytes > 0 }.sortedByDescending { it.bytes }
    }

    fun freeBytes(context: Context): Long = runCatching { context.filesDir.usableSpace }.getOrDefault(0L)

    fun isLow(context: Context): Boolean = freeBytes(context) in 1 until LOW_FREE_BYTES

    /** Aufräumen; liefert, was frei wurde (für Anzeige und Protokoll). */
    fun clean(context: Context, now: Long = System.currentTimeMillis()): String {
        val low = isLow(context)
        val files = context.filesDir
        var freed = 0L
        var models = 0
        // Übersetzungsmodelle: 30 Tage unbenutzt (bei wenig Platz 3 Tage).
        val modelAge = if (low) 3 * DAY else 30 * DAY
        File(files, "mt").listFiles().orEmpty().filter { it.isDirectory }.forEach { folder ->
            val used = File(folder, USED_MARK).takeIf { it.isFile }?.lastModified() ?: folder.lastModified()
            if (now - used > modelAge) {
                freed += size(folder)
                if (folder.deleteRecursively()) models++
            }
        }
        // Untertitel, übersetzte und nächtlich gelesene Zeilen: 60 Tage (bei wenig Platz 14 Tage).
        val subtitleAge = if (low) 14 * DAY else 60 * DAY
        var subtitles = 0
        for (name in listOf("subtitles", "subtitle-lines", "embedded-lines")) {
            File(files, name).listFiles().orEmpty().filter { it.isFile && now - it.lastModified() > subtitleAge }.forEach {
                freed += it.length()
                if (it.delete()) subtitles++
            }
        }
        // Geladene Updates werden nach der Installation nicht mehr gebraucht.
        File(context.cacheDir, "update").listFiles().orEmpty().filter { now - it.lastModified() > DAY }.forEach {
            freed += it.length()
            it.delete()
        }
        if (freed <= 0) return "nichts zu löschen"
        val what = buildList {
            if (models > 0) add(if (models == 1) "1 Übersetzungsmodell" else "$models Übersetzungsmodelle")
            if (subtitles > 0) add("$subtitles Untertitel-Dateien")
        }
        return "${format(freed)} frei geworden" + if (what.isEmpty()) "" else " (${what.joinToString(", ")})"
    }

    /** Für Anzeige: „1,2 GB“ oder „350 MB“. */
    fun format(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
        String.format(java.util.Locale.GERMAN, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    } else "${bytes / (1024 * 1024)} MB"

    private fun size(file: File): Long = when {
        !file.exists() -> 0L
        file.isFile -> file.length()
        else -> file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
