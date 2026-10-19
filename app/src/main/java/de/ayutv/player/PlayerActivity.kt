@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import de.ayutv.AyuApp
import de.ayutv.data.AppSettings
import de.ayutv.data.AudioOutput
import de.ayutv.data.BufferProfile
import de.ayutv.data.DeviceInfo
import de.ayutv.data.Languages
import de.ayutv.data.LiveFormat
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.HistoryEntity
import de.ayutv.data.db.HistoryKind
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.repo.SubtitleRepository
import de.ayutv.data.repo.Translators
import de.ayutv.data.parse.Srt
import de.ayutv.data.parse.SrtCue
import de.ayutv.ui.theme.AyuTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class Overlay { NONE, INFO, CHANNELS, CONTROLS, TRACKS }

class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as AyuApp).container }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frameRateMatcher: FrameRateMatcher
    private lateinit var settings: AppSettings
    private var headers: PlayerFactory.HeaderProvider? = null
    private var trackSelector: DefaultTrackSelector? = null
    /** Beim Laden mitgelesene eingebettete Untertitel (für Übersetzung mit Vorlauf). */
    private var embedded: EmbeddedSubtitles? = null
    private var asyncDecodingActive = false
    private var forcePcmActive = false
    private val rebufferTimes = ArrayDeque<Long>()
    private var bufferAdviceShown = false
    private var lastAudioHintFor: String? = null

    // Zustand, den die Compose-Oberfläche liest
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var isLive by mutableStateOf(true); private set
    var overlay by mutableStateOf(Overlay.NONE); private set
    var overlayToken by mutableIntStateOf(0); private set
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList()); private set
    /** Überschrift der Senderliste: Gruppe, Favoriten oder alle Sender. */
    var listTitle by mutableStateOf(""); private set
    var current by mutableStateOf<ChannelEntity?>(null); private set
    var nowNext by mutableStateOf<List<ProgrammeEntity>>(emptyList()); private set
    var nowPlayingMap by mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()); private set
    var numberInput by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    var buffering by mutableStateOf(true); private set
    /** Endgültiger Fehler: Bild schwarz statt des letzten Bildes vom vorigen Sender. */
    var fatalError by mutableStateOf(false); private set
    var showTech by mutableStateOf(false); private set
    /** Gepufferte Sekunden (nur aktualisiert, solange die Stream-Info sichtbar ist). */
    var bufferedMs by mutableLongStateOf(0L); private set
    /** Live: Wiedergabe läuft gerade minimal langsamer, um wieder Vorrat aufzubauen. */
    var cushionActive by mutableStateOf(false); private set
    var stats by mutableStateOf(StreamStats()); private set
    var vodTitle by mutableStateOf(""); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var isFavorite by mutableStateOf(false); private set
    var tracks by mutableStateOf(Tracks.EMPTY); private set
    var textDisabled by mutableStateOf(false); private set
    var subtitleScale by mutableFloatStateOf(1f); private set
    private var subtitleStatusState by mutableStateOf<String?>(null)
    var subtitleStatus: String?
        get() = subtitleStatusState
        set(value) {
            subtitleStatusState = value
            if (value != null) {
                statusHistory += value
                Log.i(SUBTITLE_TAG, value)
            }
        }
    /** Alle Statusmeldungen dieser Sitzung (für Diagnose und Tests). */
    val statusHistory = mutableListOf<String>()
    val onlineSubtitlesAvailable: Boolean get() = !isLive && ::settings.isInitialized && settings.onlineSubtitlesEnabled

    private var vod: VodRequest? = null
    private var previousChannel: ChannelEntity? = null
    /** Sender, den der Player gerade wirklich spielt ([current] zeigt beim schnellen Zappen schon das Ziel). */
    private var playing: ChannelEntity? = null
    private var retries = 0
    private var triedHls = false
    private var zapStartedAt = 0L
    private var everReady = false
    /** Dieser Stream hat schon einmal nachgeladen: erst dann greift das Puffer-Polster. */
    private var rebufferedThisStream = false
    private var fpsSample: Pair<Long, Long>? = null
    private val liveEndings = ArrayDeque<Long>()
    /** Bis hierhin gelten Fehler als Folge des HDMI-Moduswechsels (Bildwiederholrate), nicht des Streams. */
    private var displaySwitchGraceUntil = 0L
    private var displaySwitchErrors = 0
    private var resumeAfterSwitch = false
    private var progressJob: Job? = null
    private var numberJob: Job? = null
    private var watchdogJob: Job? = null
    private var infoJob: Job? = null
    private var zapJob: Job? = null
    private var lastZapAt = 0L
    private var zapTarget: ChannelEntity? = null
    private var seekJob: Job? = null
    private var pendingSeekMs = C.TIME_UNSET
    private var lastAudioErrorAt = 0L
    /** Zählt jeden neuen Stream (Sender, Neuverbindung, neuer Player): Der Watchdog fängt dann von vorn an. */
    private var streamGeneration = 0
    /** Nachgeladene/übersetzte Untertitel nach Sprache. Alle hängen gleichzeitig am Film, Umschalten ist sofort. */
    private val subtitleFiles = LinkedHashMap<String, File>()
    private var autoSubtitleTried = false
    private var subtitleJob: Job? = null
    private var fileTranslationJob: Job? = null
    private val cueTranslations = HashMap<String, String>()
    private var cueJob: Job? = null

    /** Live-Übersetzung eingebetteter Untertitel: Zielsprache, oder null wenn aus. */
    var liveTranslateTarget by mutableStateOf<String?>(null); private set
    /** Geladene (oder gerade übersetzte) Untertitel, die DnA-TV selbst einblendet: Sprache, oder null. */
    var externalLanguage by mutableStateOf<String?>(null); private set
    /** Sprachen mit gespeicherten Untertiteln zu diesem Film (für das Menü). */
    var loadedLanguages by mutableStateOf<List<String>>(emptyList()); private set
    /** Von Hand eingestellte Verschiebung der geladenen Untertitel (zusätzlich zur automatischen). */
    var subtitleDelayMs by mutableLongStateOf(0L); private set
    /** Automatisch erkannte Lage der geladenen Untertitel (an eingebetteten Untertiteln ausgerichtet). */
    var subtitleTiming by mutableStateOf(SubtitleTiming()); private set
    var subtitleAutoSynced by mutableStateOf(false); private set
    /** Untertitel kommen nicht vom Player, sondern aus DnA-TV (Übersetzung oder geladene Datei). */
    val ownSubtitles: Boolean get() = liveTranslateTarget != null || externalLanguage != null
    private var externalCues: List<TimedCue> = emptyList()
    private var externalTexts: java.util.concurrent.atomic.AtomicReferenceArray<String>? = null
    private var externalJob: Job? = null
    /** Startzeiten eingebetteter Untertitel (Filmzeit): Taktgeber für die automatische Ausrichtung. */
    private val referenceStarts = ArrayList<Long>()
    private var referenceShowing = false
    private var ignoreReferenceUntil = 0L
    private var syncJob: Job? = null
    private var liveSourceLanguage: String? = null
    /** Übersetzung eingebetteter Untertitel mit Vorlauf: Quellspur, Ergebnisse je Einblendung (Startzeit). */
    private var aheadFormat: Format? = null
    private val aheadTranslations = HashMap<Long, String>()
    private var aheadJob: Job? = null
    var translatedCue by mutableStateOf<String?>(null); private set
    var languagePicker by mutableStateOf(false); private set
    val preferredSubtitleLanguage: String get() = if (::settings.isInitialized) settings.subtitleLanguage else "en"
    val translationAvailable: Boolean get() = ::settings.isInitialized && settings.translationEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frameRateMatcher = FrameRateMatcher(this)
        frameRateMatcher.onSwitch = ::onDisplaySwitch
        vod = intent.getVodRequest()
        isLive = vod == null

        setContent { AyuTheme { PlayerScreen(this) } }

        lifecycleScope.launch {
            settings = container.settings.current()
            // Einmaliges Laden eines Sprachmodells für die Übersetzung auf dem Gerät anzeigen.
            Translators.remote.onProgress = { text -> runOnUiThread { subtitleStatus = text } }
            subtitleScale = settings.subtitleSize.scale
            textDisabled = vod != null && !settings.subtitlesAutoOn
            installPlayer(settings.asyncDecoding, settings.audioOutput == AudioOutput.PCM)
            startWatchdog()

            val request = vod
            if (request != null) {
                vodTitle = request.title
                val resume = container.content.resumePosition(request.key)
                // Datei-Hash, bevor der Player verbindet (danach nie parallel): Damit findet OpenSubtitles
                // Untertitel, die zeitgenau zu genau dieser Datei passen.
                if (settings.onlineSubtitlesEnabled && request.kind != HistoryKind.CATCHUP) {
                    container.subtitles.prepareHash(request.url, settings.userAgent)
                }
                startVod(request, resume)
            } else {
                val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
                val group = intent.getStringExtra(EXTRA_GROUP)
                val favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES, false)
                listTitle = when {
                    favoritesOnly -> "★ Favoriten"
                    group != null -> group
                    else -> "Alle Sender"
                }
                // Erst den Sender starten, dann die (evtl. zehntausende Einträge lange) Zapp-Liste laden.
                val direct = key?.let { container.content.channel(it) }
                if (direct != null) playChannel(direct)
                val list = container.content.zapList(group, favoritesOnly)
                val start = direct ?: list.firstOrNull()
                if (start == null) {
                    finish()
                    return@launch
                }
                if (direct == null) playChannel(start)
                channels = if (list.none { it.key == start.key }) listOf(start) + list else list
            }
        }
    }

    // ---------------------------------------------------------------- Stabilität

    private fun installPlayer(asyncDecoding: Boolean, forcePcm: Boolean) {
        val built = PlayerFactory.create(this, container.http, settings, live = vod == null, asyncDecoding = asyncDecoding, forcePcm = forcePcm)
        headers = built.headers
        trackSelector = built.trackSelector
        embedded = built.subtitles
        asyncDecodingActive = asyncDecoding
        forcePcmActive = forcePcm
        built.player.addListener(playerListener)
        built.player.addAnalyticsListener(analyticsListener)
        cushionActive = false
        streamGeneration++
        player = built.player
    }

    /** Player mit anderer Decoder-Konfiguration neu aufbauen und an derselben Stelle weiterspielen. */
    private fun rebuildPlayer(asyncDecoding: Boolean = asyncDecodingActive, forcePcm: Boolean = forcePcmActive) {
        val old = player ?: return
        val item = old.currentMediaItem
        val position = old.currentPosition
        val parameters = old.trackSelectionParameters
        val userAgent = headers?.userAgent
        old.removeListener(playerListener)
        old.release()
        installPlayer(asyncDecoding, forcePcm)
        val p = player ?: return
        userAgent?.let { headers?.userAgent = it }
        p.trackSelectionParameters = parameters
        if (isLive) p.repeatMode = Player.REPEAT_MODE_ONE
        if (item != null) {
            if (isLive) p.setMediaItem(item) else p.setMediaItem(item, position)
            p.prepare()
            p.playWhenReady = true
        }
    }

    /**
     * IPTV-Streams frieren oft ein, ohne dass ein Fehler kommt: Der Player puffert endlos
     * oder die Position steht still. Der Watchdog erkennt das und verbindet neu.
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = lifecycleScope.launch {
            // Nur im Vordergrund: Im Hintergrund ist der Live-Stream ohnehin gestoppt.
            repeatOnLifecycle(Lifecycle.State.STARTED) { watch() }
        }
    }

    private suspend fun watch() {
        var lastPosition = -1L
        var stalledSeconds = 0
        var lastReconnect = 0L
        var lastFrames = -1L
        var framesAdvancing = false
        var frozenVideoSeconds = 0
        var seenGeneration = -1
        while (true) {
            delay(1_000)
            val p = player ?: continue
            if (seenGeneration != streamGeneration) {
                seenGeneration = streamGeneration
                stalledSeconds = 0
                frozenVideoSeconds = 0
                lastFrames = -1
                framesAdvancing = false
                lastPosition = -1
            }
            if (displaySwitching()) {
                stalledSeconds = 0
                continue
            }
            val active = p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
            val waiting = active && p.playbackState == Player.STATE_BUFFERING
            val frozen = active && p.playbackState == Player.STATE_READY && p.currentPosition == lastPosition
            lastPosition = p.currentPosition
            stalledSeconds = if (waiting || frozen) stalledSeconds + 1 else 0
            // Bild steht, Ton läuft weiter (Decoder hängt): an den Decoder-Zählern erkennen.
            // Nur wenn die Zähler auf diesem Gerät überhaupt laufen (sonst nie eingreifen).
            val frames = if (p.videoFormat != null) p.videoDecoderCounters?.run { ensureUpdated(); renderedOutputBufferCount.toLong() } else null
            if (frames != null && lastFrames in 0 until frames) framesAdvancing = true
            frozenVideoSeconds = if (frames != null && frames == lastFrames && framesAdvancing && p.isPlaying) frozenVideoSeconds + 1 else 0
            lastFrames = frames ?: -1
            // Beim ersten Verbinden hat der Loader eigene Wiederholversuche: die nicht abwürgen.
            val limit = when {
                !everReady -> FIRST_CONNECT_STALL_SECONDS
                isLive -> LIVE_STALL_SECONDS
                else -> VOD_STALL_SECONDS
            }
            estimateFrameRate(p)
            if (isLive) keepLiveCushion(p)
            if (showTech) bufferedMs = p.totalBufferedDuration
            val now = SystemClock.elapsedRealtime()
            if (stalledSeconds >= limit && now - lastReconnect > 20_000) {
                stalledSeconds = 0
                lastReconnect = now
                reconnect("Stream hängt, verbinde neu …")
            } else if (frozenVideoSeconds >= VIDEO_FREEZE_SECONDS && now - lastReconnect > 20_000) {
                frozenVideoSeconds = 0
                lastReconnect = now
                reconnect("Bild hängt, verbinde neu …")
            }
        }
    }

    /**
     * Der Fernseher wechselt die Bildwiederholrate: HDMI synchronisiert neu, Bild und Ton fallen
     * kurz aus. In dieser Zeit pausieren (wie Kodi), statt Ton- und Bildfehler als Stream- oder
     * Decoder-Problem zu behandeln und den Player umzubauen.
     */
    private fun onDisplaySwitch(active: Boolean) {
        val p = player
        if (active) {
            displaySwitchGraceUntil = SystemClock.elapsedRealtime() + DISPLAY_SWITCH_GRACE_MS
            displaySwitchErrors = 0
            resumeAfterSwitch = p?.playWhenReady == true
            p?.playWhenReady = false
        } else {
            displaySwitchGraceUntil = SystemClock.elapsedRealtime() + DISPLAY_SWITCH_GRACE_MS
            if (p != null && (resumeAfterSwitch || isLive) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                if (p.playbackState == Player.STATE_IDLE) p.prepare()
                p.playWhenReady = true
            }
            resumeAfterSwitch = false
        }
    }

    /**
     * Live-Streams kommen in Echtzeit an: Der Puffer wächst von selbst nie, jeder Netz-Schluckauf
     * friert das Bild ein. Hat ein Stream schon einmal gestockt und ist der Vorrat wieder knapp,
     * läuft die Wiedergabe unhörbar 1,5 % langsamer, bis
     * wieder genug gepuffert ist (Tonhöhe bleibt gleich). Bei durchgereichtem Dolby/DTS und im
     * Tunneling-Modus ignoriert das Gerät die Geschwindigkeit; dann bleibt alles wie bisher.
     */
    private fun keepLiveCushion(p: ExoPlayer) {
        // Im Tunneling-Modus steuert die Hardware den Takt; dort nichts verändern.
        if (!p.isPlaying || trackSelector?.parameters?.tunnelingEnabled == true) return
        // Jede Tempoänderung kostet bei 50 Hz ab und zu ein doppeltes Bild. Deshalb nur bei Streams,
        // die schon einmal gestockt haben; alle anderen laufen exakt in Originalgeschwindigkeit.
        if (!cushionActive && !rebufferedThisStream) return
        // HLS mit Zielabstand zum Live-Rand: Media3 regelt die Geschwindigkeit selbst, nicht dazwischenfunken.
        val window = p.currentTimeline.takeUnless { it.isEmpty }?.getWindow(p.currentMediaItemIndex, Timeline.Window())
        if (window?.liveConfiguration?.targetOffsetMs?.let { it != C.TIME_UNSET } == true) return
        val buffered = p.totalBufferedDuration
        val wanted = when {
            !cushionActive && buffered < CUSHION_LOW_MS -> true
            cushionActive && buffered > CUSHION_HIGH_MS -> false
            else -> return
        }
        cushionActive = wanted
        p.setPlaybackSpeed(if (wanted) CUSHION_SPEED else 1f)
    }

    private fun displaySwitching() = frameRateMatcher.switching || SystemClock.elapsedRealtime() < displaySwitchGraceUntil

    private fun resetStreamState() {
        everReady = false
        rebufferedThisStream = false
        fatalError = false
        fpsSample = null
    }

    /**
     * Viele TS-Streams nennen keine Bildrate (fps = -1). Dann wird sie aus den Decoder-Zählern
     * gemessen und auf den nächsten Standardwert gerundet. Erst damit kann die Bildwiederholrate
     * des Fernsehers bei Live-TV auf 50 Hz umschalten.
     */
    private fun estimateFrameRate(p: ExoPlayer) {
        if (stats.frameRate > 0f || p.playbackState != Player.STATE_READY || !p.isPlaying) return
        val counters = p.videoDecoderCounters ?: return
        counters.ensureUpdated()
        val frames = (counters.renderedOutputBufferCount + counters.droppedBufferCount + counters.skippedOutputBufferCount).toLong()
        // Gegen die Wiedergabeposition messen (Frames pro Sekunde Video), nicht gegen die Uhr:
        // so stört es nicht, wenn der Decoder anfangs gepufferte Frames schubweise abarbeitet.
        val position = p.currentPosition
        val sample = fpsSample
        if (sample == null) {
            fpsSample = position to frames
            return
        }
        val elapsedMedia = position - sample.first
        if (elapsedMedia < 0) {
            fpsSample = null
            return
        }
        if (elapsedMedia < 4_000) return
        val measured = (frames - sample.second) * 1000f / elapsedMedia
        val snapped = STANDARD_FRAME_RATES.minByOrNull { abs(it - measured) }
            ?.takeIf { abs(it - measured) / it < 0.06f } ?: return
        stats = stats.copy(frameRate = snapped, frameRateMeasured = true, displayHz = frameRateMatcher.currentRefreshRate())
        suggestFrameRateMatching()
        if (settings.matchFrameRate) frameRateMatcher.match(snapped)
    }

    /** Mehrfaches Nachladen in kurzer Zeit: größeren Puffer empfehlen (einmal pro Sitzung). */
    private fun onRebuffer() {
        rebufferedThisStream = true
        val now = SystemClock.elapsedRealtime()
        rebufferTimes.addLast(now)
        while (rebufferTimes.isNotEmpty() && now - rebufferTimes.first() > 120_000) rebufferTimes.removeFirst()
        if (rebufferTimes.size >= 3 && !bufferAdviceShown && settings.bufferProfile != BufferProfile.STABLE) {
            bufferAdviceShown = true
            subtitleStatus = "Leitung schwankt: in den Einstellungen Puffer auf „Stabil (4K)“ stellen"
        }
    }

    /** Gibt es Ton, aber keine abspielbare Spur, wird das gesagt statt stumm zu bleiben. */
    private fun checkAudioSupport(tracks: Tracks) {
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (audio.isEmpty() || audio.any { group -> (0 until group.length).any(group::isTrackSupported) }) return
        val mime = audio.first().getTrackFormat(0).sampleMimeType
        val key = (current?.key ?: vod?.key) + mime
        if (key == lastAudioHintFor) return
        lastAudioHintFor = key
        subtitleStatus = unsupportedAudioMessage(mime)
    }

    /** Im Ton-Menü eine nicht abspielbare Spur gewählt: erklären statt nichts zu tun. */
    fun explainUnsupportedAudio(format: androidx.media3.common.Format) {
        closeOverlay()
        subtitleStatus = unsupportedAudioMessage(format.sampleMimeType)
    }

    private fun unsupportedAudioMessage(mime: String?): String {
        val name = when (mime) {
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "DTS"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Digital Plus"
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            else -> mime?.substringAfter('/') ?: "unbekannt"
        }
        val hint = if (AudioDecoders.ffmpegAvailable) "" else " (in dieser Version fehlt der FFmpeg-Tondecoder)"
        return "Tonformat $name kann dieses Gerät nicht wiedergeben$hint"
    }

    private fun unsupportedFormatMessage(): String {
        val format = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
            ?.let { group -> (0 until group.length).map(group::getTrackFormat).maxByOrNull { it.height } }
        val codec = when (format?.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> "HEVC"
            MimeTypes.VIDEO_H264 -> "H.264"
            MimeTypes.VIDEO_AV1 -> "AV1"
            MimeTypes.VIDEO_VP9 -> "VP9"
            MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
            MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
            null -> "dieses Videoformat"
            else -> format?.sampleMimeType?.substringAfter('/') ?: "dieses Videoformat"
        }
        val bits = format?.colorInfo?.lumaBitdepth?.takeIf { it > 8 }?.let { " $it Bit" }.orEmpty()
        val resolution = format?.height?.takeIf { it > 0 }?.let { if (it >= 2000) " in 4K" else " in ${it}p" }.orEmpty()
        return "Dieses Gerät kann $codec$bits$resolution nicht abspielen"
    }

    private fun onLiveStreamEnded() {
        val now = SystemClock.elapsedRealtime()
        liveEndings.addLast(now)
        while (liveEndings.isNotEmpty() && now - liveEndings.first() > 60_000) liveEndings.removeFirst()
        // Endet der Stream ständig sofort wieder, ist er wirklich weg: dann langsamer probieren und es sagen.
        val delayMs = if (liveEndings.size > 5) 3_000L else 200L
        if (liveEndings.size > 5) message = "Sender liefert nichts, versuche es weiter …"
        handler.postDelayed({ if (player?.playbackState == Player.STATE_ENDED) reconnect(null) }, delayMs)
    }

    private fun reconnect(reason: String?) {
        val p = player ?: return
        if (reason != null) message = reason
        streamGeneration++
        val position = p.currentPosition
        p.stop()
        if (isLive) p.seekToDefaultPosition() else p.seekTo(position)
        p.prepare()
        p.playWhenReady = true
    }

    private fun originalUrl(): String? = vod?.url ?: playing?.let { adaptLiveUrl(it.url) }

    private fun retryLater() {
        val generation = streamGeneration
        handler.postDelayed({ if (generation == streamGeneration) restartStream() }, 800L * retries.coerceAtLeast(1))
    }

    /** Nach Fehler oder Hintergrund neu starten: Live am Live-Rand (wichtig für HLS), Filme an derselben Stelle. */
    private fun restartStream() {
        val p = player ?: return
        if (isLive) p.seekToDefaultPosition()
        p.prepare()
        p.playWhenReady = true
    }

    // ---------------------------------------------------------------- Live

    fun playChannel(channel: ChannelEntity) {
        val p = player ?: return
        playing?.takeIf { it.key != channel.key }?.let { previousChannel = it }
        playing = channel
        current = channel
        // Verzögerte Wiederholungen und Rückrufe vom vorigen Sender dürfen den neuen nicht treffen.
        handler.removeCallbacksAndMessages(null)
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        streamGeneration++
        resetStreamState()
        retries = 0
        triedHls = false
        message = null
        zapStartedAt = SystemClock.elapsedRealtime()
        stats = StreamStats()
        if (cushionActive) {
            cushionActive = false
            p.setPlaybackSpeed(1f)
        }
        headers?.userAgent = channel.userAgent ?: settings.userAgent
        // Bildwiederholrate NICHT zurücksetzen: Sonst schaltet der Fernseher bei jedem Zappen zweimal
        // um (zurück auf Standard, dann wieder auf 50 Hz) und wird jedes Mal kurz schwarz.

        val url = adaptLiveUrl(channel.url)
        stopLiveTranslation()
        embedded?.clear()
        p.setMediaItem(mediaItem(url, guessMime(url)))
        // Schließt der Server die Verbindung (bei IPTV alltäglich), baut der Player die nächste schon
        // auf, während der Puffer noch läuft: nahtlos weiter statt Standbild und Neustart.
        p.repeatMode = Player.REPEAT_MODE_ONE
        p.prepare()
        p.playWhenReady = true
        showOverlay(Overlay.INFO)

        loadChannelInfo(channel)
        container.scope.launch { container.content.recordLive(channel) }
    }

    /** EPG und Favorit für die Infoleiste; eine ältere Abfrage überschreibt nie die des neuen Senders. */
    private fun loadChannelInfo(channel: ChannelEntity) {
        infoJob?.cancel()
        infoJob = lifecycleScope.launch {
            nowNext = container.content.upcoming(channel, 3)
            isFavorite = container.db.favorites().isFavorite(channel.key)
        }
    }

    /**
     * Einmal drücken schaltet sofort um. Schnell hintereinander gedrückt wandert nur die Anzeige mit,
     * umgeschaltet wird kurz nach dem letzten Druck: Sonst öffnet jeder Druck eine Verbindung, und
     * Anbieter mit nur einer erlaubten Verbindung sperren kurz (403).
     */
    private fun zap(step: Int) {
        val list = channels
        val from = zapTarget ?: current ?: return
        if (list.size < 2) return
        val index = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        val target = list[(index + step + list.size) % list.size]
        val now = SystemClock.elapsedRealtime()
        val burst = now - lastZapAt < ZAP_BURST_MS
        lastZapAt = now
        if (!burst && zapJob == null) {
            playChannel(target)
            return
        }
        zapTarget = target
        previewChannel(target)
        zapJob?.cancel()
        zapJob = lifecycleScope.launch {
            delay(ZAP_COMMIT_MS)
            zapJob = null
            zapTarget = null
            playChannel(target)
        }
    }

    /** Infoleiste zeigt schon den Zielsender, der Player bleibt noch beim alten. */
    private fun previewChannel(channel: ChannelEntity) {
        current = channel
        showOverlay(Overlay.INFO)
        loadChannelInfo(channel)
    }

    private fun adaptLiveUrl(url: String): String =
        if (settings.liveFormat == LiveFormat.HLS && "/live/" in url && url.endsWith(".ts")) url.removeSuffix(".ts") + ".m3u8" else url

    fun toggleFavorite() {
        val channel = current ?: return
        lifecycleScope.launch { isFavorite = container.content.toggleFavorite(channel.key) }
    }

    fun openChannelList() {
        showOverlay(Overlay.CHANNELS)
        lifecycleScope.launch { nowPlayingMap = container.content.nowPlaying(channels) }
    }

    fun closeOverlay() {
        overlay = Overlay.NONE
    }

    private fun onDigit(digit: Int) {
        numberInput = (numberInput + digit).takeLast(4)
        numberJob?.cancel()
        // Kann keine Sendernummer mit dieser Eingabe weitergehen, sofort umschalten statt 1,5 s zu warten.
        val typed = numberInput
        val canGrow = channels.isEmpty() || channels.any { c ->
            c.number.toString().let { it.length > typed.length && it.startsWith(typed) }
        }
        numberJob = lifecycleScope.launch {
            if (canGrow) delay(1_500)
            val number = numberInput.toIntOrNull()
            numberInput = ""
            val target = number?.let { n -> channels.firstOrNull { it.number == n } ?: container.content.channelByNumber(n) }
            if (target != null) playChannel(target) else message = "Kein Sender mit Nummer $number"
        }
    }

    // ---------------------------------------------------------------- VOD

    private fun startVod(request: VodRequest, resumeMs: Long) {
        val p = player ?: return
        headers?.userAgent = settings.userAgent
        streamGeneration++
        resetStreamState()
        // Schon einmal geladene oder übersetzte Untertitel stehen im Menü sofort bereit.
        subtitleFiles.clear()
        subtitleFiles.putAll(container.subtitles.cachedAll(request.key))
        embedded?.clear()
        loadedLanguages = subtitleFiles.keys.toList()
        p.setMediaItem(vodItem(request), resumeMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        showOverlay(Overlay.CONTROLS)
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                if (pendingSeekMs == C.TIME_UNSET) positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (++tick % 15 == 0) saveProgress()
                delay(1_000)
            }
        }
    }

    /**
     * Nur wenn "Untertitel automatisch an" eingestellt ist: Fehlt eine eingebettete Spur in der
     * bevorzugten Sprache, wird sie geladen (OpenSubtitles, sonst Übersetzung).
     */
    private fun maybeAutoSubtitles() {
        if (vod == null || autoSubtitleTried || !settings.subtitlesAutoOn) return
        autoSubtitleTried = true
        val language = settings.subtitleLanguage
        if (hasTextTrack(language)) return
        if (language in subtitleFiles || settings.onlineSubtitlesEnabled || settings.translationEnabled) loadSubtitles(language)
    }

    /**
     * Untertitel in [language] einschalten, ohne dass etwas eingerichtet sein muss. Reihenfolge:
     * 1. eingebettete Spur in der Sprache, 2. eine vollständige eingebettete Spur übersetzen (auch
     * Bild-Untertitel, per Texterkennung; immer zeitgenau), 3. gespeicherte Datei oder OpenSubtitles
     * (automatisch ausgerichtet), 4. erzwungene eingebettete Untertitel übersetzen, 5. eine
     * Online-Vorlage vorausschauend übersetzen, 6. ehrlich sagen, woran es fehlt.
     */
    fun loadSubtitles(language: String) {
        val request = vod
        languagePicker = false
        closeOverlay()
        stopLiveTranslation()
        if (hasTextTrack(language)) {
            selectTextLanguage(language)
            subtitleStatus = "Untertitel: ${Languages.label(language)}"
            return
        }
        val label = Languages.label(language)
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            // 2. Eingebettete Untertitel einer anderen Sprache übersetzen (auch Bild-Untertitel per
            //    Texterkennung): passen immer exakt zur Datei, keine Verschiebung möglich.
            //    Auf schwachen Geräten (32 Bit, wenig RAM) laufen Texterkennung und Übersetzung neben 4K
            //    nicht zuverlässig: Dort zuerst fertige Untertitel (werden automatisch ausgerichtet).
            val embeddedSource = if (settings.translationEnabled) bestTranslationSource(allowForced = false) else null
            val heavy = embeddedSource != null && embeddedSource.let { (g, i) -> g.getTrackFormat(i).isBitmapSubtitle() } &&
                DeviceInfo.isWeak(this@PlayerActivity)
            if (embeddedSource != null && !heavy) {
                startLiveTranslation(language, embeddedSource)
                return@launch
            }
            // 3. Gespeicherte Datei oder Untertitel in der Zielsprache von OpenSubtitles (werden automatisch ausgerichtet)
            var onlineProblem: String? = null
            if (request != null) {
                if (settings.onlineSubtitlesEnabled) subtitleStatus = "Suche Untertitel ($label) …"
                val found = runCatching {
                    container.subtitles.find(request.key, request.url, request.title, language, settings) { subtitleStatus = it }
                }.onFailure {
                    Log.w(SUBTITLE_TAG, "Suche fehlgeschlagen", it)
                    onlineProblem = describeOnlineProblem(it)
                }.getOrNull()
                if (found != null) {
                    attachLoaded(language, found)
                    return@launch
                }
            }
            if (!settings.translationEnabled) {
                subtitleStatus = onlineProblem ?: "Keine Untertitel in $label gefunden (Übersetzung ist in den Einstellungen aus)"
                return@launch
            }
            // 3a. Schwaches Gerät mit eingebetteten Untertiteln, die direkt (ein Modell, ohne Umweg über
            //     Englisch) übersetzbar sind: die nehmen, sie sind zeitgenau und brauchen am wenigsten Speicher.
            val embeddedLanguage = embeddedSource?.let { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) } ?: "en"
            val direct = de.ayutv.data.translate.OnDeviceTranslator.route(embeddedLanguage, Languages.normalize(language) ?: language)?.size == 1
            if (heavy && embeddedSource != null && direct && !de.ayutv.data.translate.SubtitleOcr.unavailable(filesDir)) {
                onlineProblem?.let { subtitleStatus = it }
                startLiveTranslation(language, embeddedSource)
                return@launch
            }
            // 3b. Schwaches Gerät: vorhandene Untertitel einer anderen Sprache übersetzen (nur der
            //     Übersetzer, keine Texterkennung). Sie werden an der eingebetteten Spur ausgerichtet.
            if (heavy && request != null) {
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings) { subtitleStatus = it }
                }.onFailure { if (onlineProblem == null) onlineProblem = describeOnlineProblem(it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    onlineProblem?.let { subtitleStatus = "$it · Übersetze vorhandene Untertitel" }
                    return@launch
                }
            }
            // 3c. Bild-Untertitel auf dem Gerät lesen und übersetzen (nicht, wenn die Texterkennung
            //     hier schon in allen Varianten abgestürzt ist)
            if (embeddedSource != null && !de.ayutv.data.translate.SubtitleOcr.unavailable(filesDir)) {
                startLiveTranslation(language, embeddedSource)
                return@launch
            }
            // 4. Nur erzwungene eingebettete Untertitel: besser als nichts
            bestTranslationSource()?.let { source ->
                startLiveTranslation(language, source)
                return@launch
            }
            // 5. Vorlage (gespeichert oder online) vorausschauend übersetzen
            if (request != null) {
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings) { subtitleStatus = it }
                }.onFailure { Log.w(SUBTITLE_TAG, "Vorlage fehlgeschlagen", it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    return@launch
                }
            }
            // 6. Nichts da, was sich übersetzen ließe
            onlineProblem?.let {
                subtitleStatus = it
                return@launch
            }
            subtitleStatus = when {
                request == null -> "Dieser Sender hat keine Untertitel, die übersetzt werden könnten"
                !settings.onlineSubtitlesEnabled ->
                    "Dieser Film hat keine Untertitel. Mit OpenSubtitles (Einstellungen → Einrichtung per Handy) gibt es welche für fast jeden Film"
                else -> "Für diesen Film gibt es weder eingebettete noch Online-Untertitel"
            }
        }
    }

    private fun attachLoaded(language: String, result: SubtitleRepository.Result) {
        lastOnlineProblem = null
        subtitleFiles[language] = result.file
        loadedLanguages = subtitleFiles.keys.toList()
        if (!showLoaded(language)) return
        subtitleStatus = when (result.source) {
            SubtitleRepository.Source.HASH -> "Untertitel geladen, passend zur Datei"
            SubtitleRepository.Source.TITLE -> "Untertitel geladen (nach Titel gefunden)"
            SubtitleRepository.Source.TRANSLATED -> "Untertitel übersetzt (${result.translator})"
            SubtitleRepository.Source.CACHE -> "Untertitel: ${Languages.label(language)}"
        }
    }

    /** Eingebettete Untertitel, die Text enthalten (keine Bild-Untertitel), bevorzugt Englisch. */
    /**
     * Eingebettete Spur, die sich am besten übersetzen lässt: vollständige vor erzwungenen (die nur
     * fremdsprachige Stellen zeigen), Englisch zuerst (direkte Modelle, beste Qualität), Text vor
     * Bild-Untertiteln (die erst per Texterkennung gelesen werden), normale vor SDH.
     */
    private fun bestTranslationSource(allowForced: Boolean = true): Pair<Tracks.Group, Int>? {
        val candidates = textTracks().filter { (g, i) ->
            val format = g.getTrackFormat(i)
            (allowForced || !format.isForcedSubtitle()) && (!format.isBitmapSubtitle() || settings.translationEnabled)
        }
        return candidates.sortedWith(
            compareBy<Pair<Tracks.Group, Int>> { (g, i) -> g.getTrackFormat(i).isForcedSubtitle() }
                .thenBy { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) != "en" }
                .thenBy { (g, i) -> g.getTrackFormat(i).isBitmapSubtitle() }
                .thenBy { (g, i) -> g.getTrackFormat(i).isSdhSubtitle() }
        ).firstOrNull()
    }

    private fun textTracks(): List<Pair<Tracks.Group, Int>> = player?.currentTracks?.groups.orEmpty()
        .filter { it.type == C.TRACK_TYPE_TEXT }
        .flatMap { group -> (0 until group.length).filter(group::isTrackSupported).map { group to it } }

    /** Vollständige Spur in [language] (erzwungene Untertitel zählen nicht, sie zeigen nur einzelne Stellen). */
    private fun fullTextTrack(language: String): Pair<Tracks.Group, Int>? = textTracks()
        .filter { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) == Languages.normalize(language) && !g.getTrackFormat(i).isForcedSubtitle() }
        .minByOrNull { (g, i) -> if (g.getTrackFormat(i).isSdhSubtitle()) 1 else 0 }

    private fun hasTextTrack(language: String): Boolean = fullTextTrack(language) != null

    private fun selectTextLanguage(language: String) {
        val p = player ?: return
        val track = fullTextTrack(language)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setPreferredTextLanguage(language)
            .apply { track?.let { (group, index) -> setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index)) } }
            .build()
        textDisabled = false
    }

    fun openLanguagePicker() {
        languagePicker = true
        overlayToken++
    }

    fun closeLanguagePicker() {
        languagePicker = false
        overlayToken++
    }

    /**
     * Übersetzt eine eingebettete Spur live in [target]: Jede Zeile wird beim Erscheinen übersetzt
     * und zwischengespeichert; die Originalzeilen werden dafür ausgeblendet.
     */
    fun startLiveTranslation(target: String, source: Pair<Tracks.Group, Int>? = bestTranslationSource()) {
        languagePicker = false
        closeOverlay()
        if (!settings.translationEnabled) {
            subtitleStatus = "Übersetzung ist in den Einstellungen ausgeschaltet"
            return
        }
        if (source == null) {
            subtitleStatus = "Keine Untertitel zum Übersetzen vorhanden"
            return
        }
        val (group, index) = source
        val format = group.getTrackFormat(index)
        selectTrack(group, index)
        liveSourceLanguage = Languages.normalize(format.language)
        cueTranslations.clear()
        liveTranslateTarget = target
        startAhead(format, target)
        val via = Translators.from(settings)?.name?.let { " mit $it" }.orEmpty()
        val kind = if (format.isBitmapSubtitle()) "Bild-Untertitel " else ""
        subtitleStatus = "Übersetze ${kind}${liveSourceLanguage?.let { Languages.label(it) + " " }.orEmpty()}→ ${Languages.label(target)}$via"
    }

    /**
     * Übersetzt die mitgelesenen Einblendungen der Spur [format], bevor sie zu sehen sind (der Player
     * puffert 30–60 s voraus). Bild-Untertitel werden vorher per Texterkennung gelesen. Angezeigt
     * wird zur exakten Zeit der Spur: synchron, ohne Verzögerung. Liest der Player die Spur nicht
     * mit (z. B. HLS), übernimmt die Übersetzung beim Erscheinen ([onCuesChanged]).
     */
    private fun startAhead(format: Format, target: String) {
        aheadJob?.cancel()
        aheadTranslations.clear()
        aheadFormat = format
        val translator = Translators.from(settings) ?: return
        val source = liveSourceLanguage
        val bitmap = format.isBitmapSubtitle()
        if (bitmap) {
            embedded?.readImagesOf(format)
            // Bilder der schon gepufferten Minute wurden nicht gelesen: ab hier neu laden (kurzes Nachpuffern).
            if (!isLive) player?.let { it.seekTo(it.currentPosition) }
        }
        aheadJob = lifecycleScope.launch {
            launch {
                while (true) {
                    val store = embedded
                    if (store != null && store.hasEntries(format)) {
                        val entry = store.at(format, player?.currentPosition ?: 0L)
                        // Noch nicht übersetzt: Text im Original zeigen, Bild-Untertitel erst mit Übersetzung.
                        translatedCue = entry?.let { aheadTranslations[it.startMs]?.ifEmpty { null } ?: if (bitmap) null else it.text }
                    }
                    delay(100)
                }
            }
            var failures = 0
            while (true) {
                val store = embedded ?: break
                val position = player?.currentPosition ?: 0L
                val next = store.upcoming(format, position - 2_000, 1) { it.startMs !in aheadTranslations && (it.text != null || it.image != null) }
                    .firstOrNull()
                if (next == null) {
                    delay(250)
                    continue
                }
                try {
                    val started = SystemClock.elapsedRealtime()
                    val text = next.text ?: next.image?.let { image -> Translators.remote.recognize(image).also { next.text = it; next.image = null } }.orEmpty()
                    aheadTranslations[next.startMs] = if (text.isBlank()) "" else translator.translate(listOf(text), target, source).first()
                    Log.i(SUBTITLE_TAG, "Vorlauf ${(next.startMs - position) / 1000} s, ${SystemClock.elapsedRealtime() - started} ms: $text → ${aheadTranslations[next.startMs]}")
                    failures = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(SUBTITLE_TAG, "Übersetzung mit Vorlauf fehlgeschlagen", e)
                    aheadTranslations[next.startMs] = next.text.orEmpty()
                    if (++failures >= 3) {
                        stopLiveTranslation()
                        fallbackToLoaded(target, e.message ?: e.javaClass.simpleName)
                        break
                    }
                }
            }
        }
    }

    /** Beendet Live-Übersetzung und eigene Einblendung geladener Untertitel. */
    fun stopLiveTranslation() {
        liveTranslateTarget = null
        externalLanguage = null
        translatedCue = null
        cueJob?.cancel()
        fileTranslationJob?.cancel()
        fileTranslationJob = null
        externalJob?.cancel()
        externalJob = null
        syncJob?.cancel()
        externalCues = emptyList()
        externalTexts = null
        aheadJob?.cancel()
        aheadJob = null
        aheadFormat = null
        aheadTranslations.clear()
        embedded?.readImagesOf(null)
    }

    // ---------------------------------------------------------------- Geladene Untertitel

    /** Gespeicherte Untertitel in [language] einblenden. false, wenn die Datei leer oder kaputt ist. */
    fun showLoaded(language: String): Boolean {
        if (overlay == Overlay.TRACKS) closeOverlay()
        val file = subtitleFiles[language] ?: return false
        val cues = parseCues(file)
        if (cues.isEmpty()) {
            subtitleStatus = "Die gefundenen Untertitel sind leer"
            return false
        }
        stopLiveTranslation()
        beginExternal(language, cues, texts = null)
        return true
    }

    private fun parseCues(file: File): List<TimedCue> =
        runCatching { Srt.parse(file.readText()) }.getOrDefault(emptyList())
            .mapNotNull { cue -> TimedCue(srtMillis(cue.start) ?: return@mapNotNull null, srtMillis(cue.end) ?: return@mapNotNull null, Srt.plainText(cue.text), cue) }
            .sortedBy { it.start }

    /**
     * Geladene Untertitel selbst einblenden statt über den Player: So lassen sie sich zeitlich
     * verschieben. Der Player spielt währenddessen unsichtbar eine eingebettete Spur als Taktgeber.
     */
    private fun beginExternal(language: String, cues: List<TimedCue>, texts: java.util.concurrent.atomic.AtomicReferenceArray<String>?) {
        externalLanguage = language
        externalCues = cues
        externalTexts = texts
        referenceStarts.clear()
        referenceShowing = false
        val saved = vod?.let { container.subtitles.timing(it.key, language) }
        subtitleTiming = saved?.let { SubtitleTiming(it.ratio, it.offsetMs) } ?: SubtitleTiming()
        subtitleDelayMs = saved?.manualMs ?: 0L
        subtitleAutoSynced = saved != null && (saved.offsetMs != 0L || saved.ratio != 1.0)
        textDisabled = false
        selectSyncReference()
        externalJob?.cancel()
        externalJob = lifecycleScope.launch {
            while (true) {
                val at = subtitleTimeAt(player?.currentPosition ?: 0L)
                val shown = externalTexts
                translatedCue = cues.indices.filter { cues[it].start <= at && cues[it].end > at }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { shown?.get(it) ?: cues[it].text }
                delay(100)
            }
        }
    }

    /** Filmposition → Zeit in der geladenen Datei (automatische und manuelle Verschiebung eingerechnet). */
    private fun subtitleTimeAt(position: Long): Long = subtitleTiming.toSubtitle(position - subtitleDelayMs)

    /** Untertitel früher (negativ) oder später (positiv) einblenden; wird pro Film gemerkt. */
    fun shiftSubtitles(deltaMs: Long) {
        subtitleDelayMs += deltaMs
        subtitleStatus = "Untertitel ${formatShift(subtitleDelayMs + subtitleTiming.offsetMs)}"
        saveTiming()
    }

    private fun saveTiming() {
        val request = vod ?: return
        val language = externalLanguage ?: return
        val saved = SubtitleRepository.SavedTiming(subtitleTiming.ratio, subtitleTiming.offsetMs, subtitleDelayMs)
        container.scope.launch(Dispatchers.IO) { container.subtitles.saveTiming(request.key, language, saved) }
    }

    /**
     * Eine eingebettete Spur unsichtbar mitlaufen lassen (Bild-Untertitel genügen): Ihre Einblendungen
     * zeigen, wann im Film gesprochen wird. Vollständige Spuren vor erzwungenen.
     */
    private fun selectSyncReference() {
        val p = player ?: return
        val candidates = textTracks().sortedBy { (g, i) -> if (g.getTrackFormat(i).isForcedSubtitle()) 1 else 0 }
        val (group, index) = candidates.firstOrNull() ?: return
        if (group.isTrackSelected(index)) return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
    }

    private fun recordReference(cueGroup: CueGroup) {
        val showing = cueGroup.cues.isNotEmpty()
        val begins = showing && !referenceShowing
        referenceShowing = showing
        val p = player ?: return
        if (!begins || SystemClock.elapsedRealtime() < ignoreReferenceUntil) return
        val position = p.currentPosition
        val time = (cueGroup.presentationTimeUs / 1000).takeIf { cueGroup.presentationTimeUs != C.TIME_UNSET && abs(it - position) < 3_000 } ?: position
        if (referenceStarts.isNotEmpty() && abs(referenceStarts.last() - time) < 300) return
        referenceStarts += time
        if (referenceStarts.size > 400) referenceStarts.removeAt(0)
        if (referenceStarts.size < SubtitleSync.MIN_REFERENCES || syncJob?.isActive == true) return
        val references = referenceStarts.toList()
        val starts = externalCues.map { it.start }
        syncJob = lifecycleScope.launch {
            val fit = withContext(Dispatchers.Default) { SubtitleSync.estimate(references, starts) }
            if (externalLanguage == null) return@launch
            if (fit == null) {
                // Nach vielen Einblendungen immer noch kein gemeinsamer Takt: Die Datei gehört zu einem
                // anderen Film (oder einer ganz anderen Fassung). Verwerfen und den nächsten Treffer laden.
                if (references.size >= WRONG_FILE_REFERENCES && !subtitleAutoSynced && externalTexts == null) replaceWrongSubtitles()
                return@launch
            }
            val here = subtitleTimeAt(player?.currentPosition ?: 0L)
            if (abs(fit.timing.toVideo(here) - subtitleTiming.toVideo(here)) < 150) return@launch
            subtitleTiming = fit.timing
            subtitleAutoSynced = true
            subtitleStatus = "Untertitel automatisch angepasst (${formatShift(fit.timing.offsetMs + subtitleDelayMs)}" +
                (if (fit.timing.ratio != 1.0) ", Bildrate korrigiert" else "") + ")"
            saveTiming()
        }
    }

    /**
     * Übersetzung auf dem Gerät gescheitert (Speicher, Absturz im Modell): fertige Untertitel in der
     * Sprache nehmen. Sie werden an der eingebetteten Spur automatisch ausgerichtet.
     */
    private fun fallbackToLoaded(language: String, reason: String) {
        val request = vod
        if (request == null) {
            subtitleStatus = "Übersetzung nicht möglich: $reason"
            return
        }
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val found = runCatching { container.subtitles.find(request.key, request.url, request.title, language, settings) }
                .onFailure { Log.w(SUBTITLE_TAG, "Suche fehlgeschlagen", it) }.getOrNull()
            if (found != null) {
                attachLoaded(language, found)
                subtitleStatus = "Übersetzung auf dem Gerät nicht möglich ($reason). Nehme fertige Untertitel, automatisch ausgerichtet"
            } else {
                subtitleStatus = "Übersetzung nicht möglich: $reason"
            }
        }
    }

    private var replacedSubtitles = 0

    private fun replaceWrongSubtitles() {
        val request = vod ?: return
        val language = externalLanguage ?: return
        if (replacedSubtitles >= 3) {
            subtitleStatus = "Keine passenden Untertitel gefunden. Zeit im Menü unter „Untertitel-Zeit“ selbst einstellen"
            return
        }
        replacedSubtitles++
        Log.i(SUBTITLE_TAG, "Untertitel $language passen nicht zum Film, suche andere")
        container.subtitles.markWrong(request.key, language)
        subtitleFiles.remove(language)
        loadedLanguages = subtitleFiles.keys.toList()
        stopLiveTranslation()
        subtitleStatus = "Diese Untertitel gehören zu einem anderen Film. Suche passende …"
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            val found = runCatching { container.subtitles.find(request.key, request.url, request.title, language, settings) }
                .onFailure { Log.w(SUBTITLE_TAG, "Suche fehlgeschlagen", it) }.getOrNull()
            if (found != null) attachLoaded(language, found)
            else subtitleStatus = "Keine passenden Untertitel in ${Languages.label(language)} gefunden"
        }
    }

    /** Letzter Grund, warum Untertitel aus dem Internet nicht geladen werden konnten (steht im Untertitel-Menü). */
    var lastOnlineProblem by mutableStateOf<String?>(null); private set

    /** Fehler von OpenSubtitles verständlich machen, vor allem das Tageslimit. */
    private fun describeOnlineProblem(error: Throwable): String {
        val text = error.message.orEmpty()
        Log.w(SUBTITLE_TAG, "OpenSubtitles: $text")
        return (if ("allowed" in text && ("subtitles" in text || "download" in text)) {
            "OpenSubtitles-Tageslimit erreicht (ohne Konto 5, mit kostenlosem Konto 20 Downloads pro Tag). " +
                "Konto eintragen: Einstellungen → Einrichtung per Handy"
        } else {
            "OpenSubtitles: ${text.ifEmpty { error.javaClass.simpleName }}"
        }).also { lastOnlineProblem = "$it (Originalmeldung: ${text.take(160)})" }
    }

    private fun formatShift(ms: Long): String = String.format(Locale.GERMAN, "%+.1f s", ms / 1000f)


    private fun onCuesChanged(cueGroup: CueGroup) {
        if (externalLanguage != null) {
            recordReference(cueGroup)
            return
        }
        val target = liveTranslateTarget ?: return
        // Mitgelesene Spur: Anzeige und Übersetzung laufen mit Vorlauf (startAhead).
        if (aheadFormat?.let { embedded?.hasEntries(it) } == true) return
        val text = cueGroup.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (text.isEmpty()) {
            translatedCue = null
            return
        }
        cueTranslations[text]?.let {
            translatedCue = it
            return
        }
        // Bis die Übersetzung da ist (auf dem Gerät knapp eine Sekunde), das Original zeigen statt nichts.
        translatedCue = text
        cueJob?.cancel()
        cueJob = lifecycleScope.launch {
            val translator = Translators.from(settings) ?: return@launch
            runCatching { translator.translate(listOf(text), target, liveSourceLanguage).first() }
                .onSuccess {
                    cueTranslations[text] = it
                    translatedCue = it
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    Log.w(SUBTITLE_TAG, "Live-Übersetzung fehlgeschlagen", it)
                    stopLiveTranslation()
                    subtitleStatus = "Übersetzung nicht möglich: ${it.message ?: it.javaClass.simpleName}"
                }
        }
    }

    /**
     * Übersetzt eine Untertiteldatei vorausschauend: immer die Zeilen ab der aktuellen Stelle zuerst,
     * so dass sie fertig sind, bevor sie erscheinen (auch nach dem Spulen). Angezeigt wird nach der
     * Zeit der Datei. Ist alles übersetzt, wird die Datei gespeichert und steht beim nächsten Mal sofort bereit.
     */
    private fun startFileTranslation(request: VodRequest, target: String, file: java.io.File, sourceLanguage: String?) {
        stopLiveTranslation()
        val cues = parseCues(file)
        if (cues.isEmpty()) {
            subtitleStatus = "Die gefundenen Untertitel sind leer"
            return
        }
        val translator = Translators.from(settings) ?: return
        val translations = java.util.concurrent.atomic.AtomicReferenceArray<String>(cues.size)
        // Wie geladene Untertitel einblenden (verschiebbar, automatisch ausgerichtet); fertig
        // übersetzte Zeilen ersetzen nach und nach das Original.
        beginExternal(target, cues, translations)
        subtitleStatus = "Übersetze ${sourceLanguage?.let { Languages.label(it) + " " }.orEmpty()}→ ${Languages.label(target)} mit ${translator.name}"
        fun indexAt(position: Long): Int {
            val at = subtitleTimeAt(position)
            val i = cues.indexOfFirst { it.end > at }
            return if (i < 0) cues.size else i
        }
        fileTranslationJob = lifecycleScope.launch {
            val batch = if (translator.name == de.ayutv.data.translate.OnDeviceTranslator.NAME) 3 else 40
            try {
                while (true) {
                    val from = indexAt(player?.currentPosition ?: 0L)
                    val next = (from until cues.size).firstOrNull { translations.get(it) == null }
                        ?: (0 until from).firstOrNull { translations.get(it) == null }
                        ?: break
                    val indices = (next until minOf(cues.size, next + batch)).filter { translations.get(it) == null }
                    val result = translator.translate(indices.map { cues[it].text }, target, sourceLanguage)
                    indices.forEachIndexed { k, index -> translations.set(index, result.getOrElse(k) { cues[index].text }) }
                }
                val done = cues.mapIndexed { i, cue -> cue.original.copy(text = translations.get(i) ?: cue.text) }
                val saved = withContext(Dispatchers.IO) { container.subtitles.saveTranslation(request.key, target, done) }
                subtitleFiles[target] = saved
                loadedLanguages = subtitleFiles.keys.toList()
                Log.i(SUBTITLE_TAG, "Übersetzung von ${cues.size} Zeilen fertig und gespeichert")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(SUBTITLE_TAG, "Übersetzung der Datei fehlgeschlagen", e)
                subtitleStatus = "Übersetzung nicht möglich: ${e.message ?: e.javaClass.simpleName}"
                stopLiveTranslation()
            }
        }
    }

    private class TimedCue(val start: Long, val end: Long, val text: String, val original: SrtCue)

    private fun srtMillis(value: String): Long? {
        val m = SRT_TIME.matchEntire(value.trim()) ?: return null
        val (h, min, sec, ms) = m.destructured
        return ((h.toLong() * 60 + min.toLong()) * 60 + sec.toLong()) * 1000 + ms.padEnd(3, '0').toLong()
    }

    // ---------------------------------------------------------------- Spuren (Audio/Untertitel)

    fun selectTrack(group: Tracks.Group, index: Int) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .apply {
                // Gewählte Tonsprache auch beim nächsten Sender bevorzugen.
                if (group.type == C.TRACK_TYPE_AUDIO) group.getTrackFormat(index).language?.takeIf { it != "und" }
                    ?.let { setPreferredAudioLanguages(it, Locale.getDefault().language) }
            }
            .build()
        if (group.type == C.TRACK_TYPE_TEXT) {
            textDisabled = false
            stopLiveTranslation()
        }
    }

    fun setTextEnabled(enabled: Boolean) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !enabled)
            .apply { if (!enabled) clearOverridesOfType(C.TRACK_TYPE_TEXT) }
            .build()
        textDisabled = !enabled
        if (!enabled) stopLiveTranslation()
    }

    fun openTracks() = showOverlay(Overlay.TRACKS)

    fun clearSubtitleStatus(shown: String) {
        if (subtitleStatus == shown) subtitleStatus = null
    }

    private fun saveProgress() {
        val request = vod ?: return
        // Archiv-Sendungen gehören nicht zu „Weiter schauen“.
        if (request.kind == HistoryKind.CATCHUP) return
        val p = player ?: return
        val position = p.currentPosition
        if (position < 5_000) return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        container.scope.launch {
            container.content.recordProgress(
                HistoryEntity(
                    key = request.key,
                    kind = request.kind,
                    title = request.title,
                    subtitle = request.subtitle,
                    image = request.image,
                    url = request.url,
                    groupTitle = null,
                    positionMs = position,
                    durationMs = duration,
                    watchedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    /**
     * Spulen sammelt schnelle Tastendrücke und springt erst 300 ms nach dem letzten: Gedrückt halten
     * würde sonst 20 Sprünge pro Sekunde und je eine neue Server-Anfrage auslösen. Nie bis ganz ans
     * Ende, sonst wäre der Film sofort aus.
     */
    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val max = p.duration.takeIf { it != C.TIME_UNSET }?.let { (it - 2_000).coerceAtLeast(0) } ?: Long.MAX_VALUE
        val base = if (pendingSeekMs != C.TIME_UNSET) pendingSeekMs else p.currentPosition
        pendingSeekMs = (base + deltaMs).coerceIn(0, max)
        positionMs = pendingSeekMs
        showOverlay(Overlay.CONTROLS)
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            delay(300)
            player?.seekTo(pendingSeekMs)
            pendingSeekMs = C.TIME_UNSET
        }
    }

    // ---------------------------------------------------------------- Gemeinsam

    private fun mediaItem(url: String, mime: String?): MediaItem =
        MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()

    private fun vodItem(request: VodRequest): MediaItem = mediaItem(request.url, guessMime(request.url))

    private fun guessMime(url: String): String? {
        val path = url.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    fun showOverlay(target: Overlay) {
        overlay = target
        overlayToken++
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                retries = 0
                message = null
                everReady = true
                fatalError = false
                if (!isLive) maybeAutoSubtitles()
            }
            if (state == Player.STATE_ENDED && !isLive) finish()
            // Live-Streams enden nie wirklich: Schließt der Server die Verbindung „sauber“, hält der
            // Player das für das Ende und bliebe stehen. Sofort und ohne Meldung neu verbinden.
            if (state == Player.STATE_ENDED && isLive) onLiveStreamEnded()
            if (state == Player.STATE_BUFFERING && everReady) onRebuffer()
        }

        override fun onRenderedFirstFrame() {
            if (zapStartedAt > 0) {
                stats = stats.copy(startupMs = SystemClock.elapsedRealtime() - zapStartedAt)
                zapStartedAt = 0
            }
        }

        override fun onCues(cueGroup: CueGroup) = onCuesChanged(cueGroup)

        override fun onTracksChanged(tracks: Tracks) {
            this@PlayerActivity.tracks = tracks
            checkAudioSupport(tracks)
            updateFormats()
            if (externalLanguage != null) selectSyncReference()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // Nach einem Sprung zeigt der Taktgeber eine schon laufende Zeile: deren Beginn ist unbekannt.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                ignoreReferenceUntil = SystemClock.elapsedRealtime() + 1_500
                referenceShowing = false
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateFormats()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            Log.w(TAG, "Wiedergabefehler ${error.errorCodeName}", error)
            when {
                // Fehler während des HDMI-Moduswechsels: einfach weiterspielen, nichts umbauen.
                displaySwitching() && displaySwitchErrors < 3 -> {
                    displaySwitchErrors++
                    handler.postDelayed({
                        player?.let {
                            if (isLive) it.seekToDefaultPosition()
                            it.prepare()
                            if (!frameRateMatcher.switching) it.playWhenReady = true
                        }
                    }, 1_000)
                }
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                // Viele Playlists liefern HLS ohne .m3u8-Endung. Dann einmal explizit als HLS versuchen,
                // aber nur vor dem ersten Bild: Später ist ein Lesefehler kaputte Daten, kein falsches Format.
                !triedHls && !everReady && error.isContainerError() -> {
                    triedHls = true
                    val uri = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                    p.setMediaItem(mediaItem(uri, MimeTypes.APPLICATION_M3U8))
                    p.prepare()
                }
                // Auch als HLS nichts: zurück zur ursprünglichen Adresse und normal weiter versuchen.
                triedHls && !everReady && error.isContainerError() &&
                    p.currentMediaItem?.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8 &&
                    originalUrl()?.let { guessMime(it) != MimeTypes.APPLICATION_M3U8 } == true -> {
                    val request = vod
                    if (request != null) {
                        p.setMediaItem(vodItem(request), p.currentPosition)
                    } else {
                        val url = originalUrl() ?: return
                        p.setMediaItem(mediaItem(url, guessMime(url)))
                    }
                    retries++
                    retryLater()
                }
                // Tunneling ist auf manchen Geräten/Streams instabil: einmalig ohne Tunneling neu starten.
                // Steht vor dem Ton-Zweig, sonst würde ein Tunneling-Problem als Soundbar-Problem behandelt.
                trackSelector?.parameters?.tunnelingEnabled == true && error.isDecoderOrAudioError() -> {
                    trackSelector?.let { it.setParameters(it.buildUponParameters().setTunnelingEnabled(false)) }
                    message = "Tunneling für diesen Stream deaktiviert, starte neu …"
                    restartStream()
                }
                // Soundbar/Receiver kündigt ein Format an, spielt es aber nicht: Ton im Gerät dekodieren.
                // Nur wenn wirklich durchgereicht wurde und es wiederholt (oder gleich zu Beginn) scheitert;
                // ein einzelner Fehler kommt oft vom Aufwachen des Receivers oder einem HDMI-Wechsel.
                !forcePcmActive && error.isAudioOutputError() -> {
                    val sinkFormat = (error.cause as? AudioSink.InitializationException)?.format
                        ?: (error.cause as? AudioSink.WriteException)?.format
                    val passthrough = sinkFormat?.sampleMimeType?.let { it != MimeTypes.AUDIO_RAW } == true
                    val now = SystemClock.elapsedRealtime()
                    val repeated = now - lastAudioErrorAt < 60_000
                    lastAudioErrorAt = now
                    if (passthrough && (repeated || !everReady)) {
                        message = "Ton wird jetzt im Gerät dekodiert, starte neu …"
                        forcePcmActive = true
                        handler.post { rebuildPlayer(forcePcm = true) }
                    } else if (retries < MAX_RETRIES) {
                        retries++
                        message = "Tonausgabe wird neu verbunden …"
                        handler.postDelayed({ restartStream() }, 1_500)
                    } else {
                        fatalError = true
                        message = "Tonausgabe gestört: Soundbar/Receiver prüfen oder Tonausgabe auf „Immer im Gerät dekodieren“ stellen"
                    }
                }
                // Asynchrones Dekodieren mag nicht jeder Decoder: ohne neu aufbauen und für die
                // Zukunft merken, damit der Neustart nicht bei jedem Sender wieder passiert.
                asyncDecodingActive && error.isVideoDecoderError() -> {
                    message = "Verbinde neu …"
                    asyncDecodingActive = false
                    container.scope.launch { container.settings.update { it.copy(asyncDecoding = false) } }
                    // Nicht im eigenen Callback freigeben: erst im nächsten Durchlauf neu aufbauen.
                    handler.post { rebuildPlayer(asyncDecoding = false) }
                }
                // Format kann das Gerät nicht: Wiederholen ist sinnlos, sofort klar sagen, woran es liegt.
                !everReady && error.isUnsupportedFormat() -> {
                    fatalError = true
                    message = unsupportedFormatMessage()
                }
                // Decoder-Start gescheitert: oft nur kurz belegt (4K-Decoder beim Zappen). Einmal neu versuchen.
                error.isFormatError() && !everReady && retries >= 1 -> {
                    fatalError = true
                    message = unsupportedFormatMessage()
                }
                retries < MAX_RETRIES -> {
                    retries++
                    message = "Verbindung wird wiederhergestellt … ($retries/$MAX_RETRIES)"
                    retryLater()
                }
                else -> {
                    fatalError = true
                    message = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Der Anbieter lehnt den Stream ab (z. B. zu viele Verbindungen oder Konto abgelaufen)"
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Server"
                        else -> "Stream nicht verfügbar (${error.errorCodeName})"
                    }
                }
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            stats = stats.copy(decoder = decoderName)
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            stats = stats.copy(audioDecoder = decoderName)
        }

        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            stats = stats.copy(droppedFrames = stats.droppedFrames + droppedFrames)
        }

        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            stats = stats.copy(bandwidth = bitrateEstimate)
        }
    }

    private fun updateFormats() {
        val p = player ?: return
        val video = p.videoFormat
        val audio = p.audioFormat
        stats = stats.copy(
            width = video?.width ?: 0,
            height = video?.height ?: 0,
            // Gemessene Bildrate behalten, wenn der Stream selbst keine angibt.
            frameRate = video?.frameRate?.takeIf { it > 0f } ?: stats.frameRate.takeIf { stats.frameRateMeasured } ?: 0f,
            videoCodec = video?.sampleMimeType?.substringAfter('/') ?: "",
            videoBitrate = video?.bitrate ?: 0,
            audioCodec = audio?.sampleMimeType?.substringAfter('/') ?: "",
            audioChannels = audio?.channelCount ?: 0,
            displayHz = frameRateMatcher.currentRefreshRate(),
            hdr = when {
                video == null -> ""
                video.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
                video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
                video.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> ""
            },
        )
        if (settings.matchFrameRate) video?.frameRate?.let(frameRateMatcher::match)
        suggestFrameRateMatching()
    }

    /**
     * 50-Bilder-Sender auf einem 60-Hz-Bild: Jedes fünfte Bild bleibt doppelt so lange stehen, das
     * Bild läuft sichtbar ungleichmäßig. Einmal pro App-Start darauf hinweisen, wie man es abstellt.
     */
    private fun suggestFrameRateMatching() {
        if (afrHintShown || !isLive || settings.matchFrameRate) return
        val fps = stats.frameRate
        val hz = stats.displayHz
        val pal = fps in 24.5f..25.5f || fps in 49.5f..50.5f
        if (!pal || hz !in 59f..61f) return
        afrHintShown = true
        subtitleStatus = "Bild nicht ganz flüssig? Sender hat 50 Bilder/s, der Fernseher läuft mit 60 Hz. " +
            "Einstellungen → Wiedergabe → „Bildwiederholrate anpassen“ einschalten"
    }

    // ---------------------------------------------------------------- Tasten

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    languagePicker -> closeLanguagePicker()
                    overlay != Overlay.NONE -> closeOverlay()
                    else -> finish()
                }
            }
            return true
        }
        // Noch nicht bereit (Einstellungen/Player werden geladen).
        if (player == null) return super.dispatchKeyEvent(event)
        // In Senderliste und Spurmenü übernimmt Compose die Navigation.
        if (overlay == Overlay.CHANNELS || overlay == Overlay.TRACKS) {
            if (event.action == KeyEvent.ACTION_DOWN) overlayToken++
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return isHandledKey(code) || super.dispatchKeyEvent(event)
        return if (isLive) handleLiveKey(code) else handleVodKey(code, event.repeatCount)
    }

    private fun isHandledKey(code: Int) = code in HANDLED_KEYS || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

    private fun handleLiveKey(code: Int): Boolean {
        val p = player
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> if (settings.zapWithUpDown) zap(+1) else openChannelList()
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(+1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (settings.zapWithUpDown) zap(-1) else openChannelList()
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT -> openChannelList()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (overlay == Overlay.INFO) showTech = !showTech
                showOverlay(Overlay.INFO)
            }
            // "Zurück zum letzten Sender", wie beim Fernseher.
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_PROG_BLUE -> previousChannel?.let(::playChannel)
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_PROG_RED -> {
                toggleFavorite()
                showOverlay(Overlay.INFO)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                p?.let { it.playWhenReady = !it.playWhenReady }
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> onDigit(code - KeyEvent.KEYCODE_0)
            else -> return false
        }
        return true
    }

    private fun handleVodKey(code: Int, repeat: Int): Boolean {
        val p = player ?: return false
        // Gedrückt halten spult immer schneller.
        val step = when {
            repeat > 20 -> 120_000L
            repeat > 5 -> 60_000L
            else -> 0L
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (overlay == Overlay.CONTROLS || !p.playWhenReady) p.playWhenReady = !p.playWhenReady
                showOverlay(Overlay.CONTROLS)
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_CAPTIONS -> openTracks()
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-(if (step > 0) step else 10_000L))
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(if (step > 0) step else 30_000L)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                if (code == KeyEvent.KEYCODE_INFO || overlay == Overlay.CONTROLS) showTech = !showTech
                showOverlay(Overlay.CONTROLS)
            }
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- Lebenszyklus

    override fun onStart() {
        super.onStart()
        player?.let { p ->
            if (p.playbackState == Player.STATE_IDLE) {
                streamGeneration++
                restartStream()
            } else {
                p.playWhenReady = true
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Noch nicht ausgeführtes Schnell-Zappen verwerfen: im Hintergrund keinen neuen Stream öffnen.
        if (zapJob != null) {
            zapJob?.cancel()
            zapJob = null
            zapTarget = null
            current = playing
            playing?.let(::loadChannelInfo)
        }
        saveProgress()
        // Live-Streams im Hintergrund nicht weiterladen: spart Bandbreite und eine Verbindung beim Anbieter.
        player?.let { p -> if (isLive) p.stop() else p.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        frameRateMatcher.reset()
        player?.release()
        player = null
        // Keinen Verweis auf diese Activity im App-weiten Übersetzer behalten und den Übersetzer-Prozess
        // beenden: Seine Modelle (gut 200 MB) brauchen außerhalb des Players keinen Speicher.
        Translators.remote.onProgress = {}
        Translators.remote.release()
    }

    companion object {
        /** Hinweis auf 50 Hz nur einmal pro App-Start, nicht bei jedem Sender. */
        private var afrHintShown = false
        private const val MAX_RETRIES = 3
        private const val ZAP_BURST_MS = 600L
        private const val ZAP_COMMIT_MS = 400L
        private const val TAG = "AyuPlayer"
        private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
        private const val DISPLAY_SWITCH_GRACE_MS = 6_000L
        const val SUBTITLE_TAG = "AyuSubtitles"
        private val STANDARD_FRAME_RATES = listOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f)
        private const val LIVE_STALL_SECONDS = 8
        private const val FIRST_CONNECT_STALL_SECONDS = 15
        /** So viele eingebettete Einblendungen (gut 4 Minuten Dialog) ohne gemeinsamen Takt: falsche Datei. */
        private const val WRONG_FILE_REFERENCES = 60
        private const val VIDEO_FREEZE_SECONDS = 6
        private const val CUSHION_LOW_MS = 2_500L
        private const val CUSHION_HIGH_MS = 5_000L
        // Bei 50 Hz bringt 0,985 nur alle ~1,3 s ein doppeltes Bild (0,97 ruckelte bei Schwenks sichtbar).
        private const val CUSHION_SPEED = 0.985f
        private const val VOD_STALL_SECONDS = 20
        private const val EXTRA_CHANNEL_KEY = "channel_key"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_FAVORITES = "favorites"
        private const val EXTRA_VOD_KEY = "vod_key"
        private const val EXTRA_VOD_KIND = "vod_kind"
        private const val EXTRA_VOD_TITLE = "vod_title"
        private const val EXTRA_VOD_SUBTITLE = "vod_subtitle"
        private const val EXTRA_VOD_IMAGE = "vod_image"
        private const val EXTRA_VOD_URL = "vod_url"

        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_PROG_BLUE, KeyEvent.KEYCODE_LAST_CHANNEL,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_CAPTIONS,
        )

        fun liveIntent(context: Context, channelKey: String, group: String?, favoritesOnly: Boolean = false): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_CHANNEL_KEY, channelKey)
                .putExtra(EXTRA_GROUP, group)
                .putExtra(EXTRA_FAVORITES, favoritesOnly)

        fun vodIntent(context: Context, request: VodRequest): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_VOD_KEY, request.key)
                .putExtra(EXTRA_VOD_KIND, request.kind.name)
                .putExtra(EXTRA_VOD_TITLE, request.title)
                .putExtra(EXTRA_VOD_SUBTITLE, request.subtitle)
                .putExtra(EXTRA_VOD_IMAGE, request.image)
                .putExtra(EXTRA_VOD_URL, request.url)

        fun startLive(context: Context, channelKey: String, group: String?, favoritesOnly: Boolean = false) =
            context.startActivity(liveIntent(context, channelKey, group, favoritesOnly))

        fun startVod(context: Context, request: VodRequest) = context.startActivity(vodIntent(context, request))

        /** Vergangene Sendung aus dem Archiv des Anbieters: läuft wie ein Film (spulbar). */
        fun startCatchup(context: Context, channel: ChannelEntity, programme: ProgrammeEntity, url: String) = startVod(
            context,
            VodRequest(
                key = "catchup:${channel.key}:${programme.start}",
                kind = HistoryKind.CATCHUP,
                title = programme.title.ifEmpty { channel.name },
                subtitle = "${channel.name} · Archiv · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(programme.start))}",
                image = channel.logo,
                url = url,
            ),
        )

        private fun Intent.getVodRequest(): VodRequest? {
            val key = getStringExtra(EXTRA_VOD_KEY) ?: return null
            val url = getStringExtra(EXTRA_VOD_URL) ?: return null
            return VodRequest(
                key = key,
                kind = runCatching { HistoryKind.valueOf(getStringExtra(EXTRA_VOD_KIND).orEmpty()) }.getOrDefault(HistoryKind.VOD),
                title = getStringExtra(EXTRA_VOD_TITLE).orEmpty(),
                subtitle = getStringExtra(EXTRA_VOD_SUBTITLE),
                image = getStringExtra(EXTRA_VOD_IMAGE),
                url = url,
            )
        }
    }
}

