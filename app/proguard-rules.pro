# Room, Media3, OkHttp und kotlinx.serialization bringen eigene Regeln mit.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# FFmpeg-Tondecoder: per Reflection von DefaultRenderersFactory bzw. AudioDecoders geladen.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { public static *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-dontwarn androidx.media3.decoder.ffmpeg.**
