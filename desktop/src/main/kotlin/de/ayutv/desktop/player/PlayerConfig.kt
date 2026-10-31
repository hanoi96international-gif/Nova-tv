package de.ayutv.desktop.player

import de.ayutv.desktop.data.AudioMode
import de.ayutv.desktop.data.AudioOutput
import de.ayutv.desktop.data.Deinterlace
import de.ayutv.desktop.data.SubtitleBackground
import de.ayutv.desktop.data.SubtitleColor
import de.ayutv.desktop.data.SubtitlePosition
import de.ayutv.desktop.data.SubtitleSize
import de.ayutv.desktop.data.UserData
import de.ayutv.desktop.data.languageCodes

/**
 * Einstellungen für VLC. Bild, Ton und Aussehen der Untertitel gelten für den ganzen Player ([vlcOptions]: ändern
 * sie sich, startet VLC beim nächsten Sender neu), der Rest je Wiedergabe.
 */
data class PlayerConfig(
    val hardwareDecoding: Boolean = true,
    val deinterlace: Deinterlace = Deinterlace.AUTO,
    val audioOutput: AudioOutput = AudioOutput.AUTO,
    val audioMode: AudioMode = AudioMode.NORMAL,
    val normalizeVolume: Boolean = false,
    val subtitleSize: SubtitleSize = SubtitleSize.NORMAL,
    val subtitleColor: SubtitleColor = SubtitleColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.OUTLINE,
    val subtitlePosition: SubtitlePosition = SubtitlePosition.NORMAL,
    val networkCachingMs: Int = 3_000,
    val audioDelayMs: Long = 0,
    /** Bevorzugte Tonspur (VLC-Sprachkürzel, z. B. „de,deu,ger“). */
    val audioLanguage: String = "",
    /** Eingebaute Untertitel in dieser Sprache gleich einschalten (leer = aus). */
    val subtitleLanguage: String = "",
) {
    fun vlcOptions(): List<String> = buildList {
        add("--avcodec-hw=" + if (hardwareDecoding) "any" else "none")
        add("--deinterlace=${deinterlace.vlc}")
        // Bob/Yadif mit doppelter Bildrate: 1080i50 läuft mit 50 ganzen Bildern pro Sekunde (flüssige Schwenks).
        add("--deinterlace-mode=yadif2x")
        when (audioOutput) {
            AudioOutput.AUTO -> Unit
            AudioOutput.STEREO -> add("--stereo-mode=1")
            AudioOutput.PASSTHROUGH -> add("--spdif")
        }
        val filters = buildList {
            if (audioMode != AudioMode.NORMAL) add("compressor")
            if (normalizeVolume) add("normvol")
        }
        if (filters.isNotEmpty()) add("--audio-filter=" + filters.joinToString(","))
        add("--sub-text-scale=${subtitleSize.percent}")
        add("--freetype-color=${subtitleColor.rgb}")
        when (subtitleBackground) {
            SubtitleBackground.OUTLINE -> { add("--freetype-outline-thickness=4"); add("--freetype-shadow-opacity=0") }
            SubtitleBackground.SHADOW -> { add("--freetype-outline-thickness=2"); add("--freetype-shadow-opacity=220") }
            SubtitleBackground.BOX -> {
                add("--freetype-outline-thickness=0")
                add("--freetype-background-opacity=170")
                add("--freetype-background-color=0")
            }
        }
        add("--sub-margin=${subtitlePosition.margin}")
    }

    /** Klangregelung (dB je Band, 10 Bänder 60 Hz … 16 kHz); null = aus. Dialoge: Sprachbereich anheben. */
    fun equalizerBands(): List<Float>? = when (audioMode) {
        AudioMode.DIALOG -> listOf(-4f, -3f, -1f, 2f, 4f, 5f, 3f, 0f, -1f, -2f)
        else -> null
    }

    companion object {
        fun from(user: UserData) = PlayerConfig(
            hardwareDecoding = user.hardwareDecoding,
            deinterlace = user.deinterlace,
            audioOutput = user.audioOutput,
            audioMode = user.audioMode,
            normalizeVolume = user.normalizeVolume,
            subtitleSize = user.subtitleSize,
            subtitleColor = user.subtitleColor,
            subtitleBackground = user.subtitleBackground,
            subtitlePosition = user.subtitlePosition,
            networkCachingMs = user.buffer.ms,
            audioDelayMs = user.audioDelayMs,
            audioLanguage = user.audioLanguage.takeIf { it.isNotBlank() }?.let(::languageCodes).orEmpty(),
            subtitleLanguage = if (user.subtitlesAuto) languageCodes(user.subtitleLanguage.ifBlank { "de" }) else "",
        )

        /** Alle Schalter auf einmal (für den Selbsttest: VLC muss jede dieser Einstellungen kennen). */
        val EVERYTHING = PlayerConfig(
            hardwareDecoding = false, deinterlace = Deinterlace.ON, audioOutput = AudioOutput.STEREO, audioMode = AudioMode.DIALOG,
            normalizeVolume = true, subtitleSize = SubtitleSize.XL, subtitleColor = SubtitleColor.YELLOW,
            subtitleBackground = SubtitleBackground.BOX, subtitlePosition = SubtitlePosition.HIGH,
        )
        val VARIANTS = listOf(
            EVERYTHING,
            EVERYTHING.copy(audioOutput = AudioOutput.PASSTHROUGH, subtitleBackground = SubtitleBackground.SHADOW, audioMode = AudioMode.NIGHT),
            EVERYTHING.copy(subtitleBackground = SubtitleBackground.OUTLINE, deinterlace = Deinterlace.OFF),
        )
    }
}
