package de.ayutv.data

import android.content.Context
import de.ayutv.data.parse.IntroSkip

/** Gelernte Intros pro Serie (Beginn und Länge in ms). */
class IntroStore(context: Context) {
    private val prefs = context.getSharedPreferences("intros", Context.MODE_PRIVATE)

    fun get(seriesKey: String): IntroSkip.Intro? {
        val parts = prefs.getString(seriesKey, null)?.split(',') ?: return null
        val start = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val length = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return IntroSkip.Intro(start, length)
    }

    fun put(seriesKey: String, intro: IntroSkip.Intro) {
        prefs.edit().putString(seriesKey, "${intro.startMs},${intro.lengthMs}").apply()
    }

    fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    fun restore(values: Map<String, String>) {
        prefs.edit().apply { values.forEach { (k, v) -> putString(k, v) } }.apply()
    }
}
