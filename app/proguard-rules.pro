# Room, Media3, OkHttp und kotlinx.serialization bringen eigene Regeln mit.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# FFmpeg-Tondecoder: per Reflection von DefaultRenderersFactory bzw. AudioDecoders geladen.
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { public static *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer { <init>(...); }
-dontwarn androidx.media3.decoder.ffmpeg.**

# ONNX Runtime (Übersetzung und Untertitel-Erkennung auf dem Gerät): Die native Bibliothek sucht ihre Java-Klassen
# per JNI über den Namen (ai/onnxruntime/TensorInfo …). Ohne diese Regel benennt R8 sie um, und der Prozess bricht mit
# „JNI DETECTED ERROR … java_class == null“ ab (Fehlerbericht 01.11.2026, Prozess de.ayutv:mt, Faden dna-ocr).
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
