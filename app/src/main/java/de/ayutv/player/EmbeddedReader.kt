@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Liest die eingebetteten Untertitel einer ganzen Filmdatei, ohne sie abzuspielen (nachts, zum Vorbereiten):
 * Die Datei wird einmal durchgelesen, Bild und Ton werden verworfen, nur die Untertitel bleiben. Dieselben
 * Extraktoren wie im Player, daher dieselben Spur-Kennungen: Die Übersetzungen passen beim Schauen zur Spur.
 * Bild-Untertitel werden unterwegs per Texterkennung gelesen (das bremst das Lesen, der Speicher bleibt klein).
 */
class EmbeddedReader(private val http: OkHttpClient, private val userAgent: String) {
    class Result(
        /** Gewählte Spur, null = keine passende (oder schon eine in der Zielsprache). */
        val format: Format?,
        val lines: List<Line>,
        /** Bis zum Ende gelesen. */
        val complete: Boolean,
        /** Bis hierhin gelesen (für das nächste Mal), Mikrosekunden Filmzeit. */
        val reachedUs: Long,
        val bytes: Long,
    )

    class Line(val startMs: Long, val endMs: Long, val text: String)

    /** Abbruch von außen (Film wird geschaut, Zeit um), [Result] enthält das bis dahin Gelesene. */
    private class Stop : Exception()

