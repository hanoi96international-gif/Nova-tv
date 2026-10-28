package de.ayutv.data

import android.content.Context

/** Bildformat im Player: ganz zeigen, füllen (Ränder abschneiden) oder auf den Bildschirm strecken. */
enum class AspectMode(val label: String) {
    FIT("Ganzes Bild"),
    ZOOM("Füllen (Ränder abschneiden)"),
    STRETCH("Strecken (4:3 auf 16:9)"),
}

/**
 * Was im Player zuletzt gewählt wurde, je Sender, Serie oder Film: Tonsprache, Untertitel (Sprache oder aus)
 * und Bildformat. Beim nächsten Mal stellt der Player es wieder so ein.
 */
class PlayerMemory(context: Context) {
    private val prefs = context.getSharedPreferences("player_memory", Context.MODE_PRIVATE)

    /**
     * [load]: Untertitel, die von außen kamen (OpenSubtitles, Übersetzung) – bei einer Serie lädt die nächste
     * Folge sie von selbst wieder, auch wenn „Untertitel automatisch an“ aus ist.
     */
    data class Choice(val audio: String? = null, val text: String? = null, val aspect: AspectMode? = null, val load: String? = null) {
        /** Untertitel bewusst ausgeschaltet. */
        val textOff: Boolean get() = text == OFF
    }

    fun get(key: String): Choice = decode(prefs.getString(key, null))

    fun update(key: String, transform: (Choice) -> Choice) {
        val next = transform(get(key))
        prefs.edit().putString(key, encode(next)).apply()
    }

    companion object {
        const val OFF = "-"

        fun encode(c: Choice): String = listOf(c.audio.orEmpty(), c.text.orEmpty(), c.aspect?.name.orEmpty(), c.load.orEmpty()).joinToString("|")

        fun decode(raw: String?): Choice {
            if (raw.isNullOrEmpty()) return Choice()
            val parts = raw.split('|')
            return Choice(
                audio = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
                text = parts.getOrNull(1)?.takeIf { it.isNotEmpty() },
                aspect = parts.getOrNull(2)?.let { name -> AspectMode.entries.firstOrNull { it.name == name } },
                load = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
            )
        }
    }
}
