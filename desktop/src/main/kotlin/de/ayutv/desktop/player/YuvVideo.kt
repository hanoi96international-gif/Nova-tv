package de.ayutv.desktop.player

import com.sun.jna.Native
import com.sun.jna.Pointer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import java.nio.ByteBuffer

/**
 * Schneller Videoweg für 4K: VLC liefert das Bild als YUV 4:2:0 (drei Ebenen, bei 4K 12 MB statt 33 MB in RGB) und
 * die Grafikkarte rechnet die Farben per Shader um. Das spart die Umrechnung in RGB auf dem Prozessor (bei 4K mit
 * 50 Bildern die teuerste Arbeit) und eine Kopie je Bild.
 *
 * Dabei richtig: Farbmatrix nach Auflösung (BT.601 für SD, BT.709 für HD, BT.2020 für UHD), Seitenverhältnis aus dem
 * Stream (anamorphes SD in 16:9) und HDR: PQ (HDR10) und HLG werden auf einen normalen Bildschirm abgebildet
 * (Tonwerte und Farbraum), statt blass und flau zu erscheinen.
 */
object YuvVideo {
    /** HDR-Art des Bildes. */
    enum class Transfer { SDR, PQ, HLG }

    /** Farbmatrix (Luma-Anteile Rot/Blau) nach Norm. */
    enum class Matrix(val kr: Float, val kb: Float) {
        BT601(0.299f, 0.114f),
        BT709(0.2126f, 0.0722f),
        BT2020(0.2627f, 0.0593f),
    }

    /** Pitch (Zeilenlänge) und Zeilen je Ebene, wie VLC sie für „I420“ anlegt (ausgerichtet auf 32 bzw. 16). */
    fun planes(width: Int, height: Int): Pair<IntArray, IntArray> {
        val cw = (width + 1) / 2
        val ch = (height + 1) / 2
        val pitches = intArrayOf(align(width, 32), align(cw, 32), align(cw, 32))
        val lines = intArrayOf(align(height, 16), align(ch, 16), align(ch, 16))
        return pitches to lines
    }

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to

    /**
     * Der Shader: drei Ebenen (Y, U, V als Graustufen), Farbmatrix, Wertebereich, HDR-Anpassung.
     * Ausgabe in sRGB für den Bildschirm.
     */
    private const val SKSL = """
uniform shader luma;
uniform shader cb;
uniform shader cr;
uniform float kr;
uniform float kb;
uniform float fullRange;
uniform float transfer;   // 0 = SDR, 1 = PQ, 2 = HLG
uniform float peak;       // Spitzenhelligkeit des Inhalts (Nits) für die Tonwertanpassung

float3 pqToNits(float3 e) {
    float3 p = pow(clamp(e, 0.0, 1.0), float3(1.0 / 78.84375));
    float3 num = max(p - 0.8359375, float3(0.0));
    float3 den = 18.8515625 - 18.6875 * p;
    return pow(num / den, float3(1.0 / 0.1593017578125)) * 10000.0;
}

float3 hlgToNits(float3 e) {
    e = clamp(e, 0.0, 1.0);
    float3 low = e * e / 3.0;
    float3 high = (exp((e - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
    float3 scene = mix(low, high, step(0.5, e));
    float ys = dot(scene, float3(0.2627, 0.6780, 0.0593));
    return scene * pow(max(ys, 1e-6), 0.2) * 1000.0;
}

float3 srgbEncode(float3 v) {
    v = clamp(v, 0.0, 1.0);
    return mix(v * 12.92, 1.055 * pow(v, float3(1.0 / 2.4)) - 0.055, step(0.0031308, v));
}

half4 main(float2 p) {
    float y = luma.eval(p).r;
    float u = cb.eval(p * 0.5).r;
    float v = cr.eval(p * 0.5).r;
    // Begrenzter Bereich (16–235/240) ist bei Fernsehen üblich.
    float Y = fullRange > 0.5 ? y : (y * 255.0 - 16.0) / 219.0;
    float U = fullRange > 0.5 ? u - 0.5 : (u * 255.0 - 128.0) / 224.0;
    float V = fullRange > 0.5 ? v - 0.5 : (v * 255.0 - 128.0) / 224.0;
    float kg = 1.0 - kr - kb;
    float R = Y + 2.0 * (1.0 - kr) * V;
    float B = Y + 2.0 * (1.0 - kb) * U;
    float G = (Y - kr * R - kb * B) / kg;
    float3 rgb = float3(R, G, B);
    if (transfer < 0.5) return half4(half3(clamp(rgb, 0.0, 1.0)), 1.0);

    // HDR: in Licht (Nits) zurückrechnen, BT.2020 → BT.709, Tonwerte auf SDR (Weiß = 203 Nits) abbilden.
    float3 nits = transfer < 1.5 ? pqToNits(rgb) : hlgToNits(rgb);
    float3 lin = float3(
        1.6605 * nits.r - 0.5876 * nits.g - 0.0728 * nits.b,
        -0.1246 * nits.r + 1.1329 * nits.g - 0.0083 * nits.b,
        -0.0182 * nits.r - 0.1006 * nits.g + 1.1187 * nits.b
    ) / 203.0;
    lin = max(lin, float3(0.0));
    float l = dot(lin, float3(0.2126, 0.7152, 0.0722));
    float white = max(peak / 203.0, 1.0);
    // Bis 80 % des SDR-Weiß unverändert (Hauttöne, Mitten wie gemastert); darüber werden die Spitzlichter bis zur
    // Spitzenhelligkeit weich in den Rest gerundet statt abgeschnitten (ähnlich BT.2390).
    const float knee = 0.8;
    float mapped = l;
    if (l > knee) {
        float x = (l - knee) / (1.0 - knee);
        float w = max((white - knee) / (1.0 - knee), 1.0001);
        mapped = knee + (1.0 - knee) * min(x * (1.0 + x / (w * w)) / (1.0 + x), 1.0);
    }
    float3 out3 = l > 1e-6 ? lin * (mapped / l) : lin;
    return half4(half3(srgbEncode(out3)), 1.0);
}
"""

