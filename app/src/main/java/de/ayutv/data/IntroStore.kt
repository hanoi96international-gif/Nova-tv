package de.ayutv.data

import android.content.Context
import de.ayutv.data.parse.IntroSkip
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Gelernte Intros pro Serie: Beginn und Länge vom Überspringen (ms) und, wenn der Ton mitgehört werden
 * konnte, der Klang des Intro-Anfangs und der Anfang der zuletzt gesehenen Folge (zum Vergleichen).
 */
class IntroStore(context: Context) {
    private val prefs = context.getSharedPreferences("intros", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "intros")

    /** Klang der ersten Sekunden des Intros (Lautstärke je 100 ms) und seine Länge in 100-ms-Schritten. */
    class Fingerprint(val pattern: FloatArray, val lengthBins: Int)

    fun get(seriesKey: String): IntroSkip.Intro? {
        val parts = prefs.getString(seriesKey, null)?.split(',') ?: return null
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val length = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return IntroSkip.Intro(start, length)
    }

    fun put(seriesKey: String, intro: IntroSkip.Intro) {
        prefs.edit().putString(seriesKey, "${intro.startMs},${intro.lengthMs}").apply()
    }

    /** Gelernte Länge des Abspanns (ms vor dem Ende), null = unbekannt. */
    fun credits(seriesKey: String): Long? = prefs.getString(CREDITS + seriesKey, null)?.toLongOrNull()

    fun saveCredits(seriesKey: String, remainingMs: Long) {
        prefs.edit().putString(CREDITS + seriesKey, remainingMs.toString()).apply()
    }

    fun fingerprint(seriesKey: String): Fingerprint? = runCatching {
        DataInputStream(file(seriesKey, "intro").inputStream().buffered()).use { input ->
            val length = input.readInt()
            Fingerprint(readFloats(input), length)
        }
    }.getOrNull()

    fun saveFingerprint(seriesKey: String, fingerprint: Fingerprint) = write(file(seriesKey, "intro")) { out ->
        out.writeInt(fingerprint.lengthBins)
        writeFloats(out, fingerprint.pattern)
    }

    fun opening(seriesKey: String): FloatArray? = runCatching {
        DataInputStream(file(seriesKey, "opening").inputStream().buffered()).use(::readFloats)
    }.getOrNull()

    fun saveOpening(seriesKey: String, levels: FloatArray) = write(file(seriesKey, "opening")) { writeFloats(it, levels) }

    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun restore(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }

    private fun file(seriesKey: String, kind: String) =
        File(dir, seriesKey.replace(Regex("[^A-Za-z0-9_-]"), "_").takeLast(100) + "." + kind)

    private fun write(target: File, block: (DataOutputStream) -> Unit) {
        runCatching {
            dir.mkdirs()
            val tmp = File(target.path + ".tmp")
            DataOutputStream(tmp.outputStream().buffered()).use(block)
            tmp.renameTo(target)
        }
    }

    private fun writeFloats(out: DataOutputStream, values: FloatArray) {
        out.writeInt(values.size)
        values.forEach(out::writeFloat)
    }

    private fun readFloats(input: DataInputStream): FloatArray = FloatArray(input.readInt().coerceIn(0, 20_000)) { input.readFloat() }

    private companion object {
        const val CREDITS = "credits:"
    }
}