data class VodRequest(
    val key: String,
    val kind: HistoryKind,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
)

data class StreamStats(
    /** Aktuelle Bildwiederholrate des Fernsehers. */
    val displayHz: Float = 0f,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = 0f,
    val videoCodec: String = "",
    val videoBitrate: Int = 0,
    val audioCodec: String = "",
    val audioChannels: Int = 0,
    val decoder: String = "",
    val droppedFrames: Int = 0,
    val bandwidth: Long = 0,
    val startupMs: Long = 0,
    val hdr: String = "",
    val frameRateMeasured: Boolean = false,
    val audioDecoder: String = "",
) {
    val resolutionLabel: String
        get() = when {
            height >= 2000 -> "4K"
            height >= 1000 -> "FHD"
            height >= 700 -> "HD"
            height > 0 -> "SD"
            else -> ""
        }
}

private fun PlaybackException.isFormatError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

private fun androidx.media3.common.Format.isForcedSubtitle(): Boolean =
    selectionFlags and C.SELECTION_FLAG_FORCED != 0 || label?.contains("forced", ignoreCase = true) == true ||
        label?.contains("erzwungen", ignoreCase = true) == true

private fun androidx.media3.common.Format.isSdhSubtitle(): Boolean =
    roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0 || label?.contains("SDH", ignoreCase = true) == true

/** Bild-Untertitel (PGS, DVB, VobSub) enthalten keinen Text und lassen sich nicht übersetzen. */
private fun androidx.media3.common.Format.isBitmapSubtitle(): Boolean {
    val bitmapTypes = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)
    // Seit Media3 1.4 werden Untertitel beim Einlesen umgewandelt; der ursprüngliche Typ steht dann in codecs.
    return sampleMimeType in bitmapTypes || codecs in bitmapTypes
}

private fun PlaybackException.isUnsupportedFormat(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
)

private fun PlaybackException.isContainerError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
)

private fun PlaybackException.isAudioOutputError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
)

private fun PlaybackException.isVideoDecoderError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
)

private fun PlaybackException.isDecoderOrAudioError(): Boolean = errorCode in setOf(
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
    PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
)

