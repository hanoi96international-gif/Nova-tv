package de.ayutv.player

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import de.ayutv.data.parse.PictureTuning

/**
 * Bildverbesserung auf dem Grafikchip des Fire TV – nach denselben Ideen wie die Hochrechner in Spielen (FSR,
 * Snapdragon GSR, AMD CAS), aber für Video und so sparsam, dass ein Fire TV Stick sie bei 50 Bildern schafft:
 *
 * 1. **Hochrechnung** (nur bei Sendern unter Full HD, z. B. 720p bei ARD/ZDF HD oder 576i): bikubisch (Catmull-Rom)
 *    mit Kantenschutz – keine Säume an Kanten, schärfer als die einfache Vergrößerung von Gerät oder Fernseher.
 * 2. **Detail und Farbe in einem Durchgang:** kontrastabhängiges Schärfen (schärft feine Details, nicht Kanten, die
 *    ohnehin scharf sind, und nicht Rauschen in glatten Flächen), Entbänderung in glatten Verläufen (Himmel, Rasen,
 *    Studiowände: die Stufen der Kompression verschwinden), dazu Kontrast und Sättigung je Inhalt.
 *
 * Echte KI-Hochrechnung wie DLSS braucht Bewegungsdaten aus einem Spiel und Tensor-Kerne; beides gibt es beim
 * Fernsehen bzw. auf dem Fire TV nicht.
 */
@OptIn(UnstableApi::class)
object PictureEffects {
    /** [upscale]: Sender unter Full HD auf 1080p hochrechnen (auf schwachen Geräten aus). */
    fun of(profile: PictureTuning.Profile, upscale: Boolean = true): List<Effect> = buildList {
        if (upscale) add(Upscale())
        add(Enhance(profile.sharpen, profile.contrast, profile.saturation / 100f))
    }

    /** Hochrechnung auf 1080p, nur wenn das Bild kleiner ist (sonst entfällt der Schritt). */
    class Upscale : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = UpscaleProgram(useHdr)
        override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = inputHeight >= TARGET_HEIGHT - 80
    }

    /** Schärfen, Entbänderung, Kontrast und Sättigung in einem Durchgang. */
    class Enhance(private val sharpen: Float, private val contrast: Float, private val saturation: Float) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
            EnhanceProgram(sharpen.coerceIn(0f, 1f), contrast.coerceIn(-0.5f, 0.5f), saturation.coerceIn(-0.5f, 0.5f), useHdr)
    }

    private const val TARGET_HEIGHT = 1080

    private abstract class Program(useHdr: Boolean, fragment: String) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {
        protected val program: GlProgram = try {
            GlProgram(VERTEX, fragment).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        protected var inputSize = floatArrayOf(1f, 1f)

        abstract fun setUniforms()

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                setUniforms()
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    private class UpscaleProgram(useHdr: Boolean) : Program(useHdr, UPSCALE) {
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inputSize = floatArrayOf(inputWidth.coerceAtLeast(1).toFloat(), inputHeight.coerceAtLeast(1).toFloat())
            if (inputHeight <= 0 || inputHeight >= TARGET_HEIGHT - 80) return Size(inputWidth, inputHeight)
            val width = (inputWidth.toLong() * TARGET_HEIGHT / inputHeight).toInt().let { it + (it and 1) }
            return Size(width, TARGET_HEIGHT)
        }

        override fun setUniforms() {
            program.setFloatsUniform("uInSize", inputSize)
        }
    }

    private class EnhanceProgram(
        private val sharpen: Float,
        private val contrast: Float,
        private val saturation: Float,
        useHdr: Boolean,
    ) : Program(useHdr, ENHANCE) {
        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            inputSize = floatArrayOf(inputWidth.coerceAtLeast(1).toFloat(), inputHeight.coerceAtLeast(1).toFloat())
            return Size(inputWidth, inputHeight)
        }

        override fun setUniforms() {
            program.setFloatsUniform("uTexelSize", floatArrayOf(1f / inputSize[0], 1f / inputSize[1]))
            program.setFloatUniform("uSharp", sharpen)
            program.setFloatUniform("uContrast", contrast)
            program.setFloatUniform("uSaturation", saturation)
        }
    }

    private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

    /** Catmull-Rom mit 9 Abfragen (lineare Filterung fasst je zwei zusammen) und Kantenschutz (keine Überschwinger). */
    private const val UPSCALE = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uInSize;
