package de.ayutv.player

import kotlin.math.sqrt

/**
 * Intro am Ton erkennen. Grundlage ist die Lautstärke je 100 ms (aus [SpeechActivity], NaN = nicht gehört).
 * Die Titelmusik einer Serie ist in jeder Folge dieselbe Aufnahme, Rückblick und Handlung sind es nicht:
 * Der längste gemeinsame Abschnitt der Anfänge zweier Folgen ist das Intro.
 */
object IntroMatch {
    /** Gemeinsamer Abschnitt: Beginn in der ersten und zweiten Folge und Länge, alles in 100-ms-Schritten. */
    data class Segment(val startA: Int, val startB: Int, val length: Int)

    /** Fenster für den Vergleich (5 s) und wie ähnlich es sein muss. */
    private const val WINDOW = 50
    private const val SIMILAR = 0.85
    /** Schrittweite beim Vergleich (1 s): genau genug für den Anfang, schnell genug für den Fire TV Stick. */
    private const val STEP = 10

    /**
     * Längster Abschnitt von mindestens [minBins], der in [a] und [b] gleich klingt (Versatz bis [maxLag]).
     * Null, wenn es keinen gibt (andere Serie, kaum Ton gehört, Intro übersprungen).
     */
    fun commonSegment(a: FloatArray, b: FloatArray, minBins: Int = 150, maxLag: Int = 2400): Segment? {
        var best: Segment? = null
        for (lag in -maxLag..maxLag) {
            // a[i] gegen b[i + lag]
            val from = maxOf(0, -lag)
            val to = minOf(a.size, b.size - lag)
            if (to - from < minBins) continue
            var runStart = -1
            var lastSimilar = -1
            fun close() {
                val length = lastSimilar + WINDOW - runStart
                if (length >= minBins && length > (best?.length ?: 0)) best = Segment(runStart, runStart + lag, length)
                runStart = -1
            }
            var i = from
            while (i + WINDOW <= to) {
                if (correlation(a, i, b, i + lag, WINDOW) >= SIMILAR) {
                    if (runStart < 0) runStart = i
                    lastSimilar = i
                } else if (runStart >= 0) {
                    close()
                }
                i += STEP
            }
            if (runStart >= 0) close()
        }
        return best?.let { refine(a, b, it) }
    }

    /** Ränder genauer (2-s-Fenster): Das 5-s-Fenster ragt sonst an beiden Enden ein Stück über das Intro hinaus. */
    private fun refine(a: FloatArray, b: FloatArray, s: Segment): Segment {
        val lag = s.startB - s.startA
        val fine = 20
        fun same(i: Int) = correlation(a, i, b, i + lag, fine) >= SIMILAR
        var start = s.startA
        for (i in s.startA..s.startA + WINDOW) if (same(i) && same(i + fine / 2)) { start = i; break }
        var end = s.startA + s.length
        for (i in s.startA + s.length - fine downTo s.startA + s.length - WINDOW - fine) if (same(i) && same(i - fine / 2)) { end = i + fine; break }
        return if (end - start >= fine) Segment(start, start + lag, end - start) else s
    }

    /**
     * Wo [pattern] (Anfang des Intros) in [signal] zwischen [fromBin] und [toBin] beginnt, oder null.
     * Wird im Lauf der Folge mit dem gerade Gehörten aufgerufen.
     */
    fun find(signal: FloatArray, pattern: FloatArray, fromBin: Int, toBin: Int, similar: Double = 0.9): Int? {
        if (pattern.size < WINDOW) return null
        var bestAt = -1
        var bestScore = similar
        val last = minOf(toBin, signal.size) - pattern.size
        for (start in maxOf(0, fromBin)..last) {
            val score = correlation(signal, start, pattern, 0, pattern.size)
            if (score >= bestScore) {
                bestScore = score
                bestAt = start
            }
        }
        return bestAt.takeIf { it >= 0 }
    }

    /** Pearson-Korrelation zweier gleich langer Abschnitte; Stille, Lücken oder Gleichklang zählen nicht. */
    private fun correlation(x: FloatArray, xs: Int, y: FloatArray, ys: Int, n: Int): Double {
        if (xs < 0 || ys < 0 || xs + n > x.size || ys + n > y.size) return 0.0
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (k in 0 until n) {
            val a = x[xs + k]
            val b = y[ys + k]
            if (a.isNaN() || b.isNaN()) return 0.0
            sx += a; sy += b; sxx += a * a; syy += b * b; sxy += a * b
        }
        val vx = sxx - sx * sx / n
        val vy = syy - sy * sy / n
        // Kaum Schwankung (Stille, Rauschen): kein verwertbarer Klang.
        if (vx < 0.5 * n * 0.1 || vy < 0.5 * n * 0.1) return 0.0
        return (sxy - sx * sy / n) / sqrt(vx * vy)
    }
}
