@file:OptIn(UnstableApi::class)

package de.ayutv.player

import de.ayutv.data.parse.headline
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Build
import android.content.pm.PackageManager
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
import de.ayutv.data.AspectMode
import de.ayutv.data.AudioMode
import de.ayutv.data.PlayerMemory
import de.ayutv.data.SubtitleBackground
import de.ayutv.data.SubtitleColor
import de.ayutv.data.SubtitlePosition
import de.ayutv.data.AudioOutput
import de.ayutv.data.BufferProfile
import de.ayutv.data.DeviceInfo
import de.ayutv.data.Languages
import de.ayutv.data.record.Recording
import de.ayutv.data.LiveFormat
import de.ayutv.data.parse.Episodes
import de.ayutv.data.IntroStore
import de.ayutv.hideSystemBars
import de.ayutv.ui.components.FitToScreen
import de.ayutv.ui.components.hasTouchscreen
import de.ayutv.data.parse.IntroSkip
import de.ayutv.data.repo.ContentRepository
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
    /** Abbrüche der Verbindung zum Anbieter beim laufenden Sender, für die Technik-Anzeige. */
    var dropInfo by mutableStateOf(""); private set
    private var seenDrops = 0
    private var dropWarnedAt = 0L
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
    /** Live-Übersetzung von Bild-Untertiteln: Die aktuelle Zeile ist noch nicht übersetzt, das Original bleibt sichtbar. */
    var originalVisible by mutableStateOf(false); private set
    private var externalCues: List<TimedCue> = emptyList()
    private var externalTexts: java.util.concurrent.atomic.AtomicReferenceArray<String>? = null
    private var externalJob: Job? = null
    /** Startzeiten eingebetteter Untertitel (Filmzeit): Taktgeber für die automatische Ausrichtung. */
    private val referenceStarts = ArrayList<Long>()
    private var referenceShowing = false
    private var ignoreReferenceUntil = 0L
    private var syncJob: Job? = null
    /** Mitgehörter Ton (nur Filme): zeigt, wann gesprochen wird, wenn der Film keine eigenen Untertitel hat. */
    private val heardSpeech = SpeechActivity()
    /** Nachtmodus / Dialoge verstärken, im Ton des Players. */
    private val enhance = AudioEnhance()
    var audioMode by mutableStateOf(AudioMode.NORMAL); private set
    /** Untertitel-Aussehen aus den Einstellungen. */
    var subtitleLook by mutableStateOf(SubtitleLook()); private set
    /** Gemerkte Spuren dieses Senders/dieser Serie schon angewendet. */
    private var memoryApplied = false
    /** Schlaf-Timer: Ende (Uhrzeit, ms) oder 0 = aus. */
    var sleepAt by mutableLongStateOf(0L); private set
    private var sleepJob: Job? = null
    private var heardSpeechKey: String? = null
    /** Wie viele Sekunden Ton schon mitgehört wurden (für den Gerätetest). */
    val speechHeardSeconds: Int get() = heardSpeech.seconds()
    private var speechJob: Job? = null
    /** Woran die geladenen Untertitel zuletzt ausgerichtet wurden: am gesprochenen Ton (sonst eingebettete Untertitel). */
    var syncedBySpeech by mutableStateOf(false); private set
    private var syncedByReference = false
    private var pcmForSpeechTried = false
    /** Länge und Bildrate des laufenden Films: helfen bei der Wahl der Untertitel-Datei. */
    private fun filmInfo() = SubtitleRepository.FilmInfo(
        durationMs = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
        frameRate = stats.frameRate.takeIf { it > 1f },
    )
    /** Die geladenen Untertitel passen nachweislich zur Datei (OpenSubtitles-Hash): nichts umstellen. */
    private var externalExact = false
    /** Was die Ausrichtung am Ton gerade tut, in Worten (steht im Untertitel-Menü). */
    var speechSyncInfo by mutableStateOf<String?>(null); private set
    /** Zwischenspeicher für den Zeitversatz bei Live-TV (bleibt beim Umbau des Players erhalten). */
    private val timeshift = TimeshiftHolder()
    private val recorder by lazy { TimeshiftRecorder(container.http, lifecycleScope) }
    /** Live: Das Bild kommt aus dem Zwischenspeicher (angehalten oder zeitversetzt). */
    var timeshifted by mutableStateOf(false); private set
    /** So weit liegt das Bild hinter dem Live-Bild (ms). */
    var behindLiveMs by mutableLongStateOf(0L); private set
    /** So weit lässt sich gerade zurückspulen (ms). */
    var rewindableMs by mutableLongStateOf(0L); private set
    /** Sendezeit (im Zwischenspeicher) am Anfang des laufenden zeitversetzten Abschnitts. */
    private var timeshiftStartMs = 0L
    private var timeshiftErrors = 0
    /** Serien: die nächste Folge (im Hintergrund nachgeschlagen), sonst null. */
    var nextEpisode by mutableStateOf<ContentRepository.NextEpisode?>(null); private set
    /** Hinweis „Nächste Folge“ (letzte Sekunden und nach dem Ende). */
    var nextCardVisible by mutableStateOf(false); private set
    /** Sekunden bis zum automatischen Start der nächsten Folge, -1 = kein Countdown. */
    var nextCountdown by mutableIntStateOf(-1); private set
    private var countdownJob: Job? = null
    /** Knopf „Intro überspringen“ (Serien). */
    var introVisible by mutableStateOf(false); private set
    private var introLearned: IntroSkip.Intro? = null
    /** Wie oft schon übersprungen (Rückblick, dann Intro: der Knopf kommt danach wieder). */
    private var introSkips = 0
    private var introHiddenUntil = 0L
    /** Am Ton erkannter Intro-Klang dieser Serie, und wohin das gerade erkannte Intro reicht (ms, -1 = noch nicht). */
    private var introFingerprint: IntroStore.Fingerprint? = null
    private var introAudioEnd = -1L
    private var introSearchJob: Job? = null
    private var openingLearned = false
    /** Letztes Überspringen: von wo, wohin (inkl. Korrekturen) und wann, zum Lernen der Intro-Länge. */
    private var introFrom = -1L
    private var introTo = 0L
    private var introAt = 0L
    private var liveSourceLanguage: String? = null
    /** Übersetzung eingebetteter Untertitel mit Vorlauf: Quellspur, Ergebnisse je Einblendung (Startzeit). */
    private var aheadFormat: Format? = null
    private val aheadTranslations = HashMap<Long, String>()
    private var aheadJob: Job? = null
    var translatedCue by mutableStateOf<String?>(null); private set
    var languagePicker by mutableStateOf(false); private set
    /** Auswahl „Programm zuordnen“ (TV-Guide von Hand) für den laufenden Sender. */
    var epgPicker by mutableStateOf(false); private set
    var epgChoices by mutableStateOf<List<de.ayutv.data.repo.PlaylistRepository.GuideChannel>>(emptyList()); private set
    /** Von Hand gewählte Zuordnung des laufenden Senders, oder null. */
    var epgOverride by mutableStateOf<String?>(null); private set
    val preferredSubtitleLanguage: String get() = if (::settings.isInitialized) settings.subtitleLanguage else "en"
    val translationAvailable: Boolean get() = ::settings.isInitialized && settings.translationEnabled

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frameRateMatcher = FrameRateMatcher(this)
        frameRateMatcher.onSwitch = ::onDisplaySwitch
        vod = intent.getVodRequest()
        isLive = vod == null

        if (hasTouchscreen()) hideSystemBars()
        setContent { AyuTheme { FitToScreen { PlayerScreen(this) } } }

        lifecycleScope.launch {
            settings = container.settings.current()
            // Einmaliges Laden eines Sprachmodells für die Übersetzung auf dem Gerät anzeigen.
            Translators.remote.onProgress = { text -> runOnUiThread { subtitleStatus = text } }
            subtitleScale = settings.subtitleSize.scale
            subtitleLook = SubtitleLook(settings.subtitleColor, settings.subtitleBackground, settings.subtitlePosition)
            audioMode = settings.audioMode
            enhance.mode = settings.audioMode
            textDisabled = vod != null && !settings.subtitlesAutoOn
            installPlayer(settings.asyncDecoding, settings.audioOutput == AudioOutput.PCM)
            startWatchdog()

            val request = vod
            if (request != null) {
                vodTitle = request.title
                val resume = if (intent.getBooleanExtra(EXTRA_FROM_START, false)) 0L else container.content.resumePosition(request.key)
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
                val list = container.content.zapList(group, favoritesOnly, merge = settings.mergeVariants)
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
        val built = PlayerFactory.create(
            this, container.http, settings, live = vod == null, asyncDecoding = asyncDecoding, forcePcm = forcePcm,
            speech = heardSpeech.takeIf { vod != null },
            timeshift = timeshift.takeIf { vod == null },
            enhance = enhance,
        )
        headers = built.headers
        trackSelector = built.trackSelector
        // Datensparmodus bei mobilen Daten: adaptive Streams (HLS) höchstens in 720p.
        if (settings.dataSaver && onMeteredNetwork()) built.trackSelector.let { ts ->
            ts.setParameters(ts.buildUponParameters().setMaxVideoSize(1280, 720).setMaxVideoBitrate(3_500_000))
        }
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
            if (isLive) {
                updateTimeshiftState(p)
                checkDrops()
            }
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
            if (isLive && !timeshifted) keepLiveCushion(p)
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
     * Die Datenquelle verbindet nach einem Abbruch selbst neu (der Puffer überbrückt das). Passiert das
     * oft, liegt es meist am WLAN oder an zu vielen Verbindungen beim Anbieter: einmal deutlich sagen.
     */
    private fun checkDrops() {
        val drops = timeshift.drops
        if (drops.count == seenDrops) return
        seenDrops = drops.count
        val recent = drops.recent(DROP_WINDOW_MS)
        val last = recent.lastOrNull() ?: return
        dropInfo = "Trennungen ${recent.size}× in 10 Min. (zuletzt: ${last.reason})"
        Log.i(TAG, "Verbindung zum Anbieter abgerissen: ${last.reason} (${recent.size}× in 10 Min.)")
        val now = SystemClock.elapsedRealtime()
        if (recent.size >= DROP_WARN_COUNT && message == null && (dropWarnedAt == 0L || now - dropWarnedAt > DROP_WINDOW_MS)) {
            dropWarnedAt = now
            val text = "Der Anbieter trennt oft die Verbindung (${recent.size}× in 10 Min.). DnA-TV verbindet jedes Mal neu. " +
                "Hilft meist: LAN-Kabel statt WLAN, oder nicht auf zwei Geräten gleichzeitig schauen."
            message = text
            handler.postDelayed({ if (message == text) message = null }, 8_000)
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
            if (p != null && (resumeAfterSwitch || isLive && !timeshifted) && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
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
        memoryApplied = false
        aspect = memoryKey()?.let { container.playerMemory.get(it).aspect } ?: AspectMode.FIT
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
        if (timeshifted) {
            // Zeitversetzt: An derselben Stelle neu ansetzen; am Live-Rand ohne neue Daten zurück zu Live.
            if (behindLiveMs < LIVE_EDGE_MS) goLive() else enterTimeshift(currentTimeshiftMs(p), play = true)
            return
        }
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

    /** Fassungen dieses Senders, die schon ausgefallen sind (beim Wechsel auf die nächste Fassung). */
    private val failedVariants = HashSet<String>()
    private var switchingVariant = false

    /**
     * Sender fällt aus: auf die nächste Fassung desselben Senders wechseln (HD → 4K → SD → Backup), statt
     * nur einen Fehler zu zeigen. Liefert false, wenn es keine gibt (dann zeigt der Aufrufer [reason]).
     */
    private fun tryNextVariant(reason: String): Boolean {
        if (vod != null || !settings.mergeVariants) return false
        val channel = playing ?: return false
        failedVariants += channel.key
        val generation = streamGeneration
        lifecycleScope.launch {
            val next = container.content.variantsOf(channel).firstOrNull { it.key !in failedVariants }
            if (generation != streamGeneration || playing?.key != channel.key) return@launch
            if (next == null) {
                fatalError = true
                message = reason
                return@launch
            }
            Log.i(TAG, "${channel.name} gestört ($reason), wechsle auf ${next.name}")
            switchingVariant = true
            try { playChannel(next) } finally { switchingVariant = false }
            message = "${channel.name} gestört – läuft jetzt über ${next.name}"
        }
        return true
    }

    fun playChannel(channel: ChannelEntity) {
        val p = player ?: return
        if (!switchingVariant) failedVariants.clear()
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
        leaveTimeshift()
        timeshift.buffer?.release()
        timeshift.buffer = newTimeshiftBuffer(url)
        timeshift.liveUrl = url
        seenDrops = 0
        dropInfo = ""
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
        checkConnectionLimit(channel)
        container.scope.launch { container.content.recordLive(channel) }
        checkMobileData()
        preferSmallVariant(channel)
        // Läuft gerade Chromecast: den neuen Sender dort weiterspielen.
        castingTo?.let { startCasting(it) }
    }

    // ---------------------------------------------------------------- Zeitversatz (Live anhalten)

    /** Nur direkte TS-Streams lassen sich mitschreiben; HLS hält einfach an. */
    private fun newTimeshiftBuffer(url: String): TimeshiftBuffer? {
        if (!settings.liveTimeshift || guessMime(url) == MimeTypes.APPLICATION_M3U8) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val dir = File(cacheDir, "timeshift")
        dir.mkdirs()
        // Höchstens gut die Hälfte des freien Speichers; ältere Teile werden laufend gelöscht.
        val usable = dir.usableSpace
        val maxBytes = minOf(TIMESHIFT_MAX_BYTES, (usable - TIMESHIFT_MIN_BYTES) * 6 / 10)
        if (maxBytes < TIMESHIFT_MIN_BYTES) return null
        return TimeshiftBuffer(dir, maxBytes, settings.timeshiftMinutes * 60_000L, url)
    }

    /** Sendezeit des Bildes, das gerade live gezeigt wird (das Neueste im Speicher minus Puffer des Players). */
    private fun liveNowMs(p: ExoPlayer, buffer: TimeshiftBuffer): Long =
        (buffer.latestMs - p.totalBufferedDuration).coerceAtLeast(buffer.oldestMs)

    private fun currentTimeshiftMs(p: ExoPlayer): Long = timeshiftStartMs + p.currentPosition.coerceAtLeast(0)

    /** Live: Pause hält das Bild an, der Sender wird weiter zwischengespeichert. Erneut drücken spielt zeitversetzt weiter. */
    private fun toggleLivePause(code: Int) {
        val p = player ?: return
        if (timeshifted) {
            p.playWhenReady = when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> true
                KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                else -> !p.playWhenReady
            }
        } else if (code != KeyEvent.KEYCODE_MEDIA_PLAY) {
            val buffer = timeshift.buffer
            if (buffer != null && buffer.hasData && p.playbackState == Player.STATE_READY) {
                enterTimeshift(liveNowMs(p, buffer), play = false)
            } else {
                // Kein Zwischenspeicher (HLS, zu wenig Speicher, abgeschaltet): einfach anhalten.
                p.playWhenReady = !p.playWhenReady
            }
        } else {
            p.playWhenReady = true
        }
        showOverlay(Overlay.INFO)
    }

    /** Zurück- oder vorspulen; über das Live-Bild hinaus geht es wieder live. */
    private fun shiftLive(deltaMs: Long) {
        val p = player ?: return
        val buffer = timeshift.buffer
        if (buffer == null || !buffer.hasData) {
            message = if (settings.liveTimeshift) "Zurückspulen geht bei diesem Sender nicht" else "Zurückspulen ist in den Einstellungen ausgeschaltet"
            handler.postDelayed({ message = null }, 2_500)
            return
        }
        val from = if (timeshifted) currentTimeshiftMs(p) else liveNowMs(p, buffer)
        val target = from + deltaMs
        if (target >= buffer.latestMs - LIVE_EDGE_MS) {
            if (timeshifted) goLive()
        } else {
            enterTimeshift(target.coerceAtLeast(buffer.oldestMs), play = !timeshifted || p.playWhenReady)
        }
        showOverlay(Overlay.INFO)
    }

    /** Ab Sendezeit [ms] aus dem Zwischenspeicher spielen; die Verbindung zum Anbieter übernimmt [recorder]. */
    private fun enterTimeshift(ms: Long, play: Boolean) {
        val p = player ?: return
        val buffer = timeshift.buffer ?: return
        buffer.tapping = false
        val offset = buffer.offsetAt(ms)
        timeshiftStartMs = buffer.msAt(offset)
        timeshifted = true
        streamGeneration++
        if (cushionActive) {
            cushionActive = false
            p.setPlaybackSpeed(1f)
        }
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.setMediaItem(
            MediaItem.Builder().setUri(TimeshiftBuffer.uri(buffer, offset)).setMimeType(MimeTypes.VIDEO_MP2T).build()
        )
        p.prepare()
        p.playWhenReady = play
        recorder.start(buffer, headers?.userAgent ?: settings.userAgent, timeshift.replayFilter())
        updateTimeshiftState(p)
    }

    /** Wieder live: Aufnahme beenden, der Player liest selbst vom Anbieter (und schreibt weiter mit). */
    private fun goLive() {
        val p = player ?: return
        val channel = playing ?: return
        leaveTimeshift()
        streamGeneration++
        setLiveItem(p, channel)
        p.prepare()
        p.playWhenReady = true
        updateTimeshiftState(p)
        showOverlay(Overlay.INFO)
    }

    private fun leaveTimeshift() {
        recorder.stop()
        timeshift.buffer?.tapping = true
        timeshifted = false
        timeshiftErrors = 0
        behindLiveMs = 0
    }

    private fun setLiveItem(p: ExoPlayer, channel: ChannelEntity) {
        val url = adaptLiveUrl(channel.url)
        p.setMediaItem(mediaItem(url, guessMime(url)))
        p.repeatMode = Player.REPEAT_MODE_ONE
    }

    private fun updateTimeshiftState(p: ExoPlayer) {
        val buffer = timeshift.buffer
        rewindableMs = if (buffer != null && buffer.hasData) buffer.latestMs - buffer.oldestMs else 0L
        behindLiveMs = if (timeshifted && buffer != null) (buffer.latestMs - currentTimeshiftMs(p)).coerceAtLeast(0) else 0L
    }

    /**
     * Läuft beim selben Anbieter eine Aufnahme und erlaubt das Konto nur eine Verbindung, gleich sagen,
     * warum das Bild ausbleibt (sonst sieht es aus wie ein kaputter Sender).
     */
    private fun checkConnectionLimit(channel: ChannelEntity) {
        if (container.accountLimits.maxConnections(channel.playlistId) != 1) return
        val running = container.recordings.all.value.filter { it.state == Recording.State.RUNNING && it.kind == Recording.Kind.RECORDING }
        if (running.isEmpty()) return
        lifecycleScope.launch {
            val busy = running.any { r -> r.channelKey?.let { container.content.channel(it) }?.playlistId == channel.playlistId }
            if (!busy || current?.key != channel.key) return@launch
            val text = "Gerade läuft eine Aufnahme, und dein Konto erlaubt nur 1 Verbindung. Dieser Sender startet erst, wenn die Aufnahme fertig ist."
            message = text
            handler.postDelayed({ if (message == text) message = null }, 8_000)
        }
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
        syncPanel = false
        // Beim nächsten Öffnen wieder das normale Menü, nicht die Unterauswahl von vorhin.
        epgPicker = false
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
        if (heardSpeechKey != request.key) heardSpeech.clear()
        heardSpeechKey = request.key
        loadedLanguages = subtitleFiles.keys.toList()
        p.setMediaItem(vodItem(request), resumeMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        showOverlay(Overlay.CONTROLS)
        if (request.kind == HistoryKind.EPISODE) prepareEpisodeExtras(request)
        checkMobileData()
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                if (pendingSeekMs == C.TIME_UNSET) positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (request.kind == HistoryKind.EPISODE) updateEpisodeCards(request)
                if (++tick % 15 == 0) saveProgress()
                delay(1_000)
            }
        }
    }

    // ---------------------------------------------------------------- Serien

    private fun prepareEpisodeExtras(request: VodRequest) {
        val seriesKey = Episodes.seriesKeyOf(request.key) ?: return
        introLearned = container.intros.get(seriesKey)
        lifecycleScope.launch { nextEpisode = container.content.nextEpisode(request.key) }
        lifecycleScope.launch { introFingerprint = withContext(Dispatchers.IO) { container.intros.fingerprint(seriesKey) } }
    }

    /** Läuft gerade das bekannte Intro? Am Ton der letzten Sekunden suchen (alle 2 s, in den ersten Minuten). */
    private fun searchIntroByAudio(position: Long) {
        val fingerprint = introFingerprint ?: return
        if (introAudioEnd >= 0 || position > OPENING_MS || introSearchJob?.isActive == true) return
        val from = (position - INTRO_SEARCH_BACK_MS).coerceAtLeast(0)
        introSearchJob = lifecycleScope.launch(Dispatchers.Default) {
            val heard = heardSpeech.levels(from, position)
            val at = IntroMatch.find(heard, fingerprint.pattern, 0, heard.size) ?: return@launch
            val end = from + (at + fingerprint.lengthBins) * 100L
            withContext(Dispatchers.Main) { if (end > position + 3_000) introAudioEnd = end }
        }
    }

    /**
     * Folge verlassen: Anfang dieser Folge mit dem der zuletzt gesehenen vergleichen. Der gemeinsame Abschnitt
     * ist die Titelmusik; ihr Anfang wird gespeichert und in den nächsten Folgen am Ton wiedererkannt.
     */
    private fun learnOpening(request: VodRequest) {
        if (openingLearned || request.kind != HistoryKind.EPISODE) return
        val seriesKey = Episodes.seriesKeyOf(request.key) ?: return
        val opening = heardSpeech.levels(0, OPENING_MS)
        val heard = opening.count { !it.isNaN() }
        if (heard < opening.size / 3) return
        openingLearned = true
        val intros = container.intros
        container.scope.launch(Dispatchers.Default) {
            val previous = intros.opening(seriesKey)
            val segment = previous?.let { IntroMatch.commonSegment(opening, it) }
            if (segment != null && segment.length in INTRO_MIN_BINS..INTRO_MAX_BINS) {
                // Muster aus der Folge nehmen, in der der Anfang vollständig gehört wurde.
                val fromCurrent = opening.copyOfRange(segment.startA, segment.startA + INTRO_PATTERN_BINS)
                val pattern = if (fromCurrent.none { it.isNaN() }) fromCurrent
                    else previous!!.copyOfRange(segment.startB, segment.startB + INTRO_PATTERN_BINS)
                if (pattern.none { it.isNaN() }) intros.saveFingerprint(seriesKey, IntroStore.Fingerprint(pattern, segment.length))
                Log.i(TAG, "Intro erkannt: bei ${segment.startA / 10} s, ${segment.length / 10} s lang")
            }
            // Für den nächsten Vergleich den vollständigeren Anfang behalten.
            if (previous == null || heard >= previous.count { !it.isNaN() }) intros.saveOpening(seriesKey, opening)
        }
    }

    private fun updateEpisodeCards(request: VodRequest) {
        val p = player ?: return
        val position = positionMs
        searchIntroByAudio(position)
        // Auch während kurzem Nachladen (z. B. direkt nach dem Spulen) stehen lassen: Sonst verschwindet der
        // Knopf für einen Moment, und OK blendet stattdessen die Steuerleiste ein.
        val playing = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING && everReady
        introVisible = playing && when {
            // Am Ton erkannt: genau solange das Intro läuft.
            introAudioEnd > 0 -> position < introAudioEnd - 2_000
            // Am Ton erkanntes Intro schon übersprungen.
            introAudioEnd == 0L -> false
            // Sonst nach Zeit; nach einem Sprung kurz weg, dann wieder da (erst Rückblick, dann Intro).
            else -> introSkips < MAX_INTRO_SKIPS && SystemClock.elapsedRealtime() > introHiddenUntil && IntroSkip.visible(position, introLearned)
        }
        // Korrekturen nach dem Überspringen gelten als Intro-Länge.
        if (introFrom >= 0 && SystemClock.elapsedRealtime() - introAt > IntroSkip.LEARN_WINDOW_MS) {
            IntroSkip.learn(introFrom, introTo)?.let { intro ->
                Episodes.seriesKeyOf(request.key)?.let { container.intros.put(it, intro) }
                introLearned = intro
            }
            introFrom = -1
        }
        val remaining = durationMs - position
        nextCardVisible = nextEpisode != null && (nextCountdown >= 0 || durationMs > 0 && remaining in 0..NEXT_CARD_MS)
    }

    /** OK bei sichtbarem Knopf: Intro überspringen. */
    private fun skipIntro() {
        val p = player ?: return
        val from = p.currentPosition
        if (introAudioEnd > from) {
            // Am Ton erkannt: genau ans Ende, nichts zu lernen.
            p.seekTo(introAudioEnd)
            positionMs = introAudioEnd
            introAudioEnd = 0
            introVisible = false
            subtitleStatus = "Intro übersprungen"
            return
        }
        val to = IntroSkip.target(from, introLearned)
        introSkips++
        introHiddenUntil = SystemClock.elapsedRealtime() + 4_000
        introVisible = false
        introFrom = from
        introTo = to
        introAt = SystemClock.elapsedRealtime()
        p.seekTo(to)
        positionMs = to
        subtitleStatus = "Intro übersprungen · mit ◀ ▶ nachkorrigieren, DnA-TV merkt es sich für diese Serie"
    }

    /** Folge zu Ende: nächste Folge anbieten und (wenn eingestellt) nach einem Countdown starten. */
    private fun onEpisodeEnded(): Boolean {
        if (nextEpisode == null) return false
        nextCardVisible = true
        closeOverlay()
        if (!settings.autoNextEpisode) return true
        countdownJob?.cancel()
        countdownJob = lifecycleScope.launch {
            for (left in NEXT_COUNTDOWN_SECONDS downTo 1) {
                nextCountdown = left
                delay(1_000)
            }
            playNextEpisode()
        }
        return true
    }

    private fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        nextCountdown = -1
    }

    fun playNextEpisode() {
        val next = nextEpisode ?: return
        cancelCountdown()
        saveProgress()
        PlayerActivity.startVod(
            this,
            VodRequest(
                key = Episodes.key(next.series.key, next.episode.id),
                kind = HistoryKind.EPISODE,
                title = Episodes.title(next.series.name, next.episode),
                subtitle = next.episode.title,
                image = next.episode.image ?: next.series.cover,
                url = next.url,
            ),
        )
        finish()
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
                    container.subtitles.find(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
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
            // 3b. Schwaches Gerät: vorhandene Untertitel einer anderen Sprache übersetzen (nur der
            //     Übersetzer, keine Texterkennung). Sie werden an der eingebetteten Spur ausgerichtet.
            if (heavy && request != null) {
                val template = runCatching {
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
                }.onFailure { if (onlineProblem == null) onlineProblem = describeOnlineProblem(it) }.getOrNull()
                if (template != null) {
                    startFileTranslation(request, language, template.file, template.language)
                    onlineProblem?.let { subtitleStatus = "$it · Übersetze vorhandene Untertitel" }
                    return@launch
                }
            }
            // 3c. Bild-Untertitel auf dem Gerät lesen und übersetzen (auf schwachen Geräten erst jetzt:
            //     Texterkennung ist dort langsam, eine fertige Textvorlage zu übersetzen geht schneller)
            if (embeddedSource != null) {
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
                    container.subtitles.findSource(request.key, request.url, request.title, language, settings, filmInfo()) { subtitleStatus = it }
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
        externalExact = result.source == SubtitleRepository.Source.HASH
        subtitleStatus = when (result.source) {
            SubtitleRepository.Source.HASH -> "Untertitel geladen, passend zur Datei"
            SubtitleRepository.Source.TITLE -> "Untertitel geladen (nach Titel gefunden). Daneben? ☰ → Untertitel-Zeit einstellen"
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

    fun openEpgPicker() {
        val channel = current ?: return
        epgOverride = container.epgOverrides.get(channel.key)
        epgPicker = true
        epgChoicesLoading = true
        overlayToken++
        lifecycleScope.launch {
            // Laden und nach Ähnlichkeit sortieren im Hintergrund: Es sind oft Tausende Sender, im
            // Bedienungs-Thread stand der Fire TV dabei sekundenlang still.
            epgChoices = withContext(Dispatchers.Default) {
                val all = container.playlists.guideChannels(channel.playlistId)
                val wanted = de.ayutv.data.parse.EpgMatcher.analyse(channel.name, isId = false).strong
                all.map { it to de.ayutv.data.parse.EpgMatcher.editDistance(wanted, de.ayutv.data.parse.EpgMatcher.analyse(it.name, isId = false).strong) }
                    .sortedBy { it.second }
                    .map { it.first }
            }
            epgChoicesLoading = false
        }
    }

    /** Die Liste der TV-Guide-Sender wird noch geladen und sortiert. */
    var epgChoicesLoading by mutableStateOf(false); private set

    fun closeEpgPicker() {
        epgPicker = false
    }

    /** Programm für den laufenden Sender festlegen ([guideId] = null: wieder automatisch) und den TV-Guide neu laden. */
    fun assignEpg(guideId: String?) {
        val channel = current ?: return
        container.epgOverrides.set(channel.key, guideId)
        epgPicker = false
        closeOverlay()
        message = if (guideId == null) "Zuordnung zurückgesetzt – TV-Guide wird neu geladen …" else "TV-Guide wird neu zugeordnet …"
        handler.postDelayed({ message = null }, 3_000)
        container.scope.launch {
            container.playlists.syncEpg(channel.playlistId)
            container.content.channel(channel.key)?.let { fresh -> withContext(Dispatchers.Main) { if (current?.key == fresh.key) loadChannelInfo(fresh) } }
        }
    }

    fun openLanguagePicker() {
        languagePicker = true
        overlayToken++
        warmUpTranslator()
    }

    private var warmUpJob: Job? = null

    /**
     * Während die Sprache gewählt wird, schon das Modell der zuletzt gewählten Richtung laden: Auf dem
     * Fire TV Stick dauert das bis zu einer Minute, so steht die erste Zeile früher da.
     */
    private fun warmUpTranslator() {
        if (warmUpJob?.isActive == true || liveTranslateTarget != null || Translators.from(settings) == null) return
        val target = settings.lastTranslationTarget.ifEmpty { return }
        val source = bestTranslationSource()?.let { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) } ?: return
        if (source == target || Translators.remote.isWarm(source, target)) return
        warmUpJob = lifecycleScope.launch {
            runCatching { Translators.remote.translate(listOf("Hello."), target, source) }
                .onFailure { Log.i(SUBTITLE_TAG, "Übersetzer vorab laden: ${it.message}") }
        }
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
        unlabelledTexts.clear()
        cueTranslations.clear()
        liveTranslateTarget = target
        if (settings.lastTranslationTarget != target) container.scope.launch { container.settings.update { it.copy(lastTranslationTarget = target) } }
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
                        val translated = entry?.let { aheadTranslations[it.startMs]?.ifEmpty { null } }
                        // Noch nicht übersetzt: das Original zeigen (Text selbst, Bild-Untertitel über den Player)
                        // statt gar nichts, solange der Übersetzer lädt oder nicht hinterherkommt.
                        translatedCue = translated ?: if (bitmap) null else entry?.text
                        originalVisible = bitmap && entry != null && translated == null
                    }
                    delay(100)
                }
            }
            var failures = 0
            // Wie lange eine Zeile gerade dauert (gleitender Mittelwert, ohne das erste Laden des Modells).
            var lineMs = 0L
            var done = 0
            val skipped = HashSet<Long>()
            var slowWarned = false
            val wanted: (EmbeddedSubtitles.Entry) -> Boolean =
                { it.startMs !in aheadTranslations && (it.text != null || it.image != null) }
            while (true) {
                val store = embedded ?: break
                val position = player?.currentPosition ?: 0L
                // Kommt das Gerät nicht hinterher, die Zeilen überspringen, die vorbei wären, bevor ihre
                // Übersetzung fertig ist: lieber jede zweite Zeile übersetzt als jede zu spät (dann bliebe alles im Original).
                val earliest = position - 2_000
                val ready = position + lineMs * 13 / 10
                fun lateFor(entry: EmbeddedSubtitles.Entry) = lineMs > 0 && (if (entry.endMs >= 0) entry.endMs else entry.startMs + ASSUMED_LINE_MS) <= ready
                val next = store.upcoming(format, earliest, 1) { wanted(it) && !lateFor(it) }.firstOrNull()
                if (next == null) {
                    delay(250)
                    continue
                }
                store.upcoming(format, earliest, 20) { wanted(it) && it.startMs < next.startMs && lateFor(it) }.forEach { skipped += it.startMs }
                try {
                    val started = SystemClock.elapsedRealtime()
                    val text = next.text ?: next.image?.let { image -> Translators.remote.recognize(image).also { next.text = it; next.image = null } }.orEmpty()
                    aheadTranslations[next.startMs] = if (text.isBlank()) "" else translator.translate(listOf(text), target, sourceFor(text)).first()
                    val took = SystemClock.elapsedRealtime() - started
                    Log.i(SUBTITLE_TAG, "Vorlauf ${(next.startMs - position) / 1000} s, $took ms: $text → ${aheadTranslations[next.startMs]}")
                    if (text.isNotBlank() && done++ > 0) lineMs = if (lineMs == 0L) took else (lineMs * 3 + took) / 4
                    if (!slowWarned && done >= 8 && skipped.size >= done) {
                        slowWarned = true
                        subtitleStatus = slowTranslationHint(target, lineMs)
                        Log.i(SUBTITLE_TAG, "Gerät zu langsam: $done Zeilen übersetzt, ${skipped.size} übersprungen, $lineMs ms pro Zeile")
                    }
                    failures = 0
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: de.ayutv.data.translate.RemoteTranslator.NoAnswer) {
                    // Übersetzer hängt (Modell lädt auf diesem Gerät zu lange): beenden statt endlos warten.
                    Log.w(SUBTITLE_TAG, "Übersetzer antwortet nicht", e)
                    Translators.remote.abort(e.message ?: "keine Antwort")
                    stopLiveTranslation()
                    fallbackToLoaded(target, "${e.message}. Test: Einstellungen → Untertitel → Übersetzer testen")
                    break
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

    /** Ehrlich sagen, dass das Gerät nicht mitkommt, und was stattdessen hilft. */
    private fun slowTranslationHint(target: String, lineMs: Long): String {
        val seconds = String.format(Locale.GERMAN, "%.1f", lineMs / 1000f)
        val tip = if (vod != null) " Besser: ☰ → „${Languages.label(target)} laden“ (fertige Untertitel)." else ""
        return "Dieses Gerät ist für Live-Übersetzung zu langsam ($seconds s pro Zeile). Ein Teil der Zeilen bleibt im Original.$tip"
    }

    /** Gelesene Zeilen einer Spur ohne Sprachangabe, um ihre Sprache zu erkennen. */
    private val unlabelledTexts = ArrayList<String>()

    /**
     * Sprache der Quellspur. Nennt die Spur keine (Live-TV: „Spur 1“), wird sie am Text erkannt, sonst
     * würde deutscher Text als Englisch übersetzt.
     */
    private fun sourceFor(text: String): String? {
        liveSourceLanguage?.let { return it }
        unlabelledTexts += text
        if (unlabelledTexts.size > 20) unlabelledTexts.removeAt(0)
        return Languages.guess(unlabelledTexts)?.also {
            liveSourceLanguage = it
            subtitleStatus = "Untertitel erkannt als ${Languages.label(it)}"
        }
    }

    /** Beendet Live-Übersetzung und eigene Einblendung geladener Untertitel. */
    fun stopLiveTranslation() {
        liveTranslateTarget = null
        externalLanguage = null
        speechSyncInfo = null
        translatedCue = null
        cueJob?.cancel()
        fileTranslationJob?.cancel()
        fileTranslationJob = null
        externalJob?.cancel()
        externalJob = null
        syncJob?.cancel()
        speechJob?.cancel()
        speechJob = null
        externalCues = emptyList()
        externalTexts = null
        aheadJob?.cancel()
        aheadJob = null
        aheadFormat = null
        aheadTranslations.clear()
        originalVisible = false
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
        manualAnchor = null
        manualSync = false
        externalCues = cues
        externalTexts = texts
        referenceStarts.clear()
        referenceShowing = false
        val saved = vod?.let { container.subtitles.timing(it.key, language) }
        subtitleTiming = saved?.let { SubtitleTiming(it.ratio, it.offsetMs) } ?: SubtitleTiming()
        subtitleDelayMs = saved?.manualMs ?: 0L
        subtitleAutoSynced = saved != null && (saved.offsetMs != 0L || saved.ratio != 1.0)
        syncedBySpeech = false
        syncedByReference = false
        externalExact = false
        speechSyncInfo = if (subtitleAutoSynced) "Gespeicherte Ausrichtung, wird am Ton nachgeprüft" else null
        textDisabled = false
        selectSyncReference()
        startSpeechSync()
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

    /** Gesamtverschiebung der geladenen Untertitel gegenüber der Datei, für die Anzeige. */
    val subtitleShiftLabel: String get() = formatShift(subtitleTiming.offsetMs + subtitleDelayMs) +
        if (subtitleTiming.ratio != 1.0) ", Bildrate korrigiert" else ""

    /** Untertitel-Zeit von Hand einstellen (Feld im Menü, schließt nicht von selbst). */
    var syncPanel by mutableStateOf(false); private set

    fun openSyncPanel() {
        syncPanel = true
        overlayToken++
    }

    fun closeSyncPanel() {
        syncPanel = false
        overlayToken++
    }

    class SyncLine(val start: Long, val text: String, val upcoming: Boolean)

    /** Zeilen der geladenen Datei rund um die jetzige Stelle, zum Antippen, sobald eine gesprochen wird. */
    fun syncLines(): List<SyncLine> {
        val at = subtitleTimeAt(player?.currentPosition ?: 0L)
        return externalCues.filter { it.start in at - SYNC_LINES_BACK_MS..at + SYNC_LINES_AHEAD_MS }
            .map { SyncLine(it.start, it.text.replace('\n', ' '), it.start >= at) }
    }

    /** Zuletzt von Hand festgelegte Zeile (Filmzeit, Zeit in der Datei), für die Bildraten-Korrektur aus zwei Punkten. */
    private var manualAnchor: Pair<Long, Long>? = null
    /** Von Hand an einer Zeile ausgerichtet: Die automatische Ausrichtung überschreibt das nicht mehr. */
    private var manualSync = false

    /**
     * Die Zeile mit Beginn [cueStart] wird gerade gesprochen: so verschieben, dass sie genau jetzt beginnt.
     * Ein Druck genügt, egal wie weit die Untertitel daneben lagen. Ein zweiter Druck mindestens drei
     * Minuten später erkennt auch eine falsche Bildrate (Untertitel laufen langsam auseinander).
     */
    fun anchorSubtitle(cueStart: Long) {
        val position = (player?.currentPosition ?: return) - REACTION_MS
        val previous = manualAnchor
        val ratio = previous?.takeIf { abs(cueStart - it.second) >= 3 * 60_000L }?.let { (video, subtitle) ->
            val raw = (position - video).toDouble() / (cueStart - subtitle)
            SYNC_RATIOS.minByOrNull { abs(it - raw) }?.takeIf { abs(it - raw) / it < 0.003 }
        }
        if (ratio != null && ratio != subtitleTiming.ratio) {
            subtitleTiming = SubtitleTiming(ratio, position - (cueStart * ratio).toLong())
            subtitleDelayMs = 0
        } else {
            subtitleDelayMs = position - subtitleTiming.toVideo(cueStart)
        }
        manualAnchor = position to cueStart
        manualSync = true
        subtitleAutoSynced = true
        speechSyncInfo = "Von Hand ausgerichtet"
        subtitleStatus = "Untertitel ausgerichtet ($subtitleShiftLabel)"
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
            syncedByReference = true
            applyFit(fit.timing, bySpeech = false, toleranceMs = 150)
        }
    }

    /** Automatisch gefundene Lage übernehmen, wenn sie spürbar von der jetzigen abweicht. */
    private fun applyFit(timing: SubtitleTiming, bySpeech: Boolean, toleranceMs: Long) {
        // Von Hand an einer Zeile ausgerichtet: Das ist genauer als jede Schätzung, nicht wieder verstellen.
        if (manualSync) return
        // Von Hand verschoben, bevor überhaupt automatisch ausgerichtet war: Das war der Versuch, denselben
        // Versatz auszugleichen, den die Ausrichtung jetzt genau kennt.
        if (!subtitleAutoSynced) subtitleDelayMs = 0L
        val here = subtitleTimeAt(player?.currentPosition ?: 0L)
        val jump = abs(timing.toVideo(here) - subtitleTiming.toVideo(here))
        if (jump < toleranceMs && subtitleAutoSynced) return
        val first = !subtitleAutoSynced || syncedBySpeech != bySpeech
        subtitleTiming = timing
        subtitleAutoSynced = true
        syncedBySpeech = bySpeech
        if (first || jump >= 1_000) {
            subtitleStatus = (if (bySpeech) "Untertitel am Ton ausgerichtet (" else "Untertitel automatisch angepasst (") +
                formatShift(timing.offsetMs + subtitleDelayMs) + (if (timing.ratio != 1.0) ", Bildrate korrigiert" else "") + ")"
        }
        saveTiming()
    }

    /**
     * Ohne eingebettete Untertitel als Taktgeber: die geladenen Untertitel am gesprochenen Ton ausrichten.
     * Alle 20 s mit den letzten 15 Minuten Ton (erstmals nach 2 Minuten); so folgt die Ausrichtung auch gekürzten Fassungen, in
     * denen der Versatz mitten im Film springt. Wird der Ton durchgereicht (Dolby an Soundbar), hört die
     * App nichts mit: dann einmal auf Dekodieren im Gerät umstellen.
     */
    private fun startSpeechSync() {
        speechJob?.cancel()
        if (vod == null) return
        speechJob = lifecycleScope.launch {
            var lastHeard = -1
            var playedMs = 0L
            while (isActive && externalLanguage != null) {
                delay(SPEECH_CHECK_MS)
                val p = player ?: continue
                if (!p.isPlaying) continue
                playedMs += SPEECH_CHECK_MS
                if (syncedByReference) {
                    speechSyncInfo = "An den eingebetteten Untertiteln des Films ausgerichtet"
                    continue
                }
                val heard = heardSpeech.seconds()
                if (heard < 5) {
                    // Ton geht an Soundbar/Receiver: Nur umstellen, wenn die Untertitel nicht schon passen
                    // (Datei-Hash oder gespeicherte Ausrichtung) – sonst bliebe der Raumklang ohne Not weg.
                    val needed = !subtitleAutoSynced && !externalExact
                    if (playedMs >= 2 * SPEECH_CHECK_MS && needed && textTracks().isEmpty() && !forcePcmActive && !pcmForSpeechTried) {
                        pcmForSpeechTried = true
                        Log.i(SUBTITLE_TAG, "Ton wird durchgereicht, dekodiere im Gerät für die Ausrichtung am Ton")
                        subtitleStatus = "Ton wird im Gerät dekodiert, damit sich die Untertitel am Gespräch ausrichten …"
                        rebuildPlayer(forcePcm = true)
                    }
                    speechSyncInfo = when {
                        externalExact -> "Untertitel passen zur Datei (keine Ausrichtung nötig)"
                        playedMs < 2 * SPEECH_CHECK_MS -> "Ausrichtung am Ton startet …"
                        else -> "Kein Ton zum Mithören (Ton geht direkt an Soundbar/Receiver)"
                    }
                    continue
                }
                if (heard < SpeechSync.MIN_SECONDS) {
                    if (!subtitleAutoSynced) speechSyncInfo = "Hört zu: ${heard} von ${SpeechSync.MIN_SECONDS} Sekunden Ton"
                    continue
                }
                if (heard == lastHeard) continue
                lastHeard = heard
                val position = p.currentPosition
                val cues = externalCues.map { it.start to it.end }
                val (fit, window) = withContext(Dispatchers.Default) {
                    val signal = heardSpeech.signal(position - SPEECH_WINDOW_MS, position + 5_000)
                    SpeechSync.estimate(signal, cues) to signal.count { !it.isNaN() } / 10
                }
                if (externalLanguage == null || syncedByReference) continue
                if (fit != null) {
                    Log.i(SUBTITLE_TAG, "Am Ton ausgerichtet: ${fit.timing} (Güte ${fit.votes / 10.0}, ${window} s Ton)")
                    applyFit(fit.timing, bySpeech = true, toleranceMs = 250)
                    val certainty = when {
                        fit.votes >= 100 -> "sehr sicher"
                        fit.votes >= 80 -> "sicher"
                        else -> "ausreichend sicher"
                    }
                    speechSyncInfo = "Am Ton ausgerichtet ($certainty), zuletzt geprüft bei ${clock(position)}"
                } else {
                    speechSyncInfo = if (subtitleAutoSynced) {
                        "Ausgerichtet; an dieser Stelle (${clock(position)}) kein klarer Takt, prüft weiter"
                    } else {
                        "${window / 60} min Ton geprüft, noch kein eindeutiger Takt – prüft weiter"
                    }
                }
                if (fit == null && window >= SPEECH_WRONG_FILE_SECONDS && !subtitleAutoSynced && externalTexts == null) {
                    // Eine Viertelstunde Gespräch und kein gemeinsamer Takt: Die Datei gehört zu einem anderen Film.
                    replaceWrongSubtitles()
                    return@launch
                }
            }
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
            val found = runCatching { container.subtitles.find(request.key, request.url, request.title, language, settings, filmInfo()) }
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

    /** Von Hand aus dem Menü: Die eingeblendeten Untertitel gehören nicht zu diesem Film. */
    fun reportWrongSubtitles() {
        closeOverlay()
        replaceWrongSubtitles()
    }

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
            val found = runCatching { container.subtitles.find(request.key, request.url, request.title, language, settings, filmInfo()) }
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
            val source = sourceFor(text)
            runCatching { translator.translate(listOf(text), target, source).first() }
                .onSuccess {
                    cueTranslations[text] = it
                    translatedCue = it
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) return@onFailure
                    Log.w(SUBTITLE_TAG, "Live-Übersetzung fehlgeschlagen", it)
                    if (it is de.ayutv.data.translate.RemoteTranslator.NoAnswer) Translators.remote.abort(it.message.orEmpty())
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
                if (e is de.ayutv.data.translate.RemoteTranslator.NoAnswer) Translators.remote.abort(e.message.orEmpty())
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
        // Für diesen Sender / diese Serie merken.
        val language = group.getTrackFormat(index).language?.takeIf { it != "und" }
        if (language != null) memoryKey()?.let { key ->
            container.playerMemory.update(key) { if (group.type == C.TRACK_TYPE_AUDIO) it.copy(audio = language) else it.copy(text = language) }
        }
    }

    fun setTextEnabled(enabled: Boolean) {
        val p = player ?: return
        if (!enabled) memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(text = PlayerMemory.OFF) } }
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
        // Kurz nach „Intro überspringen“: Spulen korrigiert, wo das Intro endet.
        if (introFrom >= 0 && SystemClock.elapsedRealtime() - introAt <= IntroSkip.LEARN_WINDOW_MS) {
            introTo = pendingSeekMs
            introAt = SystemClock.elapsedRealtime()
        }
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

    // ---------------------------------------------------------------- Touch (Handy, Tablet)

    /** Kurze Rückmeldung zu einer Wischgeste (Lautstärke, Helligkeit, Spulen), mittig im Bild. */
    var gestureInfo by mutableStateOf<String?>(null); private set
    private var gestureInfoJob: Job? = null
    /** Bildformat: ganz, füllen (Ränder abgeschnitten) oder gestreckt; gemerkt je Sender/Serie/Film. */
    var aspect by mutableStateOf(AspectMode.FIT); private set
    /** Läuft als kleines Fenster über anderen Apps (Bild-in-Bild). */
    var inPip by mutableStateOf(false); private set
    private var volumeLevel = -1f
    private var brightnessLevel = -1f

    private fun showGestureInfo(text: String) {
        gestureInfo = text
        gestureInfoJob?.cancel()
        gestureInfoJob = lifecycleScope.launch {
            delay(1_200)
            gestureInfo = null
        }
    }

    /** Rechts hoch/runter wischen: [delta] ist der Anteil der Bildschirmhöhe (hoch = positiv). */
    fun adjustVolume(delta: Float) {
        val audio = getSystemService(android.media.AudioManager::class.java) ?: return
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        if (volumeLevel < 0) volumeLevel = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / max
        volumeLevel = (volumeLevel + delta).coerceIn(0f, 1f)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(volumeLevel * max), 0)
        showGestureInfo("🔊 ${Math.round(volumeLevel * 100)} %")
    }

    /** Links hoch/runter wischen: Helligkeit nur für den Player (nicht für das ganze Gerät). */
    fun adjustBrightness(delta: Float) {
        val attributes = window.attributes
        if (brightnessLevel < 0) brightnessLevel = attributes.screenBrightness.takeIf { it >= 0 } ?: 0.5f
        brightnessLevel = (brightnessLevel + delta).coerceIn(0.02f, 1f)
        attributes.screenBrightness = brightnessLevel
        window.attributes = attributes
        showGestureInfo("☀ ${Math.round(brightnessLevel * 100)} %")
    }

    /** Waagerecht wischen: [fraction] der Bildschirmbreite entspricht bis zu zwei Minuten. Erst beim Loslassen springen. */
    fun seekGesture(fraction: Float, done: Boolean) {
        val deltaMs = (fraction * SWIPE_SEEK_MS / 5_000).toLong() * 5_000
        if (!done) {
            showGestureInfo((if (deltaMs >= 0) "⏩ +" else "⏪ −") + clock(kotlin.math.abs(deltaMs)))
            return
        }
        if (deltaMs == 0L) return
        if (isLive) shiftLive(deltaMs) else seekBy(deltaMs)
    }

    /** Live: senkrecht in der Bildmitte wischen schaltet um (hoch = nächster Sender). */
    fun zapGesture(up: Boolean) {
        if (isLive) zap(if (up) +1 else -1)
    }

    /** Zwei Finger auseinander: Bild füllt den Bildschirm; zusammen: wieder ganz. */
    fun zoomGesture(fill: Boolean) {
        if ((aspect == AspectMode.ZOOM) == fill) return
        chooseAspect(if (fill) AspectMode.ZOOM else AspectMode.FIT)
        showGestureInfo(if (fill) "Bild füllt den Bildschirm" else "Ganzes Bild")
    }

    fun chooseAspect(mode: AspectMode) {
        aspect = mode
        memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(aspect = mode) } }
    }

    // ---------------------------------------------------------------- Merken, Ton-Modus, Schlaf-Timer

    /** Wofür Spuren und Bildformat gemerkt werden: Sender, Serie (alle Folgen gleich) oder Film. */
    private fun memoryKey(): String? = vod?.let { Episodes.seriesKeyOf(it.key) ?: it.key } ?: playing?.key

    /** Beim ersten Mal, wenn die Spuren des Streams bekannt sind: zuletzt gewählten Ton und Untertitel einstellen. */
    private fun applyMemory(tracks: Tracks) {
        if (memoryApplied || tracks.isEmpty) return
        val key = memoryKey() ?: return
        memoryApplied = true
        val choice = container.playerMemory.get(key)
        val p = player ?: return
        choice.audio?.let { language ->
            val match = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
                .firstOrNull { (g, i) -> Languages.normalize(g.getTrackFormat(i).language) == Languages.normalize(language) }
            if (match != null && !match.first.isTrackSelected(match.second)) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                    .build()
            }
        }
        val text = choice.text
        when {
            choice.textOff -> if (!textDisabled) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
                textDisabled = true
            }
            text != null && externalLanguage == null && hasTextTrack(text) -> selectTextLanguage(text)
        }
    }

    // ---------------------------------------------------------------- Chromecast (Handy)

    /** Chromecast möglich (Handy mit Google-Play-Diensten). */
    val castAvailable: Boolean by lazy { de.ayutv.player.cast.Cast.available(this) }
    /** Name des Chromecast, auf dem gerade gespielt wird. */
    var castingTo by mutableStateOf<String?>(null); private set

    fun toggleCast() {
        if (castingTo != null) {
            de.ayutv.player.cast.Cast.stop(this)
            return
        }
        de.ayutv.player.cast.Cast.choose(
            this,
            onConnected = { name -> startCasting(name) },
            onEnded = {
                castingTo = null
                message = "Chromecast beendet – läuft wieder hier"
                player?.play()
            },
        )
    }

    private fun startCasting(name: String) {
        val p = player ?: return
        val media = if (isLive) {
            playing?.let { c ->
                val title = nowNext.firstOrNull()?.headline()?.ifEmpty { null }
                de.ayutv.player.cast.Cast.Media(c.url, c.name, title, c.logo, live = true, positionMs = 0)
            }
        } else {
            vod?.let { de.ayutv.player.cast.Cast.Media(it.url, it.title, it.subtitle, it.image, live = false, positionMs = p.currentPosition) }
        }
        if (media == null || media.url.startsWith("file:")) {
            message = "Aufnahmen und Downloads lassen sich nicht übertragen"
            return
        }
        if (de.ayutv.player.cast.Cast.play(this, media)) {
            castingTo = name
            p.pause()
            message = "📺 Läuft auf $name (📺 nochmal: zurück aufs Handy)"
        }
    }

    /** Laufende Sendung aufnehmen (ohne Programmdaten: die nächste Stunde); nochmal = Aufnahme entfernen. */
    fun recordCurrent() {
        val channel = playing ?: return
        val now = System.currentTimeMillis()
        val programme = nowNext.firstOrNull { it.start <= now && it.stop > now }
        val start = programme?.start ?: (now / 60_000 * 60_000)
        val stop = programme?.stop ?: (start + 60 * 60_000)
        val title = programme?.headline()?.ifEmpty { null } ?: channel.name
        lifecycleScope.launch {
            val on = container.recordings.toggleRecording(
                channel.key, channel.name, title, channel.url, channel.userAgent ?: settings.userAgent, start, stop, programme?.icon ?: channel.logo,
            )
            val warning = if (on) de.ayutv.data.AccountLimits.recordingWarning(container.accountLimits.maxConnections(channel.playlistId)) else null
            message = (if (on) "⏺ Wird aufgenommen bis ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(stop))}: $title" else "Aufnahme entfernt") +
                warning?.let { "\n$it" }.orEmpty()
        }
    }

    /** VLC ist installiert (Fire TV: Amazon Appstore). Manche kaputten Streams spielt es robuster ab. */
    val vlcAvailable: Boolean by lazy { runCatching { packageManager.getPackageInfo(VLC_PACKAGE, 0) }.isSuccess }

    /**
     * Den laufenden Stream an VLC übergeben. DnA-TV hält vorher an und schließt den Player: Viele Konten
     * erlauben nur eine Verbindung, VLC bekäme sonst „zu viele Verbindungen“.
     */
    fun openInVlc() {
        val request = vod
        val url = request?.url ?: playing?.let { adaptLiveUrl(it.url) } ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(VLC_PACKAGE)
            .setDataAndType(android.net.Uri.parse(url), "video/*")
            .putExtra("title", request?.title ?: playing?.name)
            .apply { if (request != null) player?.currentPosition?.takeIf { it > 0 }?.let { putExtra("position", it) } }
        player?.stop()
        try {
            startActivity(intent)
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "VLC nicht gestartet", e)
            message = "VLC ließ sich nicht starten"
            restartStream()
        }
    }

    fun chooseAudioMode(mode: AudioMode) {
        audioMode = mode
        enhance.mode = mode
        container.scope.launch { container.settings.update { it.copy(audioMode = mode) } }
    }

    /**
     * Schlaf-Timer: nach [minutes] anhalten und den Player schließen. 0 = aus, [SLEEP_END] = am Ende der
     * laufenden Sendung bzw. des Films.
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        val now = System.currentTimeMillis()
        sleepAt = when {
            minutes == 0 -> 0L
            minutes == SLEEP_END && isLive -> nowNext.firstOrNull()?.stop?.takeIf { it > now } ?: 0L
            minutes == SLEEP_END -> player?.let { p -> (p.duration - p.currentPosition).takeIf { p.duration > 0 && it > 0 }?.let { now + it } } ?: 0L
            else -> now + minutes * 60_000L
        }
        if (sleepAt == 0L) {
            if (minutes != 0) message = "Ende unbekannt – bitte eine feste Zeit wählen"
            return
        }
        message = "Schlaf-Timer: Ende um ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(sleepAt))}"
        sleepJob = lifecycleScope.launch {
            var warned = false
            while (true) {
                val left = sleepAt - System.currentTimeMillis()
                if (left <= 0) break
                if (!warned && left <= 60_000) {
                    warned = true
                    message = "Schlaf-Timer: noch 1 Minute (☰ → Schlaf-Timer zum Verlängern)"
                }
                delay(minOf(left, 5_000))
            }
            sleepAt = 0L
            player?.pause()
            finish()
        }
    }

    /** Home-Taste während der Wiedergabe: als kleines Fenster weiterlaufen (Handy, Tablet). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipIfPossible()
    }

    private fun enterPipIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !hasTouchscreen() || isFinishing) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val p = player ?: return
        if (!p.playWhenReady) return
        val size = p.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            android.util.Rational((r.coerceIn(0.42f, 2.38f) * 1000).toInt(), 1000)
        } else {
            android.util.Rational(16, 9)
        }
        closeOverlay()
        runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
            .onFailure { Log.w(TAG, "Bild-in-Bild nicht möglich", it) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (isInPictureInPictureMode) closeOverlay()
    }

    /** Mobile Daten (oder anderes Netz mit Volumen): Hinweis und auf Wunsch sparsamere Fassung. */
    private fun onMeteredNetwork(): Boolean {
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        return runCatching { connectivity.isActiveNetworkMetered }.getOrDefault(false) && hasTouchscreen()
    }

    private var meteredHintShown = false

    private fun checkMobileData() {
        if (!onMeteredNetwork()) return
        if (!meteredHintShown) {
            meteredHintShown = true
            subtitleStatus = if (settings.dataSaver) "Mobile Daten: Datensparmodus an (kleinere Fassung, höchstens 720p)"
                else "Mobile Daten: HD braucht etwa 2–3 GB pro Stunde"
        }
    }

    /** Datensparmodus: auf die SD-Fassung eines Senders wechseln, wenn es eine gibt. */
    private fun preferSmallVariant(channel: ChannelEntity) {
        if (!settings.dataSaver || !settings.mergeVariants || switchingVariant || !onMeteredNetwork()) return
        lifecycleScope.launch {
            val small = container.content.variantsOf(channel).firstOrNull { de.ayutv.data.parse.ChannelVariants.rank(it.name) == 4 }
            if (small == null || playing?.key != channel.key) return@launch
            switchingVariant = true
            try { playChannel(small) } finally { switchingVariant = false }
        }
    }

    /** Knopf auf dem Bildschirm: wirkt wie dieselbe Taste der Fernbedienung. */
    fun pressKey(code: Int) {
        if (player == null) return
        overlayToken++
        if (isLive) handleLiveKey(code, 0) else handleVodKey(code, 0)
    }

    /** Tippen ins Bild blendet die Leiste ein oder aus. */
    fun onScreenTap() {
        if (overlay == Overlay.NONE) showOverlay(if (isLive) Overlay.INFO else Overlay.CONTROLS) else closeOverlay()
    }

    /** Tippen auf den Fortschrittsbalken: dorthin springen. */
    fun seekToFraction(fraction: Float) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val base = if (pendingSeekMs != C.TIME_UNSET) pendingSeekMs else p.currentPosition
        seekBy((duration * fraction.coerceIn(0f, 1f)).toLong() - base)
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
                if (timeshifted && player?.isPlaying == true) timeshiftErrors = 0
                message = null
                everReady = true
                fatalError = false
                if (!isLive) maybeAutoSubtitles()
            }
            if (state == Player.STATE_ENDED && !isLive) {
                if (vod?.kind != HistoryKind.EPISODE || !onEpisodeEnded()) finish()
            }
            // Live-Streams enden nie wirklich: Schließt der Server die Verbindung „sauber“, hält der
            // Player das für das Ende und bliebe stehen. Sofort und ohne Meldung neu verbinden.
            if (state == Player.STATE_ENDED && isLive) {
                // Zwischenspeicher zu Ende (z. B. Speicher voll): live weiter.
                if (timeshifted) handler.post { goLive() } else onLiveStreamEnded()
            }
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
            applyMemory(tracks)
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
            if (timeshifted) {
                if (++timeshiftErrors <= 2) {
                    handler.postDelayed({ if (timeshifted) player?.let { enterTimeshift(currentTimeshiftMs(it), play = true) } }, 500)
                } else {
                    message = "Zeitversatz gestört – zurück zu Live"
                    goLive()
                }
                return
            }
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
                    // HLS liest Playlisten und Segmente: das lässt sich nicht als ein TS-Strom mitschreiben.
                    timeshift.buffer?.release()
                    timeshift.buffer = null
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
                    val reason = unsupportedFormatMessage()
                    if (!tryNextVariant(reason)) {
                        fatalError = true
                        message = reason
                    }
                }
                // Decoder-Start gescheitert: oft nur kurz belegt (4K-Decoder beim Zappen). Einmal neu versuchen.
                error.isFormatError() && !everReady && retries >= 1 -> {
                    val reason = unsupportedFormatMessage()
                    if (!tryNextVariant(reason)) {
                        fatalError = true
                        message = reason
                    }
                }
                retries < MAX_RETRIES -> {
                    retries++
                    message = "Verbindung wird wiederhergestellt … ($retries/$MAX_RETRIES)"
                    retryLater()
                }
                else -> {
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Der Anbieter lehnt den Stream ab (z. B. zu viele Verbindungen oder Konto abgelaufen)"
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Server"
                        else -> "Stream nicht verfügbar (${error.errorCodeName})"
                    }
                    if (!tryNextVariant(reason)) {
                        fatalError = true
                        message = reason
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
                    epgPicker -> closeEpgPicker()
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
        return if (isLive) handleLiveKey(code, event.repeatCount) else handleVodKey(code, event.repeatCount)
    }

    private fun isHandledKey(code: Int) = code in HANDLED_KEYS || code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9

    private fun handleLiveKey(code: Int, repeat: Int): Boolean {
        val p = player
        // Gedrückt halten spult immer schneller (wie bei Filmen).
        val step = when {
            repeat > 20 -> 120_000L
            repeat > 5 -> 60_000L
            else -> 0L
        }
        // Zeitversetzt: OK hält an, ◀ ▶ spulen. Umschalten und Menü bleiben wie gewohnt.
        if (timeshifted) when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleLivePause(code)
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                shiftLive(-(if (step > 0) step else 10_000L))
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                shiftLive(if (step > 0) step else 30_000L)
                return true
            }
        }
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
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> toggleLivePause(code)
            KeyEvent.KEYCODE_MEDIA_REWIND -> shiftLive(-(if (step > 0) step else 10_000L))
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> shiftLive(if (step > 0) step else 30_000L)
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
        // Nach dem Ende einer Folge: OK startet die nächste sofort, alles andere hält den Countdown an.
        if (p.playbackState == Player.STATE_ENDED && nextCardVisible) {
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) playNextEpisode() else cancelCountdown()
            return true
        }
        if (overlay != Overlay.CONTROLS && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (nextCardVisible) {
                playNextEpisode()
                return true
            }
            if (introVisible) {
                skipIntro()
                return true
            }
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
        vod?.let(::learnOpening)
        // Bild-in-Bild-Fenster geschlossen: Player beenden statt unsichtbar im Hintergrund zu bleiben.
        if (inPip) {
            inPip = false
            finish()
        }
        // Noch nicht ausgeführtes Schnell-Zappen verwerfen: im Hintergrund keinen neuen Stream öffnen.
        if (zapJob != null) {
            zapJob?.cancel()
            zapJob = null
            zapTarget = null
            current = playing
            playing?.let(::loadChannelInfo)
        }
        saveProgress()
        // Zeitversatz endet im Hintergrund (keine Aufnahme ohne Bild); beim Zurückkommen läuft wieder live.
        if (isLive && timeshifted) {
            leaveTimeshift()
            player?.let { p -> playing?.let { setLiveItem(p, it) } }
        }
        // Live-Streams im Hintergrund nicht weiterladen: spart Bandbreite und eine Verbindung beim Anbieter.
        player?.let { p -> if (isLive) p.stop() else p.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        recorder.stop()
        timeshift.buffer?.release()
        timeshift.buffer = null
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
        private const val VLC_PACKAGE = "org.videolan.vlc"
        /** So viel später drückt man OK, nachdem man die Zeile gehört hat. */
        private const val REACTION_MS = 400L
        private const val SYNC_LINES_BACK_MS = 2 * 60_000L
        private const val SYNC_LINES_AHEAD_MS = 3 * 60_000L
        /** Übliche Bildraten-Unterschiede zwischen Untertitel-Datei und Film. */
        private val SYNC_RATIOS = listOf(1.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0, 25.0 / 24.0, 24.0 / 25.0)
        private const val DROP_WINDOW_MS = 10 * 60_000L
        /** Angenommene Dauer einer Einblendung ohne bekanntes Ende (Live-Übersetzung, Zeilen überspringen). */
        private const val ASSUMED_LINE_MS = 3_000L
        private const val DROP_WARN_COUNT = 3
        private const val FIRST_CONNECT_STALL_SECONDS = 15
        /** So viele eingebettete Einblendungen (gut 4 Minuten Dialog) ohne gemeinsamen Takt: falsche Datei. */
        private const val WRONG_FILE_REFERENCES = 60
        private const val SPEECH_CHECK_MS = 20_000L

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        private const val SPEECH_WINDOW_MS = 15 * 60_000L
        /** So viel Ton im Fenster ohne Übereinstimmung: falsche Datei. */
        private const val SPEECH_WRONG_FILE_SECONDS = 14 * 60
        private const val VIDEO_FREEZE_SECONDS = 6
        private const val CUSHION_LOW_MS = 2_500L
        private const val CUSHION_HIGH_MS = 5_000L
        // Bei 50 Hz bringt 0,985 nur alle ~1,3 s ein doppeltes Bild (0,97 ruckelte bei Schwenks sichtbar).
        private const val CUSHION_SPEED = 0.985f
        private const val VOD_STALL_SECONDS = 20
        /** Über die ganze Bildschirmbreite wischen spult so weit. */
        private const val SWIPE_SEEK_MS = 120_000f
        /** Schlaf-Timer bis zum Ende der Sendung / des Films. */
        const val SLEEP_END = -1
        /** „Nächste Folge“ erscheint in den letzten Sekunden einer Folge. */
        private const val NEXT_CARD_MS = 20_000L
        private const val NEXT_COUNTDOWN_SECONDS = 10
        /** Die ersten Minuten einer Folge: darin wird das Intro gesucht und gelernt. */
        private const val OPENING_MS = 8 * 60_000L
        private const val INTRO_SEARCH_BACK_MS = 40_000L
        private const val INTRO_PATTERN_BINS = 80
        private const val INTRO_MIN_BINS = 150
        private const val INTRO_MAX_BINS = 2_400
        private const val MAX_INTRO_SKIPS = 3
        /** Näher als das am Live-Bild gilt als live. */
        private const val LIVE_EDGE_MS = 4_000L
        /** Mindestens so viel freier Speicher, sonst kein Zeitversatz. */
        private const val TIMESHIFT_MIN_BYTES = 200L * 1024 * 1024
        private const val TIMESHIFT_MAX_BYTES = 4L * 1024 * 1024 * 1024
        private const val EXTRA_CHANNEL_KEY = "channel_key"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_FAVORITES = "favorites"
        private const val EXTRA_FROM_START = "from_start"
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

        fun startVod(context: Context, request: VodRequest, fromStart: Boolean = false) =
            context.startActivity(vodIntent(context, request).putExtra(EXTRA_FROM_START, fromStart))

        /** Vergangene Sendung aus dem Archiv des Anbieters: läuft wie ein Film (spulbar). */
        fun startCatchup(context: Context, channel: ChannelEntity, programme: ProgrammeEntity, url: String) = startVod(
            context,
            VodRequest(
                key = "catchup:${channel.key}:${programme.start}",
                kind = HistoryKind.CATCHUP,
                title = programme.headline().ifEmpty { channel.name },
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

/** Wie Untertitel aussehen (aus den Einstellungen). */
data class SubtitleLook(
    val color: SubtitleColor = SubtitleColor.WHITE,
    val background: SubtitleBackground = SubtitleBackground.OUTLINE,
    val position: SubtitlePosition = SubtitlePosition.NORMAL,
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

