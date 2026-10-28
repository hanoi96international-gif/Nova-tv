package de.ayutv.ui.theme

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File

/**
 * Eigenes Logo und Hintergrundbild (z. B. das Wappen des Lieblingsvereins), per Handy hochgeladen. Liegen nur
 * auf diesem Gerät, nicht in der App: Wappen und Logos gehören den Vereinen.
 */
object ThemeAssets {
    enum class Kind(val file: String, val maxSide: Int) { LOGO("logo", 1024), BACKGROUND("background", 1920) }

    var logo by mutableStateOf<ImageBitmap?>(null)
        private set
    var background by mutableStateOf<ImageBitmap?>(null)
        private set

    private fun file(context: Context, kind: Kind) = File(File(context.filesDir, "theme").apply { mkdirs() }, kind.file)

    fun load(context: Context) {
        logo = decode(file(context, Kind.LOGO), Kind.LOGO.maxSide)
        background = decode(file(context, Kind.BACKGROUND), Kind.BACKGROUND.maxSide)
    }

    /** Speichert ein Bild; false, wenn es sich nicht lesen lässt (kein Bild). */
    fun save(context: Context, kind: Kind, bytes: ByteArray): Boolean {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return false
        file(context, kind).writeBytes(bytes)
        load(context)
        return true
    }

    fun clear(context: Context) {
        Kind.entries.forEach { file(context, it).delete() }
        load(context)
    }

    private fun decode(file: File, maxSide: Int): ImageBitmap? = runCatching {
        if (!file.isFile) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}
