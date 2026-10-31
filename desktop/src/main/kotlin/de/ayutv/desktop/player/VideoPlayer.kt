package de.ayutv.desktop.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import de.ayutv.player.SpeechActivity
import java.io.File
import java.nio.ByteBuffer
import javax.swing.SwingUtilities

/**
 * Wiedergabe mit VLC (libVLC über vlcj). Das Bild wird nicht in ein eigenes Fenster gezeichnet, sondern Bild für Bild
 * an die Oberfläche übergeben ([frame]); so liegen Senderinfo, Untertitel und Steuerleiste über dem Video.
 * VLC spielt praktisch jedes Format (TS, HLS, MKV, HEVC, Dolby, DTS) und kümmert sich um Pufferung und Neuverbinden.
 */
class VideoPlayer {
    /** Letztes Bild (null = noch keins). */
    var frame by mutableStateOf<ImageBitmap?>(null); private set
    var playing by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var positionMs by mutableLongStateOf(0L); private set
    var durationMs by mutableLongStateOf(0L); private set
    var volume by mutableStateOf(100); private set
    /** Untertitel- und Tonspuren (Kennung, Name). */
    var audioTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set
    var subtitleTracks by mutableStateOf<List<Pair<Int, String>>>(emptyList()); private set

    private val factory: MediaPlayerFactory? = runCatching { MediaPlayerFactory(*VLC_OPTIONS) }
        .onFailure { error = "VLC fehlt: ${it.message}" }.getOrNull()
    // Vor den Playern anlegen: [prepare] braucht sie schon beim Anlegen von [normal].
    private val format = Format()
    private val render = Render()

    /** Normaler Player: VLC gibt den Ton selbst aus (auch Raumklang/Durchreichen). */
    private val normal: EmbeddedMediaPlayer? = factory?.mediaPlayers()?.newEmbeddedMediaPlayer()?.also(::prepare)
    /** Player mit abgegriffenem Ton ([AudioTap]), für das Ausrichten der Untertitel; erst bei Bedarf angelegt. */
    private var tapped: EmbeddedMediaPlayer? = null
    /** Der gerade benutzte der beiden. */
    private var player: EmbeddedMediaPlayer? = normal

    /** Sprechpausen aus dem Ton (nur, solange [tapAudio] an ist). */
    val speech = SpeechActivity()
    private val tap = AudioTap(speech) { player?.status()?.time() ?: 0L }

    /** Ton für die Auswertung abgreifen (gilt ab dem nächsten [play]). */
    var tapAudio = false

    /** VLC-Version (null = VLC nicht geladen). */
    val vlcVersion: String? = runCatching { factory?.application()?.version() }.getOrNull()
    /** Gezeigte Bilder (für den Selbsttest). */
    val framesRendered = java.util.concurrent.atomic.AtomicLong()
    /** Zeitpunkt des letzten gezeigten Bildes (Watchdog: steht das Bild?). */
    @Volatile var lastFrameAt = 0L; private set
    /** Wann [play] aufgerufen wurde und wie lange es bis zum ersten Bild dauerte (Stream-Info). */
    @Volatile private var startedAt = 0L
    var startupMs by mutableLongStateOf(0L); private set
    /** Der Stream hat ein Bild (Radiosender nicht). */
    var hasVideo by mutableStateOf(true); private set
    /** Pausiert vom Nutzer (nicht: puffert). */
    var paused by mutableStateOf(false); private set
    /** Puffer für Netz-Streams in ms (Einstellungen: Schnell, Ausgewogen, Stabil). */
    var networkCacheMs = NETWORK_CACHE_MS

    private var bitmap: Bitmap? = null
    private var info: ImageInfo? = null
    private var pixels = ByteArray(0)

