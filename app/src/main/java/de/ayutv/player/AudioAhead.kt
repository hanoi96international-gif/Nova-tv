@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Ton nebenbei selbst dekodieren, nur um ihn auszuwerten (Lautstärke je 100 ms in [levels]). Nötig, wenn der Ton als
 * Dolby/DTS an Soundbar oder Receiver geht: Dann sieht die App ihn sonst nie ([SpeechTapProcessor] bekommt nichts) und
 * kann das Intro nicht am Ton erkennen. Gefüttert wird mit den Samples, die der Player gerade lädt, also 20–30 s vor dem
 * Bild. Läuft auf einem eigenen Thread; ein Fehler hier stört die Wiedergabe nie (dann gibt es eben keine Werte).
 */
class AudioAhead(val levels: SpeechActivity) {
    private class Sample(val format: Format, val timeUs: Long, val data: ByteArray)

    /** Nur dann Samples annehmen (sonst kostet es nichts). */
    @Volatile var active = false
    /** Nur Ton aus diesem Abschnitt des Films auswerten (ms). */
    @Volatile var untilMs = Long.MAX_VALUE

    private val queue = LinkedBlockingQueue<Sample>(QUEUE)
    @Volatile private var trackId: String? = null
    @Volatile private var generation = 0
    private var thread: Thread? = null

    // Nur auf dem eigenen Thread benutzt.
    private var codec: MediaCodec? = null
    private var codecFormat: Format? = null
    private var unsupported: String? = null
    private var expectedUs = Long.MIN_VALUE
    private var outRate = 0
    private var outChannels = 0
    private var outFloat = false
    private var nextOutUs = Long.MIN_VALUE
    private var mono = FloatArray(0)
    private var seenGeneration = 0

    /** Vom Lade-Thread des Players: Will die Auswertung dieses Sample haben? */
    fun wants(format: Format, timeUs: Long): Boolean {
        if (!active || timeUs / 1000 > untilMs) return false
        val id = trackId ?: return true
        return id == key(format)
    }

    /** Vom Lade-Thread des Players: Sample (fertige Bytes) übergeben. Ist der Thread im Rückstand, wird verworfen. */
    fun offer(format: Format, timeUs: Long, data: ByteArray) {
        if (trackId == null) trackId = key(format)
        if (!queue.offer(Sample(format, timeUs, data))) return
        ensureThread()
    }

    /** Anderer Film oder anderer Ton: alles zurücksetzen. */
    fun reset() {
        active = false
        untilMs = Long.MAX_VALUE
        trackId = null
        queue.clear()
        generation++
        levels.clear()
    }

    fun release() {
        active = false
        queue.clear()
        thread?.interrupt()
        thread = null
    }

    @Synchronized
    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "DnA-AudioAhead").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    private fun loop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val sample = queue.poll(30, TimeUnit.SECONDS) ?: break
                if (seenGeneration != generation) {
                    seenGeneration = generation
                    closeCodec()
                    unsupported = null
                }
                runCatching { decode(sample) }.onFailure {
                    Log.w(TAG, "Ton nicht auswertbar: ${it.message}")
                    closeCodec()
                    unsupported = sample.format.sampleMimeType
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            closeCodec()
        }
    }

    private fun decode(sample: Sample) {
        if (sample.format.sampleMimeType == unsupported) return
        val current = codec?.takeIf { codecFormat?.sampleMimeType == sample.format.sampleMimeType && codecFormat?.sampleRate == sample.format.sampleRate }
            ?: open(sample.format) ?: return
        // Sprung im Film (Spulen): Decoder leeren, die Lautstärke beginnt an der neuen Stelle.
        if (expectedUs != Long.MIN_VALUE && abs(sample.timeUs - expectedUs) > JUMP_US) {
            current.flush()
            nextOutUs = Long.MIN_VALUE
        }
        expectedUs = sample.timeUs + FRAME_GUESS_US
        var input = -1
        repeat(INPUT_TRIES) {
            if (input < 0) {
                input = current.dequeueInputBuffer(TIMEOUT_US)
                if (input < 0) drain(current)
            }
        }
        if (input >= 0) {
            val buffer = current.getInputBuffer(input) ?: return
            buffer.clear()
            val size = minOf(sample.data.size, buffer.remaining())
            buffer.put(sample.data, 0, size)
            current.queueInputBuffer(input, 0, size, sample.timeUs, 0)
        }
        drain(current)
    }

    private fun drain(current: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = current.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val out = current.outputFormat
                    outRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    outChannels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    outFloat = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        out.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    nextOutUs = Long.MIN_VALUE
                }
                index >= 0 -> {
                    val buffer = current.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && outRate > 0 && outChannels > 0) {
                        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
                        data.position(info.offset)
                        val bytes = if (outFloat) 4 else 2
                        val frames = info.size / (bytes * outChannels)
                        // Lücke oder Sprung: an der Zeit des Ausgabe-Puffers neu ansetzen.
                        if (nextOutUs == Long.MIN_VALUE || abs(info.presentationTimeUs - nextOutUs) > JUMP_US) {
                            levels.restart(info.presentationTimeUs / 1000, outRate)
                        }
                        nextOutUs = info.presentationTimeUs + frames * 1_000_000L / outRate
                        if (mono.size < frames) mono = FloatArray(frames)
                        val center = outChannels == 3 || outChannels >= 6
                        val base = info.offset
                        fun sample(frame: Int, channel: Int): Float {
                            val at = base + (frame * outChannels + channel) * bytes
                            return if (outFloat) data.getFloat(at) else data.getShort(at) / 32768f
                        }
                        for (i in 0 until frames) {
                            mono[i] = when {
                                center -> sample(i, 2)
                                outChannels >= 2 -> (sample(i, 0) + sample(i, 1)) * 0.5f
                                else -> sample(i, 0)
                            }
                        }
                        levels.add(mono, frames)
                    }
                    current.releaseOutputBuffer(index, false)
                }
                else -> return
            }
        }
    }

    private fun open(format: Format): MediaCodec? {
        closeCodec()
        val mediaFormat = MediaFormatUtil.createMediaFormatFromFormat(format)
        // Ausgabe als 16-Bit-PCM, höchstens Stereo + Center genügt; manche Decoder mischen auf Wunsch herunter.
        mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(mediaFormat)
        if (name == null) {
            Log.i(TAG, "Kein Decoder für ${format.sampleMimeType}: Intro-Erkennung nur mit Ton im Gerät")
            unsupported = format.sampleMimeType
            return null
        }
        val created = MediaCodec.createByCodecName(name)
        created.configure(mediaFormat, null, null, 0)
        created.start()
        codec = created
        codecFormat = format
        expectedUs = Long.MIN_VALUE
        nextOutUs = Long.MIN_VALUE
        outRate = format.sampleRate.coerceAtLeast(0)
        outChannels = format.channelCount.coerceAtLeast(0)
        outFloat = false
        Log.i(TAG, "Werte Ton nebenbei aus: ${format.sampleMimeType} mit $name")
        return created
    }

    private fun closeCodec() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        codecFormat = null
    }

    private fun key(format: Format) = format.id ?: format.sampleMimeType ?: "ton"

    private companion object {
        const val TAG = "DnAAudioAhead"
        const val QUEUE = 600
        const val TIMEOUT_US = 20_000L
        const val INPUT_TRIES = 10
        const val JUMP_US = 500_000L
        /** Grobe Länge eines Ton-Pakets (Dolby: 32 ms); nur um Sprünge zu erkennen. */
        const val FRAME_GUESS_US = 32_000L
    }
}
