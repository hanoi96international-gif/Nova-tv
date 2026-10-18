@file:OptIn(UnstableApi::class)

package de.novatv.player

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import de.novatv.data.AppSettings
import de.novatv.data.BufferProfile
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Baut einen ExoPlayer, der auf schnelles Umschalten und flüssiges 4K auf Fire-TV-Hardware ausgelegt ist:
 * - Hardware-Decoder mit Fallback statt Software-Decoding
 * - TS-Extractor startet ohne auf einen IDR-Frame zu warten (spürbar schnelleres Zappen)
 * - Pufferprofile statt fester Werte, optional Tunneling (Video läuft direkt im Decoder/Display-Pfad)
 * - Ein geteilter OkHttp-Client hält Verbindungen zum Server offen
 */
object PlayerFactory {

    class Built(val player: ExoPlayer, val headers: HeaderProvider, val trackSelector: DefaultTrackSelector)

    /** Der User-Agent kann sich pro Sender unterscheiden (#EXTVLCOPT), ohne den Player neu zu bauen. */
    class HeaderProvider(var userAgent: String)

    /** [subtitleLanguage]: bei Filmen die gewünschte Untertitelsprache, null = Untertitel aus (Live: nur erzwungene). */
    fun create(context: Context, http: OkHttpClient, settings: AppSettings, subtitleLanguage: String?, live: Boolean): Built {
        val headers = HeaderProvider(settings.userAgent)

        // Eigener, kurzer Lese-Timeout für Streams: Ein hängender Server wird nach 12 s erkannt
        // (statt 30 s), dann greift die automatische Wiederverbindung.
        val streamClient = http.newBuilder().readTimeout(12, TimeUnit.SECONDS).build()
        val httpFactory = OkHttpDataSource.Factory(streamClient)
        val resolving = ResolvingDataSource.Factory(httpFactory) { spec ->
            spec.withAdditionalHeaders(mapOf("User-Agent" to headers.userAgent))
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, resolving)

        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
            )
            .setConstantBitrateSeekingEnabled(true)

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(settings.tunneling)
                    .setPreferredAudioLanguage(Locale.getDefault().language)
                    .apply {
                        when {
                            live -> Unit
                            subtitleLanguage != null -> setPreferredTextLanguage(subtitleLanguage)
                            else -> setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        }
                    }
                    // Kein Herunterskalieren auf die UI-Auflösung: Fire OS meldet bei 4K oft nur 1080p für die UI.
                    .setViewportSizeToPhysicalDisplaySize(context, true)
            )
        }

        val (minBuffer, maxBuffer, startBuffer, rebuffer) = when (settings.bufferProfile) {
            BufferProfile.FAST -> listOf(2_000, 30_000, 500, 1_500)
            BufferProfile.BALANCED -> listOf(6_000, 40_000, 1_000, 2_500)
            BufferProfile.STABLE -> listOf(15_000, 60_000, 2_500, 5_000)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, startBuffer, rebuffer)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Hoher Startwert: adaptive Streams beginnen direkt in guter Qualität statt mit 480p.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(25_000_000L)
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractors))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        return Built(player, headers, trackSelector)
    }
}

/**
 * Passt die Bildwiederholrate des Fernsehers an die Bildrate des Videos an (z. B. 50 Hz für
 * deutsches TV, 23,976 Hz für Filme). Das beseitigt das typische Ruckeln bei Kameraschwenks.
 */
class FrameRateMatcher(private val activity: Activity) {
    private var switched = false

    @Suppress("DEPRECATION")
    fun match(frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || frameRate <= 0f) return
        val display = activity.windowManager.defaultDisplay ?: return
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val best = sameSize.firstOrNull { matches(it.refreshRate, frameRate) }
            ?: sameSize.firstOrNull { matches(it.refreshRate, frameRate * 2) }
            ?: return
        if (best.modeId == current.modeId) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = best.modeId
        activity.window.attributes = attributes
        switched = true
    }

    fun reset() {
        if (!switched || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
        switched = false
    }

    private fun matches(refreshRate: Float, frameRate: Float) = abs(refreshRate - frameRate) < 0.05f
}
