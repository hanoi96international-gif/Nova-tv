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

    /**
     * Direktausgabe ([DirectWindow]): VLC dekodiert und zeigt das Bild komplett auf der Grafikkarte (Direct3D 11, ohne
     * Umweg über den Hauptspeicher). OFF = nie, AUTO = bei 4K (Standard: sonst kommt der PC bei 4K mit 50 Bildern nicht
     * hinterher und das Bild läuft wie in Zeitlupe), ALWAYS = immer (z. B. HDR-Bildschirm). Nicht beim Ausrichten von
     * Untertiteln am Ton.
     */
    @Volatile var directMode = "AUTO"
    /** Vom Aufrufer vor [play]: Der Stream ist bekanntlich 4K (früher gemessen oder „4K/UHD“ im Namen). */
    @Volatile var expectUhd = false
    /** Fenster für die Direktausgabe (von der App gesetzt, kennt das Hauptfenster). */
    var directWindow: DirectWindow? = null
    private var directPlayer: EmbeddedMediaPlayer? = null
    /** Läuft gerade über die Direktausgabe. */
    val isDirect: Boolean get() = player != null && player === directPlayer
    /** Letzter Start (für den Wechsel auf die Direktausgabe, sobald 4K erkannt wird). */
    private var lastStart: Triple<String, String?, Array<out String>>? = null
    @Volatile private var switching = false

    /** Läuft auf dem normalen Weg, ist aber 4K: auf die Direktausgabe wechseln (an derselben Stelle). */
    private fun switchToDirect() {
        if (directMode != "AUTO" || isDirect || tapAudio || directWindow == null || switching) return
        val (url, agent, extra) = lastStart ?: return
        switching = true
        try {
            de.ayutv.desktop.Log.info("4K erkannt: wechsle auf die Direktausgabe")
            val at = player?.status()?.time()?.takeIf { durationMs > 0 && it > 0 } ?: 0L
            expectUhd = true
            play(url, agent, at, *extra)
        } finally {
            switching = false
        }
    }

    private fun directPlayer(): EmbeddedMediaPlayer? {
        directPlayer?.let { return it }
        val f = factory ?: return null
        val window = directWindow ?: return null
        window.prepare()
        return f.mediaPlayers().newEmbeddedMediaPlayer().also { p ->
            p.videoSurface().set(f.videoSurfaces().newVideoSurface(window.canvas))
            // Tasten und Maus nicht von VLC schlucken lassen: Sie gehen an DnA-TV weiter.
            runCatching { p.input().enableKeyInputHandling(false); p.input().enableMouseInputHandling(false) }
            listen(p)
            directPlayer = p
        }
    }

    /** Menüs oder Senderliste offen: Direktausgabe kurz ausblenden (die Oberfläche liegt im Hauptfenster). */
    fun coverDirect(covered: Boolean) {
        directWindow?.cover(covered)
    }

    /** Kurzer Text im Bild der Direktausgabe (z. B. Sendername beim Umschalten); sonst nichts. */
    fun osd(text: String) {
        val p = player?.takeIf { it === directPlayer } ?: return
        runCatching {
            p.marquee().setText(text)
            p.marquee().setSize(40)
            p.marquee().setColour(java.awt.Color.WHITE)
            p.marquee().setPosition(uk.co.caprica.vlcj.player.base.MarqueePosition.TOP_LEFT)
            p.marquee().setTimeout(4_000)
            p.marquee().enable(true)
        }
    }

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
        listen(p)
    }

    private fun listen(p: EmbeddedMediaPlayer) {
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            // Nur Meldungen des gerade benutzten Players zählen (der andere ist gestoppt).
            private fun mine(mediaPlayer: MediaPlayer) = mediaPlayer === player
            override fun playing(mediaPlayer: MediaPlayer) = ui {
                if (!mine(mediaPlayer)) return@ui
                detectHdr(mediaPlayer)
                playing = true; buffering = false; error = null; paused = false; readTracks()
                if (muted) mediaPlayer.audio().setMute(true)
                // Lautstärke gilt je Player: beim Wechsel mitnehmen.
                if (mediaPlayer.audio().volume() != volume) mediaPlayer.audio().setVolume(volume)
            }
            override fun paused(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) { playing = false; paused = true } }
            override fun stopped(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = ui { if (mine(mediaPlayer)) buffering = newCache < 100f }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                // Direktausgabe: Bilder sehen wir nicht; laufende Zeit zählt als Bild (für den Watchdog).
                if (mediaPlayer === directPlayer && mediaPlayer === player) directFrame()
                ui { if (mine(mediaPlayer)) positionMs = newTime }
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = ui { if (mine(mediaPlayer)) durationMs = newLength }
            override fun error(mediaPlayer: MediaPlayer) = ui {
                if (mine(mediaPlayer)) {
                    error = "Der Stream lässt sich nicht abspielen"; playing = false
                    de.ayutv.desktop.Log.warn("VLC-Fehler: ${runCatching { mediaPlayer.media().info().mrl() }.getOrNull().orEmpty()}")
                }
            }
            override fun finished(mediaPlayer: MediaPlayer) = ui { if (mine(mediaPlayer)) { playing = false; onFinished?.invoke() } }
            override fun elementaryStreamAdded(mediaPlayer: MediaPlayer, type: uk.co.caprica.vlcj.media.TrackType, id: Int) = ui {
                if (mine(mediaPlayer)) { readTracks(); detectHdr(mediaPlayer) }
            }
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
        lastStart = Triple(url, userAgent, extra)
        val wantDirect = directMode == "ALWAYS" || (directMode == "AUTO" && expectUhd)
        val uhdExpected = expectUhd
        expectUhd = false
        val useDirect = wantDirect && !tapAudio && directWindow != null
        val next = (if (useDirect) directPlayer() else if (tapAudio) tappedPlayer() else normal) ?: return
        if (next !== player) runCatching { player?.controls()?.stop() }
        player = next
        if (next === directPlayer) {
            clearFrames()
            directWindow?.show()
        } else {
            directWindow?.hide()
        }
        val p = next
        if (tapAudio) {
            speech.clear()
            tap.restart()
        }
        error = null
        buffering = true
        paused = false
        hasVideo = true
        looksHdr = false
        updateTransfer()
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
            addAll(pictureOptions(uhdExpected))
        }
        if (!p.media().play(url, *options.toTypedArray())) error = "Der Stream lässt sich nicht öffnen"
        applyAdjust()
    }

    /**
     * Bildoptimierung (Full HD): Schärfe über VLCs Filter „sharpen“ (wirkt ab dem nächsten Start, deshalb als Option
     * beim Öffnen), Kontrast und Farbe über die Bildanpassung (sofort). null = alles neutral.
     */
    var picture: de.ayutv.data.parse.PictureTuning.Profile? = null
        set(value) {
            field = value
            applyAdjust()
        }

    /** Optionen für VLC beim Öffnen: Nachschärfen nur für Full HD und kleiner (4K/HDR bleiben unverändert). */
    fun pictureOptions(uhd: Boolean): List<String> {
        val profile = picture ?: return emptyList()
        if (uhd || profile.sharpen <= 0.01f) return emptyList()
        return listOf(":video-filter=sharpen", ":sharpen-sigma=${"%.2f".format(java.util.Locale.US, (profile.sharpen * 0.6f).coerceIn(0f, 2f))}")
    }

    private fun applyAdjust() {
        val p = player ?: return
        val profile = picture?.takeIf { !looksHdr }
        runCatching {
            if (profile == null || (profile.contrast == 0f && profile.saturation == 0f)) {
                p.video().setAdjustVideo(false)
            } else {
                p.video().setAdjustVideo(true)
                p.video().setContrast((1f + profile.contrast * 1.5f).coerceIn(0f, 2f))
                p.video().setSaturation((1f + profile.saturation / 100f).coerceIn(0f, 3f))
                p.video().setBrightness(1f)
            }
        }
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
                output = if (p === directPlayer) "Direkt über die Grafikkarte (VLC, Direct3D 11, Hardware-Dekodierung)" else (if (gpuVideo) "Grafikkarte (" + when (layout) {
                    YuvVideo.Layout.I420 -> "I420"
                    YuvVideo.Layout.NV12 -> "NV12"
                    YuvVideo.Layout.P010 -> "P010, 10 Bit"
                } + ")" else "RGB") + (when (transfer) {
                    YuvVideo.Transfer.PQ -> ", HDR10 angepasst"
                    YuvVideo.Transfer.HLG -> ", HLG angepasst"
                    YuvVideo.Transfer.SDR -> ""
                }),
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

    /** Stumm (Multiview: nur ein Feld hat Ton; Mini-Vorschau). Gilt sofort und für jeden weiteren Start. */
    @Volatile var muted = false
        set(value) {
            field = value
            runCatching { player?.audio()?.setMute(value) }
        }

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
        directWindow?.hide()
        tap.close()
        clearFrames()
        playing = false
    }

    fun release() {
        clearFrames()
        runCatching { normal?.controls()?.stop() }
        runCatching { tapped?.controls()?.stop() }
        tap.close()
        runCatching { directPlayer?.controls()?.stop() }
        runCatching { normal?.release() }
        runCatching { tapped?.release() }
        runCatching { directPlayer?.release() }
        runCatching { directWindow?.dispose() }
        runCatching { factory?.release() }
    }

    private fun readTracks() {
        val p = player ?: return
        audioTracks = p.audio().trackDescriptions().map { it.id() to it.description() }
        hasVideo = runCatching { p.video().trackCount() > 0 }.getOrDefault(true)
        subtitleTracks = p.subpictures().trackDescriptions().map { it.id() to it.description() }
    }

    // ---------------------------------------------------------------- Bilder

    /** Schneller Weg (YUV + Shader auf der Grafikkarte); false = RGB wie früher (falls die Grafik den Shader nicht kann). */
    val gpuVideo: Boolean = YuvVideo.available && System.getProperty("dnatv.video") != "rgb"
    /** Aktuelles Bild auf dem schnellen Weg (die Oberfläche zeichnet es, siehe VideoView). */
    private val yuv = java.util.concurrent.atomic.AtomicReference<YuvFrame?>(null)
    fun currentFrame(): YuvFrame? = yuv.get()
    /** Zählt jedes neue Bild (die Oberfläche zeichnet dann neu, ohne alles neu aufzubauen). */
    var frameTick by mutableLongStateOf(0L); private set
    /** Es gibt ein Bild (gleich welcher Weg). */
    var hasFrame by mutableStateOf(false); private set

    /** Seitenverhältnis, wie VLC es meldet (anamorphes SD: 720×576 als 16:9). */
    @Volatile private var aspect = 16f / 9f
    @Volatile private var planeWidth = 0
    @Volatile private var planeHeight = 0

    /**
     * Videoweg: AUTO = NV12, bei 10-Bit-Quellen (HDR, HEVC Main 10) P010 mit voller Genauigkeit; I420 = kompatibel wie
     * bis Version 0.42 (falls eine Grafikkarte mit dem neuen Weg Probleme hat). P010 nur für den Starttest erzwingbar.
     * Gilt ab dem nächsten [play].
     */
    @Volatile var videoLayout = "AUTO"
    /** Ablage der gerade gelieferten Bilder. */
    @Volatile var layout = YuvVideo.Layout.I420; private set

    /** Welche Ablage VLC liefern soll. */
    private fun chooseLayout(sourceHeight: Int): YuvVideo.Layout = when (videoLayout) {
        "I420" -> YuvVideo.Layout.I420
        "P010" -> YuvVideo.Layout.P010
        "NV12" -> YuvVideo.Layout.NV12
        else -> if (tenBitSource(sourceHeight)) YuvVideo.Layout.P010 else YuvVideo.Layout.NV12
    }

    /** 10 Bit: HDR erzwungen, HEVC Main 10 oder AV1 in UHD (VLC 3 meldet die Bittiefe nicht direkt). */
    private fun tenBitSource(sourceHeight: Int): Boolean {
        if (hdrMode == "PQ" || hdrMode == "HLG") return true
        if (hdrMode == "OFF") return false
        val track = runCatching { player?.media()?.info()?.videoTracks()?.firstOrNull() }.getOrNull() ?: return looksHdr
        val codec = track.codecName().orEmpty().lowercase()
        val hevcMain10 = (codec.contains("hevc") || codec.contains("h265")) && track.profile() == 2
        val av1 = codec.contains("av01") || codec.contains("av1")
        return hevcMain10 || (av1 && sourceHeight >= 2000)
    }

    /** HDR-Art des laufenden Streams (für den Shader); aus Einstellung und Codec bestimmt. */
    @Volatile var transfer = YuvVideo.Transfer.SDR; private set
    /** Einstellung: AUTO (HEVC/AV1 10 Bit ab 4K = HDR10), PQ, HLG, OFF. */
    @Volatile var hdrMode = "AUTO"
        set(value) { field = value; updateTransfer() }
    @Volatile private var looksHdr = false

    private fun updateTransfer() {
        transfer = when (hdrMode) {
            "PQ" -> YuvVideo.Transfer.PQ
            "HLG" -> YuvVideo.Transfer.HLG
            "OFF" -> YuvVideo.Transfer.SDR
            else -> if (looksHdr) YuvVideo.Transfer.PQ else YuvVideo.Transfer.SDR
        }
    }

    /** VLC 3 meldet keine HDR-Kennung: 10 Bit (Profil Main 10) bei HEVC/AV1 in UHD gilt als HDR10. */
    private fun detectHdr(p: MediaPlayer) {
        val track = runCatching { p.media().info().videoTracks().firstOrNull() }.getOrNull() ?: return
        val codec = track.codecName().orEmpty().lowercase()
        val hevcMain10 = (codec.contains("hevc") || codec.contains("h265")) && track.profile() == 2
        val av1 = codec.contains("av01") || codec.contains("av1")
        looksHdr = (hevcMain10 || av1) && track.height() >= 2000
        updateTransfer()
    }

    private inner class Format : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            aspect = if (sourceHeight > 0) sourceWidth.toFloat() / sourceHeight else 16f / 9f
            planeWidth = sourceWidth
            planeHeight = sourceHeight
            // 4K auf dem normalen Weg: gleich auf die Direktausgabe wechseln (bevor ruckelnde Bilder zu sehen sind).
            if (sourceHeight >= UHD_HEIGHT && directMode == "AUTO" && directWindow != null && !tapAudio) ui { switchToDirect() }
            if (gpuVideo) {
                // YUV wie vom Dekoder: VLC rechnet nichts in RGB um. NV12/P010 liefert die Hardware-Dekodierung direkt.
                val chosen = chooseLayout(sourceHeight)
                layout = chosen
                val (pitches, lines) = YuvVideo.planes(sourceWidth, sourceHeight, chosen)
                return BufferFormat(chosen.chroma, sourceWidth, sourceHeight, pitches, lines)
            }
            info = ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
            pixels = ByteArray(sourceWidth * sourceHeight * 4)
            bitmap = Bitmap().apply { allocPixels(info!!) }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }

        override fun newFormatSize(bufferWidth: Int, bufferHeight: Int, displayWidth: Int, displayHeight: Int) {
            if (displayWidth > 0 && displayHeight > 0) aspect = displayWidth.toFloat() / displayHeight
        }

        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) = Unit
    }

    private inner class Render : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit
        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat, displayWidth: Int, displayHeight: Int) {
            if (mediaPlayer !== player) return
            if (gpuVideo) showYuv(nativeBuffers, bufferFormat, displayWidth, displayHeight) else showRgb(nativeBuffers)
            framesRendered.incrementAndGet()
            val now = System.currentTimeMillis()
            lastFrameAt = now
            if (startupMs == 0L && startedAt > 0) {
                val took = (now - startedAt).coerceAtLeast(1)
                ui { if (startupMs == 0L) startupMs = took }
            }
        }

        private fun showYuv(buffers: Array<out ByteBuffer>, format: BufferFormat, displayWidth: Int, displayHeight: Int) {
            val l = layout
            if (buffers.size < l.planes) return
            val w = planeWidth.takeIf { it > 0 } ?: format.width
            val h = planeHeight.takeIf { it > 0 } ?: format.height
            val pitches = format.pitches
            val ch = (h + 1) / 2
            val frame = runCatching {
                YuvFrame(
                    YuvVideo.copyPlane(buffers[0], pitches[0], YuvVideo.byteWidth(l, 0, w), h),
                    YuvVideo.copyPlane(buffers[1], pitches[1], YuvVideo.byteWidth(l, 1, w), ch),
                    if (l == YuvVideo.Layout.I420) YuvVideo.copyPlane(buffers[2], pitches[2], YuvVideo.byteWidth(l, 2, w), ch) else null,
                    w, h,
                    if (displayWidth > 0 && displayHeight > 0 && (displayWidth != w || displayHeight != h)) displayWidth.toFloat() / displayHeight else aspect,
                    transfer,
                    layout = l,
                )
            }.getOrNull() ?: return
            val old = yuv.getAndSet(frame)
            // Alte Bilder erst ein paar Bilder später freigeben: Wer gerade noch eines zeichnet (Oberfläche,
            // Starttest), hält nie ein schon freigegebenes in der Hand.
            ui {
                if (old != null) retire(old)
                frameTick++
                if (!hasFrame) hasFrame = true
            }
        }

        private fun showRgb(buffers: Array<out ByteBuffer>) {
            val target = bitmap ?: return
            val imageInfo = info ?: return
            val buffer = buffers[0]
            buffer.rewind()
            if (buffer.remaining() < pixels.size) return
            buffer.get(pixels)
            target.installPixels(imageInfo, pixels, imageInfo.width * 4)
            val image = target.asComposeImageBitmap()
            ui { frame = image; if (!hasFrame) hasFrame = true }
        }
    }

    /** Zuletzt ersetzte Bilder (nur auf dem Oberflächen-Thread angefasst). */
    private val retired = ArrayDeque<YuvFrame>()

    private fun retire(frame: YuvFrame) {
        retired.addLast(frame)
        while (retired.size > RETIRED_FRAMES) retired.removeFirst().close()
    }

    /** Direktausgabe: Bild läuft (VLC zeigt es selbst). */
    private fun directFrame() {
        framesRendered.incrementAndGet()
        val now = System.currentTimeMillis()
        lastFrameAt = now
        if (startupMs == 0L && startedAt > 0) {
            val took = (now - startedAt).coerceAtLeast(1)
            ui { if (startupMs == 0L) startupMs = took; if (!hasFrame) hasFrame = true }
        }
    }

    /** Bilder des vorigen Streams verwerfen (kein altes Standbild beim nächsten). */
    private fun clearFrames() {
        val old = yuv.getAndSet(null)
        ui {
            if (old != null) retire(old)
            while (retired.isNotEmpty()) retired.removeFirst().close()
        }
        frame = null
        hasFrame = false
    }

    private inline fun ui(crossinline block: () -> Unit) = SwingUtilities.invokeLater { block() }

    companion object {
        const val NETWORK_CACHE_MS = 2_500
        /** Ab dieser Bildhöhe gilt ein Stream als 4K. */
        const val UHD_HEIGHT = 1_600
        /** So viele ersetzte Bilder bleiben kurz erhalten, bevor ihr Speicher frei wird. */
        private const val RETIRED_FRAMES = 3
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
    /** Videoweg und HDR („Grafikkarte, HDR10 angepasst“). */
    val output: String = "–",
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
        "Ausgabe" to output,
        "Bildrate" to if (fps > 0) "%.3f fps".format(java.util.Locale.GERMAN, fps).replace(",000", "") else "–",
        "Video" to videoCodec.ifBlank { "–" },
        "Ton" to (audioCodec.ifBlank { "–" } + if (audioChannels > 0) ", ${channelsLabel(audioChannels)}" else ""),
        "Empfangen" to if (inputKbps > 0) mbit(inputKbps) else "–",
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