    /** Übersetzter Shader; null, wenn die Grafik das nicht kann (dann nimmt der Player den RGB-Weg). */
    val effect: RuntimeEffect? by lazy { runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull() }
    val available: Boolean get() = effect != null

    /** Ebene aus dem VLC-Puffer kopieren (eine Kopie im nativen Speicher, ohne Umweg über Java). */
    fun copyPlane(source: ByteBuffer, pitch: Int, width: Int, height: Int): Image {
        val info = ImageInfo(width, height, ColorType.GRAY_8, ColorAlphaType.OPAQUE)
        val bitmap = Bitmap()
        bitmap.allocPixels(info, pitch)
        val size = pitch * height
        val pixels = bitmap.peekPixels() ?: error("Pixel nicht erreichbar")
        val target = Pointer(pixels.addr).getByteBuffer(0, size.toLong())
        val view = source.duplicate()
        view.rewind()
        view.limit(minOf(size, view.capacity()))
        target.put(view)
        bitmap.setImmutable()
        return Image.makeFromBitmap(bitmap).also { bitmap.close() }
    }

    /** Für Tests: Ebene aus einem Byte-Feld. */
    fun planeFrom(bytes: ByteArray, pitch: Int, width: Int, height: Int): Image {
        val buffer = ByteBuffer.allocateDirect(bytes.size).put(bytes)
        return copyPlane(buffer, pitch, width, height)
    }

    /** Adresse eines direkten Puffers (nur zur Kontrolle, ob VLC wirklich native Puffer liefert). */
    fun isNative(buffer: ByteBuffer): Boolean = buffer.isDirect && Pointer.nativeValue(Native.getDirectBufferPointer(buffer)) != 0L

    /** Passende Farbmatrix: UHD/HDR = BT.2020, HD = BT.709, SD = BT.601. */
    fun matrixFor(height: Int, transfer: Transfer): Matrix = when {
        transfer != Transfer.SDR || height >= 2000 -> Matrix.BT2020
        height >= 700 -> Matrix.BT709
        else -> Matrix.BT601
    }
}

/**
 * Ein Bild in drei Ebenen. [aspect] ist das Seitenverhältnis, wie es gezeigt werden soll (Breite/Höhe).
 * Gehört nach dem Zeichnen der Oberfläche; [close] gibt den Speicher sofort frei.
 */
class YuvFrame(
    val y: Image,
    val u: Image,
    val v: Image,
    val width: Int,
    val height: Int,
    val aspect: Float,
    val transfer: YuvVideo.Transfer,
    val fullRange: Boolean = false,
    val peakNits: Float = 1000f,
) : AutoCloseable {
    val matrix: YuvVideo.Matrix = YuvVideo.matrixFor(height, transfer)

    /** Bild eingepasst (mit schwarzen Rändern) in [dstWidth] × [dstHeight] zeichnen. */
    fun draw(canvas: Canvas, dstWidth: Float, dstHeight: Float) {
        val effect = YuvVideo.effect ?: return
        if (dstWidth <= 0f || dstHeight <= 0f) return
        val shownWidth: Float
        val shownHeight: Float
        if (dstWidth / dstHeight > aspect) {
            shownHeight = dstHeight
            shownWidth = dstHeight * aspect
        } else {
            shownWidth = dstWidth
            shownHeight = dstWidth / aspect
        }
        val left = (dstWidth - shownWidth) / 2f
        val top = (dstHeight - shownHeight) / 2f
        val builder = RuntimeShaderBuilder(effect)
        builder.child("luma", y.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        builder.child("cb", u.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        builder.child("cr", v.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null))
        builder.uniform("kr", matrix.kr)
        builder.uniform("kb", matrix.kb)
        builder.uniform("fullRange", if (fullRange) 1f else 0f)
        builder.uniform("transfer", transfer.ordinal.toFloat())
        builder.uniform("peak", peakNits)
        // Bildschirmpunkt → Bildpunkt der Y-Ebene.
        val sx = width / shownWidth
        val sy = height / shownHeight
        val local = Matrix33(1f / sx, 0f, left, 0f, 1f / sy, top, 0f, 0f, 1f)
        val shader = builder.makeShader(local)
        Paint().use { paint ->
            paint.shader = shader
            canvas.drawRect(Rect.makeXYWH(left, top, shownWidth, shownHeight), paint)
        }
        shader.close()
        builder.close()
    }

    override fun close() {
        y.close()
        u.close()
        v.close()
    }
}
