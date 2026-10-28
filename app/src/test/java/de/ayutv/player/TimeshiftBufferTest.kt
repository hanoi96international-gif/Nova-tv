package de.ayutv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.random.Random

class TimeshiftBufferTest {
    @get:Rule val folder = TemporaryFolder()

    private var wall = 0L

    /** TS-Paket mit PCR (Sendezeit [ms]) auf PID 0x100, gefolgt von [filler] Paketen ohne PCR. */
    private fun packets(ms: Long, filler: Int = 3, pid: Int = 0x100): ByteArray {
        val out = ByteArray(188 * (1 + filler))
        for (p in 0..filler) {
            val o = p * 188
            out[o] = 0x47
            out[o + 1] = ((pid shr 8) and 0x1F).toByte()
            out[o + 2] = (pid and 0xFF).toByte()
            if (p == 0) {
                out[o + 3] = 0x30 // Anpassungsfeld + Nutzdaten
                out[o + 4] = 7
                out[o + 5] = 0x10 // PCR vorhanden
                val base = ms * 90
                out[o + 6] = (base shr 25).toByte()
                out[o + 7] = (base shr 17).toByte()
                out[o + 8] = (base shr 9).toByte()
                out[o + 9] = (base shr 1).toByte()
                out[o + 10] = ((base and 1) shl 7).toByte()
            } else {
                out[o + 3] = 0x10
                for (i in 4 until 188) out[o + i] = (p + i).toByte()
            }
        }
        return out
    }

    private fun buffer(maxBytes: Long = Long.MAX_VALUE, maxMs: Long = Long.MAX_VALUE, chunk: Long = 64 * 1024) =
        TimeshiftBuffer(folder.newFolder(), maxBytes, maxMs, "http://x/live/1.ts", clock = { wall }, chunkBytes = chunk)

    /** Schreibt [seconds] Sekunden Stream (alle 40 ms eine PCR) in zufällig großen Stücken. */
    private fun feed(b: TimeshiftBuffer, fromMs: Long, seconds: Int, random: Random = Random(1)): Long {
        val all = java.io.ByteArrayOutputStream()
        var ms = fromMs
        repeat(seconds * 25) {
            all.write(packets(ms))
            ms += 40
        }
        val bytes = all.toByteArray()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(bytes.size - i, 1 + random.nextInt(5000))
            wall += 1
            b.write(bytes, i, n)
            i += n
        }
        return ms
    }

    @Test
    fun countsStreamTimeFromPcr() {
        val b = buffer()
        feed(b, fromMs = 1_000_000, seconds = 60)
        // 1500 PCR-Werte im Abstand von 40 ms: 59,96 s ab der ersten
        assertEquals(59_960L, b.latestMs)
        assertEquals(0L, b.oldestMs)
        assertTrue(b.hasData)
    }

    @Test
    fun offsetsAndTimesMatch() {
        val b = buffer()
        feed(b, fromMs = 0, seconds = 30)
        val offset = b.offsetAt(10_000)
        assertEquals(0L, offset % 188)
        val ms = b.msAt(offset)
        assertTrue("$ms", ms in 9_500..10_000)
        // Am Anfang der Marke beginnt ein TS-Paket mit PCR
        val slot = b.await(offset, 0)!!
        RandomAccessFile(slot.file, "r").use { raf ->
            raf.seek(slot.fileOffset)
            assertEquals(0x47, raf.read())
        }
    }

    @Test
    fun jumpsInPcrDoNotBreakTheClock() {
        val b = buffer()
        feed(b, fromMs = 5_000, seconds = 10)
        val before = b.latestMs
        wall += 1_500 // neue Verbindung nach 1,5 s, PCR des Senders springt zurück
        feed(b, fromMs = 200, seconds = 10)
        val after = b.latestMs
        assertTrue("vorher $before, nachher $after", after - before in 11_000..11_600)
    }

    @Test
    fun oldPartsAreDeletedByTime() {
        val b = buffer(maxMs = 20_000, chunk = 32 * 1024)
        feed(b, fromMs = 0, seconds = 60)
        // Gelöscht wird stückweise: höchstens ein Stück (hier knapp 2 s) mehr als eingestellt
        val span = b.latestMs - b.oldestMs
        assertTrue("$span", span in 18_000..22_000)
        assertTrue(b.startOffset > 0)
        // Vor dem ältesten Stück geht es dort weiter
        assertEquals(b.startOffset, b.clamp(0))
    }

    @Test
    fun oldPartsAreDeletedBySize() {
        val b = buffer(maxBytes = 200 * 1024, chunk = 32 * 1024)
        feed(b, fromMs = 0, seconds = 60)
        assertTrue(b.bytesStored <= 200 * 1024 + 32 * 1024)
    }

    @Test
    fun readerWaitsAtTheEndAndStopsAfterRelease() {
        val b = buffer()
        feed(b, fromMs = 0, seconds = 1)
        assertNull(b.await(b.endOffset, 10))
        b.release()
        assertSame(TimeshiftBuffer.END, b.await(b.endOffset, 10))
    }

    @Test
    fun packetsSplitAcrossWritesAreParsed() {
        val b = buffer()
        val one = packets(0) + packets(40) + packets(80)
        // Byte für Byte schreiben
        for (i in one.indices) b.write(one, i, 1)
        assertEquals(80L, b.latestMs)
    }

    @Test
    fun writingInTheBackgroundKeepsOrderAndTime() {
        val direct = buffer()
        feed(direct, fromMs = 0, seconds = 20)
        wall = 0
        val later = buffer()
        val random = Random(1)
        var ms = 0L
        repeat(20 * 25) {
            val data = packets(ms)
            var i = 0
            while (i < data.size) {
                val n = minOf(data.size - i, 1 + random.nextInt(700))
                later.writeLater(data, i, n)
                i += n
            }
            ms += 40
            wall += 40
        }
        later.awaitWritten()
        assertEquals(direct.endOffset, later.endOffset)
        assertTrue("${later.latestMs}", later.latestMs > 19_000)
    }
}
