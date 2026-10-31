package de.ayutv.desktop.player

import de.ayutv.player.SpeechActivity
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.callback.DefaultAudioCallbackAdapter
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Ton selbst ausgeben, um ihn nebenbei auszuwerten: VLC liefert die Samples (16 Bit, Stereo, 48 kHz), wir spielen sie
 * über Java Sound und füttern daraus [speech] (Sprechpausen für das Ausrichten der Untertitel). Nur für Filme und
 * Folgen mit geladenen Untertiteln; sonst gibt VLC den Ton wie gewohnt selbst aus (auch Raumklang).
 */
class AudioTap(val speech: SpeechActivity, private val positionMs: () -> Long) : DefaultAudioCallbackAdapter(BYTES_PER_FRAME) {
    private val format = AudioFormat(RATE.toFloat(), 16, CHANNELS, true, false)
    private var line: SourceDataLine? = null
    /** Kein Tonausgang (z. B. ohne Lautsprecher): nicht bei jedem Stück neu versuchen, aber weiter auswerten. */
    private var noLine = false
    @Volatile private var gain = 1f
    @Volatile private var restartPending = true
    private var mono = FloatArray(0)

    /** Nach Start oder Sprung: Position neu nehmen. */
    fun restart() {
        restartPending = true
    }

    override fun onPlay(mediaPlayer: MediaPlayer, data: ByteArray, sampleCount: Int, pts: Long) = consume(data, sampleCount)

    /** [sampleCount] Stereo-Samples (16 Bit) auswerten und ausgeben. */
    fun consume(data: ByteArray, sampleCount: Int) {
        val out = line ?: if (noLine) null else open()
        if (restartPending) {
            restartPending = false
            speech.restart(positionMs().coerceAtLeast(0), RATE)
        }
        if (mono.size < sampleCount) mono = FloatArray(sampleCount)
        val g = gain
        for (i in 0 until sampleCount) {
            val o = i * BYTES_PER_FRAME
            val left = (data[o].toInt() and 0xFF) or (data[o + 1].toInt() shl 8)
            val right = (data[o + 2].toInt() and 0xFF) or (data[o + 3].toInt() shl 8)
            mono[i] = (left + right) / 65_536f
            if (g != 1f) {
                put(data, o, (left * g).toInt())
                put(data, o + 2, (right * g).toInt())
            }
        }
        speech.add(mono, sampleCount)
        out?.write(data, 0, sampleCount * BYTES_PER_FRAME)
    }

    override fun pause(mediaPlayer: MediaPlayer, pts: Long) { line?.stop() }
    override fun resume(mediaPlayer: MediaPlayer, pts: Long) { line?.start() }
    override fun flush(mediaPlayer: MediaPlayer, pts: Long) {
        line?.flush()
        restart()
    }
    override fun drain(mediaPlayer: MediaPlayer) { line?.drain() }
    override fun setVolume(volume: Float, mute: Boolean) {
        gain = if (mute) 0f else volume
    }

    fun close() {
        runCatching { line?.stop(); line?.close() }
        line = null
        noLine = false
    }

    private fun open(): SourceDataLine? = runCatching {
        AudioSystem.getSourceDataLine(format).apply {
            // Kleiner Puffer: Der Ton läuft sonst hinter dem Bild her (VLC rechnet mit sofortiger Ausgabe).
            open(format, BUFFER_FRAMES * BYTES_PER_FRAME)
            start()
        }
    }.getOrNull().also { line = it; noLine = it == null }

    private fun put(data: ByteArray, at: Int, value: Int) {
        val v = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        data[at] = v.toByte()
        data[at + 1] = (v shr 8).toByte()
    }

    companion object {
        const val RATE = 48_000
        const val CHANNELS = 2
        const val FORMAT = "S16N"
        private const val BYTES_PER_FRAME = 4
        private const val BUFFER_FRAMES = 3_072
    }
}
