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
}

/**
 * Datenquelle des Players: Adressen mit `timeshift://` liest sie aus dem Zwischenspeicher, alles andere
 * aus dem Netz. Ist der Live-Stream des Zwischenspeichers dabei, wird mitgeschrieben.
 */
class LiveDataSource(private val upstream: DataSource, private val holder: TimeshiftHolder) : DataSource {
    private val local = TimeshiftDataSource(holder)
    private var active: DataSource? = null
    private var tap: TimeshiftBuffer? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri.scheme == TimeshiftBuffer.SCHEME) {
            active = local
            return local.open(dataSpec)
        }
        active = upstream
        tap = holder.buffer?.takeIf { it.tapping && it.url == dataSpec.uri.toString() }
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = active ?: throw IOException("nicht geöffnet")
        val n = source.read(buffer, offset, length)
        if (n > 0) tap?.takeIf { it.tapping }?.write(buffer, offset, n)
        return n
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        tap = null
        val source = active
        active = null
        source?.close()
    }

    class Factory(private val upstream: DataSource.Factory, private val holder: TimeshiftHolder) : DataSource.Factory {
        override fun createDataSource(): DataSource = LiveDataSource(upstream.createDataSource(), holder)
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

    fun start(buffer: TimeshiftBuffer, userAgent: String) {
        if (running) return
        job = scope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive && !buffer.isReleased) {
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
                                buffer.write(bytes, 0, n)
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
