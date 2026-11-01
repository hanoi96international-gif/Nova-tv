@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

/**
 * Baut einen ExoPlayer, der auf schnelles Umschalten und flüssiges 4K auf Fire-TV-Hardware ausgelegt ist:
 * - Hardware-Decoder mit Fallback statt Software-Decoding
 * - TS-Extractor startet ohne auf einen IDR-Frame zu warten (spürbar schnelleres Zappen)
 * - Pufferprofile statt fester Werte, optional Tunneling (Video läuft direkt im Decoder/Display-Pfad)
 * - Ein geteilter OkHttp-Client hält Verbindungen zum Server offen
 */
object PlayerFactory {
    private const val MB = 1024 * 1024

    class Built(val player: ExoPlayer, val headers: HeaderProvider, val trackSelector: DefaultTrackSelector, val subtitles: EmbeddedSubtitles)

    /** Der User-Agent kann sich pro Sender unterscheiden (#EXTVLCOPT), ohne den Player neu zu bauen. */
    class HeaderProvider(@Volatile var userAgent: String)

    /** Untertitel sind bei Filmen optional: bevorzugte Sprache ist gesetzt, eingeschaltet nur mit "automatisch an". */
    fun create(
        context: Context,
        http: OkHttpClient,
        settings: AppSettings,
        live: Boolean,
        asyncDecoding: Boolean = settings.asyncDecoding,
        forcePcm: Boolean = settings.audioOutput == AudioOutput.PCM,
        speech: SpeechActivity? = null,
        timeshift: TimeshiftHolder? = null,
        enhance: AudioEnhance? = null,
    ): Built {
        val headers = HeaderProvider(settings.userAgent)

        // Eigener, kurzer Lese-Timeout für Streams: Ein hängender Server wird nach 8 s (Live) bzw.
        // 12 s (Film) erkannt statt nach 30 s; dann verbindet der Player sofort neu.
        val streamClient = http.newBuilder()
            // Streams am Proxy vorbei (Einstellung): Anmeldung, Listen und TV-Guide laufen weiter über ihn.
            .apply { if (!settings.proxyStreams) proxy(java.net.Proxy.NO_PROXY) }
            .readTimeout(if (live) 8L else 12L, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            // Großer Empfangspuffer: WLAN-Aussetzer werden schon im Netzwerk abgefedert.
            .socketFactory(LargeBufferSocketFactory)
            .build()
        val httpFactory = OkHttpDataSource.Factory(streamClient)
        val resolving = ResolvingDataSource.Factory(httpFactory) { spec ->
            spec.withAdditionalHeaders(mapOf("User-Agent" to headers.userAgent))
        }
        // Live: mitschreiben für den Zeitversatz und timeshift://-Adressen aus dem Zwischenspeicher lesen.
        val upstream = if (timeshift != null) LiveDataSource.Factory(resolving, timeshift) else resolving
        val dataSourceFactory = DefaultDataSource.Factory(context, upstream)

        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        // Eingebettete Untertitel schon beim Laden mitlesen (für Übersetzung mit Vorlauf).
        val subtitles = EmbeddedSubtitles()
        val extractors = subtitles.tap(
            DefaultExtractorsFactory()
                .setTsExtractorFlags(tsFlags)
                .setConstantBitrateSeekingEnabled(true)
        )

        val renderers = AyuRenderersFactory(context, forcePcm, speech, enhance)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(DolbyVisionFallbackSelector)
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
            // Nach einem Hänger erst mit etwas Vorrat weiterspielen: verhindert Serien kurzer Ruckler.
            BufferProfile.FAST -> listOf(3_000, 30_000, 600, 2_500)
            BufferProfile.BALANCED -> listOf(8_000, 45_000, 1_200, 4_000)
            BufferProfile.STABLE -> listOf(15_000, 60_000, 2_500, 6_000)
        }
        // Speicher-Obergrenze: Ein 4K-Stream mit 25 Mbit/s bräuchte für 60 s rund 190 MB Heap.
        // Auf Sticks mit 1,5–2 GB RAM führt das nach längerem Schauen zu Abstürzen. Das Limit gilt
        // vor den Zeitwerten; Streams mit normaler Bitrate erreichen ihre Zeitziele trotzdem.
        val profileBytes = when (settings.bufferProfile) {
            BufferProfile.FAST -> 40 * MB
            BufferProfile.BALANCED -> 64 * MB
            BufferProfile.STABLE -> 100 * MB
        }
        // Sticks mit 1,5–2 GB RAM: Bei 100 MB Puffer beendet das System die App während eines 4K-Films
        // wegen Speichermangel (auf einem Fire TV Stick 4K beobachtet). 64 MB reichen auch für 4K-Remuxe.
        val maxBytes = if (de.ayutv.data.DeviceInfo.isWeak(context)) minOf(profileBytes, 64 * MB) else profileBytes
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
            .setMediaSourceFactory(StreamMediaSourceFactory(dataSourceFactory, extractors, tsFlags, live))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                true,
            )
            // Bei Live-TV nicht pausieren, wenn Android beim Ein-/Ausschalten der Soundbar „Kopfhörer
            // getrennt“ meldet: Sonst stünde das Bild, bis jemand Play drückt.
            .setHandleAudioBecomingNoisy(!live)
            // WLAN während der Wiedergabe wach halten: Fire TV schaltet es sonst in den Stromsparmodus,
            // eine häufige Ursache für kurze Hänger bei Live-TV.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        // Mit eigener Bildwiederholraten-Anpassung keine zweite Stelle, die per Surface.setFrameRate
        // mitredet: Sonst können sich beide Anforderungen gegenseitig umschalten.
        if (settings.matchFrameRate) player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        // Filme: zum nächsten Keyframe springen statt exakt. Bei 4K HEVC mit langen GOPs spart das
        // pro Sprung Sekunden Dekodieren.
        if (!live) player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
        return Built(player, headers, trackSelector, subtitles)
    }

    /**
     * Für den Sender-Check: nur Spuren erkennen, nichts dekodieren (Bild, Ton und Untertitel aus). Läuft auf [looper].
     */
    fun createProbe(context: Context, http: OkHttpClient, userAgent: String, looper: android.os.Looper): ExoPlayer {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).build()
        val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(client)) { spec ->
            spec.withAdditionalHeaders(mapOf("User-Agent" to userAgent))
        }
        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        return ExoPlayer.Builder(context)
            .setLooper(looper)
            .setMediaSourceFactory(StreamMediaSourceFactory(DefaultDataSource.Factory(context, upstream), DefaultExtractorsFactory().setTsExtractorFlags(tsFlags), tsFlags, live = true))
            .setTrackSelector(trackSelector)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_000, 3_000, 500, 500).setTargetBufferBytes(4 * MB).build())
            .build()
            .apply { volume = 0f }
    }

    /**
     * Zweites, kleines Bild (Konferenz): ohne Ton, ohne Untertitel, höchstens 720p und mit kleinem Puffer, damit
     * es neben dem Hauptbild auf schwachen Sticks Platz hat.
     */
    fun createPreview(context: Context, http: OkHttpClient, userAgent: String): ExoPlayer {
        val client = http.newBuilder()
            .readTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .build()
        val upstream = ResolvingDataSource.Factory(OkHttpDataSource.Factory(client)) { spec ->
            spec.withAdditionalHeaders(mapOf("User-Agent" to userAgent))
        }
        val tsFlags = DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(tsFlags)
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1280, 720)
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 10_000, 800, 1_500)
            .setTargetBufferBytes(12 * MB)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
            .setMediaSourceFactory(StreamMediaSourceFactory(DefaultDataSource.Factory(context, upstream), extractors, tsFlags, live = true))
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .build()
            .apply { volume = 0f }
    }
}

