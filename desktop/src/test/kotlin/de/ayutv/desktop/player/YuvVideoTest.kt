package de.ayutv.desktop.player

import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YuvVideoTest {
    /** Einfarbiges Bild w×h in YUV rendern und den mittleren Bildpunkt (R, G, B) lesen. */
    private fun render(yv: Int, uv: Int, vv: Int, transfer: YuvVideo.Transfer = YuvVideo.Transfer.SDR, w: Int = 64, h: Int = 36, outW: Int = 128, outH: Int = 72): IntArray {
        val (pitches, lines) = YuvVideo.planes(w, h)
        val y = YuvVideo.planeFrom(ByteArray(pitches[0] * lines[0]) { yv.toByte() }, pitches[0], w, h)
        val u = YuvVideo.planeFrom(ByteArray(pitches[1] * lines[1]) { uv.toByte() }, pitches[1], (w + 1) / 2, (h + 1) / 2)
        val v = YuvVideo.planeFrom(ByteArray(pitches[2] * lines[2]) { vv.toByte() }, pitches[2], (w + 1) / 2, (h + 1) / 2)
        val frame = YuvFrame(y, u, v, w, h, w.toFloat() / h, transfer)
        val surface = Surface.makeRaster(ImageInfo(outW, outH, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        frame.draw(surface.canvas, outW.toFloat(), outH.toFloat())
        val pixels = surface.makeImageSnapshot().let { img ->
            val bmp = org.jetbrains.skia.Bitmap.makeFromImage(img)
            bmp.readPixels(ImageInfo(outW, outH, ColorType.RGBA_8888, ColorAlphaType.PREMUL), outW * 4, 0, 0)!!
        }
        val at = (outH / 2 * outW + outW / 2) * 4
        frame.close()
        return intArrayOf(pixels[at].toInt() and 0xFF, pixels[at + 1].toInt() and 0xFF, pixels[at + 2].toInt() and 0xFF)
    }

    private fun near(expected: IntArray, actual: IntArray, tolerance: Int = 4) =
        assertTrue("erwartet ${expected.toList()}, ist ${actual.toList()}", expected.indices.all { kotlin.math.abs(expected[it] - actual[it]) <= tolerance })

    @Test
    fun shaderCompiles() = assertTrue(YuvVideo.available)

    @Test
    fun limitedRangeWhiteBlackGrey() {
        near(intArrayOf(255, 255, 255), render(235, 128, 128))
        near(intArrayOf(0, 0, 0), render(16, 128, 128))
        near(intArrayOf(128, 128, 128), render(126, 128, 128), 2)
    }

    @Test
    fun bt709Red() {
        // Reines Rot in BT.709, begrenzter Bereich: Y=63, Cb=102, Cr=240 (64×36 → HD-Matrix erst ab 700 Zeilen).
        near(intArrayOf(255, 0, 0), render(63, 102, 240, h = 720, w = 1280), 6)
    }

    @Test
    fun bt601Red() {
        // Reines Rot in BT.601: Y=81, Cb=90, Cr=240.
        near(intArrayOf(255, 0, 0), render(81, 90, 240), 6)
    }

    @Test
    fun hdrIsMappedNotWashedOut() {
        // PQ-Weiß bei 203 Nits ≈ Code 0.58 → Y ≈ 16 + 0.58·219 ≈ 143. Ergibt Weiß wie SDR-Referenz (nicht grau).
        val white203 = render(143, 128, 128, YuvVideo.Transfer.PQ)
        assertTrue("PQ 203 Nits soll fast weiß sein: ${white203.toList()}", white203.all { it in 230..255 })
        // Ohne Anpassung wäre derselbe Wert ein mittleres Grau (der „blasse“ Eindruck).
        val raw = render(143, 128, 128)
        assertTrue(raw.all { it in 130..160 })
        // Sehr hell (PQ 1.0 = 10000 Nits): rund weiß, nicht übersteuert.
        near(intArrayOf(255, 255, 255), render(235, 128, 128, YuvVideo.Transfer.PQ), 3)
        // Dunkel bleibt dunkel.
        val dark = render(40, 128, 128, YuvVideo.Transfer.PQ)
        assertTrue(dark.all { it < 40 })
    }

    @Test
    fun aspectAddsBlackBars() {
        // 4:3-Bild in 16:9-Fläche: links und rechts schwarz.
        val (pitches, lines) = YuvVideo.planes(64, 48)
        val y = YuvVideo.planeFrom(ByteArray(pitches[0] * lines[0]) { 235.toByte() }, pitches[0], 64, 48)
        val u = YuvVideo.planeFrom(ByteArray(pitches[1] * lines[1]) { 128.toByte() }, pitches[1], 32, 24)
        val v = YuvVideo.planeFrom(ByteArray(pitches[2] * lines[2]) { 128.toByte() }, pitches[2], 32, 24)
        val frame = YuvFrame(y, u, v, 64, 48, 4f / 3f, YuvVideo.Transfer.SDR)
        val surface = Surface.makeRaster(ImageInfo(160, 90, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        frame.draw(surface.canvas, 160f, 90f)
        val bmp = org.jetbrains.skia.Bitmap.makeFromImage(surface.makeImageSnapshot())
        val px = bmp.readPixels(ImageInfo(160, 90, ColorType.RGBA_8888, ColorAlphaType.PREMUL), 160 * 4, 0, 0)!!
        assertEquals(0, px[(45 * 160 + 5) * 4].toInt() and 0xFF)
        assertEquals(255, px[(45 * 160 + 80) * 4].toInt() and 0xFF)
    }
}

