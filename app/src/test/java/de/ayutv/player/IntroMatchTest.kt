package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class IntroMatchTest {
    /** Lautstärkeverlauf wie Ton: zufällig schwankend, je Quelle anders. */
    private fun sound(n: Int, seed: Int): FloatArray {
        val r = Random(seed)
        var v = -6f
        return FloatArray(n) { v = (v + r.nextFloat() * 2f - 1f).coerceIn(-12f, -1f); v }
    }

    private fun episode(length: Int, introAt: Int, intro: FloatArray, seed: Int): FloatArray {
        val out = sound(length, seed)
        // Gleiche Aufnahme, leicht anders gemessen (Lautstärke, Rauschen)
        val r = Random(seed + 1000)
        for (k in intro.indices) if (introAt + k < out.size) out[introAt + k] = intro[k] + 0.3f + (r.nextFloat() - 0.5f) * 0.2f
        return out
    }

    @Test
    fun findsTheSameIntroBehindDifferentRecaps() {
        val intro = sound(450, 7) // 45 s Titelmusik
        val first = episode(4800, introAt = 950, intro = intro, seed = 1) // Rückblick 95 s
        val second = episode(4800, introAt = 1420, intro = intro, seed = 2) // Rückblick 142 s
        val segment = IntroMatch.commonSegment(first, second)
        assertNotNull(segment)
        segment!!
        assertTrue("Beginn ${segment.startA}", abs(segment.startA - 950) <= 10)
        assertTrue("Beginn ${segment.startB}", abs(segment.startB - 1420) <= 10)
        assertTrue("Länge ${segment.length}", abs(segment.length - 450) <= 20)
    }

    @Test
    fun differentSeriesHaveNoCommonPart() {
        assertNull(IntroMatch.commonSegment(sound(3000, 1), sound(3000, 2)))
    }

    @Test
    fun gapsAreIgnored() {
        val intro = sound(300, 9)
        val first = episode(3000, 600, intro, 3)
        val second = episode(3000, 800, intro, 4)
        // In der zweiten Folge wurde die erste Minute übersprungen (nicht gehört)
        for (k in 0 until 600) second[k] = Float.NaN
        val segment = IntroMatch.commonSegment(first, second)
        assertNotNull(segment)
        assertTrue(abs(segment!!.startB - 800) <= 10)
    }

    @Test
    fun introStartIsFoundWhileWatching() {
        val intro = sound(450, 7)
        val third = episode(3000, introAt = 1210, intro = intro, seed = 5)
        val pattern = intro.copyOfRange(0, 80)
        // Bis jetzt (Position 132 s) gehört: der Anfang des Intros ist schon gelaufen
        val heard = third.copyOf().also { for (k in 1320 until it.size) it[k] = Float.NaN }
        val at = IntroMatch.find(heard, pattern, fromBin = 900, toBin = 1320)
        assertNotNull(at)
        assertEquals(1210.0, at!!.toDouble(), 2.0)
        // Noch vor dem Intro: nichts
        assertNull(IntroMatch.find(heard, pattern, fromBin = 0, toBin = 1200))
    }
}
