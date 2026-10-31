package de.ayutv.desktop.player

import de.ayutv.desktop.Log
import de.ayutv.desktop.data.Recording
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Aufnahmen: VLC schreibt den Stream im Hintergrund unverändert in eine TS-Datei (ohne Neukodieren, auch HLS),
 * während man weiter schaut. Jede laufende Aufnahme hat einen eigenen, unsichtbaren Player – beim Anbieter ist das
 * eine zweite Verbindung.
 */
class Recorder(val folder: File = defaultFolder()) {
    private val factory: MediaPlayerFactory? by lazy { runCatching { MediaPlayerFactory("--quiet", "--no-video", "--http-reconnect") }.getOrNull() }
    private val running = HashMap<Long, MediaPlayer>()

    val available: Boolean get() = factory != null

    /** Datei für eine Aufnahme: „2026-10-31 20.15 Sender – Titel.ts“. */
    fun fileFor(channelName: String, title: String, start: Long): File {
        folder.mkdirs()
        val name = "${NAME_TIME.format(Instant.ofEpochMilli(start))} $channelName – $title".replace(Regex("""[\\/:*?"<>|]"""), "_").take(150)
        var file = File(folder, "$name.ts")
        var n = 2
        while (file.exists()) file = File(folder, "$name ($n).ts").also { n++ }
        return file
    }

    @Synchronized
    fun start(recording: Recording, url: String, userAgent: String, extra: List<String> = emptyList()): Boolean {
        if (recording.id in running) return true
        val f = factory ?: return false
        val player = f.mediaPlayers().newMediaPlayer()
        val path = recording.file.replace("\"", "")
        val ok = player.media().play(
            url,
            ":http-user-agent=$userAgent",
            ":sout=#std{access=file,mux=ts,dst=\"$path\"}",
            ":sout-keep",
            ":sout-all",
            *extra.toTypedArray(),
        )
        if (!ok) {
            player.release()
            return false
        }
        running[recording.id] = player
        Log.info("Aufnahme gestartet: ${recording.title}")
        return true
    }

    @Synchronized
    fun stop(id: Long) {
        val player = running.remove(id) ?: return
        runCatching { player.controls().stop() }
        runCatching { player.release() }
        Log.info("Aufnahme beendet: $id")
    }

    @Synchronized
    fun isRunning(id: Long) = id in running

    @Synchronized
    fun release() {
        running.keys.toList().forEach(::stop)
        runCatching { factory?.release() }
    }

    companion object {
        private val NAME_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm").withZone(ZoneId.systemDefault())

        /** Videos\DnA-TV im Benutzerordner. */
        fun defaultFolder(): File = File(System.getProperty("user.home"), "Videos" + File.separator + "DnA-TV")
    }
}
