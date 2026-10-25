package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Untertitel am Ton ausrichten: künstlicher Filmton mit Sätzen aus Silben (Rauschen mit Silbenrhythmus),
 * Pausen, Musikszenen und Grundrauschen. Die Untertitel sind die Satzzeiten, ungenau wie echte.
 */
class SpeechSyncTest {
    private val rate = 16_000

    private class Film(val pcm: FloatArray, val cues: List<Pair<Long, Long>>)

    private fun film(seed: Int, minutes: Int): Film {
        val random = Random(seed)
        val pcm = FloatArray(minutes * 60 * rate)
        for (i in pcm.indices) pcm[i] = (random.nextFloat() - 0.5f) * 0.01f
        // Musikszenen: gehaltene Akkorde, mal leise, mal laut
        var m = random.nextInt(20, 90) * rate
        while (m < pcm.size) {
            val length = random.nextInt(20, 60) * rate
            val level = random.nextFloat() * 0.3f
            val notes = listOf(220.0, 277.0, 330.0, 440.0).shuffled(random).take(3)
            for (i in m until minOf(pcm.size, m + length)) {
                pcm[i] += level * notes.sumOf { sin(2 * PI * it * i / rate) }.toFloat() / 3
            }
            m += length + random.nextInt(30, 120) * rate
        }
        // Sätze: 2–12 Silben à 120–260 ms, dazwischen 0,4–6 s Pause
        val cues = ArrayList<Pair<Long, Long>>()
        var t = random.nextInt(2, 8) * rate
        while (t < pcm.size - 5 * rate) {
            val start = t
            repeat(random.nextInt(2, 13)) {
                val syllable = random.nextInt(120, 260) * rate / 1000
                val loud = 0.15f + random.nextFloat() * 0.35f
                for (k in 0 until syllable) {
                    if (t + k >= pcm.size) break
                    val envelope = (0.5 - 0.5 * cos(2 * PI * k / syllable)).toFloat()
                    pcm[t + k] += loud * envelope * (random.nextFloat() - 0.5f) * 2
                }
                t += syllable + random.nextInt(10, 80) * rate / 1000
            }
            val end = t
            // Untertitel: etwas früher an, länger stehen, manche Sätze fehlen
            if (random.nextDouble() > 0.1) {
                cues += (start * 1000L / rate - random.nextLong(0, 300)) to (end * 1000L / rate + random.nextLong(200, 800))
            }
            t += random.nextInt(400, 6_000) * rate / 1000
        }
        return Film(pcm, cues)
    }

    private fun listen(film: Film, fromMinute: Int, minutes: Int): SpeechActivity {
        val heard = SpeechActivity()
        heard.restart(fromMinute * 60_000L, rate)
        val from = fromMinute * 60 * rate
        val to = minOf(film.pcm.size, from + minutes * 60 * rate)
        heard.add(film.pcm.copyOfRange(from, to), to - from)
        return heard
    }

    /** Untertiteldatei einer anderen Fassung: Filmzeit = Dateizeit × ratio + offset. */
    private fun shifted(cues: List<Pair<Long, Long>>, ratio: Double, offsetMs: Long) =
        cues.map { (a, b) -> ((a - offsetMs) / ratio).toLong() to ((b - offsetMs) / ratio).toLong() }.filter { it.first >= 0 }

    private val film = film(seed = 3, minutes = 20)

    @Test
    fun findsOffsetFromSpeech() {
        val heard = listen(film, fromMinute = 2, minutes = 6)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 1.0, 7_300))
        assertNotNull("keine Ausrichtung gefunden", fit)
        assertEquals(1.0, fit!!.timing.ratio, 0.0)
        assertTrue("Versatz ${fit.timing.offsetMs}", abs(fit.timing.offsetMs - 7_300) <= 200)
    }

    @Test
    fun findsFrameRateAndOffset() {
        val heard = listen(film, fromMinute = 0, minutes = 10)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 25.0 / 23.976, -20_000))
        assertNotNull("keine Ausrichtung gefunden", fit)
        assertEquals(25.0 / 23.976, fit!!.timing.ratio, 1e-9)
        val middle = 5 * 60_000L
        val error = fit.timing.toVideo(((middle + 20_000) / (25.0 / 23.976)).toLong()) - middle
        assertTrue("Abweichung $error ms", abs(error) <= 200)
    }

    @Test
    fun alreadyFittingSubtitlesStayAtZero() {
        val heard = listen(film, fromMinute = 10, minutes = 5)
        val fit = SpeechSync.estimate(heard.signal(), film.cues)
        assertNotNull(fit)
        assertTrue("Versatz ${fit!!.timing.offsetMs}", abs(fit.timing.offsetMs) <= 200)
    }

    @Test
    fun windowFollowsCutVersion() {
        // Gekürzte Fassung: ab Minute 9 liegen die Untertitel 6 s später als vorher.
        val heard = listen(film, fromMinute = 0, minutes = 20)
        val cues = film.cues.map { (a, b) -> if (a < 540_000) (a - 1_000) to (b - 1_000) else (a - 7_000) to (b - 7_000) }
        val late = SpeechSync.estimate(heard.signal(13 * 60_000L, 19 * 60_000L), cues)
        assertNotNull(late)
        assertTrue("Versatz ${late!!.timing.offsetMs}", abs(late.timing.offsetMs - 7_000) <= 200)
    }

    @Test
    fun rejectsSubtitlesOfAnotherFilm() {
        val heard = listen(film, fromMinute = 0, minutes = 12)
        for (seed in listOf(11, 12, 13)) {
            assertNull("fremder Film $seed passt angeblich", SpeechSync.estimate(heard.signal(), film(seed, 20).cues))
        }
    }

    @Test
    fun needsEnoughSound() {
        val heard = listen(film, fromMinute = 0, minutes = 1)
        assertNull(SpeechSync.estimate(heard.signal(), film.cues))
        assertNull(SpeechSync.estimate(SpeechActivity().signal(), film.cues))
    }

    @Test
    fun seekKeepsFilmTime() {
        val heard = SpeechActivity()
        for ((from, minutes) in listOf(0 to 3, 12 to 3)) {
            heard.restart(from * 60_000L, rate)
            val a = from * 60 * rate
            val b = a + minutes * 60 * rate
            heard.add(film.pcm.copyOfRange(a, b), b - a)
        }
        assertEquals(360, heard.seconds(), 2)
        val fit = SpeechSync.estimate(heard.signal(), shifted(film.cues, 1.0, -2_500))
        assertNotNull(fit)
        assertTrue("Versatz ${fit!!.timing.offsetMs}", abs(fit.timing.offsetMs + 2_500) <= 200)
    }

    private fun assertEquals(expected: Int, actual: Int, delta: Int) =
        assertTrue("$actual statt $expected", abs(expected - actual) <= delta)
}
