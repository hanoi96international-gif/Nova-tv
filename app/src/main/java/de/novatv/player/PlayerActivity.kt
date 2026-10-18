@file:OptIn(UnstableApi::class)

package de.novatv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import de.novatv.NovaApp
import de.novatv.data.AppSettings
import de.novatv.data.LiveFormat
import de.novatv.data.db.ChannelEntity
import de.novatv.data.db.HistoryEntity
import de.novatv.data.db.HistoryKind
import de.novatv.data.db.ProgrammeEntity
import de.novatv.ui.theme.NovaTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

enum class Overlay { NONE, INFO, CHANNELS, CONTROLS }

class PlayerActivity : ComponentActivity() {

    private val container by lazy { (application as NovaApp).container }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var frameRateMatcher: FrameRateMatcher
    private lateinit var settings: AppSettings
    private var headers: PlayerFactory.HeaderProvider? = null

    // Zustand, den die Compose-Oberfläche liest
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var isLive by mutableStateOf(true); private set
    var overlay by mutableStateOf(Overlay.NONE); private set
    var overlayToken by mutableIntStateOf(0); private set
    var channels by mutableStateOf<List<ChannelEntity>>(emptyList()); private set
    var current by mutableStateOf<ChannelEntity?>(null); private set
    var nowNext by mutableStateOf<List<ProgrammeEntity>>(emptyList()); private set
    var nowPlayingMap by mutableStateOf<Map<String, ProgrammeEntity>>(emptyMap()); private set
    var numberInput by mutableStateOf(""); private set
    var message by mutableStateOf<String?>(null); private set
    var buffering by mutableStateOf(true); private set
    var showTech by mutableStateOf(false); private set
    var stats by mutableStateOf(StreamStats()); private set
    var vodTitle by mutableStateOf(""); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var isFavorite by mutableStateOf(false); private set

    private var vod: VodRequest? = null
    private var retries = 0
    private var triedHls = false
    private var zapStartedAt = 0L
    private var progressJob: Job? = null
    private var numberJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        frameRateMatcher = FrameRateMatcher(this)
        vod = intent.getVodRequest()
        isLive = vod == null

        setContent { NovaTheme { PlayerScreen(this) } }

