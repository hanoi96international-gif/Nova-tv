@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import de.ayutv.data.AppSettings
import de.ayutv.data.AudioOutput
import de.ayutv.data.BufferProfile
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
    private const val MB = 1024 * 1024

    class Built(val player: ExoPlayer, val headers: HeaderProvider, val trackSelector: DefaultTrackSelector)

    /** Der User-Agent kann sich pro Sender unterscheiden (#EXTVLCOPT), ohne den Player neu zu bauen. */
    class HeaderProvider(var userAgent: String)

    /** Untertitel sind bei Filmen optional: bevorzugte Sprache ist gesetzt, eingeschaltet nur mit "automatisch an". */
    fun create(
        context: Context,
        http: OkHttpClient,
        settings: AppSettings,
        live: Boolean,
        asyncDecoding: Boolean = settings.asyncDecoding,
        forcePcm: Boolean = settings.audioOutput == AudioOutput.PCM,
    ): Built {
        val headers = HeaderProvider(settings.userAgent)

        // Eigener, kurzer Lese-Timeout für Streams: Ein hängender Server wird nach 8 s (Live) bzw.
        // 12 s (Film) erkannt statt nach 30 s; dann verbindet der Player sofort neu.
        val streamClient = http.newBuilder()
            .readTimeout(if (live) 8L else 12L, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
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

        val renderers = AyuRenderersFactory(context, forcePcm)
            .setEnableDecoderFallback(true)
            // FFmpeg nur als Ergänzung: Was das Gerät selbst kann (oder an den Receiver durchreicht),
            // bleibt beim Gerät; DTS, TrueHD & Co. ohne Receiver übernimmt FFmpeg.
            .setExtensionRendererMode(
                if (AudioDecoders.ffmpegAvailable) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            )
            .apply {
                // Fire OS 6–8 entspricht Android 9–11: dort ist asynchrones Queueing nicht Standard,
                // bringt aber bei 4K mehr Decoder-Durchsatz. Bei Problemen schaltet der Player es selbst ab.
                if (asyncDecoding) forceEnableMediaCodecAsynchronousQueueing() else forceDisableMediaCodecAsynchronousQueueing()
            }

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTunnelingEnabled(settings.tunneling)
                    .setPreferredAudioLanguage(Locale.getDefault().language)
                    .apply {
                        if (!live) {
                            setPreferredTextLanguage(settings.subtitleLanguage)
                            if (!settings.subtitlesAutoOn) setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
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
        // Speicher-Obergrenze: Ein 4K-Stream mit 25 Mbit/s bräuchte für 60 s rund 190 MB Heap.
        // Auf Sticks mit 1,5–2 GB RAM führt das nach längerem Schauen zu Abstürzen. Das Limit gilt
        // vor den Zeitwerten; Streams mit normaler Bitrate erreichen ihre Zeitziele trotzdem.
        val maxBytes = when (settings.bufferProfile) {
            BufferProfile.FAST -> 40 * MB
            BufferProfile.BALANCED -> 64 * MB
            BufferProfile.STABLE -> 100 * MB
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, startBuffer, rebuffer)
            .setTargetBufferBytes(maxBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // Hoher Startwert: adaptive Streams beginnen direkt in guter Qualität statt mit 480p.
        val bandwidthMeter = DefaultBandwidthMeter.Builder(context)
            .setInitialBitrateEstimate(25_000_000L)
            .build()

        val player = ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(dataSourceFactory, extractors)
                    .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy(live))
            )
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Mit eigener Bildwiederholraten-Anpassung keine zweite Stelle, die per Surface.setFrameRate
        // mitredet: Sonst können sich beide Anforderungen gegenseitig umschalten.
        if (settings.matchFrameRate) player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        return Built(player, headers, trackSelector)
    }
}

/**
 * Abgerissene Verbindungen sind bei IPTV normal. Statt nach drei langsamen Versuchen einen Fehler
 * zu melden (Bild weg, Neustart), versucht der Player es schnell erneut, während der Puffer noch
 * läuft. Meist merkt man davon nichts. Endgültige Fehler (z. B. falsches Format) bleiben endgültig.
 */
private class StreamLoadErrorPolicy(private val live: Boolean) : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val delay = super.getRetryDelayMsFor(loadErrorInfo)
        if (delay == C.TIME_UNSET) return delay
        return minOf(300L * loadErrorInfo.errorCount, 2_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (live) 8 else 6
}

/**
 * Mit [forcePcm] meldet die Tonausgabe nur Stereo-PCM als unterstützt: Dann wird jedes Format im
 * Gerät dekodiert (MediaCodec oder FFmpeg) statt an Soundbar/Receiver durchgereicht. Hilft bei
 * Geräten, die Dolby/DTS ankündigen, aber nicht wiedergeben.
 */
private class AyuRenderersFactory(context: Context, private val forcePcm: Boolean) : DefaultRenderersFactory(context) {
    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        if (!forcePcm) {
            super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
        } else {
            // Bewusst ohne Context: Nur dann gelten feste Fähigkeiten "kein Durchreichen".
            // Mit Context fragt Media3 das Gerät ab und würde setAudioCapabilities ignorieren.
            DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                .setEnableFloatOutput(enableFloatOutput)
                .build()
        }
}

/**
 * Passt die Bildwiederholrate des Fernsehers an die Bildrate des Videos an (z. B. 50 Hz für
 * deutsches TV, 23,976 Hz für Filme). Das beseitigt das typische Ruckeln bei Kameraschwenks.
 *
 * Ein Moduswechsel lässt HDMI neu synchronisieren (1–3 s schwarz). Deshalb:
 * - pro Ziel-Modus nur einmal anfordern, nie erneut, solange der Fernseher noch umschaltet
 * - 25/30 fps auf 50/60 Hz statt 25/30 Hz (das können alle Fernseher ruckelfrei, 25 Hz flimmert)
 * - [onSwitch] meldet Beginn und Ende des Wechsels, damit der Player so lange pausiert
 */
class FrameRateMatcher(private val activity: Activity) {
    private var requestedModeId = 0
    private var listener: DisplayManager.DisplayListener? = null
    private var timeout: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    /** true = Fernseher schaltet um, false = fertig (oder Zeitüberschreitung). */
    var onSwitch: (Boolean) -> Unit = {}

    var switching = false
        private set

    /** Liefert true, wenn ein Wechsel angefordert wurde. */
    @Suppress("DEPRECATION")
    fun match(frameRate: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || frameRate <= 0f || switching) return false
        val display = activity.windowManager.defaultDisplay ?: return false
        val current = display.mode
        val sameSize = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        val targets = if (frameRate in 24.5f..30.5f) listOf(frameRate * 2, frameRate) else listOf(frameRate, frameRate * 2)
        val best = targets.firstNotNullOfOrNull { target -> sameSize.firstOrNull { matches(it.refreshRate, target) } } ?: return false
        if (best.modeId == current.modeId || best.modeId == requestedModeId) return false
        requestedModeId = best.modeId
        startSwitch(best.modeId)
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = best.modeId
        activity.window.attributes = attributes
        return true
    }

    fun reset() {
        finishSwitch()
        if (requestedModeId == 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = 0
        activity.window.attributes = attributes
        requestedModeId = 0
    }

    @Suppress("DEPRECATION")
    private fun startSwitch(modeId: Int) {
        finishSwitch()
        switching = true
        onSwitch(true)
        val manager = activity.getSystemService(DisplayManager::class.java)
        val displayId = activity.windowManager.defaultDisplay.displayId
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId || activity.windowManager.defaultDisplay.mode.modeId != modeId) return
                // Modus steht, der Fernseher braucht aber noch einen Moment für das neue HDMI-Signal.
                timeout?.let { handler.removeCallbacks(it) }
                handler.postDelayed({ finishSwitch() }, SETTLE_MS)
            }
        }
        listener = l
        manager?.registerDisplayListener(l, handler)
        // Manche Geräte melden den Wechsel nicht (oder lehnen ihn ab): spätestens dann weiterspielen.
        val t = Runnable { finishSwitch() }
        timeout = t
        handler.postDelayed(t, MAX_SWITCH_MS)
    }

    private fun finishSwitch() {
        listener?.let { activity.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) }
        listener = null
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        handler.removeCallbacksAndMessages(null)
        if (switching) {
            switching = false
            onSwitch(false)
        }
    }

    private fun matches(refreshRate: Float, frameRate: Float) = abs(refreshRate - frameRate) < 0.05f

    private companion object {
        const val SETTLE_MS = 1_200L
        const val MAX_SWITCH_MS = 5_000L
    }
}
