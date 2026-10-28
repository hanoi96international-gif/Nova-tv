package de.ayutv.data.parse

import java.io.File
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Was über die Qualität jeder Senderfassung bekannt ist: gemessene Auflösung (beim Schauen oder vom
 * Hintergrund-Check), Startzeit und ob sie zuletzt ausfiel. Daraus ergibt sich, welche Fassung eines doppelt
 * geführten Senders die beste ist, und welche Sender eine Mindestqualität erfüllen. Ohne Messung zählt, was
 * der Name verrät („FHD“, „4K“, „SD“ …).
 */
object ChannelQuality {
    data class Info(val height: Int, val startupMs: Long, val failures: Int, val checkedAt: Long) {
        val broken: Boolean get() = failures >= 2
    }

    private val known = ConcurrentHashMap<String, Info>()
    @Volatile private var dirty = false

    /** 4K bevorzugen (sonst gilt Full HD als beste Fassung: stabiler, weniger Daten). */
    @Volatile var preferUhd = false
    /** Mindesthöhe in Pixeln (0 = alle). Sender mit bekannter, kleinerer Auflösung werden ausgeblendet. */
    @Volatile var minHeight = 0
    /** Sender, die im Check wiederholt nicht liefen, ausblenden. */
    @Volatile var hideBroken = false

    fun configure(minHeight: Int, preferUhd: Boolean, hideBroken: Boolean) {
        this.minHeight = minHeight
        this.preferUhd = preferUhd
        this.hideBroken = hideBroken
    }

    fun info(key: String): Info? = known[key]

    val size: Int get() = known.size

    /** Beim Schauen gemessen (kostet nichts extra). */
    fun recordPlayback(key: String, height: Int, startupMs: Long, now: Long = System.currentTimeMillis()) {
        if (height <= 0) return
        known[key] = Info(height, startupMs, 0, now)
        dirty = true
    }

    /** Vom Check gemessen oder ausgefallen ([height] 0). */
    fun recordCheck(key: String, height: Int, startupMs: Long, now: Long = System.currentTimeMillis()) {
        val previous = known[key]
        known[key] = if (height > 0) Info(height, startupMs, 0, now)
        else Info(previous?.height ?: 0, previous?.startupMs ?: 0, (previous?.failures ?: 0) + 1, now)
        dirty = true
    }

    fun recordFailure(key: String, now: Long = System.currentTimeMillis()) = recordCheck(key, 0, 0, now)

    /** Auflösung aus dem Namen: „4K/UHD/2160“, „FHD/1080“, „HD/720“, „SD/576/480“; sonst 0 (unbekannt). */
    fun heightFromName(name: String): Int {
        val n = " " + Normalizer.normalize(name, Normalizer.Form.NFKD).lowercase() + " "
        return when {
            UHD.containsMatchIn(n) -> 2160
            FHD.containsMatchIn(n) -> 1080
            HD.containsMatchIn(n) -> 720
            SD.containsMatchIn(n) -> 576
            else -> 0
        }
    }

    /** Gemessene oder aus dem Namen geschätzte Auflösung, 0 = unbekannt. */
    fun height(key: String, name: String): Int = known[key]?.height?.takeIf { it > 0 } ?: heightFromName(name)

    /**
     * Bewertung einer Fassung, kleiner = besser. Ausgefallene ganz hinten; sonst die höchste Auflösung bis zur
     * Wunschgrenze (Full HD, mit [preferUhd] 4K), Fassungen darüber nach den passenden; Ersatz-/Backup-Fassungen
     * und sehr langsam startende etwas weiter hinten. Unbekannte Auflösung zählt wie 720p, knapp dahinter.
     */
    fun score(key: String, name: String): Int {
        val info = known[key]
        val cap = if (preferUhd) 2160 else 1080
        val measured = height(key, name)
        val h = if (measured > 0) measured else 700
        var score = 3000 - minOf(h, cap)
        if (h > cap) score += 900
        if (BACKUP.containsMatchIn(" " + name.lowercase() + " ")) score += 600
        if (info != null && info.startupMs > 4_000) score += 60
        if (info != null && info.broken) score += 10_000
        return score
    }

    /** Erfüllt die Fassung die Mindestqualität (und läuft sie, falls ausgefallene ausgeblendet werden)? */
    fun passes(key: String, name: String): Boolean {
        if (hideBroken && known[key]?.broken == true) return false
        if (minHeight <= 0) return true
        val h = height(key, name)
        // Unbekannt bleibt sichtbar: lieber einmal SD sehen als einen Sender nie finden
        return h == 0 || h >= minHeight * 9 / 10
    }

    // ---------------------------------------------------------------- Speichern

    fun load(file: File) {
        runCatching {
            file.takeIf { it.isFile }?.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) known[p[0]] = Info(p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toLongOrNull() ?: 0)
            }
        }
    }

    fun saveIfChanged(file: File) {
        if (!dirty) return
        dirty = false
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.bufferedWriter().use { out ->
                for ((key, i) in known) out.append(key.replace('\t', ' ')).append('\t').append(i.height.toString()).append('\t')
                    .append(i.startupMs.toString()).append('\t').append(i.failures.toString()).append('\t').append(i.checkedAt.toString()).append('\n')
            }
            tmp.renameTo(file)
        }
    }

    /** Nur für Tests. */
    fun clear() = known.clear()

    private val UHD = Regex("""\b(uhd|4k|2160p?)\b""")
    private val FHD = Regex("""\b(fhd|1080[ip]?|full ?hd)\b""")
    private val HD = Regex("""\b(hd|720p?)\b""")
    private val SD = Regex("""\b(sd|576[ip]?|480[ip]?)\b""")
    private val BACKUP = Regex("""\b(backup|alt|bkp|reserve|test|bar)\b|\(2\)""")
}
