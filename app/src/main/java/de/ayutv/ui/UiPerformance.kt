package de.ayutv.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.atomic.AtomicLong

/**
 * Wie viel Bewegung die Oberfläche sich leisten kann, und wie flüssig sie tatsächlich läuft.
 *
 * Auf schwachen Geräten (Fire TV Stick 4K: 32 Bit, 2 GB, kleiner Grafikchip) entfallen Überblendungen zwischen
 * den Bereichen, das Start-Intro und das Federn der Seitenleiste: Jede Überblendung zeichnet den ganzen Bildschirm
 * zweimal halbdurchsichtig, das schafft der Grafikchip nicht ohne Ruckeln.
 */
object UiPerformance {
    /** Weniger Bewegung (schwaches Gerät oder vom System als Gerät mit wenig Speicher gemeldet). */
    @Volatile var reduced: Boolean = false
        private set

    /** Nur für Tests: wie auf einem schwachen Stick (ohne Übergänge, Hintergrund kleiner gezeichnet). */
    @androidx.annotation.VisibleForTesting
    fun force(value: Boolean) {
        reduced = value
    }

    fun init(context: Context) {
        val lowRam = runCatching { context.getSystemService(ActivityManager::class.java).isLowRamDevice }.getOrDefault(false)
        reduced = lowRam || de.ayutv.data.DeviceInfo.isWeak(context)
    }

    // ------------------------------------------------------------------ Ruckel-Messung

    private val frames = AtomicLong()
    private val slow = AtomicLong()
    private val verySlow = AtomicLong()
    private val worstMs = AtomicLong()
    private var thread: HandlerThread? = null
    private val listeners = HashMap<Window, Window.OnFrameMetricsAvailableListener>()

    /** Dauer jedes gezeichneten Bildes der Menüs mitzählen (kostet nichts, solange nichts gezeichnet wird). */
    fun watch(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window
        if (window in listeners) return
        val handler = Handler((thread ?: HandlerThread("frame-stats").apply { start() }.also { thread = it }).looper)
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return@OnFrameMetricsAvailableListener
            val ms = metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000
            frames.incrementAndGet()
            if (ms > SLOW_MS) slow.incrementAndGet()
            if (ms > VERY_SLOW_MS) verySlow.incrementAndGet()
            worstMs.accumulateAndGet(ms) { a, b -> maxOf(a, b) }
        }
        runCatching { window.addOnFrameMetricsAvailableListener(listener, handler) }.onSuccess { listeners[window] = listener }
    }

    fun unwatch(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = activity.window
        listeners.remove(window)?.let { runCatching { window.removeOnFrameMetricsAvailableListener(it) } }
    }

    /** Für den Fehlerbericht: Anteil ruckeliger Bilder seit dem Start. */
    fun summary(): String {
        val total = frames.get()
        if (total == 0L) return "noch keine Messung"
        fun share(n: Long) = String.format(java.util.Locale.GERMAN, "%.1f %%", n * 100.0 / total)
        return "$total Bilder, über ${SLOW_MS} ms: ${share(slow.get())}, über ${VERY_SLOW_MS} ms: ${share(verySlow.get())}, " +
            "längstes ${worstMs.get()} ms" + if (reduced) " (weniger Bewegung aktiv)" else ""
    }

    private const val SLOW_MS = 25L
    private const val VERY_SLOW_MS = 50L
}
