@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Gilt für einen Player: der Zwischenspeicher des aktuellen Senders (oder keiner). */
class TimeshiftHolder {
    @Volatile var buffer: TimeshiftBuffer? = null

    /**
     * Adresse des laufenden Live-Senders. Nur dieser Stream wird bei einem Abbruch in der Datenquelle
     * selbst neu verbunden; ein neuer Sender beginnt mit frischem Filter und leerer Abbruch-Liste.
     */
    @Volatile var liveUrl: String? = null
        set(value) {
            if (value == field) return
            field = value
            synchronized(this) { replay = null }
            drops.clear()
        }

    private var replay: TsReplayFilter? = null

    /** Filter gegen die Wiederholung nach dem Neuverbinden, über neue Datenquellen desselben Senders hinweg. */
    @Synchronized fun replayFilter(): TsReplayFilter = replay ?: TsReplayFilter().also { replay = it }

    val drops = ConnectionDrops()
}

/** Abbrüche der Verbindung zum Anbieter beim laufenden Sender: wann und warum. */
class ConnectionDrops {
    class Drop(val at: Long, val reason: String)

    private val list = ArrayDeque<Drop>()

    /** Steigt bei jedem Abbruch; der Player merkt daran, dass es einen neuen gibt. */
    @Volatile var count = 0
        private set

    @Synchronized fun add(reason: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        list.addLast(Drop(now, reason))
        while (list.size > 50) list.removeFirst()
        count++
    }

    @Synchronized fun recent(windowMs: Long): List<Drop> {
        val since = android.os.SystemClock.elapsedRealtime() - windowMs
        return list.filter { it.at >= since }
    }

    @Synchronized fun clear() {
        list.clear()
        count = 0
    }
}

/**
 * Datenquelle des Players: Adressen mit `timeshift://` liest sie aus dem Zwischenspeicher, alles andere
 * aus dem Netz. Ist der Live-Stream des Zwischenspeichers dabei, wird mitgeschrieben.
 *
 * Beim Live-Stream des Senders ([TimeshiftHolder.liveUrl], unbekannte Länge, TS) verbindet sie nach
 * einem Abbruch selbst neu: Media3 würde sonst erst den ganzen Puffer abspielen, dann „Lädt …“ zeigen
 * und von vorn beginnen. So merkt der Player nichts, der Puffer überbrückt die Pause. Was der Server
 * nach dem Neuverbinden wiederholt, fällt mit [TsReplayFilter] weg (sonst Sprung zurück).
 */
class LiveDataSource(private val upstream: DataSource, private val holder: TimeshiftHolder) : DataSource {
    private val local = TimeshiftDataSource(holder)
    private var active: DataSource? = null
    private var tap: TimeshiftBuffer? = null
    private var liveSpec: DataSpec? = null
    private var filter: TsReplayFilter? = null
    private var chunk: ByteArray? = null
    private var out = ByteArray(0)
    private var outPos = 0
    private var outLen = 0
    private var ended = false

    private val emit: (ByteArray, Int, Int) -> Unit = { data, off, len ->
        if (out.size < outLen + len) out = out.copyOf(maxOf(out.size * 2, outLen + len))
        System.arraycopy(data, off, out, outLen, len)
        outLen += len
        tap?.takeIf { it.tapping }?.write(data, off, len)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        outPos = 0
        outLen = 0
        ended = false
        liveSpec = null
        filter = null
        if (dataSpec.uri.scheme == TimeshiftBuffer.SCHEME) {
            active = local
            return local.open(dataSpec)
        }
        active = upstream
        val url = dataSpec.uri.toString()
        tap = holder.buffer?.takeIf { it.tapping && it.url == url }
        val length = upstream.open(dataSpec)
        if (length == C.LENGTH_UNSET.toLong() && dataSpec.position == 0L && url == holder.liveUrl) {
            liveSpec = dataSpec
            filter = holder.replayFilter().also { it.reconnected() }
            if (chunk == null) chunk = ByteArray(CHUNK)
        }
        return length
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = active ?: throw IOException("nicht geöffnet")
        val f = filter
        if (f == null) {
            val n = source.read(buffer, offset, length)
            if (n > 0) tap?.takeIf { it.tapping }?.write(buffer, offset, n)
            return n
        }
        if (length == 0) return 0
        while (outPos == outLen) {
            if (ended) return C.RESULT_END_OF_INPUT
            outPos = 0
            outLen = 0
            val data = chunk!!
            val n = readLive(f, data)
            if (n == C.RESULT_END_OF_INPUT) {
                f.flush(emit)
                ended = true
            } else {
                f.process(data, 0, n, emit)
                // Anderer Zeitstempel nach dem Neuverbinden: Der Player soll wie bei einem neuen Stream
                // ansetzen (Media3 beginnt nach einem Lesefehler neu), sonst liefe die Zeit rückwärts.
                if (f.takeNewTimeBase()) throw IOException("Sender liefert nach dem Neuverbinden einen neuen Stream")
            }
        }
        val n = minOf(length, outLen - outPos)
        System.arraycopy(out, outPos, buffer, offset, n)
        outPos += n
        return n
    }