        lifecycleScope.launch {
            settings = container.settings.current()
            val built = PlayerFactory.create(this@PlayerActivity, container.http, settings)
            headers = built.headers
            built.player.addListener(playerListener)
            built.player.addAnalyticsListener(analyticsListener)
            player = built.player

            val request = vod
            if (request != null) {
                vodTitle = request.title
                val resume = container.content.resumePosition(request.key)
                startVod(request, resume)
            } else {
                val key = intent.getStringExtra(EXTRA_CHANNEL_KEY)
                val group = intent.getStringExtra(EXTRA_GROUP)
                val favoritesOnly = intent.getBooleanExtra(EXTRA_FAVORITES, false)
                channels = container.content.zapList(group, favoritesOnly)
                val start = key?.let { k -> channels.firstOrNull { it.key == k } ?: container.content.channel(k) }
                    ?: channels.firstOrNull()
                if (start == null) {
                    finish()
                    return@launch
                }
                if (channels.none { it.key == start.key }) channels = listOf(start) + channels
                playChannel(start)
            }
        }
    }

    // ---------------------------------------------------------------- Live

    fun playChannel(channel: ChannelEntity) {
        val p = player ?: return
        current = channel
        retries = 0
        triedHls = false
        message = null
        zapStartedAt = SystemClock.elapsedRealtime()
        stats = StreamStats()
        headers?.userAgent = channel.userAgent ?: settings.userAgent
        frameRateMatcher.reset()

        val url = adaptLiveUrl(channel.url)
        p.setMediaItem(mediaItem(url, guessMime(url)))
        p.prepare()
        p.playWhenReady = true
        showOverlay(Overlay.INFO)

        lifecycleScope.launch {
            container.content.recordLive(channel)
            nowNext = container.content.upcoming(channel, 3)
            isFavorite = container.db.favorites().isFavorite(channel.key)
        }
    }

    private fun zap(step: Int) {
        val list = channels
        val now = current ?: return
        if (list.size < 2) return
        val index = list.indexOfFirst { it.key == now.key }.coerceAtLeast(0)
        playChannel(list[(index + step + list.size) % list.size])
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
        numberJob = lifecycleScope.launch {
            delay(1_500)
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
        p.setMediaItem(mediaItem(request.url, guessMime(request.url)), resumeMs.coerceAtLeast(0))
        p.prepare()
        p.playWhenReady = true
        showOverlay(Overlay.CONTROLS)
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            var tick = 0
            while (isActive) {
                positionMs = p.currentPosition
                durationMs = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                if (++tick % 15 == 0) saveProgress()
                delay(1_000)
            }
        }
    }

    private fun saveProgress() {
        val request = vod ?: return
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

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, duration))
        positionMs = p.currentPosition
        showOverlay(Overlay.CONTROLS)
    }

    // ---------------------------------------------------------------- Gemeinsam

    private fun mediaItem(url: String, mime: String?): MediaItem =
        MediaItem.Builder().setUri(url).apply { if (mime != null) setMimeType(mime) }.build()

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
            }
            if (state == Player.STATE_ENDED && !isLive) finish()
        }

        override fun onRenderedFirstFrame() {
            if (zapStartedAt > 0) {
                stats = stats.copy(startupMs = SystemClock.elapsedRealtime() - zapStartedAt)
                zapStartedAt = 0
            }
        }

        override fun onTracksChanged(tracks: Tracks) = updateFormats()

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateFormats()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            when {
                error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    p.seekToDefaultPosition()
                    p.prepare()
                }
                // Viele Playlists liefern HLS ohne .m3u8-Endung. Dann einmal explizit als HLS versuchen.
                !triedHls && (
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                    ) -> {
                    triedHls = true
                    val uri = p.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
                    p.setMediaItem(mediaItem(uri, MimeTypes.APPLICATION_M3U8))
                    p.prepare()
                }
                retries < MAX_RETRIES -> {
                    retries++
                    message = "Verbindung wird wiederhergestellt … ($retries/$MAX_RETRIES)"
                    handler.postDelayed({ player?.prepare() }, 800L * retries)
                }
                else -> message = "Stream nicht verfügbar (${error.errorCodeName})"
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
            frameRate = video?.frameRate ?: 0f,
            videoCodec = video?.sampleMimeType?.substringAfter('/') ?: "",
            videoBitrate = video?.bitrate ?: 0,
            audioCodec = audio?.sampleMimeType?.substringAfter('/') ?: "",
            audioChannels = audio?.channelCount ?: 0,
        )
        if (settings.matchFrameRate) video?.frameRate?.let(frameRateMatcher::match)
    }

    // ---------------------------------------------------------------- Tasten

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                if (overlay != Overlay.NONE) closeOverlay() else finish()
            }
            return true
        }
        // Noch nicht bereit (Einstellungen/Player werden geladen).
        if (player == null) return super.dispatchKeyEvent(event)
        // In der Senderliste übernimmt Compose die Navigation.
        if (overlay == Overlay.CHANNELS) {
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
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_PROG_RED -> {
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
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // Live-Streams im Hintergrund nicht weiterladen: spart Bandbreite und eine Verbindung beim Anbieter.
        player?.let { p -> if (isLive) p.stop() else p.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        frameRateMatcher.reset()
        player?.release()
        player = null
    }

    companion object {
        private const val MAX_RETRIES = 3
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
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        )

        fun startLive(context: Context, channelKey: String, group: String?, favoritesOnly: Boolean = false) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_CHANNEL_KEY, channelKey)
                    .putExtra(EXTRA_GROUP, group)
                    .putExtra(EXTRA_FAVORITES, favoritesOnly)
            )
        }

        fun startVod(context: Context, request: VodRequest) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra(EXTRA_VOD_KEY, request.key)
                    .putExtra(EXTRA_VOD_KIND, request.kind.name)
                    .putExtra(EXTRA_VOD_TITLE, request.title)
                    .putExtra(EXTRA_VOD_SUBTITLE, request.subtitle)
                    .putExtra(EXTRA_VOD_IMAGE, request.image)
                    .putExtra(EXTRA_VOD_URL, request.url)
            )
        }

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
