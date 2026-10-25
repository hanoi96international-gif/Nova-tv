package de.ayutv.player

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Sprechpausen aus dem Ton: je 100 ms, wie laut es im Sprachband (300–3000 Hz) ist und wie stark die
 * Lautstärke von Silbe zu Silbe schwankt (Sprache pulsiert, Musik klingt gleichmäßiger). Wird vom Ton
 * gefüttert, während der Film läuft ([SpeechTapProcessor]); kostet kaum Rechenzeit.
 */
class SpeechActivity(maxMinutes: Int = 300) {
    private val bins = maxMinutes * 60 * 10
    private val level = FloatArray(bins) { Float.NaN }
    private val flux = FloatArray(bins)

    // Filterzustand (zwei Biquads: Hochpass 300 Hz, Tiefpass 3000 Hz)
    private var rate = 0
    private val hp = Biquad()
    private val lp = Biquad()
    private var frameEnergy = 0.0
    private var frameSamples = 0
    private var frameSize = 0
    private var binIndex = -1
    private var binSum = 0.0
    private var binFlux = 0.0
    private var binFrames = 0
    private var lastFrameLog = Double.NaN
    private var sampleIndex = 0L
    private var startMs = 0L
    @Volatile private var heardBins = 0

    /** Neuer Abschnitt (Start oder Sprung im Film): ab [positionMs] beginnt der Ton. */
    @Synchronized
    fun restart(positionMs: Long, sampleRate: Int) {
        flushBin()
        if (sampleRate <= 0) { rate = 0; return }
        if (sampleRate != rate) {
            rate = sampleRate
            hp.highPass(300.0, sampleRate)
            lp.lowPass(3000.0, sampleRate)
            frameSize = sampleRate / 100
        }
        hp.reset(); lp.reset()
        startMs = positionMs
        sampleIndex = 0
        frameEnergy = 0.0; frameSamples = 0
        binIndex = -1; binSum = 0.0; binFlux = 0.0; binFrames = 0
        lastFrameLog = Double.NaN
    }

    /** Anderer Film: alles Gehörte vergessen. */
    @Synchronized
    fun clear() {
        level.fill(Float.NaN)
        flux.fill(0f)
        heardBins = 0
        rate = 0
    }

    /** Monosamples (−1…1) in Filmreihenfolge. */
    @Synchronized
    fun add(samples: FloatArray, count: Int) {
        if (rate == 0) return
        for (k in 0 until count) add(samples[k])
    }

    private fun add(sample: Float) {
        val y = lp.process(hp.process(sample.toDouble()))
        frameEnergy += y * y
        if (++frameSamples < frameSize) return
        val log = ln(frameEnergy / frameSamples + 1e-10)
        val ms = startMs + sampleIndex * 1000 / rate
        sampleIndex += frameSamples
        frameEnergy = 0.0; frameSamples = 0
        val bin = (ms / 100).toInt()
        if (bin != binIndex) {
            flushBin()
            binIndex = bin
        }
        binSum += log
        if (!lastFrameLog.isNaN()) binFlux += abs(log - lastFrameLog)
        lastFrameLog = log
        binFrames++
    }

    private fun flushBin() {
        if (binIndex in 0 until bins && binFrames >= 5) {
            if (level[binIndex].isNaN()) heardBins++
            level[binIndex] = (binSum / binFrames).toFloat()
            flux[binIndex] = (binFlux / binFrames).toFloat()
        }
        binSum = 0.0; binFlux = 0.0; binFrames = 0
    }

    /** Wie viele Sekunden Ton insgesamt ausgewertet sind. */
    fun seconds(): Int = heardBins / 10

    /**
     * Sprachsignal je 100 ms zwischen [fromMs] und [toMs] (sonst und wo nicht gehört: NaN): Lautstärke
     * und Silbenschwankung, jeweils über dem örtlichen Grundpegel (±15 s), damit laute Musikszenen
     * nicht als Dauersprechen gelten.
     */
    fun signal(fromMs: Long = 0, toMs: Long = Long.MAX_VALUE): FloatArray {
        val from = (fromMs / 100).coerceIn(0, bins.toLong()).toInt()
        val to = (toMs / 100).coerceIn(0, bins.toLong()).toInt()
        // Kurz unter Sperre kopieren: Der Ton läuft weiter, während gerechnet wird.
        val l: FloatArray
        val f: FloatArray
        synchronized(this) {
            l = level.copyOfRange(from, to)
            f = flux.copyOfRange(from, to)
        }
        val out = FloatArray(bins) { Float.NaN }
        val known = l.indices.filter { !l[it].isNaN() }
        if (known.isEmpty()) return out
        val lb = localBaseline(l, known)
        val fb = localBaseline(f, known)
        for (i in known) out[from + i] = (l[i] - lb[i]) + 2f * (f[i] - fb[i])
        return out
    }

