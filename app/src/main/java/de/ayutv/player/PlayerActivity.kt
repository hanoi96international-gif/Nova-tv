@file:OptIn(UnstableApi::class)

package de.ayutv.player

import de.ayutv.data.parse.headline
import de.ayutv.data.parse.sportHeadline
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
import de.ayutv.data.parse.pairing
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
    /** Wiedergabefehler dieser Sitzung (Neustarts des Players), für Diagnose und Tests. */
    var playerErrors = 0; private set
    /** Alle Statusmeldungen dieser Sitzung (für Diagnose und Tests). */
    val statusHistory = mutableListOf<String>()
    val onlineSubtitlesAvailable: Boolean get() = !isLive && ::settings.isInitialized && settings.onlineSubtitlesEnabled

    private var vod: VodRequest? = null
    /** Zuletzt gesehener anderer Sender: ◀ springt dorthin zurück (wie die Zurück-Taste am Fernseher). */
    var previousChannel by mutableStateOf<ChannelEntity?>(null); private set
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
    /** Ton, den die App nebenbei selbst dekodiert (wenn er als Dolby/DTS an die Soundbar geht), 20–30 s voraus. */
    private val aheadSpeech = SpeechActivity()
    private val audioAhead = AudioAhead(aheadSpeech)
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
    /** Zeile für Zeile an den eingebetteten Untertiteln ausgerichtet (null = noch nicht oder nicht möglich). */
    private var elasticTiming: ElasticTiming? = null
    /** Geladene Datei automatisch gegen die Übersetzung der Spur tauschen, wenn sie zu schlecht passt. */
    private var autoSwitch = false
    private var autoSwitchNext = false
    private var sourceDecided = false
    /** Wie gut die geladene Datei zur Spur des Films passt, in Prozent (null = noch nicht gemessen). */
    var matchPercent by mutableStateOf<Int?>(null); private set
    private var elasticJob: Job? = null
    /** Sprachen, für die schon weitere Dateien verglichen wurden (je Film einmal, das kostet Downloads). */
    private val comparedLanguages = HashSet<String>()
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
    /** Serien: Intro und Rückblick überspringen, nächste Folge ab dem Abspann ([EpisodeExtras]). */
    private val episodes = EpisodeExtras(object : EpisodeExtras.Host {
        override val player get() = this@PlayerActivity.player
        override val vod get() = this@PlayerActivity.vod
        override val positionMs get() = this@PlayerActivity.positionMs
        override val durationMs get() = this@PlayerActivity.durationMs
        override val everReady get() = this@PlayerActivity.everReady
        override val scope get() = lifecycleScope
        override val container get() = this@PlayerActivity.container
        override val autoNextEpisode get() = settings.autoNextEpisode
        override fun levels(fromMs: Long, toMs: Long) = introLevels(fromMs, toMs)
        override fun lastSubtitleEnd(): Long? = externalCues.lastOrNull()?.let { last ->
            (elasticTiming?.toVideo(last.end) ?: subtitleTiming.toVideo(last.end)) + subtitleDelayMs
        }
        override fun seekExact(to: Long) {
            val p = this@PlayerActivity.player ?: return
            seekExact(p, to)
            this@PlayerActivity.positionMs = to
        }
        override fun showStatus(text: String) { subtitleStatus = text }
        override fun closeOverlay() = this@PlayerActivity.closeOverlay()
        override fun startEpisode(next: ContentRepository.NextEpisode) {
            saveProgress()
            PlayerActivity.startVod(
                this@PlayerActivity,
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
    })
    val nextEpisode get() = episodes.nextEpisode
    val nextCardVisible get() = episodes.nextCardVisible
    val nextCountdown get() = episodes.nextCountdown
    val introVisible get() = episodes.introVisible
    val introLabel get() = episodes.introLabel
    fun playNextEpisode() = episodes.playNext()
    private var liveSourceLanguage: String? = null
    /** Übersetzung eingebetteter Untertitel mit Vorlauf: Quellspur, Ergebnisse je Einblendung (Startzeit). */
    private var aheadFormat: Format? = null
    private val aheadTranslations = HashMap<Long, String>()
    /** Beginn der Zeilen, die schon vor diesem Schauen übersetzt waren (früher geschaut, nachts vorbereitet). */
    @Volatile private var storedAhead: Set<Long> = emptySet()
    private var aheadJob: Job? = null
    /** Kurzer Vorlauf vor der Übersetzung: Anteil (0–1), solange der Film dafür angehalten ist, sonst null. */
    var prerollProgress by mutableStateOf<Float?>(null); private set
    private var prerollJob: Job? = null
    /** Der Film lief vor dem Vorlauf (dann danach weiter abspielen). */
    private var prerollResume = false
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
        // Proxy an: einmal nachsehen, mit welcher IP und welchem Land die Streams laufen (für die Stream-Info).
        if (de.ayutv.data.net.AppProxy.current.active && de.ayutv.data.net.ProxyCheck.last?.proxied == null) {
            lifecycleScope.launch { runCatching { de.ayutv.data.net.ProxyCheck.run(container.http, de.ayutv.data.net.AppProxy.current) } }
        }
        frameRateMatcher = FrameRateMatcher(this)
        frameRateMatcher.onSwitch = ::onDisplaySwitch
        vod = intent.getVodRequest()
        isLive = vod == null

        if (hasTouchscreen()) hideSystemBars()
        setContent { AyuTheme { FitToScreen { PlayerScreen(this) } } }

        // Anstoß eines gefolgten Vereins (oder andere Erinnerung), während hier etwas läuft: einblenden.
        lifecycleScope.launch {
            container.dueReminder.collect { reminder ->
                if (reminder == null) return@collect
                container.dueReminder.value = null
                if (reminder.channelKey == playing?.key) return@collect
                showReminder(reminder)
            }
        }

        lifecycleScope.launch {
            settings = container.settings.current()
            // Einmaliges Laden eines Sprachmodells für die Übersetzung auf dem Gerät anzeigen.
            Translators.remote.onProgress = { text -> runOnUiThread { subtitleStatus = text } }
            subtitleScale = settings.subtitleSize.scale
            subtitleLook = SubtitleLook(settings.subtitleColor, settings.subtitleBackground, settings.subtitlePosition)
            audioMode = settings.audioMode
            enhance.mode = settings.audioMode
            enhance.leveling = settings.loudnessLevel
            loudnessLevel = settings.loudnessLevel
            textDisabled = vod != null && !settings.subtitlesAutoOn
            installPlayer(settings.asyncDecoding, settings.audioOutput == AudioOutput.PCM)
            startWatchdog()
            startGoalWatch()

            val request = vod
            if (request != null) {
                vodTitle = request.title
                val handedOver = intent.getLongExtra(EXTRA_START_AT, 0L)
                val resume = when {
                    handedOver > 0 -> handedOver // vom Handy übergeben: an derselben Stelle weiter
                    intent.getBooleanExtra(EXTRA_FROM_START, false) -> 0L
                    else -> container.content.resumePosition(request.key)
                }
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
                    group != null -> container.content.groupLabel(group)
                    else -> "Alle Sender"
                }
                // Erst den Sender starten, dann die (evtl. zehntausende Einträge lange) Zapp-Liste laden.
                // Vom Handy übergeben und hier nicht in der Playlist: direkt über die Adresse spielen.
                val handedUrl = intent.getStringExtra(EXTRA_LIVE_URL)
                val direct = key?.let { container.content.channel(it) } ?: handedUrl?.let { url ->
                    ChannelEntity(
                        key = "handy:$url", playlistId = -1, name = intent.getStringExtra(EXTRA_LIVE_NAME) ?: "Vom Handy",
                        groupTitle = "Vom Handy", logo = intent.getStringExtra(EXTRA_LIVE_LOGO), url = url, epgId = null, number = 0,
                        userAgent = intent.getStringExtra(EXTRA_LIVE_AGENT),
                    )
                }
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
        built.subtitles.audio = audioAhead
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
        memoryKey()?.let { container.playerMemory.rememberFrameRate(it, snapped, isLive) }
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

    /** Für die Stream-Info: läuft dieser Stream über den Proxy (mit Land, sobald einmal geprüft) oder direkt? */
    fun routeLabel(): String {
        val host = de.ayutv.data.net.AppProxy.hostOf(originalUrl())
        if (!de.ayutv.data.net.AppProxy.proxies(host)) return "Weg: direkt"
        if (!settings.proxyStreams) return "Weg: direkt (Proxy nur für Listen und Anmeldung)"
        val seen = de.ayutv.data.net.ProxyCheck.last?.proxied
        return "Weg: über Proxy" + (seen?.let { " (${it.country} · ${it.ip})" } ?: "")
    }

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
        // Diese Fassung ist ausgefallen: beim nächsten Mal weiter hinten einsortieren.
        if (isLive) playing?.let { de.ayutv.data.parse.ChannelQuality.recordFailure(it.key) }
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

    /** Seit wann der jetzige Sender läuft und wie viele Wiedergabefehler es da schon gab (für die Stabilitätswertung). */
    private var watchStartedAt = 0L
    private var watchErrorsAtStart = 0

    /** Für „Best of“: wie lange der Sender lief und wie oft dabei die Verbindung abbrach. Erst ab einer Minute. */
    private fun recordWatch() {
        val channel = playing ?: return
        if (!isLive || watchStartedAt == 0L) return
        val watched = SystemClock.elapsedRealtime() - watchStartedAt
        watchStartedAt = 0L
        // Datenrate des Senders merken (ab 20 s Schauen ist die Schätzung eingeschwungen): Bei doppelten Sendern gewinnt
        // bei gleicher Auflösung die Fassung, die der Anbieter mit mehr Daten (schärfer) schickt.
        if (watched >= 20_000 && stats.bandwidth > 0) de.ayutv.data.parse.ChannelQuality.recordBitrate(channel.key, (stats.bandwidth / 1000).toInt())
        if (watched < 60_000) return
        de.ayutv.data.parse.ChannelQuality.recordWatch(channel.key, watched, timeshift.drops.count + (playerErrors - watchErrorsAtStart))
    }

    // ---------------------------------------------------------------- Schnellumschalter

    /** Leiste mit zuletzt gesehenen Sendern und Favoriten (◀ halten); null = zu. */
    var quickZap by mutableStateOf<List<ChannelEntity>?>(null); private set
    var quickIndex by mutableIntStateOf(0); private set
    private var quickOpenedThisPress = false
    private var quickHide: kotlinx.coroutines.Job? = null

    private fun openQuickZap() {
        lifecycleScope.launch {
            val current = playing?.key
            val recent = runCatching { container.db.history().recentLiveKeys(20) }.getOrDefault(emptyList())
                .mapNotNull { container.db.channels().byKey(it) }
            val favorites = runCatching { container.content.zapList(null, favoritesOnly = true, merge = settings.mergeVariants) }.getOrDefault(emptyList())
            val list = (listOfNotNull(previousChannel) + recent + favorites).filter { it.key != current }.distinctBy { it.key }.take(16)
            if (list.isEmpty()) {
                openChannelList()
                return@launch
            }
            quickZap = list
            quickIndex = 0
            armQuickHide()
        }
    }

    private fun armQuickHide() {
        quickHide?.cancel()
        quickHide = lifecycleScope.launch {
            delay(6_000)
            quickZap = null
        }
    }

    /** Tasten, solange die Schnellwahl offen ist: ◀ ▶ wählen, OK schaltet um, Zurück schließt. */
    private fun handleQuickKey(event: KeyEvent): Boolean {
        val list = quickZap ?: return false
        val code = event.keyCode
        if (event.action != KeyEvent.ACTION_DOWN) {
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) quickOpenedThisPress = false
            return code in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK)
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (!quickOpenedThisPress) quickIndex = (quickIndex - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT -> quickIndex = (quickIndex + 1).coerceAtMost(list.size - 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val target = list.getOrNull(quickIndex)
                quickZap = null
                quickHide?.cancel()
                target?.let { playChannel(it) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                quickZap = null
                quickHide?.cancel()
                return true
            }
            else -> {
                // Andere Tasten (Umschalten, Menü …) schließen die Leiste und wirken wie gewohnt.
                quickZap = null
                return false
            }
        }
        armQuickHide()
        return true
    }

    /** Zum vorherigen Sender; null, wenn es keinen gibt. */
    fun backZap(): Unit? {
        val previous = previousChannel?.takeIf { it.key != playing?.key } ?: return null
        playChannel(previous)
        message = "◀ Zurück: ${previous.name}"
        handler.postDelayed({ if (message?.startsWith("◀ Zurück") == true) message = null }, 2_500)
        return Unit
    }

    fun playChannel(channel: ChannelEntity) {
        val p = player ?: return
        recordWatch()
        watchStartedAt = SystemClock.elapsedRealtime()
        watchErrorsAtStart = playerErrors
        if (!switchingVariant) failedVariants.clear()
        // Beim Ausweichen auf eine andere Fassung bleibt der Sender derselbe: kein „vorheriger Sender“.
        if (!switchingVariant) playing?.takeIf { it.key != channel.key }?.let { previousChannel = it }
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
        matchKnownFrameRate(channel.key, live = true)
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
        pipChoices = null
    }

    private fun onDigit(digit: Int) {
        numberInput = (numberInput + digit).takeLast(4)
        numberJob?.cancel()
        // Kann keine Sendernummer mit dieser Eingabe weitergehen, sofort umschalten statt 1,5 s zu warten.
        val typed = numberInput
        val custom = container.channelNumbers.all.value.values.map { it.number }
        val canGrow = channels.isEmpty() || (channels.map { it.number } + custom).any { n ->
            n.toString().let { it.length > typed.length && it.startsWith(typed) }
        }
        numberJob = lifecycleScope.launch {
            if (canGrow) delay(1_500)
            val number = numberInput.toIntOrNull()
            numberInput = ""
            // Eigene Sendernummern zuerst: Die Playlist kann dieselbe Nummer noch für einen anderen Sender haben.
            val target = number?.let { n ->
                container.channelNumbers.keyFor(n)?.let { key -> channels.firstOrNull { it.key == key } ?: container.content.channel(key) }
                    ?: channels.firstOrNull { it.number == n } ?: container.content.channelByNumber(n)
            }
            if (target != null) playChannel(target) else message = "Kein Sender mit Nummer $number"
        }
    }

    /**
     * Bildwiederholrate schon beim Start umschalten, solange der Stream noch lädt: mit der gemerkten Bildrate dieses
     * Senders oder Films (bei einem neuen Sender die des zuletzt geschauten). Sonst schaltet der Fernseher erst um,
     * wenn das Bild schon läuft (bei Sendern ohne Angabe nach einigen Sekunden Messung), und wird dann schwarz.
     * Stimmt die Schätzung nicht, korrigiert die Messung wie bisher.
     */
    private fun matchKnownFrameRate(key: String, live: Boolean) {
        if (!settings.matchFrameRate) return
        val memory = container.playerMemory
        val fps = memory.frameRate(key).takeIf { it > 0f } ?: if (live) memory.lastLiveFrameRate else 0f
        if (fps > 0f) frameRateMatcher.match(fps)
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
        if (heardSpeechKey != request.key) {
            heardSpeech.clear()
            audioAhead.reset()
        }
        heardSpeechKey = request.key
        loadedLanguages = subtitleFiles.keys.toList()
        p.setMediaItem(vodItem(request), resumeMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        matchKnownFrameRate(Episodes.seriesKeyOf(request.key) ?: request.key, live = false)
        showOverlay(Overlay.CONTROLS)
        if (request.kind == HistoryKind.EPISODE) episodes.prepare(request)
        checkMobileData()
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                if (pendingSeekMs == C.TIME_UNSET) positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (request.kind == HistoryKind.EPISODE) episodes.update(request)
                if (++tick % 15 == 0) saveProgress()
                delay(1_000)
            }
        }
    }

    // ---------------------------------------------------------------- Serien

    /** Gehörte Lautstärke und, wo nichts gehört wurde (Ton an der Soundbar), die selbst dekodierte. */
    private fun introLevels(fromMs: Long, toMs: Long): FloatArray {
        val heard = heardSpeech.levels(fromMs, toMs)
        if (aheadSpeech.seconds() == 0) return heard
        val ahead = aheadSpeech.levels(fromMs, toMs)
        return FloatArray(maxOf(heard.size, ahead.size)) { i ->
            heard.getOrNull(i)?.takeUnless { it.isNaN() } ?: ahead.getOrNull(i) ?: Float.NaN
        }
    }

    /**
     * Folgen, deren Ton an Soundbar oder Receiver geht: ihn nebenbei selbst dekodieren (nur die ersten Minuten, fürs
     * Intro). Wird er im Gerät dekodiert, hört die App ohnehin mit.
     */
    private fun updateAudioAhead() {
        val format = player?.audioFormat
        val wanted = forcedAhead || vod?.kind == HistoryKind.EPISODE && format != null && passesThrough(format)
        audioAhead.untilMs = EpisodeExtras.OPENING_MS + EpisodeExtras.INTRO_SEARCH_AHEAD_MS
        if (wanted != audioAhead.active) {
            audioAhead.active = wanted
            Log.i(TAG, if (wanted) "Ton geht an Soundbar/Receiver: werte ihn nebenbei aus (${format?.sampleMimeType})" else "Ton-Auswertung nebenbei aus")
        }
    }

    private var forcedAhead = false

    /** Gerätetest: Ton-Auswertung nebenbei erzwingen (im Emulator geht kein Ton an eine Soundbar). */
    fun forceAudioAhead() {
        forcedAhead = true
        updateAudioAhead()
    }

    /** So viele Sekunden Ton hat die Auswertung nebenbei schon (für Test und Fehlerbericht). */
    val aheadSeconds: Int get() = aheadSpeech.seconds()

    private fun passesThrough(format: Format): Boolean {
        if (settings.audioOutput == AudioOutput.PCM) return false
        val mime = format.sampleMimeType ?: return false
        val encoding = MimeTypes.getEncoding(mime, format.codecs)
        if (encoding == C.ENCODING_INVALID || androidx.media3.common.util.Util.isEncodingLinearPcm(encoding)) return false
        return runCatching {
            androidx.media3.exoplayer.audio.AudioCapabilities
                .getCapabilities(this, androidx.media3.common.AudioAttributes.DEFAULT, null)
                .isPassthroughPlaybackSupported(format, androidx.media3.common.AudioAttributes.DEFAULT)
        }.getOrDefault(false)
    }

    /** Genau an [to] springen (sonst springt der Film zum nächsten Schlüsselbild, bei Serien oft Sekunden daneben). */
    private fun seekExact(p: ExoPlayer, to: Long) {
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
        p.seekTo(to)
        p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
    }

    /**
     * Nur wenn "Untertitel automatisch an" eingestellt ist: Fehlt eine eingebettete Spur in der
     * bevorzugten Sprache, wird sie geladen (OpenSubtitles, sonst Übersetzung).
     */
    private fun maybeAutoSubtitles() {
        if (vod == null || autoSubtitleTried) return
        // Serie: Untertitel, die man bei einer Folge geladen hat, kommen bei der nächsten von selbst.
        val remembered = memoryKey()?.let { container.playerMemory.get(it) }?.takeUnless { it.textOff }?.load
        if (!settings.subtitlesAutoOn && remembered == null) return
        autoSubtitleTried = true
        val language = remembered ?: settings.subtitleLanguage
        if (remembered != null) {
            loadSubtitles(language)
            return
        }
        if (hasTextTrack(language)) return
        if (language in subtitleFiles || settings.onlineSubtitlesEnabled || settings.translationEnabled) loadSubtitles(language)
    }

    /**
     * Untertitel in [language] einschalten, ohne dass etwas eingerichtet sein muss. Reihenfolge:
     * 1. eingebettete Spur in der Sprache, 2. eine vollständige eingebettete Spur übersetzen (auch
     * Bild-Untertitel, per Texterkennung; immer zeitgenau, auf jedem Gerät), nur ohne solche Spur:
     * 3. gespeicherte Datei oder OpenSubtitles (automatisch ausgerichtet), 4. erzwungene eingebettete
     * Untertitel übersetzen, 5. eine Online-Vorlage vorausschauend übersetzen, 6. ehrlich sagen, woran es fehlt.
     */
    /**
     * Untertitel in [language]. [via]: von Hand gewählte Quelle ([PlayerMemory.SOURCE_FILE] = OpenSubtitles,
     * [PlayerMemory.SOURCE_TRANSLATE] = Spur des Films übersetzen), wird je Film bzw. Serie gemerkt.
     */
    fun loadSubtitles(language: String, via: String? = null) {
        val request = vod
        if (request != null) memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(text = null, load = language) } }
        if (request != null && via != null) memoryKey()?.let { container.playerMemory.setSubtitleSource(it, via) }
        val chosen = via ?: memoryKey()?.let { container.playerMemory.subtitleSource(it) }
        val decided = request?.let { container.playerMemory.subtitleSource(PlayerMemory.autoKey(it.key)) }
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
            // 2. Eingebettete Untertitel einer anderen Sprache übersetzen (auch Bild-Untertitel per Texterkennung), wenn
            //    so gewählt oder für diesen Film schon entschieden, und bei Sendern. Englisch geht vor (direkte Modelle).
            //    Kurzer Vorlauf: Die erste Minute wird übersetzt, bevor es weitergeht.
            val embeddedSource = if (settings.translationEnabled) bestTranslationSource(allowForced = false) else null
            val translate = chosen == PlayerMemory.SOURCE_TRANSLATE ||
                chosen == null && decided == PlayerMemory.SOURCE_TRANSLATE || request == null
            if (embeddedSource != null && translate) {
                startLiveTranslation(language, embeddedSource, preroll = true)
                return@launch
            }
            // 3. OpenSubtitles zuerst (menschlich übersetzt): Datei laden und an der Spur des Films ausrichten. Passt sie
            //    nach dem Abgleich unter [FIT_PERCENT], wird auf die Übersetzung der Spur gewechselt (nur mit DeepL).
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
                    autoSwitchNext = embeddedSource != null && chosen != PlayerMemory.SOURCE_FILE
                    attachLoaded(language, found)
                    return@launch
                }
            }
            if (embeddedSource != null) {
                startLiveTranslation(language, embeddedSource, preroll = true)
                if (chosen == PlayerMemory.SOURCE_FILE) subtitleStatus = "Keine Datei bei OpenSubtitles – übersetze stattdessen die Spur des Films"
                return@launch
            }
            if (!settings.translationEnabled) {
                subtitleStatus = onlineProblem ?: "Keine Untertitel in $label gefunden (Übersetzung ist in den Einstellungen aus)"
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
        // Rechnet das Handy mit oder übersetzt DeepL, braucht der Fernseher das Modell nicht.
        if (de.ayutv.data.translate.PhoneHelper.usable) return
        if (settings.translationProvider == de.ayutv.data.TranslationProvider.DEEPL && settings.deeplKey.isNotBlank() &&
            de.ayutv.data.translate.DeepLTranslator.targetCode(target) != null
        ) return
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
    fun startLiveTranslation(target: String, source: Pair<Tracks.Group, Int>? = bestTranslationSource(), preroll: Boolean = false) {
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
        if (preroll && vod != null && !isLive) startPreroll(format, thorough = onlineTranslation())
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
        saveAheadTranslations()
        aheadTranslations.clear()
        aheadDone.clear()
        storedAhead = emptySet()
        aheadFormat = format
        val request = vod
        aheadKey = request?.let { Triple(it.key, EmbeddedSubtitles.trackKey(format), target) }
        val translator = Translators.from(settings) ?: return
        val bitmap = format.isBitmapSubtitle()
        if (bitmap) {
            embedded?.readImagesOf(format)
            // Bilder der schon gepufferten Minute wurden nicht gelesen: ab hier neu laden (kurzes Nachpuffern).
            if (!isLive) player?.let { it.seekTo(it.currentPosition) }
        }
        aheadJob = lifecycleScope.launch {
            // Schon früher übersetzte Zeilen dieser Spur (Zurückspulen, erneutes Schauen): sofort da, nichts neu rechnen.
            aheadKey?.let { (key, track, language) ->
                val stored = withContext(Dispatchers.IO) { container.subtitles.lineTranslations(key, track, language) }
                aheadTranslations.putAll(stored)
                aheadDone.putAll(stored)
                storedAhead = stored.keys
                if (stored.isNotEmpty()) Log.i(SUBTITLE_TAG, "${stored.size} schon übersetzte Zeilen geladen")
            }
            var unsaved = 0
            launch {
                while (true) {
                    val store = embedded
                    if (store != null && store.hasEntries(format)) {
                        val position = player?.currentPosition ?: 0L
                        val entry = store.at(format, position)
                        val translated = entry?.let { aheadTranslations[it.startMs]?.ifEmpty { null } }
                        // Noch nicht übersetzt: das Original zeigen (Text selbst, Bild-Untertitel über den Player)
                        // statt gar nichts, solange der Übersetzer lädt oder nicht hinterherkommt. Mit DeepL kommt die
                        // Übersetzung meist binnen einer Sekunde: so lange nichts zeigen, statt Englisch und dann Deutsch.
                        val waitForOnline = entry != null && translated == null && !bitmap && onlineTranslation() &&
                            position - entry.startMs < ONLINE_HOLD_MS
                        translatedCue = translated ?: if (bitmap || waitForOnline) null else entry?.text
                        originalVisible = bitmap && entry != null && translated == null
                    }
                    delay(100)
                }
            }
            // Namen der Serie (aus früheren Folgen gemerkt), laufend aus den gesehenen Zeilen ergänzt.
            val namesKey = request?.key?.let { de.ayutv.data.parse.Episodes.seriesKeyOf(it) ?: it }
            val seriesNames = de.ayutv.data.translate.SeriesNames(this@PlayerActivity)
            var names = namesKey?.let { seriesNames.get(it) }.orEmpty()
            var smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
            val seen = ArrayList<String>()
            // Vorherige Zeile mit Übersetzung und Ende: Zusammenhang für eine kurze nächste Zeile.
            var lastLine: Triple<String, String, Long>? = null
            var failures = 0
            var hangs = 0
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
                // Übersetzer im Internet (DeepL): alle schon gelesenen Zeilen auf einmal schicken statt jede einzeln,
                // so ist die nächste Minute mit einer Anfrage (unter einer Sekunde) übersetzt.
                if (translator is de.ayutv.data.translate.DeepLTranslator && de.ayutv.data.translate.DeepLTranslator.problem == null &&
                    de.ayutv.data.translate.DeepLTranslator.targetCode(target) != null
                ) {
                    val batch = store.upcoming(format, earliest, ONLINE_BATCH) { wanted(it) && !it.text.isNullOrBlank() }
                    if (batch.size > 1) {
                        val done = runCatching { translateBatch(batch, smart, target, lastLine?.let { (src, tr, _) -> src to tr }) }
                            .onFailure { Log.w(SUBTITLE_TAG, "Übersetzung im Block fehlgeschlagen", it) }.getOrNull()
                        if (done != null) {
                            lastLine = done
                            if (++unsaved >= 10) {
                                unsaved = 0
                                saveAheadTranslations()
                            }
                            de.ayutv.data.translate.DeepLTranslator.problem?.let { subtitleStatus = "$it – übersetze auf dem Gerät (langsamer)" }
                            continue
                        }
                    }
                }
                try {
                    val started = SystemClock.elapsedRealtime()
                    val text = next.text ?: next.image?.let { image -> Translators.remote.recognize(image).also { next.text = it; next.image = null } }.orEmpty()
                    // Geht der Satz in den nächsten Einblendungen weiter, alle zusammen übersetzen und wieder aufteilen.
                    val chain = if (text.isBlank() || bitmap) emptyList() else sentenceAfter(store, format, next, text)
                    if (text.isNotBlank() && !bitmap) {
                        seen += text
                        if (seen.size % 25 == 0 && namesKey != null) {
                            val learned = seriesNames.add(namesKey, de.ayutv.data.translate.TranslationNames.learn(seen))
                            if (learned != names) {
                                names = learned
                                smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
                            }
                        }
                    }
                    val before = lastLine?.takeIf { (_, _, end) -> next.startMs - end in 0..CONTEXT_GAP_MS }?.let { (src, tr, _) -> src to tr }
                    if (text.isBlank()) {
                        aheadTranslations[next.startMs] = ""
                    } else if (chain.isEmpty()) {
                        aheadTranslations[next.startMs] = smart.translate(listOf(text), target, sourceFor(text), before).first()
                        lastLine = Triple(text, aheadTranslations.getValue(next.startMs), if (next.endMs >= 0) next.endMs else next.startMs + ASSUMED_LINE_MS)
                    } else {
                        val all = listOf(next) + chain
                        val texts = all.map { it.text.orEmpty() }
                        val sentence = smart.translate(listOf(texts.joinToString(" ") { de.ayutv.data.translate.SubtitleSentences.plain(it) }), target, sourceFor(text), before).first()
                        val lastEntry = all.last()
                        lastLine = Triple(texts.joinToString(" "), sentence, if (lastEntry.endMs >= 0) lastEntry.endMs else lastEntry.startMs + ASSUMED_LINE_MS)
                        val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, texts.map { it.length })
                        all.forEachIndexed { k, entry ->
                            val part = de.ayutv.data.translate.SubtitleSentences.wrap(parts[k])
                            aheadTranslations[entry.startMs] = part
                            if (k > 0) aheadDone[entry.startMs] = part
                        }
                    }
                    val took = SystemClock.elapsedRealtime() - started
                    if (text.isNotBlank()) {
                        aheadDone[next.startMs] = aheadTranslations.getValue(next.startMs)
                        if (++unsaved >= 10) {
                            unsaved = 0
                            saveAheadTranslations()
                        }
                    }
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
                    // Übersetzer hängt (vom System beendet und lädt neu, oder belegt): neu starten und weiter übersetzen,
                    // diese Zeile im Original. Erst wenn er auch nach zwei Neustarts nicht antwortet, das Original zeigen.
                    Log.w(SUBTITLE_TAG, "Übersetzer antwortet nicht", e)
                    // Nicht im Vorlauf stehen bleiben, solange der Übersetzer neu startet: Der Film läuft weiter.
                    endPreroll()
                    Translators.remote.abort(e.message ?: "keine Antwort")
                    aheadTranslations[next.startMs] = next.text.orEmpty()
                    if (++hangs > MAX_TRANSLATOR_RESTARTS) {
                        showOriginalInstead(format, "${e.message}. Test: Einstellungen → Untertitel → Übersetzer testen")
                        break
                    }
                    subtitleStatus = "Übersetzer hing, startet neu ($hangs/$MAX_TRANSLATOR_RESTARTS) – solange Original"
                    lineMs = 0L
                } catch (e: Exception) {
                    Log.w(SUBTITLE_TAG, "Übersetzung mit Vorlauf fehlgeschlagen", e)
                    // Übersetzer ausgefallen (z. B. vom System beendet): nicht im Vorlauf warten, der Film läuft weiter.
                    endPreroll()
                    // Diese Zeile im Original zeigen. Hakt es öfter, kurz durchatmen (Speicher, Modell lädt neu) und weiter;
                    // erst wenn es dauerhaft nicht geht, die ganze Spur im Original, nie eine heruntergeladene Datei.
                    aheadTranslations[next.startMs] = next.text.orEmpty()
                    failures++
                    if (failures >= 6) {
                        showOriginalInstead(format, e.message ?: e.javaClass.simpleName)
                        break
                    }
                    if (failures == 3) delay(15_000)
                }
            }
        }
    }

    /**
     * Mehrere Zeilen in einem Aufruf übersetzen (Übersetzer im Internet): satzweise zusammengefasst und wieder auf die
     * Einblendungen verteilt, wie beim nächtlichen Vorbereiten. Liefert die letzte Zeile als Zusammenhang für die nächste.
     */
    private suspend fun translateBatch(
        batch: List<EmbeddedSubtitles.Entry>,
        smart: de.ayutv.data.translate.SubtitleTranslator,
        target: String,
        before: Pair<String, String>?,
    ): Triple<String, String, Long> {
        val cues = batch.map { de.ayutv.data.translate.SubtitleSentences.Cue(it.startMs, if (it.endMs >= 0) it.endMs else it.startMs + ASSUMED_LINE_MS, it.text.orEmpty()) }
        val groups = de.ayutv.data.translate.SubtitleSentences.groups(cues)
        val texts = groups.map { de.ayutv.data.translate.SubtitleSentences.joined(cues, it) }
        val result = smart.translate(texts, target, sourceFor(cues.first().text), before)
        groups.forEachIndexed { k, range ->
            val sentence = result.getOrNull(k) ?: return@forEachIndexed
            val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, range.map { de.ayutv.data.translate.SubtitleSentences.plain(cues[it].text).length })
            range.forEachIndexed { j, index ->
                val part = de.ayutv.data.translate.SubtitleSentences.wrap(parts[j])
                aheadTranslations[cues[index].startMs] = part
                aheadDone[cues[index].startMs] = part
            }
        }
        Log.i(SUBTITLE_TAG, "Block: ${batch.size} Zeilen in einem Aufruf übersetzt")
        return Triple(texts.last(), result.lastOrNull().orEmpty(), cues.last().endMs)
    }

    /** Folgende Text-Einblendungen, in denen der Satz von [first] weitergeht (höchstens zwei, noch nicht übersetzt). */
    private fun sentenceAfter(store: EmbeddedSubtitles, format: Format, first: EmbeddedSubtitles.Entry, text: String): List<EmbeddedSubtitles.Entry> {
        val chain = ArrayList<EmbeddedSubtitles.Entry>()
        var previous = first
        var previousText = text
        for (entry in store.upcoming(format, first.startMs + 1, 2) { true }) {
            val next = entry.text ?: break
            if (entry.startMs in aheadTranslations) break
            val gap = if (previous.endMs >= 0) entry.startMs - previous.endMs else entry.startMs - previous.startMs - ASSUMED_LINE_MS
            if (gap > 1_500 || !de.ayutv.data.translate.SubtitleSentences.continues(previousText, next)) break
            chain += entry
            previous = entry
            previousText = next
        }
        return chain
    }

    /**
     * Kurzer Vorlauf (höchstens [PREROLL_MAX_MS]): Der Film hält an, bis die Zeilen der nächsten Minute übersetzt
     * sind, dann bleibt die Übersetzung beim Schauen vorn. Der Player puffert währenddessen weiter, so liegen die
     * Zeilen schon vor. Was schon übersetzt ist (über Nacht, früheres Schauen), braucht keinen Vorlauf.
     * OK oder Zurück: sofort weiterschauen.
     */
    private fun startPreroll(format: Format, thorough: Boolean = false) {
        endPreroll()
        val p = player ?: return
        val from = p.currentPosition
        val until = from + PREROLL_WINDOW_MS
        prerollResume = p.playWhenReady
        prerollJob = lifecycleScope.launch {
            // Gespeicherte Übersetzungen laden lassen: Sind sie schon da, geht es ohne Halt weiter.
            delay(400)
            val started = SystemClock.elapsedRealtime()
            var paused = false
            while (isActive) {
                val player = this@PlayerActivity.player ?: break
                val store = embedded
                val loadedUntil = minOf(until, player.bufferedPosition)
                // Puffer voll (lädt nicht mehr) oder Fenster geladen: mehr Zeilen kommen vorerst nicht dazu.
                val loaded = loadedUntil >= until || !player.isLoading && player.bufferedPosition > from
                // Ab der aktuellen Stelle: Schon vorbeigezogene Zeilen (übersprungen) halten nichts mehr auf.
                val lines = store?.upcoming(format, maxOf(from, player.currentPosition), 400) { it.startMs < loadedUntil }.orEmpty()
                val done = lines.count { it.startMs in aheadTranslations }
                val elapsed = SystemClock.elapsedRealtime() - started
                if (thorough) {
                    // Übersetzer im Internet: 8–10 s anhalten. In der Zeit lädt der Player weit voraus und DeepL
                    // übersetzt alles Geladene; danach bleibt die Übersetzung beim Schauen sicher vorn. Nur wenn
                    // die Stelle schon übersetzt ist (früher geschaut, nachts vorbereitet), geht es ohne Halt weiter.
                    // (Gespeicherte Übersetzungen kommen kurz nach dem Start aus der Datenbank.)
                    if (elapsed < 2_000 && prepared(player.currentPosition)) break
                    val soon = player.currentPosition + ONLINE_LEAD_MS
                    val missing = lines.any { it.startMs !in aheadTranslations && it.startMs < soon }
                    val ahead = loaded || player.bufferedPosition >= soon
                    if (paused && elapsed >= ONLINE_PAUSE_MIN_MS && !missing && ahead || elapsed >= ONLINE_PAUSE_MAX_MS) break
                } else if ((loaded && done >= lines.size) || elapsed >= PREROLL_MAX_MS) {
                    break
                } else if (!paused && (done >= lines.size || elapsed > PREROLL_DECIDE_MS)) {
                    // Nur anhalten, wenn wirklich Zeilen fehlen, und nur gleich zu Beginn: Ist alles Bekannte schon
                    // übersetzt (über Nacht vorbereitet), läuft der Film einfach weiter, statt mitten drin zu stoppen.
                    if (done >= lines.size && elapsed <= PREROLL_DECIDE_MS) {
                        delay(250)
                        continue
                    }
                    break
                }
                if (!paused) {
                    paused = true
                    player.playWhenReady = false
                }
                val coverage = ((loadedUntil - from).toFloat() / PREROLL_WINDOW_MS).coerceIn(0f, 1f)
                val share = if (lines.isEmpty()) 0f else done.toFloat() / lines.size
                val byTime = elapsed.toFloat() / (if (thorough) ONLINE_PAUSE_MIN_MS else PREROLL_MAX_MS)
                prerollProgress = maxOf(byTime, coverage * 0.3f + share * coverage * 0.7f).coerceIn(0.02f, 0.99f)
                delay(250)
            }
            if (paused) {
                Log.i(SUBTITLE_TAG, "Vorlauf fertig nach ${SystemClock.elapsedRealtime() - started} ms")
                endPreroll()
            } else {
                prerollProgress = null
                prerollJob = null
            }
        }
    }

    /** Sind die nächsten Minuten ab [positionMs] schon übersetzt (früher geschaut, nachts vorbereitet)? */
    private fun prepared(positionMs: Long): Boolean =
        storedAhead.count { it in positionMs..positionMs + PREPARED_WINDOW_MS } >= PREPARED_MIN_LINES

    /** Übersetzt gerade ein Dienst im Internet (DeepL, ohne Störung)? Dann lohnt kurzes Warten auf die Übersetzung. */
    private fun onlineTranslation(): Boolean =
        settings.translationProvider == de.ayutv.data.TranslationProvider.DEEPL && settings.deeplKey.isNotBlank() &&
            de.ayutv.data.translate.DeepLTranslator.problem == null

    /** Vorlauf beenden und den Film weiterlaufen lassen (falls er vorher lief). */
    fun endPreroll() {
        val job = prerollJob ?: return
        prerollJob = null
        job.cancel()
        if (prerollProgress != null) {
            prerollProgress = null
            if (prerollResume) player?.playWhenReady = true
        }
    }

    /**
     * Übersetzer streikt: die eingebettete Spur im Original zeigen (zeitgenau). Nie auf eine heruntergeladene
     * Datei wechseln, die kann zu einer anderen Fassung gehören. Die gewählte Sprache bleibt gemerkt.
     */
    private fun showOriginalInstead(format: Format, reason: String) {
        val track = textTracks().firstOrNull { (g, i) -> g.getTrackFormat(i).let { it == format || it.id != null && it.id == format.id } }
        stopLiveTranslation()
        val p = player
        if (track != null && p != null) {
            val (group, index) = track
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                .build()
            textDisabled = false
        }
        val original = Languages.normalize(format.language)?.let { Languages.label(it) } ?: "Original"
        subtitleStatus = "Übersetzung gerade nicht möglich ($reason). Zeige die eingebetteten Untertitel ($original). Neu versuchen: ☰ → Untertitel"
    }

    /** Ehrlich sagen, dass das Gerät nicht mitkommt, und was stattdessen hilft. */
    private fun slowTranslationHint(target: String, lineMs: Long): String {
        val seconds = String.format(Locale.GERMAN, "%.1f", lineMs / 1000f)
        val tip = if (vod != null) " Über Nacht vorbereitet ist alles übersetzt: Film in „Meine Liste“ legen." else ""
        return "Dieses Gerät ist für Live-Übersetzung zu langsam ($seconds s pro Zeile). Ein Teil der Zeilen bleibt im Original.$tip"
    }

    /** Erfolgreich übersetzte Zeilen der laufenden Live-Übersetzung (werden gespeichert) und wofür (Film, Spur, Sprache). */
    private val aheadDone = HashMap<Long, String>()
    private var aheadKey: Triple<String, String, String>? = null

    private fun saveAheadTranslations() {
        val (key, track, language) = aheadKey ?: return
        if (aheadDone.isEmpty()) return
        val lines = HashMap(aheadDone)
        container.scope.launch(Dispatchers.IO) { container.subtitles.saveLineTranslations(key, track, language, lines) }
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
        fileTranslationInfo = null
        externalJob?.cancel()
        externalJob = null
        elasticJob?.cancel()
        elasticJob = null
        elasticTiming = null
        matchPercent = null
        syncJob?.cancel()
        speechJob?.cancel()
        speechJob = null
        externalCues = emptyList()
        externalTexts = null
        endPreroll()
        aheadJob?.cancel()
        aheadJob = null
        aheadFormat = null
        saveAheadTranslations()
        aheadKey = null
        aheadDone.clear()
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
        autoSwitch = autoSwitchNext
        autoSwitchNext = false
        sourceDecided = false
        manualAnchor = null
        manualSync = false
        seekRecheckFrom = -1L
        alternativeTried = false
        externalCues = cues
        externalTexts = texts
        referenceStarts.clear()
        referenceShowing = false
        elasticTiming = null
        matchPercent = null
        val saved = vod?.let { container.subtitles.timing(it.key, language) }
        subtitleTiming = saved?.let { SubtitleTiming(it.ratio, it.offsetMs) } ?: SubtitleTiming()
        subtitleDelayMs = saved?.manualMs ?: 0L
        // Nur die Bildrate aus der Filmlänge voreingestellt (Versatz 0): der Versatz muss noch gesucht werden.
        subtitleAutoSynced = saved != null && (saved.offsetMs != 0L || saved.manualMs != 0L)
        syncedBySpeech = false
        syncedByReference = false
        externalExact = false
        speechSyncInfo = if (subtitleAutoSynced) "Gespeicherte Ausrichtung, wird am Ton nachgeprüft" else null
        textDisabled = false
        selectSyncReference()
        startSpeechSync()
        startElasticSync(language, cues)
        if (!subtitleAutoSynced) adoptTimingFromOtherLanguage(language, cues)
        externalJob?.cancel()
        externalJob = lifecycleScope.launch {
            while (true) {
                val position = player?.currentPosition ?: 0L
                // Zeilen-Abgleich ohne Anker in der Nähe (gerade weit gesprungen): lieber kurz nichts als falsche Zeilen,
                // bis die Einblendungen dort gelesen und neu ausgerichtet sind (dauert wenige Sekunden).
                if (!elasticCovers(position)) {
                    translatedCue = null
                    delay(100)
                    continue
                }
                val at = subtitleTimeAt(position)
                val shown = externalTexts
                translatedCue = cues.indices.filter { cues[it].start <= at && cues[it].end > at }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString("\n") { shown?.get(it) ?: cues[it].text }
                delay(100)
            }
        }
    }

    /**
     * Ist für diesen Film schon eine andere Sprache ausgerichtet (am Ton, an einer Zeile, per Datei-Hash), deren
     * Zeiten als Taktgeber nehmen: Gesprochen wird in jeder Sprache zur selben Zeit. So passt eine Übersetzung oder
     * eine zweite Sprache sofort, ohne erneut zuzuhören. Aus derselben Vorlage übersetzte Dateien passen exakt.
     */
    private fun adoptTimingFromOtherLanguage(language: String, cues: List<TimedCue>) {
        val request = vod ?: return
        if (cues.size < SubtitleSync.MIN_REFERENCES) return
        lifecycleScope.launch {
            val fit = withContext(Dispatchers.Default) {
                container.subtitles.cachedAll(request.key).filterKeys { it != language }.firstNotNullOfOrNull { (other, file) ->
                    val timing = container.subtitles.timing(request.key, other)?.takeIf { it.offsetMs != 0L || it.manualMs != 0L } ?: return@firstNotNullOfOrNull null
                    val reference = SubtitleTiming(timing.ratio, timing.offsetMs)
                    val starts = parseCues(file).map { reference.toVideo(it.start) + timing.manualMs }
                    SubtitleSync.estimate(starts, cues.map { it.start })?.let { other to it }
                }
            } ?: return@launch
            if (externalLanguage != language || subtitleAutoSynced) return@launch
            val (other, found) = fit
            Log.i(SUBTITLE_TAG, "Ausrichtung von ${Languages.label(other)} übernommen: ${found.timing}")
            applyFit(found.timing, bySpeech = false, toleranceMs = 0)
            speechSyncInfo = "Von den schon ausgerichteten Untertiteln (${Languages.label(other)}) übernommen, wird am Ton nachgeprüft"
        }
    }

    /** Gibt es beim Zeilen-Abgleich einen Anker in der Nähe von [position]? (Ohne Zeilen-Abgleich immer.) */
    private fun elasticCovers(position: Long): Boolean =
        elasticTiming?.let { it.distanceTo(position - subtitleDelayMs) <= ELASTIC_COVER_MS } ?: true

    /** Filmposition → Zeit in der geladenen Datei (automatische und manuelle Verschiebung eingerechnet). */
    private fun subtitleTimeAt(position: Long): Long =
        elasticTiming?.toSubtitle(position - subtitleDelayMs) ?: subtitleTiming.toSubtitle(position - subtitleDelayMs)

    /**
     * Zeile für Zeile ausrichten: Die eingebetteten Untertitel des Films (jede Sprache, auch Bild-Untertitel) liest der
     * Player 30–60 s voraus mit. Jede Zeile der geladenen Datei bekommt den Versatz ihrer Umgebung, so passt eine andere
     * Fassung auch nach fehlenden Szenen oder Werbepausen. Passt die Datei nur mäßig, werden einmal bis zu zwei weitere
     * Dateien derselben Suche verglichen und die beste genommen.
     */
    private fun startElasticSync(language: String, cues: List<TimedCue>) {
        elasticJob?.cancel()
        val starts = cues.map { it.start }
        elasticJob = lifecycleScope.launch {
            var lastCount = 0
            delay(500)
            while (isActive && externalLanguage == language) {
                val references = embedded?.referenceStarts().orEmpty()
                // Schnell: Der Player liest 20–30 s voraus. Schon mit den ersten Einblendungen dort den Versatz bestimmen und
                // sofort übernehmen, bevor die Zeilen zu sehen sind (nicht erst warten, bis sie gezeigt wurden).
                if (references.size >= SubtitleSync.MIN_REFERENCES && references.size != lastCount && elasticTiming == null && !manualSync) {
                    val fit = withContext(Dispatchers.Default) { SubtitleSync.estimate(references, starts) }
                    if (externalLanguage != language) break
                    if (fit != null) {
                        syncedByReference = true
                        applyFit(fit.timing, bySpeech = false, toleranceMs = 150)
                    }
                    matchPercent = fit?.let { SubtitleSync.coverage(references, starts, it.timing) } ?: 0
                }
                if (references.size >= SubtitleAlign.MIN_REFERENCES && references.size != lastCount) {
                    lastCount = references.size
                    val (global, result) = withContext(Dispatchers.Default) { alignTo(references, starts) }
                    if (externalLanguage != language) break
                    // Zeilen-Abgleich noch ohne Ergebnis (zu wenige sichere Zuordnungen): die Schnellprüfung gilt weiter.
                    result?.let { matchPercent = it.percent }
                    if (result != null && result.percent >= ELASTIC_MIN_PERCENT && !manualSync) {
                        val first = elasticTiming == null
                        elasticTiming = result.timing
                        // Gesamtversatz mitführen (Anzeige, Speichern, Rückfall, wenn der Zeilen-Abgleich später wegfällt).
                        global?.let { subtitleTiming = it }
                        syncedByReference = true
                        syncedBySpeech = false
                        subtitleAutoSynced = true
                        if (first) {
                            Log.i(SUBTITLE_TAG, "Zeile für Zeile ausgerichtet: ${result.matched} von ${result.references} Einblendungen, ${result.percent} %")
                            subtitleStatus = "Untertitel Zeile für Zeile an der Spur des Films ausgerichtet – passt ${result.percent} %"
                        }
                    }
                    // OpenSubtitles oder Übersetzung der Spur: entscheiden, sobald es sicher ist.
                    if (autoSwitch && !sourceDecided) {
                        if (decideSource(language, references)) return@launch
                        delay(1_000)
                        continue
                    }
                    // Passt mäßig (oder gar nicht): einmal weitere Dateien vergleichen und die beste nehmen.
                    if (language !in comparedLanguages && references.size >= COMPARE_REFERENCES &&
                        (result?.percent ?: 0) < de.ayutv.data.repo.OpenSubtitlesQuota.compareBelowPercent
                    ) {
                        comparedLanguages += language
                        if (compareCandidates(language, references, result?.percent ?: 0)) return@launch
                    }
                }
                // Ausgerichtet und Anker in der Nähe: selten nachprüfen. Nach einem Sprung (kein Anker in der Nähe): sofort
                // mit den dort neu gelesenen Einblendungen weiter ausrichten.
                delay(1_000)
                if (elasticTiming != null) {
                    var waited = 1_000L
                    while (waited < 30_000 && isActive && elasticCovers(player?.currentPosition ?: 0L)) {
                        delay(1_000)
                        waited += 1_000
                    }
                }
            }
        }
    }

    /**
     * Passt die geladene Datei? Klar daneben (unter [EARLY_MISS_PERCENT] bei den ersten Einblendungen) oder nach
     * [DECIDE_REFERENCES] Einblendungen unter [FIT_PERCENT]: erst weitere Dateien vergleichen, dann auf die Übersetzung
     * der Spur des Films wechseln (nur mit DeepL: auf dem Gerät wäre sie zu langsam). true = Datei gewechselt.
     */
    private suspend fun decideSource(language: String, references: List<Long>): Boolean {
        val percent = matchPercent ?: 0
        val clearMiss = percent < EARLY_MISS_PERCENT
        if (!clearMiss && references.size < DECIDE_REFERENCES) return false
        if (percent >= FIT_PERCENT) {
            sourceDecided = true
            Log.i(SUBTITLE_TAG, "OpenSubtitles passt ($percent % nach ${references.size} Einblendungen) – bleibt")
            return false
        }
        if (language !in comparedLanguages) {
            comparedLanguages += language
            // Eine besser passende Datei beginnt die Prüfung von vorn.
            autoSwitchNext = true
            if (compareCandidates(language, references, percent)) return true
            autoSwitchNext = false
        }
        if (externalLanguage != language) return true
        sourceDecided = true
        if (!onlineTranslation() || bestTranslationSource(allowForced = false) == null) {
            Log.i(SUBTITLE_TAG, "OpenSubtitles passt nur $percent %, keine schnelle Übersetzung – bleibt")
            return false
        }
        val best = matchPercent ?: percent
        Log.i(SUBTITLE_TAG, "OpenSubtitles passt nur $best % (${references.size} Einblendungen) – wechsle zur Übersetzung der Spur")
        lifecycleScope.launch { switchToTranslation(language, best) }
        return true
    }

    /** Statt der geladenen Datei die Spur des Films übersetzen; für diesen Film (diese Folge) gemerkt. */
    private fun switchToTranslation(language: String, percent: Int) {
        val source = bestTranslationSource(allowForced = false) ?: return
        vod?.let { container.playerMemory.setSubtitleSource(PlayerMemory.autoKey(it.key), PlayerMemory.SOURCE_TRANSLATE) }
        stopLiveTranslation()
        // Der Player ist schon 20–30 s voraus: Die Übersetzung ist fertig, bevor die Zeilen kommen, kein Vorlauf nötig.
        startLiveTranslation(language, source)
        autoSwitched = percent
        subtitleStatus = "OpenSubtitles passt nur $percent % – übersetze stattdessen die Spur des Films"
    }

    /** Übereinstimmung der Datei, gegen die automatisch zur Übersetzung gewechselt wurde (für das Menü). */
    var autoSwitched by mutableStateOf<Int?>(null); private set

    /**
     * Sprache, für die im Menü zwischen OpenSubtitles und Übersetzung gewählt werden kann (nur Filme mit
     * übersetzbarer eingebetteter Spur).
     */
    val sourceChoiceLanguage: String? get() {
        if (vod == null || !settings.translationEnabled) return null
        val language = externalLanguage ?: liveTranslateTarget ?: return null
        return language.takeIf { bestTranslationSource(allowForced = false) != null }
    }

    /** Name des Übersetzers für das Menü („DeepL“, „DnA-Übersetzer“ …). */
    val translatorName: String get() = Translators.from(settings)?.name ?: "Übersetzer"

    /** Im Menü gewählt: OpenSubtitles ([translate] = false) oder Übersetzung der Spur; je Film bzw. Serie gemerkt. */
    fun chooseSubtitleSource(language: String, translate: Boolean) {
        autoSwitched = null
        loadSubtitles(language, if (translate) PlayerMemory.SOURCE_TRANSLATE else PlayerMemory.SOURCE_FILE)
    }

    /** Erst grob (Versatz und Bildrate), dann Zeile für Zeile. */
    private fun alignTo(references: List<Long>, starts: List<Long>): Pair<SubtitleTiming?, SubtitleAlign.Result?> {
        val estimated = SubtitleSync.estimate(references, starts)?.timing
        val global = estimated ?: subtitleTiming.takeIf { subtitleAutoSynced }
        return estimated to SubtitleAlign.align(references, starts, global?.ratio ?: 1.0, global?.offsetMs ?: 0L)
    }

    /** Bis zu zwei weitere Dateien derselben Suche laden und an der Spur messen; true, wenn eine übernommen wurde. */
    private suspend fun compareCandidates(language: String, references: List<Long>, current: Int): Boolean {
        val request = vod ?: return false
        var best = current
        repeat(de.ayutv.data.repo.OpenSubtitlesQuota.candidatesPerFilm - 1) {
            if (externalLanguage != language || !container.subtitles.hasAlternatives(request.key, language)) return false
            speechSyncInfo = "Vergleiche weitere Untertitel-Dateien mit der Spur des Films …"
            val alternative = runCatching { container.subtitles.downloadAlternative(request.key, language, settings, filmInfo()) }
                .onFailure { Log.i(SUBTITLE_TAG, "Weitere Datei nicht ladbar: ${it.message}") }.getOrNull() ?: return false
            val cues = withContext(Dispatchers.IO) { parseCues(alternative.file) }
            val (global, result) = withContext(Dispatchers.Default) { alignTo(references, cues.map { it.start }) }
            val percent = result?.percent ?: 0
            Log.i(SUBTITLE_TAG, "Weitere Datei „${alternative.release}“ passt zu $percent % (bisher $best %)")
            if (result != null && percent >= ELASTIC_MIN_PERCENT && percent >= best + 10 && externalLanguage == language) {
                val file = container.subtitles.adoptAlternative(request.key, language, alternative)
                subtitleFiles[language] = file
                beginExternal(language, cues, null)
                elasticTiming = result.timing
                global?.let { subtitleTiming = it }
                matchPercent = percent
                syncedByReference = true
                subtitleAutoSynced = true
                subtitleStatus = "Andere Untertitel-Datei passt besser ($percent % statt $best %) – übernommen und Zeile für Zeile ausgerichtet"
                return true
            }
            container.subtitles.dropAlternative(alternative)
            best = maxOf(best, percent)
        }
        return false
    }

    /** Untertitel früher (negativ) oder später (positiv) einblenden; wird pro Film gemerkt. */
    fun shiftSubtitles(deltaMs: Long) {
        subtitleDelayMs += deltaMs
        subtitleStatus = "Untertitel ${formatShift(subtitleDelayMs + subtitleTiming.offsetMs)}"
        saveTiming()
    }

    /** Fortschritt der vorausschauenden Übersetzung einer Untertitel-Datei (null = keine läuft). */
    var fileTranslationInfo by mutableStateOf<String?>(null); private set

    /** Gesamtverschiebung der geladenen Untertitel gegenüber der Datei, für die Anzeige. */
    val subtitleShiftLabel: String get() = formatShift(subtitleTiming.offsetMs + subtitleDelayMs) +
        if (subtitleTiming.ratio != 1.0) ", Bildrate korrigiert" else ""

    /**
     * Wie sicher die Untertitel gerade zum Bild passen, als kurze Zeile für die Info-Leiste (null: keine
     * Untertitel von außen und keine Übersetzung).
     */
    val syncBadge: String? get() {
        if (externalLanguage == null) return if (liveTranslateTarget != null) "✓ Untertitel zeitgenau (Spur des Films übersetzt)" else null
        val info = speechSyncInfo
        return when {
            manualSync -> "✓ Untertitel von Hand ausgerichtet ($subtitleShiftLabel)"
            elasticTiming != null -> "✓ Untertitel Zeile für Zeile an der Spur des Films ausgerichtet" + (matchPercent?.let { " · passt $it %" } ?: "")
            syncedByReference -> "✓ Untertitel an der Spur des Films ausgerichtet"
            externalExact -> "✓ Untertitel passen genau zu dieser Datei"
            subtitleAutoSynced && syncedBySpeech -> "✓ Untertitel am Ton ausgerichtet ($subtitleShiftLabel)"
            subtitleAutoSynced -> "✓ Untertitel: gespeicherte Ausrichtung ($subtitleShiftLabel)"
            else -> "◌ Untertitel noch nicht geprüft" + (info?.let { " – $it" } ?: "") + ". Passt es nicht: ☰ → Untertitel-Zeit einstellen"
        }
    }

    /** Untertitel-Zeit von Hand einstellen (Feld im Menü, schließt nicht von selbst). */
    var syncPanel by mutableStateOf(false); private set

    /**
     * Ausrichten mit einem Druck: unten steht groß die Zeile, die als Nächstes kommt. Sobald man sie hört, OK.
     * ◀ ▶ nimmt die vorige/nächste Zeile, ▲ ▼ schiebt um eine halbe Sekunde. Kein Suchen in einer Liste.
     */
    var syncCandidate by mutableStateOf(-1); private set
    /** Rückmeldung nach dem Druck („✓ ausgerichtet …“), sonst null. */
    var syncConfirmed by mutableStateOf<String?>(null); private set
    private var syncCloseJob: Job? = null

    fun openSyncPanel() {
        closeOverlay()
        syncCandidate = nextCueIndex()
        syncConfirmed = null
        syncPanel = true
    }

    fun closeSyncPanel() {
        syncCloseJob?.cancel()
        syncPanel = false
        syncConfirmed = null
        overlayToken++
    }

    /** Die Zeile, die nach der jetzigen Ausrichtung als Nächstes beginnt. */
    private fun nextCueIndex(): Int {
        val at = subtitleTimeAt(player?.currentPosition ?: 0L)
        val index = externalCues.indexOfFirst { it.start >= at - 500 }
        return if (index < 0) externalCues.lastIndex else index
    }

    /** Beginn der angezeigten Zeile in der Datei (für Tests). */
    val syncCandidateStart: Long? get() = externalCues.getOrNull(syncCandidate)?.start

    fun syncCue(index: Int): String? = externalCues.getOrNull(index)?.let { cue ->
        (externalTexts?.get(index) ?: cue.text).replace('\n', ' ')
    }

    fun syncStep(delta: Int) {
        if (externalCues.isEmpty()) return
        syncCloseJob?.cancel()
        syncConfirmed = null
        syncCandidate = (syncCandidate + delta).coerceIn(0, externalCues.lastIndex)
    }

    /** Die angezeigte Zeile wird gerade gesprochen. */
    fun syncConfirm() {
        val cue = externalCues.getOrNull(syncCandidate) ?: return
        anchorSubtitle(cue.start)
        syncConfirmed = "✓ Ausgerichtet ($subtitleShiftLabel). Passt es noch nicht ganz: in ein paar Minuten einfach noch eine Zeile bestätigen."
        syncCandidate = nextCueIndex()
        syncCloseJob?.cancel()
        syncCloseJob = lifecycleScope.launch {
            delay(5_000)
            closeSyncPanel()
        }
    }

    /** Tasten, solange die Ausrichtungsleiste offen ist. OK wirkt schon beim Drücken (genauer). */
    private fun handleSyncKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (event.repeatCount == 0) syncConfirm()
            KeyEvent.KEYCODE_DPAD_LEFT -> syncStep(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> syncStep(+1)
            KeyEvent.KEYCODE_DPAD_UP -> { syncCloseJob?.cancel(); shiftSubtitles(-500) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { syncCloseJob?.cancel(); shiftSubtitles(500) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.let { it.playWhenReady = !it.playWhenReady }
            else -> return false
        }
        return true
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
    /** Position nach einem großen Sprung, ab der einmal neu am Ton geprüft wird (-1 = nichts zu prüfen). */
    private var seekRecheckFrom = -1L

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
        val elastic = elasticTiming
        if (elastic != null) {
            // Zeile für Zeile ausgerichtet: nur den Rest von Hand ausgleichen, die Ausrichtung bleibt.
            subtitleDelayMs = position - elastic.toVideo(cueStart)
        } else if (ratio != null && ratio != subtitleTiming.ratio) {
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
        // Schon Zeile für Zeile ausgerichtet: genauer als der eine Gesamtversatz hier.
        if (elasticTiming != null) return
        val references = referenceStarts.toList()
        val starts = externalCues.map { it.start }
        syncJob = lifecycleScope.launch {
            val fit = withContext(Dispatchers.Default) { SubtitleSync.estimate(references, starts) }
            if (externalLanguage == null) return@launch
            if (fit == null) {
                // Nach vielen Einblendungen immer noch kein gemeinsamer Takt: Die Datei gehört zu einem
                // anderen Film (oder einer ganz anderen Fassung). Verwerfen und den nächsten Treffer laden.
                // Der Zeilen-Abgleich hat noch nicht entschieden oder findet einen Teil wieder: nicht verwerfen.
                if (references.size >= WRONG_FILE_REFERENCES && !subtitleAutoSynced && externalTexts == null && (matchPercent ?: 0) < 30) replaceWrongSubtitles()
                return@launch
            }
            syncedByReference = true
            applyFit(fit.timing, bySpeech = false, toleranceMs = 150)
        }
    }

    /** Automatisch gefundene Lage übernehmen, wenn sie spürbar von der jetzigen abweicht. */
    private fun applyFit(timing: SubtitleTiming, bySpeech: Boolean, toleranceMs: Long, afterSeek: Boolean = false) {
        // Von Hand an einer Zeile ausgerichtet: Das ist genauer als jede Schätzung, nicht wieder verstellen –
        // außer nach einem großen Sprung, wenn der Ton dort sicher einen anderen Versatz zeigt (gekürzte Fassung).
        if (manualSync && !afterSeek) return
        if (afterSeek) {
            val here = subtitleTimeAt(player?.currentPosition ?: 0L)
            val jump = abs(timing.toVideo(here) - subtitleTiming.toVideo(here) - subtitleDelayMs)
            if (jump < 1_000) return
            Log.i(SUBTITLE_TAG, "Nach dem Spulen neu ausgerichtet: $timing (Abweichung $jump ms)")
            subtitleDelayMs = 0L
            subtitleTiming = timing
            subtitleAutoSynced = true
            syncedBySpeech = bySpeech
            subtitleStatus = "Nach dem Spulen am Ton nachgeprüft und neu ausgerichtet (${formatShift(timing.offsetMs)})"
            saveTiming()
            return
        }
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
                // Nach einem großen Sprung: einmal nur mit dem Ton ab der neuen Stelle prüfen (gekürzte Fassungen haben
                // dort oft einen anderen Versatz), sobald davon genug gehört ist.
                val seekFrom = seekRecheckFrom
                val afterSeek = seekFrom >= 0 && position - seekFrom >= SEEK_RECHECK_MS
                if (seekFrom >= 0 && !afterSeek && manualSync) continue
                val (fit, window) = withContext(Dispatchers.Default) {
                    val signal = heardSpeech.signal(if (afterSeek) seekFrom else position - SPEECH_WINDOW_MS, position + 5_000)
                    SpeechSync.estimate(signal, cues) to signal.count { !it.isNaN() } / 10
                }
                if (externalLanguage == null || syncedByReference) continue
                if (afterSeek) seekRecheckFrom = -1L
                if (afterSeek && fit != null && fit.votes >= SEEK_RECHECK_VOTES) {
                    applyFit(fit.timing, bySpeech = true, toleranceMs = 250, afterSeek = true)
                    speechSyncInfo = "Nach dem Spulen am Ton nachgeprüft (${clock(position)})"
                    continue
                }
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
                if (fit == null && window >= SPEECH_COMPARE_SECONDS && !subtitleAutoSynced && externalTexts == null && !alternativeTried) {
                    // Drei Minuten Gespräch und kein Takt: mit dem nächsten Treffer der Suche vergleichen (ein Download).
                    alternativeTried = true
                    if (tryAlternative(position)) return@launch
                }
                if (fit == null && window >= SPEECH_WRONG_FILE_SECONDS && !subtitleAutoSynced && externalTexts == null) {
                    // Eine Viertelstunde Gespräch und kein gemeinsamer Takt: Die Datei gehört zu einem anderen Film.
                    replaceWrongSubtitles()
                    return@launch
                }
            }
        }
    }

    private var replacedSubtitles = 0
    /** Für die geladene Datei wurde schon ein weiterer Treffer am Ton verglichen. */
    private var alternativeTried = false

    /**
     * Die geladene Datei findet am Ton keinen Takt: den nächsten Treffer derselben Suche laden und mit demselben
     * Ton prüfen. Findet er einen Takt, wird er übernommen (true); sonst bleibt alles, wie es ist.
     */
    private suspend fun tryAlternative(position: Long): Boolean {
        val request = vod ?: return false
        val language = externalLanguage ?: return false
        if (!container.subtitles.hasAlternatives(request.key, language)) return false
        speechSyncInfo = "Kein Takt gefunden, vergleiche mit einer anderen Untertitel-Datei …"
        val alternative = runCatching { container.subtitles.downloadAlternative(request.key, language, settings, filmInfo()) }
            .onFailure { describeOnlineProblem(it) }.getOrNull() ?: return false
        val cues = withContext(Dispatchers.IO) { parseCues(alternative.file) }
        val fit = withContext(Dispatchers.Default) {
            SpeechSync.estimate(heardSpeech.signal(position - SPEECH_WINDOW_MS, position + 5_000), cues.map { it.start to it.end })
        }
        if (fit == null || externalLanguage != language || subtitleAutoSynced) {
            Log.i(SUBTITLE_TAG, "Weitere Datei „${alternative.release}“ passt auch nicht besser")
            container.subtitles.dropAlternative(alternative)
            return false
        }
        Log.i(SUBTITLE_TAG, "Weitere Datei „${alternative.release}“ passt zum Ton: ${fit.timing} (Güte ${fit.votes / 10.0})")
        val file = container.subtitles.adoptAlternative(request.key, language, alternative)
        subtitleFiles[language] = file
        beginExternal(language, parseCues(file), null)
        applyFit(fit.timing, bySpeech = true, toleranceMs = 0)
        subtitleStatus = "Eine andere Untertitel-Datei passt zum Ton: übernommen und ausgerichtet ($subtitleShiftLabel)"
        return true
    }

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
        // In der Ruhezeit oder beim letzten Schauen schon übersetzte Zeilen gleich übernehmen.
        val partial = container.subtitles.lineTranslations(request.key, de.ayutv.data.repo.Pretranslation.PARTIAL_TRACK, target)
        cues.forEachIndexed { i, cue -> partial[cue.start]?.let { translations.set(i, it) } }
        // Wie geladene Untertitel einblenden (verschiebbar, automatisch ausgerichtet); fertig
        // übersetzte Zeilen ersetzen nach und nach das Original.
        beginExternal(target, cues, translations)
        subtitleStatus = "Übersetze ${sourceLanguage?.let { Languages.label(it) + " " }.orEmpty()}→ ${Languages.label(target)} mit ${translator.name}"
        fun indexAt(position: Long): Int {
            val at = subtitleTimeAt(position)
            val i = cues.indexOfFirst { it.end > at }
            return if (i < 0) cues.size else i
        }
        // Sätze über mehrere Einblendungen als Ganzes übersetzen, dann wieder aufteilen.
        val sentenceCues = cues.map { de.ayutv.data.translate.SubtitleSentences.Cue(it.start, it.end, it.text) }
        val groups = de.ayutv.data.translate.SubtitleSentences.groups(sentenceCues)
        val groupOf = IntArray(cues.size).also { index -> groups.forEachIndexed { g, range -> range.forEach { index[it] = g } } }
        // Namen, die zugleich Wörter sind („Will“, „Hope“), aus der ganzen Datei lernen und je Serie merken.
        val seriesKey = de.ayutv.data.parse.Episodes.seriesKeyOf(request.key) ?: request.key
        val names = de.ayutv.data.translate.SeriesNames(this)
            .add(seriesKey, de.ayutv.data.translate.TranslationNames.learn(cues.map { it.text }))
        val smart = de.ayutv.data.translate.SubtitleTranslator(translator, names)
        fileTranslationJob = lifecycleScope.launch {
            var done = 0
            fileTranslationInfo = "Übersetzung startet …"
            val batch = if (translator.name == de.ayutv.data.translate.OnDeviceTranslator.NAME) 3 else 40
            try {
                while (true) {
                    val from = indexAt(player?.currentPosition ?: 0L)
                    val next = (from until cues.size).firstOrNull { translations.get(it) == null }
                        ?: (0 until from).firstOrNull { translations.get(it) == null }
                        ?: break
                    val chosen = (groupOf[next] until minOf(groups.size, groupOf[next] + batch)).map { groups[it] }
                        .filter { range -> range.any { translations.get(it) == null } }
                    // Der Satz davor (falls schon übersetzt) als Zusammenhang für eine kurze erste Zeile.
                    val before = groups.getOrNull(groups.indexOf(chosen.first()) - 1)?.let { range ->
                        val done = range.map { translations.get(it) }
                        if (done.any { it == null }) null
                        else de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, range) to done.joinToString(" ") { it!!.replace('\n', ' ') }
                    }
                    val result = smart.translate(chosen.map { de.ayutv.data.translate.SubtitleSentences.joined(sentenceCues, it) }, target, sourceLanguage, before)
                    chosen.forEachIndexed { k, range ->
                        val sentence = result.getOrNull(k)
                        if (sentence == null) {
                            range.forEach { translations.set(it, cues[it].text) }
                            return@forEachIndexed
                        }
                        val parts = de.ayutv.data.translate.SubtitleSentences.split(sentence, range.map { cues[it].text.length })
                        range.forEachIndexed { j, index -> translations.set(index, de.ayutv.data.translate.SubtitleSentences.wrap(parts[j])) }
                    }
                    done += chosen.sumOf { it.count() }
                    // Wie weit die Übersetzung vor der aktuellen Stelle ist (im Untertitel-Menü zu sehen).
                    val ahead = (indexAt(player?.currentPosition ?: 0L) until cues.size).takeWhile { translations.get(it) != null }.count()
                    fileTranslationInfo = "Übersetzt: $done von ${cues.size} Zeilen · ab hier $ahead Zeilen fertig"
                }
                fileTranslationInfo = null
                val finished = cues.mapIndexed { i, cue -> cue.original.copy(text = translations.get(i) ?: cue.text) }
                val saved = withContext(Dispatchers.IO) { container.subtitles.saveTranslation(request.key, target, finished) }
                subtitleFiles[target] = saved
                loadedLanguages = subtitleFiles.keys.toList()
                Log.i(SUBTITLE_TAG, "Übersetzung von ${cues.size} Zeilen fertig und gespeichert")
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Abgebrochen (Film beendet, andere Untertitel): Bisheriges für das nächste Mal aufheben.
                val lines = cues.indices.mapNotNull { i -> translations.get(i)?.let { cues[i].start to it } }.toMap()
                container.subtitles.saveLineTranslations(request.key, de.ayutv.data.repo.Pretranslation.PARTIAL_TRACK, target, lines)
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
            container.playerMemory.update(key) { if (group.type == C.TRACK_TYPE_AUDIO) it.copy(audio = language) else it.copy(text = language, load = null) }
        }
    }

    fun setTextEnabled(enabled: Boolean) {
        val p = player ?: return
        if (!enabled) memoryKey()?.let { key -> container.playerMemory.update(key) { it.copy(text = PlayerMemory.OFF, load = null) } }
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
        episodes.onManualSeek(pendingSeekMs)
        showOverlay(Overlay.CONTROLS)
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            delay(300)
            player?.seekTo(pendingSeekMs)
            pendingSeekMs = C.TIME_UNSET
        }
    }

    // ---------------------------------------------------------------- Anstoß-Hinweis

    /** Fällige Erinnerung im Bild (OK schaltet um, ⏩ als kleines Bild, Zurück blendet aus). */
    var dueReminder by mutableStateOf<de.ayutv.data.reminders.Reminder?>(null); private set
    private var reminderJob: Job? = null

    private fun showReminder(reminder: de.ayutv.data.reminders.Reminder) {
        dueReminder = reminder
        reminderJob?.cancel()
        reminderJob = lifecycleScope.launch {
            delay(REMINDER_VISIBLE_MS)
            dueReminder = null
        }
    }

    /** Taste, während der Hinweis zu sehen ist. true: verbraucht. */
    private fun handleReminderKey(code: Int): Boolean {
        val reminder = dueReminder ?: return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                dueReminder = null
                lifecycleScope.launch {
                    val channel = container.content.channel(reminder.channelKey) ?: return@launch
                    if (isLive) playChannel(channel) else {
                        startActivity(liveIntent(this@PlayerActivity, channel.key, null))
                        finish()
                    }
                }
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive) return false
                dueReminder = null
                lifecycleScope.launch { container.content.channel(reminder.channelKey)?.let(::startPip) }
            }
            KeyEvent.KEYCODE_BACK -> dueReminder = null
            else -> return false
        }
        return true
    }

    // ---------------------------------------------------------------- Konferenz (zweites, kleines Bild)

    /** Zweiter Sender als kleines Bild unten rechts (ohne Ton). ⏩ tauscht die Bilder. */
    var pipPlayer by mutableStateOf<ExoPlayer?>(null); private set
    var pipChannel by mutableStateOf<ChannelEntity?>(null); private set
    /** Auswahl für das kleine Bild: laufende Spiele und der vorige Sender (Sender, Beschriftung). */
    var pipChoices by mutableStateOf<List<Pair<ChannelEntity, String>>?>(null); private set
    private var pipRetries = 0
    /** Multiview: zweiter Sender gleich groß neben dem Hauptbild statt klein unten rechts (gemerkt). */
    var pipSplit by mutableStateOf(false); private set

    fun togglePipSplit() {
        pipSplit = !pipSplit
        getSharedPreferences(PIP_PREFS, MODE_PRIVATE).edit().putBoolean(PIP_SPLIT, pipSplit).apply()
        closeOverlay()
    }

    fun openPipPicker() {
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val games = runCatching { container.content.sportEvents(hours = 1) }.getOrDefault(emptyList())
                .filter { it.programme.start <= now && it.programme.stop > now && it.channel.key != playing?.key }
                .map { it.channel to "⚽ ${it.programme.sportHeadline()} · ${it.channel.name}" }
            val previous = previousChannel?.takeIf { p -> p.key != playing?.key && games.none { it.first.key == p.key } }
                ?.let { listOf(it to "↩ Voriger Sender: ${it.name}") }.orEmpty()
            val favorites = runCatching { container.content.zapList(null, favoritesOnly = true, merge = settings.mergeVariants) }.getOrDefault(emptyList())
                .filter { f -> f.key != playing?.key && games.none { it.first.key == f.key } && previous.none { it.first.key == f.key } }
                .take(12).map { it to "★ ${it.name}" }
            pipChoices = games + previous + favorites
            overlayToken++
        }
    }

    fun closePipPicker() {
        pipChoices = null
        overlayToken++
    }

    fun startPip(channel: ChannelEntity) {
        pipChoices = null
        closeOverlay()
        val main = playing
        // Konto mit nur einer Verbindung: Zwei Sender desselben Anbieters gleichzeitig gehen nicht.
        if (main != null && main.playlistId == channel.playlistId && container.accountLimits.maxConnections(channel.playlistId) == 1) {
            message = "Dein Konto erlaubt nur 1 Verbindung: zwei Sender gleichzeitig gehen bei diesem Anbieter nicht."
            handler.postDelayed({ message = null }, 8_000)
            return
        }
        val preview = pipPlayer ?: PlayerFactory.createPreview(this, container.http, channel.userAgent ?: settings.userAgent).also { created ->
            created.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Kleines Bild: ${error.errorCodeName}", error)
                    if (pipRetries++ < 3) {
                        handler.postDelayed({ pipPlayer?.let { it.prepare(); it.play() } }, 1_500)
                        return
                    }
                    val limit = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                    closePip()
                    message = if (limit != null && limit in 400..499) {
                        "Zweiter Sender abgelehnt (HTTP $limit) – vermutlich erlaubt das Konto nur eine Verbindung."
                    } else "Zweites Bild nicht möglich: ${error.errorCodeName}"
                    handler.postDelayed({ message = null }, 8_000)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) pipRetries = 0
                }
            })
        }
        pipRetries = 0
        pipSplit = getSharedPreferences(PIP_PREFS, MODE_PRIVATE).getBoolean(PIP_SPLIT, false)
        val url = adaptLiveUrl(channel.url)
        preview.setMediaItem(mediaItem(url, guessMime(url)))
        preview.repeatMode = Player.REPEAT_MODE_ONE
        preview.prepare()
        preview.play()
        pipPlayer = preview
        pipChannel = channel
        message = "Kleines Bild: ${channel.name} · ⏩ tauschen · ☰ schließen"
        handler.postDelayed({ if (message?.startsWith("Kleines Bild") == true) message = null }, 5_000)
    }

    /** Hauptbild und kleines Bild tauschen (der Ton folgt dem Hauptbild). */
    fun swapPip() {
        val small = pipChannel ?: return
        val main = playing ?: return
        playChannel(small)
        startPip(main)
    }

    fun closePip() {
        pipPlayer?.release()
        pipPlayer = null
        pipChannel = null
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

    /**
     * 📺 auf dem Handy: auf einen Fernseher übertragen – einen Fire TV (oder anderen Fernseher) mit DnA-TV im selben
     * WLAN, oder einen Chromecast / Google-TV (nur mit Google-Play-Diensten). Fire TV kann kein Chromecast.
     */
    val castAvailable: Boolean by lazy { hasTouchscreen() }
    /** Name des Fernsehers, auf dem gerade gespielt wird. */
    var castingTo by mutableStateOf<String?>(null); private set
    /** Läuft gerade auf einem Fernseher mit DnA-TV (statt Chromecast). */
    private var castingToDnaTv = false

    fun toggleCast() {
        if (castingTo != null) {
            if (castingToDnaTv) {
                castingTo = null
                castingToDnaTv = false
                message = "Läuft wieder hier (auf dem Fernseher läuft es weiter)"
                restartStream()
            } else {
                de.ayutv.player.cast.Cast.stop(this)
            }
            return
        }
        lifecycleScope.launch {
            message = "Suche Fernseher im WLAN …"
            val client = de.ayutv.data.remote.RemoteClient(applicationContext, container)
            var found = emptyList<de.ayutv.data.remote.RemoteClient.Tv>()
            kotlinx.coroutines.withTimeoutOrNull(2_500) { client.discover().collect { found = it } }
            // Schon gekoppelter Fernseher, auch wenn ihn die Suche (Router blockt Ankündigungen) nicht findet.
            val paired = client.address?.takeIf { client.paired && found.none { tv -> tv.address == it } }
                ?.let { de.ayutv.data.remote.RemoteClient.Tv("Gekoppelter Fernseher", it) }
            val tvs = found + listOfNotNull(paired)
            val chromecast = de.ayutv.player.cast.Cast.available(this@PlayerActivity)
            message = null
            if (tvs.isEmpty() && !chromecast) {
                message = "Kein Fernseher gefunden. Auf dem Fire TV DnA-TV öffnen (gleiches WLAN, Einstellungen → Handy als Fernbedienung: an)."
                handler.postDelayed({ if (message?.startsWith("Kein Fernseher") == true) message = null }, 10_000)
                return@launch
            }
            val labels = tvs.map { "📺 ${it.name}" } + listOfNotNull(if (chromecast) "Chromecast / Google TV" else null)
            android.app.AlertDialog.Builder(this@PlayerActivity)
                .setTitle("Auf Fernseher abspielen")
                .setItems(labels.toTypedArray()) { _, which ->
                    if (which < tvs.size) sendToTv(client, tvs[which]) else chooseChromecast()
                }
                .setNegativeButton("Abbrechen", null)
                .show()
        }
    }

    private fun chooseChromecast() {
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

    /** An einen Fernseher mit DnA-TV übergeben; beim ersten Mal nach der PIN fragen (steht dort in den Einstellungen). */
    private fun sendToTv(client: de.ayutv.data.remote.RemoteClient, tv: de.ayutv.data.remote.RemoteClient.Tv) {
        val p = player ?: return
        val request = if (isLive) {
            playing?.let { de.ayutv.data.remote.RemotePlay(live = true, url = it.url, title = it.name, image = it.logo, userAgent = it.userAgent ?: settings.userAgent) }
        } else {
            vod?.let { de.ayutv.data.remote.RemotePlay(false, it.url, it.title, it.key, it.kind, it.subtitle, it.image, p.currentPosition, settings.userAgent) }
        }
        if (request == null || request.url.startsWith("file:")) {
            message = "Aufnahmen und Downloads lassen sich nicht übertragen"
            return
        }
        fun send(pin: String) {
            lifecycleScope.launch {
                var released = false
                runCatching {
                    val name = client.check(tv.address, pin)
                    client.address = tv.address
                    client.pin = pin
                    // Erst hier loslassen: Viele Konten erlauben nur eine Verbindung, der Fernseher braucht sie.
                    p.stop()
                    released = true
                    client.play(request, tv.address, pin)
                    name
                }.onSuccess { name ->
                    castingTo = name
                    castingToDnaTv = true
                    message = "📺 Läuft auf $name – das Handy kannst du jetzt sperren oder weglegen. (📺 nochmal: hier weiterschauen)"
                    handler.postDelayed({ if (message?.startsWith("📺 Läuft") == true) message = null }, 8_000)
                }.onFailure { e ->
                    if (released) restartStream()
                    if (e.message?.contains("PIN") == true) askPin(tv) { send(it) }
                    else message = "Fernseher: ${e.message ?: "nicht erreichbar"}"
                }
            }
        }
        val known = client.pin?.takeIf { client.address == tv.address }
        if (known != null) send(known) else askPin(tv) { send(it) }
    }

    private fun askPin(tv: de.ayutv.data.remote.RemoteClient.Tv, onPin: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "PIN"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(tv.name)
            .setMessage("PIN vom Fernseher eingeben: dort Einstellungen → Daten & Info → Handy als Fernbedienung.")
            .setView(input)
            .setPositiveButton("Verbinden") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onPin) }
            .setNegativeButton("Abbrechen", null)
            .show()
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

    var loudnessLevel by mutableStateOf(false); private set

    /**
     * Tor-Hinweis: Trifft ein gefolgter Verein in einem anderen Spiel als dem, das gerade läuft, kurz den neuen
     * Stand einblenden. Fragt jede Minute (OpenLigaDB); das erste Abfragen merkt nur die Stände.
     */
    private fun startGoalWatch() {
        if (!settings.goalAlerts || !settings.liveScores || settings.followedTeams.isEmpty()) return
        lifecycleScope.launch {
            val known = HashMap<Long, Pair<Int, Int>>()
            while (isActive) {
                val now = System.currentTimeMillis()
                val matches = runCatching { container.liveScores.current(now) }.getOrDefault(emptyList())
                val teams = settings.followedTeams
                val watching = playing?.let { nowPlayingMap[it.key] }?.pairing()
                for (m in matches) {
                    if (!m.live(now) && !m.finished) continue
                    val team = teams.firstOrNull { de.ayutv.data.parse.Teams.plays(it, m.pairing) } ?: continue
                    val before = known.put(m.id, m.goals1 to m.goals2) ?: continue
                    if (before == (m.goals1 to m.goals2) || m.goals1 + m.goals2 <= before.first + before.second) continue
                    if (watching != null && m.matches(watching)) continue
                    val scorer = if (m.goals1 > before.first) m.short1.ifEmpty { m.team1 } else m.short2.ifEmpty { m.team2 }
                    val text = "⚽ Tor für $scorer!  ${m.short1.ifEmpty { m.team1 }} ${m.score} ${m.short2.ifEmpty { m.team2 }}" +
                        (m.lastGoal?.let { " · $it" } ?: "")
                    Log.i(TAG, "Tor-Hinweis ($team): $text")
                    goalNotice = text
                    delay(GOAL_NOTICE_MS)
                    if (goalNotice == text) goalNotice = null
                }
                delay(60_000)
            }
        }
    }

    /** Eingeblendeter Tor-Hinweis (oben im Bild), sonst null. */
    var goalNotice by mutableStateOf<String?>(null); private set

    /** Lautstärke angleichen an/aus (gilt für alle Sender und Filme). */
    fun toggleLoudness() {
        val on = !enhance.leveling
        enhance.leveling = on
        loudnessLevel = on
        container.scope.launch { container.settings.update { it.copy(loudnessLevel = on) } }
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
                if (vod?.kind != HistoryKind.EPISODE || !episodes.onEnded()) finish()
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
                // Großer Sprung mit geladenen Untertiteln: dort einmal still am Ton nachprüfen.
                if (vod != null && externalLanguage != null && abs(newPosition.positionMs - oldPosition.positionMs) >= SEEK_RECHECK_JUMP_MS) {
                    seekRecheckFrom = newPosition.positionMs
                }
                // Übersetzung mit DeepL: An der neuen Stelle sind die Zeilen noch nicht gelesen. Kurz anhalten, bis
                // sie übersetzt sind, statt erst Englisch und dann Deutsch zu zeigen.
                val format = aheadFormat
                if (vod != null && !isLive && format != null && liveTranslateTarget != null && onlineTranslation() && player?.playWhenReady == true &&
                    abs(newPosition.positionMs - oldPosition.positionMs) >= ONLINE_SEEK_JUMP_MS
                ) {
                    // Zurück an eine schon übersetzte Stelle: kein Halt nötig.
                    val at = newPosition.positionMs
                    val known = embedded?.upcoming(format, at, 50) { it.startMs < at + ONLINE_LEAD_MS }.orEmpty()
                    if (known.isEmpty() || known.any { it.startMs !in aheadTranslations }) startPreroll(format, thorough = true)
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            updateFormats()
            // Gemessene Auflösung merken: bestimmt, welche Fassung doppelter Sender die beste ist, und den Qualitätsfilter.
            if (isLive && videoSize.height > 0) playing?.let { de.ayutv.data.parse.ChannelQuality.recordPlayback(it.key, videoSize.height, stats.startupMs) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            Log.w(TAG, "Wiedergabefehler ${error.errorCodeName}", error)
            playerErrors++
            // Für den Fehlerbericht: welcher Fehler, mit der eigentlichen Ursache.
            statusHistory += "Fehler ${error.errorCodeName}: ${(error.cause?.message ?: error.message).orEmpty().take(140)}"
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
                    // Erst Bescheid sagen, wenn nach 3 s noch kein Bild da ist: Meist läuft es nach dem Neustart gleich weiter.
                    val generation = streamGeneration
                    val attempt = retries
                    handler.postDelayed({
                        if (generation == streamGeneration && player?.playbackState != Player.STATE_READY) {
                            // Liegt es am Proxy, das sagen: sonst sucht man den Fehler beim Anbieter.
                            val proxy = de.ayutv.data.net.AppProxy.recentFailure()
                            message = (proxy?.let { "$it – " } ?: "") + "Verbindung wird wiederhergestellt … ($attempt/$MAX_RETRIES)"
                        }
                    }, 3_000)
                    retryLater()
                }
                else -> {
                    val reason = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Der Anbieter lehnt den Stream ab (z. B. zu viele Verbindungen oder Konto abgelaufen)"
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Keine Verbindung zum Server"
                        else -> "Stream nicht verfügbar (${error.errorCodeName})"
                    }.let { base -> de.ayutv.data.net.AppProxy.recentFailure()?.let { "$it (Einstellungen → Proxy)" } ?: base }
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
        updateAudioAhead()
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
        video?.frameRate?.takeIf { it > 1f }?.let { fps -> memoryKey()?.let { container.playerMemory.rememberFrameRate(it, fps, isLive) } }
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
        // Vorlauf der Übersetzung: OK oder Zurück schaut sofort weiter (Zeilen ohne Übersetzung bleiben im Original).
        if (prerollProgress != null && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK ||
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY)
        ) {
            if (event.action == KeyEvent.ACTION_UP) endPreroll()
            return true
        }
        if (dueReminder != null && overlay != Overlay.CHANNELS && overlay != Overlay.TRACKS &&
            (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_BACK ||
                (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD && isLive))
        ) {
            if (event.action == KeyEvent.ACTION_UP) handleReminderKey(code)
            return true
        }
        if (quickZap != null && handleQuickKey(event)) return true
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                when {
                    syncPanel && overlay == Overlay.NONE -> closeSyncPanel()
                    languagePicker -> closeLanguagePicker()
                    epgPicker -> closeEpgPicker()
                    pipChoices != null -> closePipPicker()
                    overlay != Overlay.NONE -> closeOverlay()
                    else -> finish()
                }
            }
            return true
        }
        // Noch nicht bereit (Einstellungen/Player werden geladen).
        if (player == null) return super.dispatchKeyEvent(event)
        if (syncPanel && overlay == Overlay.NONE && handleSyncKey(event)) return true
        // In Senderliste und Spurmenü übernimmt Compose die Navigation.
        if (overlay == Overlay.CHANNELS || overlay == Overlay.TRACKS) {
            if (event.action == KeyEvent.ACTION_DOWN) overlayToken++
            return super.dispatchKeyEvent(event)
        }
        // ◀ kurz: zum letzten Sender (beim Loslassen, damit Halten die Schnellwahl öffnen kann).
        if (isLive && !timeshifted && code == KeyEvent.KEYCODE_DPAD_LEFT && event.action == KeyEvent.ACTION_UP) {
            if (!quickOpenedThisPress) backZap() ?: openChannelList()
            quickOpenedThisPress = false
            return true
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
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> openChannelList()
            // ◀ springt zum vorherigen Sender (und von dort wieder zurück); ohne vorherigen öffnet es die Liste.
            // ◀ halten (etwa eine halbe Sekunde): Schnellwahl mit zuletzt gesehenen Sendern und Favoriten.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (repeat == 3 && !quickOpenedThisPress) {
                quickOpenedThisPress = true
                openQuickZap()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> {
                if (overlay == Overlay.INFO) showTech = !showTech
                showOverlay(Overlay.INFO)
            }
            // "Zurück zum letzten Sender", wie beim Fernseher.
            KeyEvent.KEYCODE_LAST_CHANNEL, KeyEvent.KEYCODE_PROG_BLUE -> backZap()
            KeyEvent.KEYCODE_MENU -> openTracks()
            KeyEvent.KEYCODE_PROG_RED -> {
                toggleFavorite()
                showOverlay(Overlay.INFO)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> toggleLivePause(code)
            KeyEvent.KEYCODE_MEDIA_REWIND -> shiftLive(-(if (step > 0) step else 10_000L))
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (pipPlayer != null && !timeshifted && repeat == 0) swapPip() else shiftLive(if (step > 0) step else 30_000L)
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
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) playNextEpisode() else episodes.cancelCountdown()
            return true
        }
        if (overlay != Overlay.CONTROLS && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (nextCardVisible) {
                playNextEpisode()
                return true
            }
            if (introVisible) {
                episodes.skipIntro()
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
        // Fire-TV-Startseite: Weiterschauen mit dem neuen Stand.
        container.scope.launch {
            delay(1_000)
            runCatching { de.ayutv.data.FireTvHome.update(applicationContext, container) }
        }
        vod?.let(episodes::learnOpening)
        if (isFinishing) player?.let { episodes.learnCredits(it.currentPosition) }
        saveAheadTranslations()
        recordWatch()
        container.scope.launch(Dispatchers.IO) { de.ayutv.data.parse.ChannelQuality.saveIfChanged(container.qualityFile) }
        // Für den Fehlerbericht: was zuletzt lief und was der Player gemeldet hat.
        de.ayutv.data.Diagnostics.savePlayerSession(
            this,
            if (isLive) playing?.name.orEmpty() else vodTitle,
            statusHistory + listOf("Technik: $stats", "Abbrüche: ${dropInfo.ifEmpty { "keine" }}"),
        )
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
        closePip()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        handler.removeCallbacksAndMessages(null)
        recorder.stop()
        timeshift.buffer?.release()
        timeshift.buffer = null
        frameRateMatcher.reset()
        audioAhead.release()
        closePip()
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
        private const val REMINDER_VISIBLE_MS = 40_000L
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
        /** Vorlauf der Übersetzung: so viel Film wird vorab übersetzt, höchstens so lange angehalten. */
        private const val PREROLL_WINDOW_MS = 60_000L
        private const val PREROLL_MAX_MS = 40_000L
        /** So oft wird ein hängender Übersetzer neu gestartet, bevor die Spur im Original bleibt. */
        private const val MAX_TRANSLATOR_RESTARTS = 2
        /** So viele Zeilen gehen höchstens in eine Anfrage an einen Übersetzer im Internet (DeepL erlaubt 50). */
        private const val ONLINE_BATCH = 40
        /** Nur in den ersten Sekunden entscheidet sich, ob angehalten wird. */
        private const val PREROLL_DECIDE_MS = 3_000L
        /** DeepL: Vorlauf beim Start und nach dem Spulen, mindestens und höchstens. */
        private const val ONLINE_PAUSE_MIN_MS = 8_000L
        private const val ONLINE_PAUSE_MAX_MS = 10_000L
        /** DeepL: So viel Film muss nach dem Vorlauf geladen und übersetzt sein. */
        private const val ONLINE_LEAD_MS = 30_000L
        /** Schon übersetzt gilt eine Stelle mit so vielen Zeilen in den nächsten zwei Minuten. */
        private const val PREPARED_WINDOW_MS = 120_000L
        private const val PREPARED_MIN_LINES = 10
        /** DeepL: So lange bleibt eine noch nicht übersetzte Zeile leer, bevor das Original erscheint. */
        private const val ONLINE_HOLD_MS = 1_500L
        /** DeepL: Ab so einem Sprung gibt es an der neuen Stelle einen kurzen Vorlauf. */
        private const val ONLINE_SEEK_JUMP_MS = 5_000L
        private const val DROP_WARN_COUNT = 3
        private const val FIRST_CONNECT_STALL_SECONDS = 15
        /** So viele eingebettete Einblendungen (gut 4 Minuten Dialog) ohne gemeinsamen Takt: falsche Datei. */
        private const val WRONG_FILE_REFERENCES = 60
        /** OpenSubtitles bleibt ab so viel Prozent Übereinstimmung, darunter wird zur Übersetzung der Spur gewechselt. */
        private const val FIT_PERCENT = 90
        /** So viele Einblendungen, bevor endgültig entschieden wird (meist die ersten 20–30 s Dialog, vorausgelesen). */
        private const val DECIDE_REFERENCES = 20
        /** Darunter ist die Datei schon bei den ersten Einblendungen klar daneben. */
        private const val EARLY_MISS_PERCENT = 40
        /** Zeilen-Abgleich: ab so viel Prozent übernehmen, darunter vergleichen. */
        private const val ELASTIC_MIN_PERCENT = 50
        private const val PIP_PREFS = "multiview"
        private const val PIP_SPLIT = "split"
        /** Weiter als so weit vom nächsten Anker weg gilt der Zeilen-Abgleich dort als ungeklärt. */
        private const val ELASTIC_COVER_MS = 90_000L
        /** So viele Einblendungen des Films, bevor weitere Dateien verglichen werden (Downloads sind begrenzt). */
        private const val COMPARE_REFERENCES = 40
        private const val SPEECH_CHECK_MS = 20_000L

        private fun clock(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
        private const val SPEECH_WINDOW_MS = 15 * 60_000L
        private const val GOAL_NOTICE_MS = 8_000L
        /** Liegt die vorherige Zeile höchstens so weit zurück, gehört sie zum Gespräch (Zusammenhang). */
        private const val CONTEXT_GAP_MS = 3_000L
        /** Ab diesem Sprung wird an der neuen Stelle nachgeprüft … */
        private const val SEEK_RECHECK_JUMP_MS = 3 * 60_000L
        /** … sobald so viel ab dort gelaufen ist … */
        private const val SEEK_RECHECK_MS = 90_000L
        /** … und nur ein sicherer Takt (wie „sicher“ in der Anzeige) ändert eine Ausrichtung von Hand. */
        private const val SEEK_RECHECK_VOTES = 80
        /** So viel Ton im Fenster ohne Übereinstimmung: falsche Datei. */
        private const val SPEECH_WRONG_FILE_SECONDS = 14 * 60
        /** Nach so viel Gespräch ohne Takt wird ein weiterer Treffer der Suche verglichen. */
        private const val SPEECH_COMPARE_SECONDS = 3 * 60
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
        /** Näher als das am Live-Bild gilt als live. */
        private const val LIVE_EDGE_MS = 4_000L
        /** Mindestens so viel freier Speicher, sonst kein Zeitversatz. */
        private const val TIMESHIFT_MIN_BYTES = 200L * 1024 * 1024
        private const val TIMESHIFT_MAX_BYTES = 4L * 1024 * 1024 * 1024
        private const val EXTRA_CHANNEL_KEY = "channel_key"
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_FAVORITES = "favorites"
        private const val EXTRA_FROM_START = "from_start"
        /** Startposition in ms (vom Handy übergeben). */
        const val EXTRA_START_AT = "start_at"
        private const val EXTRA_LIVE_URL = "live_url"
        private const val EXTRA_LIVE_NAME = "live_name"
        private const val EXTRA_LIVE_LOGO = "live_logo"
        private const val EXTRA_LIVE_AGENT = "live_agent"

        /** Sender, den es hier nicht gibt (vom Handy übergeben), direkt über seine Adresse spielen. */
        fun liveUrlIntent(context: Context, url: String, name: String, logo: String?, userAgent: String?): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_LIVE_URL, url)
                .putExtra(EXTRA_LIVE_NAME, name)
                .putExtra(EXTRA_LIVE_LOGO, logo)
                .putExtra(EXTRA_LIVE_AGENT, userAgent)
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
