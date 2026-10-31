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
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.HslAdjustment
import de.ayutv.data.parse.PictureTuning

/**
 * Bildoptimierung auf dem Fire TV: Effekte von Media3, die der Grafikchip auf jedes Bild anwendet, bevor es zum
 * Fernseher geht (Nachschärfen, Kontrast, Sättigung je nach Inhalt, siehe [PictureTuning]).
 */
@OptIn(UnstableApi::class)
object PictureEffects {
    fun of(profile: PictureTuning.Profile): List<Effect> = buildList {
        if (profile.sharpen > 0.01f) add(Sharpen(profile.sharpen))
        if (profile.contrast > 0.005f || profile.contrast < -0.005f) add(Contrast(profile.contrast.coerceIn(-1f, 1f)))
        if (profile.saturation > 0.5f || profile.saturation < -0.5f) {
            add(HslAdjustment.Builder().adjustSaturation(profile.saturation.coerceIn(-100f, 100f)).build())
        }
        // Mindestens ein Effekt, damit die Bildverarbeitung bestehen bleibt (spätere Wechsel ohne Neustart).
        if (isEmpty()) add(Sharpen(0f))
    }

    /** Nachschärfen (Unschärfemaske mit den vier Nachbarpunkten). [amount]: 0 = aus, 0,3 = deutlich. */
    class Sharpen(private val amount: Float) : GlEffect {
        override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = SharpenProgram(amount, useHdr)
    }

    private class SharpenProgram(private val amount: Float, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {
        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var texel = floatArrayOf(0f, 0f)

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            texel = floatArrayOf(1f / inputWidth.coerceAtLeast(1), 1f / inputHeight.coerceAtLeast(1))
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uTexelSize", texel)
                program.setFloatUniform("uAmount", amount)
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

    private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexSamplingCoord;
void main() {
  gl_Position = aFramePosition;
  vTexSamplingCoord = vec2(aFramePosition.x * 0.5 + 0.5, aFramePosition.y * 0.5 + 0.5);
}
"""

    private const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAmount;
varying vec2 vTexSamplingCoord;
void main() {
  vec4 c = texture2D(uTexSampler, vTexSamplingCoord);
  vec3 n = texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb
         + texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb;
  vec3 sharp = c.rgb + uAmount * (4.0 * c.rgb - n);
  gl_FragColor = vec4(clamp(sharp, 0.0, 1.0), c.a);
}
"""
}