    private fun prepare(p: EmbeddedMediaPlayer) {
        val f = factory ?: return
        p.videoSurface().set(f.videoSurfaces().newVideoSurface(format, render, true))
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // Nur Meldungen des gerade benutzten Players zählen (der andere ist gestoppt).
            private fun mine(mediaPlayer: MediaPlayer) = mediaPlayer === player
            override fun playing(mediaPlayer: MediaPlayer) = ui {
                if (!mine(mediaPlayer)) return@ui
                playing = true; buffering = false; error = null; paused = false; readTracks()
                // Lautstärke gilt je Player: beim Wechsel mitnehmen.
                if (mediaPlayer.audio().volume() != volume) mediaPlayer.audio().setVolume(volume)
            }
            override fun paused(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) { playing = false; paused = true } }
            override fun stopped(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { if (mine(mediaPlayer)) buffering = newCache < 100f }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = ui { if (mine(mediaPlayer)) positionMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { if (mine(mediaPlayer)) durationMs = newLength }
            override fun error(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) { error = "Der Stream lässt sich nicht abspielen"; playing = false } }
            override fun finished(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) { playing = false; onFinished?.invoke() } }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = ui { if (mine(mediaPlayer)) readTracks() }
        })
    }

    private fun tappedPlayer(): EmbeddedMediaPlayer? = tapped ?: factory?.mediaPlayers()?.newEmbeddedMediaPlayer()?.also { p ->
        prepare(p)
        p.audio().callback(AudioTap.FORMAT, AudioTap.RATE, AudioTap.CHANNELS, tap, true)
        tapped = p
    }

    /** Wird am Ende eines Films aufgerufen. */
    var onFinished: (() -> Unit)? = null

    val available: Boolean get() = player != null

    fun play(url: String, userAgent: String? = null, startMs: Long = 0, vararg extra: String) {
        val next = (if (tapAudio) tappedPlayer() else normal) ?: return
        if (next !== player) runCatching { player?.controls()?.stop() }
        player = next
        val p = next
        if (tapAudio) {
            speech.clear()
            tap.restart()
        }
        error = null
        buffering = true
        paused = false
        hasVideo = true
        positionMs = startMs
        durationMs = 0
        startupMs = 0
        startedAt = System.currentTimeMillis()
        lastFrameAt = startedAt
        val options = buildList {
            userAgent?.let { add(":http-user-agent=$it") }
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
            add(":network-caching=$networkCacheMs")
            addAll(extra)
        }
        if (!p.media().play(url, *options.toTypedArray())) error = "Der Stream lässt sich nicht öffnen"
    }

    /** Technische Angaben zum laufenden Stream (für die Stream-Info, Taste ▶ bzw. Info). */
    fun streamInfo(): StreamInfo? {
        val p = player ?: return null
        return runCatching {
            val info = p.media().info()
            val video = info.videoTracks().firstOrNull()
            val audio = info.audioTracks().firstOrNull()
            val stats = info.statistics()
            StreamInfo(
                width = video?.width() ?: 0,
                height = video?.height() ?: 0,
                fps = video?.let { if (it.frameRateBase() > 0) it.frameRate().toDouble() / it.frameRateBase() else 0.0 } ?: 0.0,
                videoCodec = video?.codecDescription()?.takeIf { it.isNotBlank() } ?: video?.codecName().orEmpty(),
                audioCodec = audio?.codecDescription()?.takeIf { it.isNotBlank() } ?: audio?.codecName().orEmpty(),
                audioChannels = audio?.channels() ?: 0,
                inputKbps = ((stats?.inputBitrate() ?: 0f) * 8000).toInt(),
                demuxKbps = ((stats?.demuxBitrate() ?: 0f) * 8000).toInt(),
                displayed = stats?.picturesDisplayed() ?: 0,
                lost = stats?.picturesLost() ?: 0,
                corrupted = stats?.demuxCorrupted() ?: 0,
                startupMs = startupMs,
                cacheMs = networkCacheMs,
            )
        }.getOrNull()
    }

    fun togglePause() {
        val p = player ?: return
        if (p.status().isPlaying) p.controls().pause() else p.controls().play()
    }

    fun pause() { player?.takeIf { it.status().isPlaying }?.controls()?.pause() }
    fun resume() { player?.takeIf { !it.status().isPlaying }?.controls()?.play() }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val target = (p.status().time() + deltaMs).coerceIn(0, maxOf(0, p.status().length() - 1_000))
        p.controls().setTime(target)
    }

    fun seekTo(ms: Long) = player?.controls()?.setTime(ms.coerceAtLeast(0))

    fun changeVolume(delta: Int) {
        val p = player ?: return
        val next = (p.audio().volume() + delta).coerceIn(0, 150)
        p.audio().setVolume(next)
        volume = next
    }

    fun toggleMute() = player?.audio()?.mute()

    fun selectAudio(id: Int) = player?.audio()?.setTrack(id)
    fun selectSubtitle(id: Int) = player?.subpictures()?.setTrack(id)

    /** Externe Untertitel-Datei (SRT) einblenden. */
    fun addSubtitleFile(file: File) = player?.subpictures()?.setSubTitleFile(file)

    /** Untertitel verschieben (ms, positiv = später). */
    fun subtitleDelay(ms: Long) = player?.subpictures()?.setDelay(ms * 1000)

    /** Aufgeben (Watchdog): anhalten und [message] zeigen. */
    fun stopWithError(message: String) {
        runCatching { player?.controls()?.stop() }
        playing = false
        buffering = false
        error = message
    }

    fun stop() {
        player?.controls()?.stop()
        tap.close()
        frame = null
        playing = false
    }

    fun release() {
        runCatching { normal?.controls()?.stop() }
        runCatching { tapped?.controls()?.stop() }
        tap.close()
        runCatching { normal?.release() }
        runCatching { tapped?.release() }
        runCatching { factory?.release() }
    }

    private fun readTracks() {
        val p = player ?: return
        audioTracks = p.audio().trackDescriptions().map { it.id() to it.description() }
        hasVideo = runCatching { p.video().trackCount() > 0 }.getOrDefault(true)
        subtitleTracks = p.subpictures().trackDescriptions().map { it.id() to it.description() }
    }

    private inner class Format : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            pixels = ByteArray(sourceWidth * sourceHeight * 4)
            bitmap = Bitmap().apply { allocPixels(info!!) }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private inner class Render : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit
        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val target = bitmap ?: return
            val imageInfo = info ?: return
            val buffer = nativeBuffers[0]
            buffer.rewind()
            if (buffer.remaining() < pixels.size) return
            buffer.get(pixels)
            target.installPixels(imageInfo, pixels, imageInfo.width * 4)
            val image = target.asComposeImageBitmap()
            framesRendered.incrementAndGet()
            val now = System.currentTimeMillis()
            lastFrameAt = now
            if (startupMs == 0L && startedAt > 0) {
                val took = (now - startedAt).coerceAtLeast(1)
                ui { if (startupMs == 0L) startupMs = took }
            }
            ui { frame = image }
        }
    }

    private inline fun ui(crossinline block: () -> Unit) = SwingUtilities.invokeLater { block() }

    companion object {
        const val NETWORK_CACHE_MS = 2_500
        private val VLC_OPTIONS = arrayOf(
            "--quiet", "--no-video-title-show", "--http-reconnect", "--avcodec-hw=any",
            // Live-TV kommt oft als 1080i/576i: automatisch entflechten, mit voller Bildrate (50 statt 25 Bilder).
            "--deinterlace=-1", "--deinterlace-mode=yadif2x",
            // Kurze Aussetzer überbrücken statt abzubrechen.
            "--network-caching=$NETWORK_CACHE_MS",
        )

        /**
         * Mitgeliefertes VLC (im App-Ordner, Unterordner „vlc“) vor dem ersten Player bekannt machen. Ohne eigenes VLC
         * sucht vlcj ein installiertes.
         */
        fun useBundledVlc() {
            val dirs = listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
                File(System.getProperty("user.dir"), "vlc"),
            )
            val dir = dirs.firstOrNull { File(it, "libvlc.dll").isFile || File(it, "libvlc.so").isFile } ?: return
            val path = System.getProperty("jna.library.path")
            // Die Plugins findet libVLC selbst im Unterordner „plugins“ neben libvlc.dll.
            System.setProperty("jna.library.path", if (path.isNullOrEmpty()) dir.absolutePath else path + File.pathSeparator + dir.absolutePath)
        }
    }
}

