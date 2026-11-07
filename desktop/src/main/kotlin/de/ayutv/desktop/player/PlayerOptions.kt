package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.SubtitleBackground
import de.ayutv.desktop.data.SubtitleColor
import de.ayutv.desktop.data.SubtitlePosition
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.UserData

/**
 * Einstellungen aus „Klang“ und „Untertitel-Aussehen“ als VLC-Optionen. VLC übernimmt sie nur beim Anlegen des
 * Players ([VideoPlayer.reconfigure]); Ton-Versatz und Klangregelung gelten dagegen sofort.
 */
object PlayerOptions {
    fun vlc(user: UserData): List<String> = vlc(user.audioMode, user.normalizeVolume, user.subtitleSize, user.subtitleColor, user.subtitleBackground, user.subtitlePosition)

    fun vlc(
        audioMode: AudioMode,
        normalizeVolume: Boolean,
        size: SubtitleSize,
        color: SubtitleColor,
        background: SubtitleBackground,
        position: SubtitlePosition,
    ): List<String> = buildList {
        val filters = buildList {
            // Nachtmodus und Dialoge: laute Stellen leiser, leise lauter (Dynamik-Kompressor).
            if (audioMode != AudioMode.NORMAL) add("compressor")
            if (normalizeVolume) add("normvol")
        }
        if (filters.isNotEmpty()) add("--audio-filter=" + filters.joinToString(","))
        if (size != SubtitleSize.NORMAL) add("--sub-text-scale=${size.percent}")
        if (color != SubtitleColor.WHITE) add("--freetype-color=${color.rgb}")
        when (background) {
            SubtitleBackground.OUTLINE -> Unit
            SubtitleBackground.SHADOW -> { add("--freetype-outline-thickness=2"); add("--freetype-shadow-opacity=220") }
            SubtitleBackground.BOX -> {
                add("--freetype-outline-thickness=0")
                add("--freetype-background-opacity=170")
                add("--freetype-background-color=0")
            }
        }
        if (position != SubtitlePosition.NORMAL) add("--sub-margin=${position.margin}")
    }

    /** Klangregelung: Dialoge = Sprachbereich (1–6 kHz) anheben, Bässe und Höhen leiser. */
    fun equalizer(user: UserData): List<Float>? = when (user.audioMode) {
        AudioMode.DIALOG -> listOf(-4f, -3f, -1f, 2f, 4f, 5f, 3f, 0f, -1f, -2f)
        else -> null
    }

    /** Alle Schalter auf einmal (für den Starttest: VLC muss jede dieser Optionen kennen). */
    val VARIANTS: List<List<String>> = listOf(
        vlc(AudioMode.DIALOG, true, SubtitleSize.XL, SubtitleColor.YELLOW, SubtitleBackground.BOX, SubtitlePosition.HIGH),
        vlc(AudioMode.NIGHT, false, SubtitleSize.SMALL, SubtitleColor.CYAN, SubtitleBackground.SHADOW, SubtitlePosition.LOW),
    )
}