/**
 * HLS mit denselben TS-Einstellungen wie direkte Streams: Ohne die Flags wartet HLS auf einen
 * IDR-Frame, Sender mit reinen I-Frames starten langsam oder gar nicht. Alles andere (direkte
 * Streams, DASH, Filme mit nachgeladenen Untertiteln) geht über die Standard-Fabrik.
 */
private class StreamMediaSourceFactory(
    dataSourceFactory: androidx.media3.datasource.DataSource.Factory,
    extractors: androidx.media3.extractor.ExtractorsFactory,
    tsFlags: Int,
    live: Boolean,
) : MediaSource.Factory {
    private val default = DefaultMediaSourceFactory(dataSourceFactory, extractors)
        .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy(live))
    private val hls = HlsMediaSource.Factory(dataSourceFactory)
        .setExtractorFactory(
            DefaultHlsExtractorFactory(tsFlags, true)
                // Wie der Standard-Konstruktor: Abhängigkeiten innerhalb der GOP für H.264/H.265 auswerten.
                .experimentalSetCodecsToParseWithinGopSampleDependencies(C.VIDEO_CODEC_FLAG_H264 or C.VIDEO_CODEC_FLAG_H265)
        )
        .setLoadErrorHandlingPolicy(StreamLoadErrorPolicy(live))

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        default.setDrmSessionManagerProvider(provider)
        hls.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        default.setLoadErrorHandlingPolicy(policy)
        hls.setLoadErrorHandlingPolicy(policy)
    }

    override fun getSupportedTypes(): IntArray = default.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val plainHls = config != null && config.mimeType == MimeTypes.APPLICATION_M3U8 && config.subtitleConfigurations.isEmpty()
        return if (plainHls) {
            hls.createMediaSource(mediaItem)
        } else {
            default.createMediaSource(mediaItem)
        }
    }
}