varying vec2 vTexSamplingCoord;
void main() {
  vec2 samplePos = vTexSamplingCoord * uInSize;
  vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
  vec2 f = samplePos - texPos1;
  vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
  vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
  vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
  vec2 w3 = f * f * (-0.5 + 0.5 * f);
  vec2 w12 = w1 + w2;
  vec2 offset12 = w2 / w12;
  vec2 p0 = (texPos1 - 1.0) / uInSize;
  vec2 p3 = (texPos1 + 2.0) / uInSize;
  vec2 p12 = (texPos1 + offset12) / uInSize;
  vec3 c = vec3(0.0);
  c += texture2D(uTexSampler, vec2(p0.x, p0.y)).rgb * w0.x * w0.y;
  c += texture2D(uTexSampler, vec2(p12.x, p0.y)).rgb * w12.x * w0.y;
  c += texture2D(uTexSampler, vec2(p3.x, p0.y)).rgb * w3.x * w0.y;
  c += texture2D(uTexSampler, vec2(p0.x, p12.y)).rgb * w0.x * w12.y;
  c += texture2D(uTexSampler, vec2(p12.x, p12.y)).rgb * w12.x * w12.y;
  c += texture2D(uTexSampler, vec2(p3.x, p12.y)).rgb * w3.x * w12.y;
  c += texture2D(uTexSampler, vec2(p0.x, p3.y)).rgb * w0.x * w3.y;
  c += texture2D(uTexSampler, vec2(p12.x, p3.y)).rgb * w12.x * w3.y;
  c += texture2D(uTexSampler, vec2(p3.x, p3.y)).rgb * w3.x * w3.y;
  vec2 px = 1.0 / uInSize;
  vec2 base = texPos1 / uInSize;
  vec3 a = texture2D(uTexSampler, base).rgb;
  vec3 b = texture2D(uTexSampler, base + vec2(px.x, 0.0)).rgb;
  vec3 d = texture2D(uTexSampler, base + vec2(0.0, px.y)).rgb;
  vec3 e = texture2D(uTexSampler, base + px).rgb;
  vec3 lo = min(min(a, b), min(d, e));
  vec3 hi = max(max(a, b), max(d, e));
  gl_FragColor = vec4(clamp(c, lo, hi), 1.0);
}
"""

    /**
     * Glatte Flächen (kaum Unterschied zu den Nachbarn): entbändern (Mittel aus vier entfernten Punkten, wenn sie
     * zum Pixel passen, plus feines Rauschen gegen neue Stufen). Sonst kontrastabhängig schärfen (AMD CAS):
     * wenig Kontrast in der Umgebung = mehr Schärfe, starke Kanten = kaum. Danach Kontrast und Sättigung.
     */
    private const val ENHANCE = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uSharp;
uniform float uContrast;
uniform float uSaturation;
varying vec2 vTexSamplingCoord;
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
void main() {
  vec2 uv = vTexSamplingCoord;
  vec3 e = texture2D(uTexSampler, uv).rgb;
  vec3 b = texture2D(uTexSampler, uv - vec2(0.0, uTexelSize.y)).rgb;
  vec3 h = texture2D(uTexSampler, uv + vec2(0.0, uTexelSize.y)).rgb;
  vec3 d = texture2D(uTexSampler, uv - vec2(uTexelSize.x, 0.0)).rgb;
  vec3 f = texture2D(uTexSampler, uv + vec2(uTexelSize.x, 0.0)).rgb;
  vec3 mn = min(e, min(min(b, h), min(d, f)));
  vec3 mx = max(e, max(max(b, h), max(d, f)));
  float range = max(max(mx.r - mn.r, mx.g - mn.g), mx.b - mn.b);
  vec3 color = e;
  if (range < 0.012) {
    float angle = rand(uv) * 6.2831853;
    vec2 r = vec2(cos(angle), sin(angle)) * uTexelSize * 12.0;
    vec3 s1 = texture2D(uTexSampler, uv + r).rgb;
    vec3 s2 = texture2D(uTexSampler, uv - r).rgb;
    vec3 s3 = texture2D(uTexSampler, uv + vec2(-r.y, r.x)).rgb;
    vec3 s4 = texture2D(uTexSampler, uv + vec2(r.y, -r.x)).rgb;
    vec3 avg = (s1 + s2 + s3 + s4) * 0.25;
    vec3 diff = abs(avg - e);
    if (max(max(diff.r, diff.g), diff.b) < 0.02) color = avg;
    color += (rand(uv + 0.37) - 0.5) / 255.0;
  } else if (uSharp > 0.0) {
    vec3 amp = clamp(min(mn, 2.0 - mx) / max(mx, vec3(0.0001)), 0.0, 1.0);
    amp = sqrt(amp);
    vec3 w = amp * (-1.0 / mix(8.0, 5.0, uSharp));
    color = (b * w + h * w + d * w + f * w + e) / (1.0 + 4.0 * w);
  }
  color = (color - 0.5) * (1.0 + uContrast) + 0.5;
  float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
  color = mix(vec3(luma), color, 1.0 + uSaturation);
  gl_FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
"""
}