    /** Liest vom Anbieter; reißt die Verbindung ab, wird neu verbunden (nur bei erkanntem TS). */
    private fun readLive(f: TsReplayFilter, data: ByteArray): Int {
        while (true) {
            var error: IOException? = null
            val reason = try {
                val n = upstream.read(data, 0, data.size)
                if (n != C.RESULT_END_OF_INPUT) return n
                "Anbieter hat die Verbindung beendet"
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                error = e
                e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
            if (!f.isTs) {
                if (error != null) throw error
                return C.RESULT_END_OF_INPUT
            }
            holder.drops.add(reason)
            resume(error)
        }
    }

    private fun resume(cause: IOException?) {
        val spec = liveSpec ?: throw cause ?: IOException("nicht geöffnet")
        val started = android.os.SystemClock.elapsedRealtime()
        var attempt = 0
        var last = cause
        while (true) {
            runCatching { upstream.close() }
            // Beim Anbieter ist die alte Verbindung oft noch einen Moment offen (sonst 403/458).
            try {
                Thread.sleep(minOf(RESUME_FIRST_MS shl attempt.coerceAtMost(3), RESUME_MAX_WAIT_MS))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("abgebrochen")
            }
            try {
                upstream.open(spec)
                filter?.reconnected()
                return
            } catch (e: InterruptedIOException) {
                throw e
            } catch (e: IOException) {
                last = e
            }
            attempt++
            if (android.os.SystemClock.elapsedRealtime() - started > RESUME_LIMIT_MS) {
                throw last ?: IOException("Neu verbinden gescheitert")
            }
        }
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        tap = null
        liveSpec = null
        filter = null
        val source = active
        active = null
        source?.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val holder: TimeshiftHolder) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveDataSource(upstream.createDataSource(), holder)
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val RESUME_FIRST_MS = 250L
        const val RESUME_MAX_WAIT_MS = 2_000L
        /** So lange selbst neu versuchen; danach meldet der Player den Fehler wie bisher. */
        const val RESUME_LIMIT_MS = 15_000L
    }
}

/** Liest den Zwischenspeicher wie einen Live-Stream: Am Ende wird auf neue Daten gewartet. */
class TimeshiftDataSource(private val holder: TimeshiftHolder) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var buffer: TimeshiftBuffer? = null
    private var position = 0L
    private var file: File? = null
    private var reader: RandomAccessFile? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val b = holder.buffer
        val id = dataSpec.uri.host?.toIntOrNull()
        if (b == null || b.id != id || b.isReleased) throw IOException("Zwischenspeicher nicht mehr vorhanden")
        buffer = b
        uri = dataSpec.uri
        val from = dataSpec.uri.getQueryParameter("from")?.toLongOrNull() ?: 0L
        position = b.clamp(from + dataSpec.position)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val b = buffer ?: throw IOException("nicht geöffnet")
        var waited = 0L
        while (true) {
            if (Thread.interrupted()) throw InterruptedIOException()
            // Wurde ein altes Stück schon gelöscht (zu lange angehalten), beim ältesten weiterlesen.
            position = maxOf(position, b.startOffset)
            val slot = b.await(position, WAIT_SLICE_MS)
            if (slot === TimeshiftBuffer.END) return C.RESULT_END_OF_INPUT
            if (slot == null) {
                waited += WAIT_SLICE_MS
                if (waited >= STALL_MS) throw IOException("Zwischenspeicher bekommt keine Daten")
                continue
            }
            val raf = readerFor(slot.file)
            raf.seek(slot.fileOffset)
            val n = raf.read(target, offset, minOf(length, slot.available))
            if (n <= 0) {
                // Datei schon gelöscht/ersetzt: neu ansetzen.
                closeReader()
                continue
            }
            position += n
            bytesTransferred(n)
            return n
        }
    }

    private fun readerFor(f: File): RandomAccessFile {
        reader?.takeIf { file == f }?.let { return it }
        closeReader()
        return RandomAccessFile(f, "r").also {
            reader = it
            file = f
        }
    }

    private fun closeReader() {
        reader?.runCatching { close() }
        reader = null
        file = null
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closeReader()
        uri = null
        buffer = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val WAIT_SLICE_MS = 100L
        const val STALL_MS = 20_000L
    }
}

/**
 * Lädt den Live-Stream in den Zwischenspeicher, solange der Player daraus spielt (angehalten oder
 * zeitversetzt). Es bleibt bei einer Verbindung zum Anbieter: Der Player liest dann nur noch lokal.
 */
class TimeshiftRecorder(http: OkHttpClient, private val scope: CoroutineScope) {
    private val client = http.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .build()
    private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile var lastError: String? = null
        private set

    val running: Boolean get() = job?.isActive == true

    /** [seed]: Stand des Players, damit die Wiederholung beim ersten Verbinden nicht doppelt gespeichert wird. */
    fun start(buffer: TimeshiftBuffer, userAgent: String, seed: TsReplayFilter? = null) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            // Wiederholung nach dem Neuverbinden nicht doppelt in den Zwischenspeicher schreiben.
            val replay = seed?.copy() ?: TsReplayFilter()
            val write: (ByteArray, Int, Int) -> Unit = { b, o, l -> buffer.write(b, o, l) }
            while (isActive && !buffer.isReleased) {
                replay.reconnected()
                try {
                    val request = Request.Builder().url(buffer.url).header("User-Agent", userAgent).build()
                    val c = client.newCall(request)
                    call = c
                    c.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val input = response.body.byteStream()
                        val bytes = ByteArray(64 * 1024)
                        while (isActive && !buffer.isReleased) {
                            val n = input.read(bytes)
                            if (n < 0) break
                            if (n > 0) {
                                replay.process(bytes, 0, n, write)
                                failures = 0
                                lastError = null
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (!isActive) break
                    failures++
                    lastError = e.message
                }
                // Abgerissene Verbindungen sind bei IPTV normal: kurz warten (beim Anbieter ist die alte
                // Verbindung oft noch einen Moment offen) und neu verbinden.
                if (isActive) delay(minOf(400L * failures.coerceAtLeast(1), 3_000L))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        call?.cancel()
        call = null
    }
}
