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

    private val factory: MediaPlayerFactory? = runCatching { MediaPlayerFactory(*(VLC_OPTIONS + cacheOptions())) }
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

    // Bild-Übergabe von VLC an die Oberfläche: VLC kopiert nur Bytes (zwei Puffer im Wechsel), das Skia-Bild wird
    // ausschließlich im Oberflächen-Thread befüllt (zwei Bitmaps im Wechsel). So schreibt nie ein Thread in ein Bild,
    // das gerade gezeichnet wird (das ließ die App vor allem im Vollbild abstürzen).
    @Volatile private var info: ImageInfo? = null
    private val buffers = arrayOf(ByteArray(0), ByteArray(0))
    private var writeIndex = 0
    private val bitmaps = arrayOf(Bitmap(), Bitmap())
    private var bitmapIndex = 0
    /** Die Oberfläche hat das letzte Bild noch nicht übernommen: weitere Bilder bis dahin auslassen. */
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun prepare(p: EmbeddedMediaPlayer) {
        val f = factory ?: return
        p.videoSurface().set(f.videoSurfaces().newVideoSurface(format, render, true))
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // Nur Meldungen des gerade benutzten Players zählen (der andere ist gestoppt).
            private fun mine(mediaPlayer: MediaPlayer) = mediaPlayer === player
            override fun playing(mediaPlayer: MediaPlayer) = ui {
                if (!mine(mediaPlayer)) return@ui
                playing = true; buffering = false; error = null; readTracks()
                // Lautstärke gilt je Player: beim Wechsel mitnehmen.
                if (mediaPlayer.audio().volume() != volume) mediaPlayer.audio().setVolume(volume)
            }
            override fun paused(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) playing = false }
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
        positionMs = startMs
        durationMs = 0
        val options = buildList {
            userAgent?.let { add(":http-user-agent=$it") }
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
            add(":network-caching=$NETWORK_CACHE_MS")
            addAll(extra)
        }
        p.media().play(url, *options.toTypedArray())
    }

    fun togglePause() {
        val p = player ?: return
        if (p.status().isPlaying) p.controls().pause() else p.controls().play()
    }

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

    fun stop() {
        player?.controls()?.stop()
        tap.close()
        frame = null
        pending.set(false)
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
        subtitleTracks = p.subpictures().trackDescriptions().map { it.id() to it.description() }
    }

    private inner class Format : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) = Unit
        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private inner class Render : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit
        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val imageInfo = info ?: return
            if (pending.get()) return
            val size = imageInfo.width * imageInfo.height * 4
            val index = writeIndex
            if (buffers[index].size != size) buffers[index] = ByteArray(size)
            val target = buffers[index]
            val buffer = nativeBuffers[0]
            buffer.rewind()
            if (buffer.remaining() < size) return
            buffer.get(target)
            writeIndex = 1 - index
            pending.set(true)
            framesRendered.incrementAndGet()
            ui {
                try {
                    val bitmap = bitmaps[bitmapIndex]
                    bitmapIndex = 1 - bitmapIndex
                    if (bitmap.installPixels(imageInfo, target, imageInfo.width * 4)) frame = bitmap.asComposeImageBitmap()
                } finally {
                    pending.set(false)
                }
            }
        }
    }

    private inline fun ui(crossinline block: () -> Unit) = SwingUtilities.invokeLater { block() }

    companion object {
        private const val NETWORK_CACHE_MS = 3_000
        private val VLC_OPTIONS = arrayOf("--quiet", "--no-video-title-show", "--http-reconnect", "--avcodec-hw=any")

        /**
         * Mitgeliefertes VLC (im App-Ordner, Unterordner „vlc“) vor dem ersten Player bekannt machen. Ohne eigenes VLC
         * sucht vlcj ein installiertes.
         */
        /** Ordner des mitgelieferten VLC (null = installiertes VLC). */
        private var bundledDir: File? = null

        /**
         * Plugin-Zwischenspeicher: Ohne ihn liest VLC bei jedem Start alle rund 300 Plugins ein (mit Virenscanner
         * viele Sekunden). Fehlt er oder gehört er zu einer anderen VLC-Fassung (Update), legt VLC ihn diesmal neu an;
         * danach startet es schnell.
         */
        private fun cacheOptions(): Array<String> {
            val plugins = bundledDir?.let { File(it, "plugins") } ?: return emptyArray()
            if (!plugins.isDirectory || !plugins.canWrite()) return emptyArray()
            val core = File(bundledDir, "libvlccore.dll").takeIf { it.isFile } ?: File(bundledDir, "libvlccore.so")
            val signature = "${core.length()}-${core.lastModified()}"
            val marker = File(plugins, "dna-tv-cache.txt")
            val cache = File(plugins, "plugins.dat")
            val current = cache.isFile && runCatching { marker.readText() }.getOrNull() == signature
            if (current) return emptyArray()
            runCatching { marker.writeText(signature) }
            return arrayOf("--reset-plugins-cache")
        }

        fun useBundledVlc() {
            val dirs = listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") },
                File(System.getProperty("user.dir"), "vlc"),
            )
            val dir = dirs.firstOrNull { File(it, "libvlc.dll").isFile || File(it, "libvlc.so").isFile } ?: return
            bundledDir = dir
            val path = System.getProperty("jna.library.path")
            // Die Plugins findet libVLC selbst im Unterordner „plugins“ neben libvlc.dll.
            System.setProperty("jna.library.path", if (path.isNullOrEmpty()) dir.absolutePath else path + File.pathSeparator + dir.absolutePath)
        }
    }
}