    private fun localBaseline(values: FloatArray, known: List<Int>): FloatArray {
        val base = FloatArray(values.size)
        val window = 150
        // Median über ein gleitendes Fenster, in Schritten von 1 s (genügt für einen Grundpegel).
        var i = 0
        while (i < known.size) {
            val center = known[i]
            val from = known.binarySearch(center - window).let { if (it < 0) -it - 1 else it }
            val to = known.binarySearch(center + window).let { if (it < 0) -it - 1 else it + 1 }
            val sample = FloatArray(to - from) { values[known[from + it]] }
            sample.sort()
            val median = sample[sample.size / 2]
            var j = i
            while (j < known.size && known[j] < center + 10) { base[known[j]] = median; j++ }
            i = j
        }
        return base
    }

    private class Biquad {
        var b0 = 1.0; var b1 = 0.0; var b2 = 0.0; var a1 = 0.0; var a2 = 0.0
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        fun reset() { x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }
        fun process(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
        fun lowPass(f: Double, rate: Int) = set(f, rate, low = true)
        fun highPass(f: Double, rate: Int) = set(f, rate, low = false)
        private fun set(f: Double, rate: Int, low: Boolean) {
            val w = 2 * Math.PI * f / rate
            val alpha = kotlin.math.sin(w) / (2 * 0.7071)
            val cos = kotlin.math.cos(w)
            val a0 = 1 + alpha
            if (low) { b0 = (1 - cos) / 2; b1 = 1 - cos; b2 = (1 - cos) / 2 } else { b0 = (1 + cos) / 2; b1 = -(1 + cos); b2 = (1 + cos) / 2 }
            a1 = -2 * cos; a2 = 1 - alpha
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0
        }
    }
}

/**
 * Richtet geladene Untertitel am gesprochenen Ton aus, wenn der Film keine eigenen Untertitel hat:
 * Wo Untertitel stehen, wird gesprochen. Gesucht wird der Versatz (bis ±10 min) und die Bildrate,
 * bei der Untertitel und Sprachsignal am besten übereinanderliegen; nur ein klar herausragender
 * Treffer zählt, sonst bleibt alles, wie es ist.
 */
object SpeechSync {
    private const val BIN_MS = 100L
    private const val COARSE = 5           // grob: 500-ms-Schritte
    private const val MAX_SHIFT_MS = 600_000L
    const val MIN_SECONDS = 240            // mindestens 4 min gehörter Ton
    private val RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)

    /** [signal] aus [SpeechActivity.signal], [cues] Start/Ende der geladenen Untertitel (Dateizeit, ms). */
    fun estimate(signal: FloatArray, cues: List<Pair<Long, Long>>): SubtitleSync.Fit? {
        val heard = signal.indices.filter { !signal[it].isNaN() }
        if (heard.size < MIN_SECONDS * 10 || cues.size < 20) return null
        // Sprachsignal standardisieren (nur gehörte Stellen).
        val mean = heard.sumOf { signal[it].toDouble() } / heard.size
        val sd = sqrt(heard.sumOf { (signal[it] - mean) * (signal[it] - mean) } / heard.size).takeIf { it > 1e-6 } ?: return null
        val x = FloatArray(signal.size) { if (signal[it].isNaN()) 0f else ((signal[it] - mean) / sd).toFloat() }

        val sortedCues = cues.sortedBy { it.first }
        val first = heard.first().toLong() * BIN_MS
        val last = heard.last().toLong() * BIN_MS
        val found = RATIOS.mapNotNull { ratio -> search(x, heard, sortedCues, ratio)?.let { Found(ratio, it.first, it.second, it.third) } }
        val winner = found.maxByOrNull { it.z } ?: return null
        // Andere Lösung = andere Zuordnung irgendwo im gehörten Stück (Bildraten wie 24 und 23,976 fps
        // liegen über kurze Strecken praktisch aufeinander und sind keine Konkurrenz).
        fun differs(o: Found) = listOf(first, last).any { t ->
            abs(o.timing.toSubtitle(t) - winner.timing.toSubtitle(t)) * winner.ratio > SAME_MS
        }
        val runnerUp = maxOf(winner.secondZ, found.filter { it !== winner && differs(it) }.maxOfOrNull { it.z } ?: 0.0)
        // Eindeutig: weit über dem Rauschen und deutlich vor der zweitbesten Lösung.
        if (winner.z < MIN_Z || winner.z < runnerUp * CLEAR) return null
        // Liegen mehrere Bildraten im gehörten Stück praktisch gleich gut, die übliche nehmen (zuerst
        // "gleiche Bildrate"): 24 statt 23,976 fps liefe sonst nach einer Stunde 4 s daneben.
        val chosen = found.filter { !differs(it) && it.z >= winner.z - TIE_Z }.minBy { RATIOS.indexOf(it.ratio) }
        return SubtitleSync.Fit(chosen.timing, (winner.z * 10).toInt(), heard.size / 10)
    }

