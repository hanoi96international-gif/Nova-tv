package de.ayutv.data.repo

import android.os.SystemClock
import de.ayutv.data.BufferProfile
import de.ayutv.data.db.ChannelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Leitungs-Check: Wie gut kommt ein Sender beim Anbieter an? Lädt den Stream einige Sekunden (ohne Bild und Ton)
 * und misst Antwortzeit, Datenrate und Aussetzer (über eine Sekunde nichts). Daraus folgt eine Empfehlung für
 * den Puffer: schnell umschalten, ausgewogen oder stabil.
 */
class LineCheck(private val http: OkHttpClient) {
    data class Result(
        val channel: String,
        val firstByteMs: Long,
        val kbps: Int,
        val stalls: Int,
        val longestGapMs: Long,
        val failed: String? = null,
    ) {
        val recommendation: BufferProfile get() = recommend(firstByteMs, stalls, longestGapMs, kbps, failed)
        val summary: String get() = failed?.let { "$channel: kein Empfang ($it)" }
            ?: "$channel: Antwort nach ${firstByteMs} ms, ${"%.1f".format(kbps / 1000.0)} Mbit/s, " +
                (if (stalls == 0) "keine Aussetzer" else "$stalls Aussetzer (längster ${"%.1f".format(longestGapMs / 1000.0)} s)")
    }

    suspend fun run(channel: ChannelEntity, userAgent: String, seconds: Int = 10): Result = withContext(Dispatchers.IO) {
        val client = http.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val url = if (channel.url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.endsWith(".m3u") }) {
            firstSegment(client, channel.url, userAgent) ?: channel.url
        } else channel.url
        val started = SystemClock.elapsedRealtime()
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result(channel.name, 0, 0, 0, 0, "HTTP ${response.code}")
                val stream = response.body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var firstByte = -1L
                var bytes = 0L
                var last = started
                var stalls = 0
                var longest = 0L
                val end = started + seconds * 1000L
                while (SystemClock.elapsedRealtime() < end) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    val now = SystemClock.elapsedRealtime()
                    if (firstByte < 0) firstByte = now - started else {
                        val gap = now - last
                        if (gap > STALL_MS) stalls++
                        longest = maxOf(longest, gap)
                    }
                    last = now
                    bytes += n
                }
                val measuredMs = (SystemClock.elapsedRealtime() - started - firstByte.coerceAtLeast(0)).coerceAtLeast(1)
                Result(channel.name, firstByte.coerceAtLeast(0), (bytes * 8 / measuredMs).toInt(), stalls, longest)
            }
        }.getOrElse { Result(channel.name, 0, 0, 0, 0, it.message ?: it.javaClass.simpleName) }
    }

    /** Bei HLS: das erste Segment der (ersten) Wiedergabeliste. */
    private fun firstSegment(client: OkHttpClient, url: String, userAgent: String, depth: Int = 0): String? = runCatching {
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { it.body.string() }
        val line = body.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val next = java.net.URI(url).resolve(line).toString()
        if (depth == 0 && line.substringBefore('?').endsWith(".m3u8")) firstSegment(client, next, userAgent, 1) else next
    }.getOrNull()

    companion object {
        private const val STALL_MS = 1_000L

        /** Puffer-Empfehlung aus den Messwerten. */
        fun recommend(firstByteMs: Long, stalls: Int, longestGapMs: Long, kbps: Int, failed: String?): BufferProfile = when {
            failed != null -> BufferProfile.STABLE
            stalls >= 2 || longestGapMs > 3_000 || firstByteMs > 3_000 -> BufferProfile.STABLE
            // Live-Streams kommen in Echtzeit: Die Datenrate zeigt die Stream-Qualität, nicht die Reserve der Leitung.
            stalls == 1 || firstByteMs > 1_200 -> BufferProfile.BALANCED
            else -> BufferProfile.FAST
        }
    }
}