/**
 * Dolby Vision Profil 7 (UHD-Blu-ray-Remuxe) kann kaum ein Gerät als DV dekodieren, die Basisschicht
 * ist aber normales HEVC/HDR10. HEVC-Decoder hinten anhängen: Kann der DV-Decoder das Profil, gewinnt
 * er weiter (Media3 sortiert nach Unterstützung), sonst läuft das Bild in HDR10 statt gar nicht.
 */
private val DolbyVisionFallbackSelector = MediaCodecSelector { mime, secure, tunneling ->
    val infos = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
    if (mime == MimeTypes.VIDEO_DOLBY_VISION) infos + MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H265, secure, tunneling) else infos
}

/** Sockets mit 2 MB Empfangspuffer (vor dem Verbinden gesetzt, damit TCP ein großes Fenster aushandelt). */
private object LargeBufferSocketFactory : SocketFactory() {
    private const val RECEIVE_BUFFER = 2 * 1024 * 1024
    private val base: SocketFactory = getDefault()

    private fun Socket.tuned() = apply { runCatching { receiveBufferSize = RECEIVE_BUFFER } }

    override fun createSocket(): Socket = base.createSocket().tuned()
    override fun createSocket(host: String?, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localHost, localPort))
            connect(InetSocketAddress(host, port))
        }
    override fun createSocket(host: InetAddress?, port: Int): Socket = createSocket().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        createSocket().apply {
            bind(InetSocketAddress(localAddress, localPort))
            connect(InetSocketAddress(address, port))
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
        // Direkte Streams (kein HLS/DASH): Ein 401/404/410 wird nie gut, sofort melden statt
        // ~10 s „Lädt …“. 403/429 kommen oft kurz nach dem Zappen (alte Verbindung noch offen).
        val progressive = loadErrorInfo.mediaLoadData.trackType == C.TRACK_TYPE_UNKNOWN
        val code = (loadErrorInfo.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (progressive) when (code) {
            400, 401, 404, 405, 410, 451 -> return C.TIME_UNSET
            403, 429, 458, 509 -> if (loadErrorInfo.errorCount > 3) return C.TIME_UNSET
        }
        return minOf(300L * loadErrorInfo.errorCount, 2_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = if (live) 8 else 6
}

/**
 * Mit [forcePcm] meldet die Tonausgabe nur Stereo-PCM als unterstützt: Dann wird jedes Format im
 * Gerät dekodiert (MediaCodec oder FFmpeg) statt an Soundbar/Receiver durchgereicht. Hilft bei
 * Geräten, die Dolby/DTS ankündigen, aber nicht wiedergeben. Mit [speech] hört [SpeechTapProcessor]
 * beim Ton mit (Ausrichtung geladener Untertitel an der Sprache), mit [enhance] gibt es Nachtmodus und
 * Dialog-Verstärkung.
 */
private class AyuRenderersFactory(
    context: Context,
    private val forcePcm: Boolean,
    private val speech: SpeechActivity?,
    private val enhance: AudioEnhance?,
) : DefaultRenderersFactory(context) {
    @Suppress("DEPRECATION")
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        // Bewusst ohne Context bei forcePcm: Nur dann gelten feste Fähigkeiten "kein Durchreichen".
        // Mit Context fragt Media3 das Gerät ab und würde setAudioCapabilities ignorieren.
        val builder = if (forcePcm) {
            DefaultAudioSink.Builder().setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
        } else {
            DefaultAudioSink.Builder(context).setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
        }
        // Erst mithören (unveränderte Sprache), dann Nachtmodus/Dialoge.
        val processors = listOfNotNull<AudioProcessor>(speech?.let(::SpeechTapProcessor), enhance?.let(::AudioEnhanceProcessor))
        if (processors.isNotEmpty()) builder.setAudioProcessors(processors.toTypedArray())
        return builder.setEnableFloatOutput(enableFloatOutput).build()
    }
}

