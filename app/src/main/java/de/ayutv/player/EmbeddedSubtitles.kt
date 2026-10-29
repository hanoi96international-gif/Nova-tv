@file:OptIn(UnstableApi::class)

package de.ayutv.player

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Eingebettete Untertitel, schon beim Laden mitgelesen: Der Player puffert 30–60 s voraus, so
 * liegen Zeilen lange vor ihrer Einblendung vor. Damit lassen sich auch Bild-Untertitel (PGS, DVB,
 * VobSub) per Texterkennung lesen und übersetzen, bevor sie gebraucht werden: synchron zum Bild,
 * ohne Verzögerung. Gilt für alle Untertitelspuren, nicht nur die gerade gewählte.
 */
class EmbeddedSubtitles(
    /** Bilder nicht nach 30 s verwerfen (nächtliches Lesen: die Texterkennung kommt hinterher). */
    private val keepImages: Boolean = false,
) {
    /** Eine Einblendung. [text] kommt aus Textspuren oder später aus der Texterkennung. */
    class Entry(val startMs: Long, @Volatile var endMs: Long, @Volatile var text: String?, @Volatile var image: GrayImage?)

    /** Graustufen-Bild einer Bild-Untertitel-Einblendung: hell = Schrift, dunkel = Rand/Hintergrund. */
    class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
        companion object {
            /** Helligkeit × Deckkraft: Schrift hell, Rand und Hintergrund dunkel. */
            fun of(bitmap: Bitmap): GrayImage {
                val pixels = ByteArray(bitmap.width * bitmap.height)
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (x in 0 until bitmap.width) pixels[y * bitmap.width + x] = gray(row[x])
                }
                return GrayImage(bitmap.width, bitmap.height, pixels)
            }

            internal fun gray(argb: Int): Byte {
                val a = argb ushr 24
                val luma = (299 * (argb shr 16 and 0xFF) + 587 * (argb shr 8 and 0xFF) + 114 * (argb and 0xFF)) / 1000
                return (luma * a / 255).toByte()
            }
        }
    }

    private class Track(val format: Format) {
        val entries = TreeMap<Long, Entry>()
    }

    private val tracks = ConcurrentHashMap<String, Track>()

    /**
     * Spur, deren Bilder gelesen werden sollen (Übersetzung läuft), sonst null. Bild-Untertitel aller
     * anderen Spuren werden gar nicht erst dekodiert: Das spart Rechenzeit und Speicher bei 4K.
     */
    @Volatile
    private var imageTrack: String? = null

    fun clear() = tracks.clear()

    fun readImagesOf(format: Format?) {
        imageTrack = format?.let(::key)
    }

    internal fun wantsImages(format: Format) = imageTrack == key(format)

    /** Bild-Untertitel (PGS, DVB, VobSub): ursprüngliches Format steht nach der Umwandlung in codecs. */
    internal fun isBitmap(format: Format) = format.codecs in BITMAP_TYPES || format.sampleMimeType in BITMAP_TYPES

    /** Gibt es schon mitgelesene Einblendungen dieser Spur? */
    fun hasEntries(format: Format): Boolean = find(format)?.let { synchronized(it) { it.entries.isNotEmpty() } } == true

    /** Einblendung, die bei [positionMs] zu sehen ist. */
    fun at(format: Format, positionMs: Long): Entry? {
        val track = find(format) ?: return null
        synchronized(track) {
            val entry = track.entries.floorEntry(positionMs)?.value ?: return null
            val end = entry.endMs.takeIf { it >= 0 }
                ?: track.entries.higherKey(entry.startMs)
                ?: (entry.startMs + OPEN_END_MS)
            return entry.takeIf { positionMs < end }
        }
    }

    /** Einblendungen ab [fromMs] in zeitlicher Reihenfolge, die [wanted] erfüllen. */
    fun upcoming(format: Format, fromMs: Long, limit: Int, wanted: (Entry) -> Boolean): List<Entry> {
        val track = find(format) ?: return emptyList()
        synchronized(track) {
            return track.entries.tailMap(fromMs, true).values.asSequence().filter(wanted).take(limit).toList()
        }
    }

    private fun find(format: Format): Track? {
        format.id?.let { id -> tracks[id]?.let { return it } }
        // Ohne Kennung: über Sprache und Bezeichnung (eindeutig) zuordnen.
        return tracks.values.singleOrNull { it.format.language == format.language && it.format.label == format.label }
    }

    private fun key(format: Format) = format.id ?: "${format.language}|${format.label}|${format.codecs}"

    internal fun add(format: Format, timeUs: Long, durationUs: Long, cues: List<Cue>) {
        val track = tracks.getOrPut(key(format)) { Track(format) }
        val startMs = timeUs / 1000
        val endMs = if (durationUs != C.TIME_UNSET && durationUs > 0) startMs + durationUs / 1000 else -1L
        synchronized(track) {
            // Bild-Untertitel enden mit der nächsten (ggf. leeren) Einblendung.
            track.entries.lowerEntry(startMs)?.value?.let { if (it.endMs < 0 || it.endMs > startMs) it.endMs = startMs }
            if (cues.isEmpty()) return
            val existing = track.entries[startMs]
            if (existing != null && (existing.text != null || existing.image != null)) {
                if (endMs >= 0) existing.endMs = endMs
                return
            }
            val text = cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
            val image = if (text == null && wantsImages(format)) toGray(cues) else null
            if (text == null && image == null) return
            track.entries[startMs] = Entry(startMs, endMs, text, image)
            while (track.entries.size > MAX_ENTRIES) track.entries.pollFirstEntry()
            // Bilder längst vergangener Einblendungen braucht niemand mehr: Speicher freigeben.
            if (image != null && !keepImages) track.entries.headMap(startMs - KEEP_IMAGES_BEHIND_MS).values.forEach { it.image = null }
        }
    }

    /** Alle Bilder einer Einblendung untereinander (von oben nach unten) zu einem Graustufenbild. */
    private fun toGray(cues: List<Cue>): GrayImage? {
        val bitmaps = cues.filter { it.bitmap != null }
            .sortedBy { if (it.line == Cue.DIMEN_UNSET) 0f else it.line }
            .mapNotNull { it.bitmap }
        if (bitmaps.isEmpty()) return null
        val scaled = bitmaps.map { bitmap ->
            // Große Bilder verkleinern: Für die Erkennung reichen ~60 px pro Zeile.
            val factor = (MAX_HEIGHT_PER_IMAGE.toFloat() / bitmap.height).coerceAtMost(1f)
            if (factor < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * factor).toInt().coerceAtLeast(1), (bitmap.height * factor).toInt().coerceAtLeast(1), true)
            else bitmap
        }
        val width = scaled.maxOf { it.width }
        val gap = 12
        val height = scaled.sumOf { it.height } + gap * (scaled.size - 1)
        val pixels = ByteArray(width * height)
        var top = 0
        val row = IntArray(width)
        for (bitmap in scaled) {
            for (y in 0 until bitmap.height) {
                bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                val offset = (top + y) * width
                for (x in 0 until bitmap.width) pixels[offset + x] = GrayImage.gray(row[x])
            }
            top += bitmap.height + gap
        }
        return GrayImage(width, height, pixels)
    }

    /** Hängt sich zwischen Extractor und Player und liest Untertitel-Samples mit. */
    fun tap(delegate: ExtractorsFactory): ExtractorsFactory = TapFactory(delegate, this)

    companion object {
        /** Kennung einer Spur für gespeicherte Übersetzungen: gleich beim Schauen und beim nächtlichen Lesen. */
        fun trackKey(format: Format): String = format.id ?: format.language ?: "spur"

        private const val MAX_ENTRIES = 3_000
        private const val OPEN_END_MS = 6_000L
        private const val MAX_HEIGHT_PER_IMAGE = 110
        private const val KEEP_IMAGES_BEHIND_MS = 30_000L
        private val BITMAP_TYPES = setOf(MimeTypes.APPLICATION_PGS, MimeTypes.APPLICATION_DVBSUBS, MimeTypes.APPLICATION_VOBSUB)
    }
}

