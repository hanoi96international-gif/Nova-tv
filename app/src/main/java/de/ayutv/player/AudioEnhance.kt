package de.ayutv.player

import de.ayutv.data.AudioMode
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Ton für leise Abende. Nachtmodus: Laute Stellen (Explosionen, Musik) werden gedämpft, leise (Flüstern)
 * angehoben. Dialoge verstärken: Sprache liegt bei 5.1 im Center-Kanal, bei Stereo in der Mitte
 * (links + rechts gleich); sie wird lauter, der Rest leiser. Ein weicher Begrenzer verhindert Übersteuern.
 *
 * Arbeitet auf verschachtelten Proben (−1…1), Kanalfolge wie bei Android: links, rechts, Center, Bass, …
 */
class AudioEnhance {
    @Volatile var mode: AudioMode = AudioMode.NORMAL
    private var gain = 1f
    private var envelope = 0f
    private var rate = 0
    private var attack = 0f
    private var release = 0f

    fun reset() {
        gain = 1f
        envelope = 0f
    }

    fun process(samples: FloatArray, frames: Int, channels: Int, sampleRate: Int) {
        val m = mode
        if (m == AudioMode.NORMAL || channels <= 0) return
        if (sampleRate != rate) {
            rate = sampleRate
            // Hüllkurve: schnell bei lauten Stellen (5 ms), langsam zurück (300 ms), sonst „pumpt“ es.
            attack = coefficient(0.005f, sampleRate)
            release = coefficient(0.3f, sampleRate)
        }
        when (m) {
            AudioMode.DIALOG -> dialog(samples, frames, channels)
            AudioMode.NIGHT -> compress(samples, frames, channels)
            AudioMode.NORMAL -> Unit
        }
    }

    private fun dialog(s: FloatArray, frames: Int, channels: Int) {
        val center = channels == 3 || channels >= 6
        for (f in 0 until frames) {
            val base = f * channels
            if (center) {
                for (c in 0 until channels) s[base + c] = s[base + c] * if (c == 2) CENTER_GAIN else REST_GAIN
            } else if (channels >= 2) {
                val mid = (s[base] + s[base + 1]) * 0.5f * MID_GAIN
                val side = (s[base] - s[base + 1]) * 0.5f * SIDE_GAIN
                s[base] = mid + side
                s[base + 1] = mid - side
            } else {
                s[base] *= MONO_GAIN
            }
            for (c in 0 until channels) s[base + c] = limit(s[base + c])
        }
    }

    private fun compress(s: FloatArray, frames: Int, channels: Int) {
        for (f in 0 until frames) {
            val base = f * channels
            var peak = 0f
            for (c in 0 until channels) peak = maxOf(peak, abs(s[base + c]))
            envelope = if (peak > envelope) attack * envelope + (1 - attack) * peak else release * envelope + (1 - release) * peak
            val target = targetGain(envelope)
            // Verstärkung gleitend nachführen: kein Knacken.
            gain += (target - gain) * if (target < gain) 1 - attack else 1 - release
            for (c in 0 until channels) s[base + c] = limit(s[base + c] * gain)
        }
    }

    companion object {
        private const val CENTER_GAIN = 1.8f
        private const val REST_GAIN = 0.6f
        private const val MID_GAIN = 1.5f
        private const val SIDE_GAIN = 0.5f
        private const val MONO_GAIN = 1.2f
        /** Ab hier (≈ −24 dB) wird gedämpft, im Verhältnis 4:1; leise Stellen bis zu 3-fach angehoben. */
        private const val THRESHOLD = 0.063f
        private const val RATIO = 4f
        private const val MAKEUP = 3f
        private const val MAX_GAIN = 4f

        /** Verstärkung für eine Lautstärke [level]: unter der Schwelle anheben, darüber zusammendrücken. */
        fun targetGain(level: Float): Float {
            if (level <= 1e-4f) return 1f
            val compressed = if (level <= THRESHOLD) 1f else (THRESHOLD / level).pow(1 - 1 / RATIO)
            return (compressed * MAKEUP).coerceAtMost(MAX_GAIN)
        }

        /** Weicher Begrenzer: bis 0,8 unverändert, darüber sanft gegen 1. */
        fun limit(x: Float): Float {
            val a = abs(x)
            if (a <= 0.8f) return x
            val over = (a - 0.8f) / 0.2f
            val y = 0.8f + 0.2f * (over / (1 + over))
            return if (x < 0) -y else y
        }

        private fun coefficient(seconds: Float, sampleRate: Int) = exp(-1.0 / (seconds * sampleRate)).toFloat()
    }
}