/** Stream-Info: was gerade läuft und wie gut. */
data class StreamInfo(
    val width: Int,
    val height: Int,
    val fps: Double,
    val videoCodec: String,
    val audioCodec: String,
    val audioChannels: Int,
    /** Gelesene Daten (Leitung) und Video-/Ton-Daten in kbit/s. */
    val inputKbps: Int,
    val demuxKbps: Int,
    val displayed: Int,
    val lost: Int,
    val corrupted: Int,
    val startupMs: Long,
    val cacheMs: Int,
) {
    val resolution: String get() = when {
        height >= 2000 -> "4K (${width}×$height)"
        height >= 1000 -> "Full HD (${width}×$height)"
        height >= 700 -> "HD (${width}×$height)"
        height > 0 -> "SD (${width}×$height)"
        else -> "–"
    }

    /** Zeilen für die Anzeige. */
    fun lines(): List<Pair<String, String>> = listOf(
        "Auflösung" to resolution,
        "Bildrate" to if (fps > 0) "%.3f fps".format(java.util.Locale.GERMAN, fps).replace(",000", "") else "–",
        "Video" to videoCodec.ifBlank { "–" },
        "Ton" to (audioCodec.ifBlank { "–" } + if (audioChannels > 0) ", ${channelsLabel(audioChannels)}" else ""),
        "Leitung" to if (inputKbps > 0) mbit(inputKbps) else "–",
        "Datenrate" to if (demuxKbps > 0) mbit(demuxKbps) else "–",
        "Bilder" to "$displayed gezeigt, $lost verworfen" + if (corrupted > 0) ", $corrupted beschädigt" else "",
        "Startzeit" to if (startupMs > 0) "%.2f s".format(java.util.Locale.GERMAN, startupMs / 1000.0) else "–",
        "Puffer" to "%.1f s".format(java.util.Locale.GERMAN, cacheMs / 1000.0),
    )

    private fun mbit(kbps: Int) = "%.1f Mbit/s".format(java.util.Locale.GERMAN, kbps / 1000.0)
    private fun channelsLabel(n: Int) = when (n) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n Kanäle" }
}

/** Pufferprofile wie auf dem Fire TV. */
enum class BufferProfile(val label: String, val cacheMs: Int, val hint: String) {
    FAST("Schnell", 1_200, "kurze Umschaltzeit, für gute Leitungen"),
    BALANCED("Ausgewogen", VideoPlayer.NETWORK_CACHE_MS, "Standard"),
    STABLE("Stabil (4K)", 6_000, "großer Puffer gegen Ruckeln bei 4K und schwankender Leitung"),
    ;

    companion object {
        fun of(name: String) = entries.firstOrNull { it.name == name } ?: BALANCED
    }
}