private class TapFactory(private val delegate: ExtractorsFactory, private val store: EmbeddedSubtitles) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map { TapExtractor(it, store) }.toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map { TapExtractor(it, store) }.toTypedArray()

    @Deprecated("Media3: experimentell")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory = apply {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory = apply {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory = apply {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory = apply {
        delegate.setParseHagcMetadata(parseHagcMetadata)
    }
}

private class TapExtractor(private val delegate: Extractor, private val store: EmbeddedSubtitles) : Extractor {
    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)
    override fun getSniffFailureDetails(): List<SniffFailure> = delegate.sniffFailureDetails
    override fun init(output: ExtractorOutput) = delegate.init(TapOutput(output, store))
    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = delegate.read(input, seekPosition)
    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)
    override fun release() = delegate.release()
    override fun getUnderlyingImplementation(): Extractor = delegate.underlyingImplementation
}

private class TapOutput(private val delegate: ExtractorOutput, private val store: EmbeddedSubtitles) : ExtractorOutput {
    private val outputs = HashMap<Int, TrackOutput>()

    override fun track(id: Int, type: Int): TrackOutput {
        val track = delegate.track(id, type)
        if (type != C.TRACK_TYPE_TEXT) return track
        return outputs.getOrPut(id) { TapTrackOutput(track, store) }
    }

    override fun endTracks() = delegate.endTracks()
    override fun seekMap(seekMap: SeekMap) = delegate.seekMap(seekMap)
}

/**
 * Reicht alles unverändert weiter und dekodiert nebenbei die vom Player schon umgewandelten
 * Untertitel (Media3-Cues). Ein Fehler hier darf die Wiedergabe nie stören.
 */
private class TapTrackOutput(private val delegate: TrackOutput, private val store: EmbeddedSubtitles) : TrackOutput {
    private var format: Format? = null
    private val pending = ByteArrayOutputStream()
    private val decoder = CueDecoder()

    private val capturing get() = format?.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES

    override fun format(format: Format) {
        this.format = format
        delegate.format(format)
    }

    override fun durationUs(durationUs: Long) = delegate.durationUs(durationUs)

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
        if (!capturing || sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }
        val buffer = ByteArray(length)
        val read = input.read(buffer, 0, length)
        if (read == C.RESULT_END_OF_INPUT) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        pending.write(buffer, 0, read)
        delegate.sampleData(ParsableByteArray(buffer, read), read, sampleDataPart)
        return read
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (capturing && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) pending.write(data.data, data.position, length)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        val current = format ?: return
        if (!capturing) return
        val bytes = pending.toByteArray()
        pending.reset()
        val end = bytes.size - offset
        if (offset > 0 && end >= 0) pending.write(bytes, end, offset)
        val start = end - size
        if (start < 0 || size <= 0) return
        // Bild-Untertitel, die niemand lesen will: nicht dekodieren (PNG pro Einblendung).
        if (store.isBitmap(current) && !store.wantsImages(current)) return
        runCatching {
            val cues = decoder.decode(timeUs, bytes, start, size)
            store.add(current, timeUs, cues.durationUs, cues.cues)
        }
    }
}