    /**
     * Liest [url] ab [resumeUs]. [choose] wählt aus den Untertitelspuren die zu übersetzende (null = keine,
     * dann wird gar nicht weitergelesen). [recognize] liest Bild-Untertitel; [keepGoing] wird laufend gefragt.
     */
    suspend fun read(
        url: String,
        resumeUs: Long,
        maxBytes: Long,
        choose: (List<Format>) -> Format?,
        recognize: suspend (EmbeddedSubtitles.GrayImage) -> String,
        keepGoing: () -> Boolean,
    ): Result {
        val store = EmbeddedSubtitles(keepImages = true)
        val output = Output()
        val extractors = store.tap(
            DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setSubtitleParserFactory(DefaultSubtitleParserFactory())
                .apply {
                    @Suppress("DEPRECATION")
                    experimentalSetTextTrackTranscodingEnabled(true)
                }
        )
        val adapter = BundledExtractorsAdapter(extractors)
        val source = OkHttpDataSource.Factory(http).setUserAgent(userAgent).createDataSource()
        val uri = Uri.parse(url)
        val holder = PositionHolder()
        var position = 0L
        var chosen: Format? = null
        var decided = false
        var seekDone = resumeUs <= 0
        /** Nach dem nächsten Öffnen dorthin springen (Filmzeit in µs). */
        var pendingSeekUs: Long? = null
        var reads = 0L
        var ocrFromMs = 0L
        var complete = false
        var errors = 0
        var total = C.LENGTH_UNSET.toLong()
        var reachedUs = resumeUs
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val length = try {
                    source.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
                } catch (e: IOException) {
                    if (++errors > MAX_ERRORS) throw e
                    Log.w(TAG, "Öffnen bei $position fehlgeschlagen, neuer Versuch", e)
                    kotlinx.coroutines.delay(5_000L * errors)
                    continue
                }
                if (length != C.LENGTH_UNSET.toLong() && position == 0L) total = length
                if (total > maxBytes) throw IOException("Datei zu groß (${total / 1_000_000} MB)")
                var result = Extractor.RESULT_CONTINUE
                try {
                    adapter.init(source, uri, source.responseHeaders, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length, output)
                    pendingSeekUs?.let { adapter.seek(position, it) }
                    pendingSeekUs = null
                    while (result == Extractor.RESULT_CONTINUE) {
                        result = adapter.read(holder)
                        // Ein Lesevorgang ist ein Stück der Datei: Nebenarbeit nur ab und zu.
                        if (++reads % CHECK_EVERY != 0L && decided && seekDone) continue
                        if (!decided && output.tracksEnded) {
                            decided = true
                            chosen = choose(output.textFormats())
                            if (chosen == null) throw Stop()
                            if (store.isBitmap(chosen)) store.readImagesOf(chosen)
                        }
                        // Weiterlesen ab dem Stand vom letzten Mal (sobald die Datei sagt, wo das liegt).
                        if (!seekDone && output.seekMap != null) {
                            seekDone = true
                            val map = output.seekMap!!
                            if (map.isSeekable) {
                                val point = map.getSeekPoints(resumeUs).first
                                position = point.position
                                pendingSeekUs = point.timeUs
                                result = SEEK_ELSEWHERE
                                break
                            }
                        }
                        chosen?.let { format ->
                            // Bild-Untertitel gleich lesen: so liegen nie viele Bilder im Speicher.
                            for (entry in store.upcoming(format, ocrFromMs, 50) { it.image != null }) {
                                val image = entry.image ?: continue
                                entry.text = runCatching { recognize(image) }.getOrDefault("")
                                entry.image = null
                                ocrFromMs = entry.startMs
                            }
                            store.upcoming(format, reachedUs / 1000, Int.MAX_VALUE) { true }.lastOrNull()?.let { reachedUs = maxOf(reachedUs, it.startMs * 1000) }
                        }
                        if (!keepGoing()) throw Stop()
                        currentCoroutineContext().ensureActive()
                    }
                    errors = 0
                } catch (e: IOException) {
                    // Verbindung abgerissen: an derselben Stelle weiterlesen (wie der Player).
                    if (++errors > MAX_ERRORS) throw e
                    Log.w(TAG, "Lesefehler bei ${adapter.currentInputPosition}, neuer Versuch", e)
                    position = adapter.currentInputPosition.takeIf { it >= 0 } ?: position
                    runCatching { source.close() }
                    kotlinx.coroutines.delay(5_000L * errors)
                    continue
                } finally {
                    if (result != SEEK_ELSEWHERE && result != Extractor.RESULT_SEEK) {
                        adapter.currentInputPosition.takeIf { it >= 0 }?.let { position = it }
                    }
                    runCatching { source.close() }
                }
                when (result) {
                    Extractor.RESULT_SEEK -> position = holder.position
                    Extractor.RESULT_END_OF_INPUT -> {
                        complete = true
                        break
                    }
                }
            }
        } catch (_: Stop) {
            // Gelesenes behalten.
        } finally {
            runCatching { adapter.release() }
        }
        val lines = chosen?.let { format ->
            store.upcoming(format, 0, Int.MAX_VALUE) { !it.text.isNullOrBlank() }.map { Line(it.startMs, it.endMs, it.text.orEmpty()) }
        }.orEmpty()
        return Result(chosen, lines, complete && chosen != null, reachedUs, total)
    }

    /** Verwirft Bild und Ton, merkt sich die Untertitelspuren und die Sprungtabelle der Datei. */
    private class Output : ExtractorOutput {
        private val formats = LinkedHashMap<Int, Format>()
        @Volatile var tracksEnded = false
        @Volatile var seekMap: SeekMap? = null

        fun textFormats(): List<Format> = synchronized(formats) { formats.values.toList() }

        override fun track(id: Int, type: Int): TrackOutput {
            val discard = DiscardingTrackOutput()
            if (type != C.TRACK_TYPE_TEXT) return discard
            return object : TrackOutput {
                override fun format(format: Format) {
                    synchronized(formats) { formats[id] = format }
                }

                override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
                    discard.sampleData(input, length, allowEndOfInput, sampleDataPart)

                override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
                    discard.sampleData(data, length, sampleDataPart)

                override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
                    discard.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            }
        }

        override fun endTracks() {
            tracksEnded = true
        }

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    companion object {
        private const val TAG = "DnAEmbeddedReader"
        private const val MAX_ERRORS = 5
        private const val CHECK_EVERY = 64L
        /** Eigener Wert: zum nächsten Öffnen an anderer Stelle (Fortsetzen). */
        private const val SEEK_ELSEWHERE = -100

        /**
         * Spur zum Übersetzen wie im Player: vollständige (keine erzwungenen), Englisch zuerst, Text vor Bild,
         * normale vor SDH. Gibt es schon eine vollständige Spur in [target], ist nichts zu tun (null).
         */
        fun bestSource(formats: List<Format>, target: String): Format? {
            val language = { f: Format -> de.ayutv.data.Languages.normalize(f.language) }
            val full = formats.filter { !it.isForcedSubtitle() }
            if (full.any { language(it) == de.ayutv.data.Languages.normalize(target) }) return null
            return full.sortedWith(
                compareBy<Format> { language(it) != "en" }
                    .thenBy { it.isBitmapSubtitle() }
                    .thenBy { it.isSdhSubtitle() }
            ).firstOrNull()
        }
    }
}
