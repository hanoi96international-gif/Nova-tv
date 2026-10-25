package de.ayutv.player

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zwischenspeicher für Live-TV (Zeitversatz): Alles, was der Player von einem TS-Stream liest, landet
 * zusätzlich in Dateistücken auf dem Gerät. So lässt sich anhalten und zurückspulen, ohne dass der
 * Anbieter eine zweite Verbindung sieht. Die Zeit im Stream kommt aus der PCR (Programmuhr im TS);
 * daraus entsteht eine Marke etwa alle halbe Sekunde (Byte-Position ↔ Sendezeit).
 *
 * Ältere Stücke werden gelöscht, sobald [maxContentMs] Sendezeit oder [maxBytes] erreicht sind.
 */
class TimeshiftBuffer(
    private val dir: File,
    private val maxBytes: Long,
    private val maxContentMs: Long,
    /** Adresse, deren Daten mitgeschrieben werden, solange der Player live spielt. */
    val url: String,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val chunkBytes: Long = CHUNK_BYTES,
) {
    val id: Int = ids.incrementAndGet()

    /** Mitschreiben aus dem Player (live). Beim Zeitversatz schreibt stattdessen [TimeshiftRecorder]. */
    @Volatile var tapping = true

    private class Chunk(val start: Long, val file: File) {
        var length = 0L
    }

    private class Mark(val offset: Long, val ms: Long)

    /** Ort im Speicher für das nächste Lesen: Datei, Position darin und wie viel dort schon liegt. */
    class Slot(val file: File, val fileOffset: Long, val available: Int)

    private val lock = Object()
    private val chunks = ArrayDeque<Chunk>()
    private val marks = ArrayList<Mark>()
    private var out: FileOutputStream? = null
    private var end = 0L
    private var released = false

    // Uhr des Streams
    private var contentMs = 0L
    private var pcrPid = -1
    private var lastPcr = -1L
    private var lastPcrWall = -1L
    private var lastWriteWall = -1L

    // TS-Pakete, die über zwei Schreibvorgänge verteilt sind
    private val packet = ByteArray(TS_PACKET)
    private var packetFill = 0
    private var packetStart = 0L

    init {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
    }

    /** Erste noch gespeicherte Byte-Position. */
    val startOffset: Long get() = synchronized(lock) { chunks.firstOrNull()?.start ?: end }
    val endOffset: Long get() = synchronized(lock) { end }
    /** Sendezeit des neuesten gespeicherten Bildes (ms, fortlaufend seit Beginn der Aufnahme). */
    val latestMs: Long get() = synchronized(lock) { contentMs }
    val oldestMs: Long get() = synchronized(lock) { marks.firstOrNull()?.ms ?: contentMs }
    val hasData: Boolean get() = synchronized(lock) { marks.isNotEmpty() }
    val bytesStored: Long get() = synchronized(lock) { end - (chunks.firstOrNull()?.start ?: end) }

    fun write(data: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        synchronized(lock) {
            if (released) return
            index(data, off, len)
            var written = 0
            while (written < len) {
                val chunk = chunks.lastOrNull()?.takeIf { it.length < chunkBytes } ?: newChunk()
                val n = minOf((chunkBytes - chunk.length).toInt(), len - written)
                try {
                    out?.write(data, off + written, n)
                } catch (e: IOException) {
                    // Speicher voll o. Ä.: Zeitversatz für diesen Sender aufgeben, die Wiedergabe läuft weiter.
                    releaseLocked()
                    return
                }
                chunk.length += n
                written += n
                end += n
            }
            trim()
            lock.notifyAll()
        }
    }

    private fun newChunk(): Chunk {
        out?.runCatching { close() }
        val start = end
        val chunk = Chunk(start, File(dir, "ts-$id-$start.bin"))
        out = FileOutputStream(chunk.file)
        chunks.addLast(chunk)
        return chunk
    }

    private fun trim() {
        while (chunks.size > 1) {
            val first = chunks.first()
            val second = chunks[1]
            val tooBig = end - first.start > maxBytes
            val firstMsOfSecond = marks.firstOrNull { it.offset >= second.start }?.ms ?: contentMs
            val tooLong = contentMs - firstMsOfSecond > maxContentMs
            if (!tooBig && !tooLong) return
            chunks.removeFirst()
            first.file.delete()
            val keep = marks.indexOfFirst { it.offset >= second.start }
            if (keep < 0) marks.clear() else if (keep > 0) marks.subList(0, keep).clear()
        }
    }

    /** TS-Pakete durchgehen und an jeder PCR die Sendezeit weiterzählen. */
    private fun index(data: ByteArray, off: Int, len: Int) {
        val now = clock()
        var i = 0
        while (i < len) {
            if (packetFill == 0) {
                if (data[off + i] != SYNC) {
                    i++
                    continue
                }
                if (len - i >= TS_PACKET) {
                    readPcr(data, off + i, end + i, now)
                    i += TS_PACKET
                    continue
                }
                packetStart = end + i
            }
            val n = minOf(TS_PACKET - packetFill, len - i)
            System.arraycopy(data, off + i, packet, packetFill, n)
            packetFill += n
            i += n
            if (packetFill == TS_PACKET) {
                readPcr(packet, 0, packetStart, now)
                packetFill = 0
            }
        }
        // Streams ohne PCR: nach der Uhr des Geräts zählen.
        if (lastPcrWall < 0 || now - lastPcrWall > NO_PCR_MS) {
            if (lastWriteWall >= 0) contentMs += (now - lastWriteWall).coerceIn(0, MAX_STEP_MS)
            addMark(end)
        }
        lastWriteWall = now
    }

    private fun readPcr(buf: ByteArray, pos: Int, offset: Long, now: Long) {
        val b3 = buf[pos + 3].toInt() and 0xFF
        if (b3 and 0x20 == 0) return
        val afLength = buf[pos + 4].toInt() and 0xFF
        if (afLength < 7 || buf[pos + 5].toInt() and 0x10 == 0) return
        val pid = ((buf[pos + 1].toInt() and 0x1F) shl 8) or (buf[pos + 2].toInt() and 0xFF)
        val base = ((buf[pos + 6].toLong() and 0xFF) shl 25) or
            ((buf[pos + 7].toLong() and 0xFF) shl 17) or
            ((buf[pos + 8].toLong() and 0xFF) shl 9) or
            ((buf[pos + 9].toLong() and 0xFF) shl 1) or
            ((buf[pos + 10].toLong() and 0xFF) shr 7)
        onPcr(pid, base / 90, offset, now)
    }

    private fun onPcr(pid: Int, pcrMs: Long, offset: Long, now: Long) {
        if (pcrPid < 0) pcrPid = pid
        if (pid != pcrPid) {
            // Anderer Sender/neue Verbindung mit anderer PCR-Spur: erst übernehmen, wenn die alte schweigt.
            if (lastPcrWall >= 0 && now - lastPcrWall < NO_PCR_MS) return
            pcrPid = pid
            lastPcr = -1
        }
        val step = if (lastPcr < 0) -1L else ((pcrMs - lastPcr) % PCR_WRAP_MS + PCR_WRAP_MS) % PCR_WRAP_MS
        contentMs += when {
            step in 0..MAX_STEP_MS -> step
            // Sprung (neue Verbindung, Werbung, Uhr neu gestellt): so weit, wie die Gerätezeit gelaufen ist.
            lastPcrWall >= 0 -> (now - lastPcrWall).coerceIn(0, MAX_STEP_MS)
            else -> 0
        }
        lastPcr = pcrMs
        lastPcrWall = now
        addMark(offset)
    }

    private fun addMark(offset: Long) {
        val last = marks.lastOrNull()
        if (last == null || contentMs - last.ms >= MARK_MS && offset > last.offset) marks += Mark(offset, contentMs)
    }

    /** Byte-Position, ab der das Bild zur Sendezeit [ms] beginnt (auf ein TS-Paket ausgerichtet). */
    fun offsetAt(ms: Long): Long = synchronized(lock) {
        if (marks.isEmpty()) return startOffsetLocked()
        val i = marks.binarySearchBy(ms) { it.ms }.let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        maxOf(marks[i].offset, startOffsetLocked())
    }

    /** Sendezeit an der Byte-Position [offset]. */
    fun msAt(offset: Long): Long = synchronized(lock) {
        if (marks.isEmpty()) return contentMs
        val i = marks.binarySearchBy(offset) { it.offset }.let { if (it >= 0) it else (-it - 2).coerceAtLeast(0) }
        marks[i].ms
    }

    private fun startOffsetLocked() = chunks.firstOrNull()?.start ?: end

    /** Liegt [position] vor dem ältesten gespeicherten Stück, geht es dort weiter. */
    fun clamp(position: Long): Long = synchronized(lock) { position.coerceIn(startOffsetLocked(), end) }

    /**
     * Wartet bis zu [waitMs], bis an [position] Daten liegen. null = noch nichts da, [END] = Aufnahme beendet.
     */
    @Throws(InterruptedIOException::class)
    fun await(position: Long, waitMs: Long): Slot? = synchronized(lock) {
        if (position >= end && !released) {
            try {
                lock.wait(waitMs)
            } catch (e: InterruptedException) {
                throw InterruptedIOException()
            }
        }
        if (position >= end) return if (released) END else null
        val chunk = chunks.lastOrNull { it.start <= position } ?: return null
        val inChunk = position - chunk.start
        val available = (chunk.length - inChunk).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (available <= 0) null else Slot(chunk.file, inChunk, available)
    }

    fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        if (released) return
        released = true
        out?.runCatching { close() }
        out = null
        chunks.forEach { it.file.delete() }
        chunks.clear()
        marks.clear()
        lock.notifyAll()
    }

    val isReleased: Boolean get() = synchronized(lock) { released }

    companion object {
        private const val TS_PACKET = 188
        private const val SYNC = 0x47.toByte()
        const val CHUNK_BYTES = 4L * 1024 * 1024
        private const val MARK_MS = 500L
        private const val MAX_STEP_MS = 3_000L
        private const val NO_PCR_MS = 2_000L
        private const val PCR_WRAP_MS = (1L shl 33) / 90
        val END = Slot(File(""), 0, 0)
        private val ids = AtomicInteger()
        const val SCHEME = "timeshift"

        /** Adresse für den Player: liest diesen Zwischenspeicher ab Byte [offset]. */
        fun uri(buffer: TimeshiftBuffer, offset: Long): String = "$SCHEME://${buffer.id}?from=$offset"
    }
}