    private class Found(val ratio: Double, offset: Long, val z: Double, val secondZ: Double) {
        val timing = SubtitleTiming(ratio, offset)
    }

    /** Bester Versatz für eine Bildrate: grob in 500-ms-Schritten, dann fein in 100 ms. */
    private fun search(x: FloatArray, heard: List<Int>, cues: List<Pair<Long, Long>>, ratio: Double): Triple<Long, Double, Double>? {
        val from = heard.first()
        val to = heard.last()
        // Untertitel als 0/1-Folge auf dem 100-ms-Raster der Datei (mit Bildrate umgerechnet in Filmzeit).
        val subEnd = ((cues.maxOf { it.second } * ratio) / BIN_MS).toInt() + 1
        val sub = FloatArray(subEnd)
        for ((a, b) in cues) {
            val s = max(0, ((a * ratio) / BIN_MS).toInt())
            val e = min(subEnd, ((b * ratio) / BIN_MS).toInt())
            for (i in s until e) sub[i] = 1f
        }
        val maxShift = (MAX_SHIFT_MS / BIN_MS).toInt()
        // Grob: Summe über 500-ms-Blöcke
        val coarseX = block(x, from, to, COARSE)
        val coarseSub = block(sub, 0, sub.size - 1, COARSE)
        val shifts = (-maxShift / COARSE)..(maxShift / COARSE)
        val scores = DoubleArray(shifts.count())
        val base = from / COARSE
        for ((k, shift) in shifts.withIndex()) {
            var s = 0.0
            for (i in coarseX.indices) {
                val j = base + i - shift
                if (j < 0 || j >= coarseSub.size) continue
                s += coarseX[i] * coarseSub[j]
            }
            scores[k] = s
        }
        val m = scores.average()
        val sd = sqrt(scores.sumOf { (it - m) * (it - m) } / scores.size).takeIf { it > 1e-9 } ?: return null
        val top = scores.indices.maxBy { scores[it] }
        // Zweitbester Gipfel mindestens 3 s entfernt
        val runner = scores.indices.filter { abs(it - top) > 6 }.maxOfOrNull { scores[it] } ?: m
        // Fein: ±1 s um den groben Treffer in 100-ms-Schritten
        val coarseShift = shifts.first + top
        var bestShift = coarseShift * COARSE
        var bestScore = Double.NEGATIVE_INFINITY
        for (shift in coarseShift * COARSE - 10..coarseShift * COARSE + 10) {
            var s = 0.0
            for (i in heard) {
                val j = i - shift
                if (j < 0 || j >= sub.size) continue
                s += x[i] * sub[j]
            }
            if (s > bestScore) { bestScore = s; bestShift = shift }
        }
        return Triple(bestShift * BIN_MS, (scores[top] - m) / sd, (runner - m) / sd)
    }

    private fun block(values: FloatArray, from: Int, to: Int, size: Int): FloatArray {
        val start = from / size
        val out = FloatArray(to / size - start + 1)
        for (i in from..to) out[i / size - start] += values[i]
        return out
    }

    private const val MIN_Z = 6.0
    private const val CLEAR = 1.3
    private const val SAME_MS = 1_500L
    private const val TIE_Z = 1.0
}
