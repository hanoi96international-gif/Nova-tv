package de.ayutv.player

/**
 * Prüft, ob die FFmpeg-Tondecoder in der App stecken (siehe ci/build-ffmpeg.sh). Ohne sie
 * spielt die App nur Tonformate, die das Gerät selbst dekodiert oder an den Receiver durchreicht.
 */
object AudioDecoders {
    private const val LIBRARY = "androidx.media3.decoder.ffmpeg.FfmpegLibrary"

    val ffmpegAvailable: Boolean by lazy {
        runCatching { Class.forName(LIBRARY).getMethod("isAvailable").invoke(null) as Boolean }.getOrDefault(false)
    }

    val ffmpegVersion: String? by lazy {
        if (!ffmpegAvailable) null else runCatching { Class.forName(LIBRARY).getMethod("getVersion").invoke(null) as String? }.getOrNull()
    }
}
