package de.ayutv.data.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Process
import android.util.Log
import de.ayutv.player.EmbeddedSubtitles
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.util.concurrent.Executors

/**
 * Texterkennung für Bild-Untertitel (Blu-ray/PGS, DVB, DVD) auf dem Gerät: PaddleOCR PP-OCRv5
 * für lateinische Schrift (Deutsch, Englisch, Französisch, … inkl. Umlaute), als ONNX-Modell
 * (Apache 2.0, einmalig ca. 8 MB). Untertitel sind saubere Schrift vor leerem Hintergrund:
 * Zeilen werden per Helligkeitsprofil getrennt, eine eigene Texterkennung für Bereiche ist unnötig.
 */
class SubtitleOcr(baseDir: File, private val http: OkHttpClient) {
    @Volatile
    var onProgress: (String) -> Unit = {}

    /** Meldet jeden Schritt in nativem Code vorher (und null danach): Bei einem Absturz ist klar, wo. */
    @Volatile
    var trace: (String?) -> Unit = {}

    private val dir = File(baseDir, "ocr")
    private val variantFile = File(baseDir, VARIANT_FILE)
    private val env by lazy { OrtEnvironment.getEnvironment().also { runCatching { it.setTelemetry(false) } } }
    private var session: OrtSession? = null
    private var alphabet: List<String> = emptyList()
    private val lock = Mutex()
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, "dna-ocr")
    }.asCoroutineDispatcher()

    val installed: Boolean get() = FILES.all { (name, size) -> File(dir, name).length() == size }

    /** Text einer Einblendung, Zeilen durch "\n" getrennt ("" wenn nichts erkennbar). */
    suspend fun recognize(image: EmbeddedSubtitles.GrayImage): String = withContext(dispatcher) {
        val model = model()
        lines(image).mapNotNull { (top, bottom) -> line(model, image, top, bottom).trim().takeIf { it.isNotEmpty() } }
            .joinToString("\n")
    }

    suspend fun release() = withContext(dispatcher) {
        lock.withLock {
            session?.close()
            session = null
        }
    }

    private suspend fun model(): OrtSession = lock.withLock {
        session?.let { return@withLock it }
        if (!installed) download()
        alphabet = File(dir, DICT).readLines(Charsets.UTF_8).map { it.trimEnd('\r', '\n') }
        val variant = variant(variantFile)
        if (variant >= VARIANTS) throw IOException("Texterkennung stürzt auf diesem Gerät ab (alle Varianten probiert)")
        val options = OrtSession.SessionOptions().apply {
            // Ein Thread (der eigene, mit Hintergrund-Vorrang): kein Thread-Pool, der dem Video Rechenzeit nimmt.
            setIntraOpNumThreads(1)
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            addConfigEntry("session.intra_op.allow_spinning", "0")
            addConfigEntry("session.inter_op.allow_spinning", "0")
            // Stürzt das Modell auf einem Gerät ab (32-Bit-ARM), beim nächsten Mal vorsichtiger rechnen:
            // 1. normal, 2. ohne Graph-Optimierungen, 3. mit XNNPACK statt der eigenen Rechenkerne.
            when (variant) {
                0 -> setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT)
                1 -> setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                else -> {
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                    addXnnpack(mapOf("intra_op_num_threads" to "1"))
                }
            }
        }
        currentVariant = variant + 1
        try {
            trace("Texterkennung (Variante $currentVariant) startet")
            env.createSession(File(dir, MODEL).path, options).also { session = it; trace(null) }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw IOException("Texterkennung defekt, wird neu geladen: ${e.message}", e)
        }
    }

    /** Zeilen finden: zusammenhängende Bildzeilen mit heller Schrift, kleine Lücken überbrückt. */
    private fun lines(image: EmbeddedSubtitles.GrayImage): List<Pair<Int, Int>> {
        val ink = BooleanArray(image.height) { y ->
            val offset = y * image.width
            var count = 0
            for (x in 0 until image.width) if (image.pixels[offset + x].toInt() and 0xFF > INK) count++
            count >= 2
        }
        val result = mutableListOf<Pair<Int, Int>>()
        var y = 0
        while (y < image.height) {
            if (!ink[y]) {
                y++
                continue
            }
            var end = y
            var gap = 0
            var k = y
            while (k < image.height && gap <= MAX_GAP) {
                if (ink[k]) {
                    end = k
                    gap = 0
                } else {
                    gap++
                }
                k++
            }
            if (end - y + 1 >= MIN_LINE) result += (y - PAD).coerceAtLeast(0) to (end + PAD).coerceAtMost(image.height - 1)
            y = end + 1
        }
        return result
    }

    private fun line(model: OrtSession, image: EmbeddedSubtitles.GrayImage, top: Int, bottom: Int): String {
        // Waagrecht auf die Schrift zuschneiden.
        var left = image.width
        var right = -1
        for (y in top..bottom) {
            val offset = y * image.width
            for (x in 0 until image.width) {
                if (image.pixels[offset + x].toInt() and 0xFF > INK) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (right < left) return ""
        left = (left - PAD).coerceAtLeast(0)
        right = (right + PAD).coerceAtMost(image.width - 1)
        val srcW = right - left + 1
        val srcH = bottom - top + 1
        val width = (srcW * HEIGHT / srcH).coerceIn(16, MAX_WIDTH)
        // Bilinear auf 48 px Höhe, Werte wie beim Training: (x/255 − 0,5) / 0,5, drei gleiche Kanäle.
        val plane = FloatArray(HEIGHT * width)
        for (ty in 0 until HEIGHT) {
            val sy = ((ty + 0.5f) * srcH / HEIGHT - 0.5f).coerceIn(0f, (srcH - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = (y0 + 1).coerceAtMost(srcH - 1)
            val fy = sy - y0
            for (tx in 0 until width) {
                val sx = ((tx + 0.5f) * srcW / width - 0.5f).coerceIn(0f, (srcW - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = (x0 + 1).coerceAtMost(srcW - 1)
                val fx = sx - x0
                fun p(x: Int, y: Int) = (image.pixels[(top + y) * image.width + left + x].toInt() and 0xFF).toFloat()
                val v = (p(x0, y0) * (1 - fx) + p(x1, y0) * fx) * (1 - fy) + (p(x0, y1) * (1 - fx) + p(x1, y1) * fx) * fy
                plane[ty * width + tx] = (v / 255f - 0.5f) / 0.5f
            }
        }
        val input = FloatArray(3 * plane.size)
        for (c in 0 until 3) plane.copyInto(input, c * plane.size)
        trace("Texterkennung (Variante $currentVariant) rechnet (Zeile ${width}×$HEIGHT)")
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, HEIGHT.toLong(), width.toLong())).use { tensor ->
            model.run(mapOf(model.inputNames.first() to tensor)).also { trace(null) }.use { result ->
                @Suppress("UNCHECKED_CAST")
                val scores = (result[0].value as Array<Array<FloatArray>>)[0]
                return decode(scores)
            }
        }
    }

    /** CTC: je Zeitschritt das wahrscheinlichste Zeichen, Wiederholungen und Leerstellen (0) entfernen. */
    private fun decode(scores: Array<FloatArray>): String = buildString {
        var last = -1
        for (step in scores) {
            var best = 0
            for (k in 1 until step.size) if (step[k] > step[best]) best = k
            if (best != last && best != 0) append(if (best - 1 < alphabet.size) alphabet[best - 1] else " ")
            last = best
        }
    }.replace(Regex(" {2,}"), " ")

    private fun download() {
        dir.mkdirs()
        onProgress("Lade Texterkennung für Bild-Untertitel (einmalig ca. 8 MB) …")
        for ((name, size) in FILES) {
            val target = File(dir, name)
            if (target.length() == size) continue
            val part = File(dir, "$name.part")
            http.newCall(Request.Builder().url("$BASE/$name").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Texterkennung nicht erreichbar (HTTP ${response.code})")
                response.body.byteStream().use { input -> part.outputStream().use { input.copyTo(it, 256 * 1024) } }
            }
            if (part.length() != size) {
                part.delete()
                throw IOException("Download der Texterkennung unvollständig, bitte erneut versuchen")
            }
            if (!part.renameTo(target)) throw IOException("Texterkennung konnte nicht gespeichert werden")
        }
        Log.i(TAG, "Texterkennung geladen")
    }

    private var currentVariant = 1

    companion object {
        private const val VARIANT_FILE = "ocr_variant.txt"
        private const val VARIANTS = 3

        /** Gewählte Variante; nach einem App-Update wieder von vorn (neue ONNX-Version kann es lösen). */
        private fun variant(file: File): Int = runCatching {
            val (variant, version) = file.readText().trim().split(' ')
            if (version.toInt() == de.ayutv.BuildConfig.VERSION_CODE) variant.toInt() else 0
        }.getOrDefault(0)

        /** Nach einem Absturz in der Texterkennung: nächstes Mal die nächste Variante. Gibt sie zurück (1-basiert), null = keine mehr. */
        fun markCrash(baseDir: File): Int? {
            val file = File(baseDir, VARIANT_FILE)
            val next = variant(file) + 1
            runCatching { file.writeText("$next ${de.ayutv.BuildConfig.VERSION_CODE}") }
            return (next + 1).takeIf { next < VARIANTS }
        }

        private const val TAG = "DnAOcr"
        private const val BASE = "https://huggingface.co/monkt/paddleocr-onnx/resolve/main/languages/latin"
        private const val MODEL = "rec.onnx"
        private const val DICT = "dict.txt"
        private val FILES = listOf(MODEL to 7_862_832L, DICT to 1_634L)
        private const val HEIGHT = 48
        private const val MAX_WIDTH = 2_000
        private const val INK = 140
        private const val MAX_GAP = 5
        private const val MIN_LINE = 8
        private const val PAD = 4
    }
}
