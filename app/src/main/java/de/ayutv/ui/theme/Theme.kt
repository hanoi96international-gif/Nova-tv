package de.ayutv.ui.theme

import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.Border
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import kotlinx.coroutines.launch
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Wählbares Aussehen der App. */
enum class UiTheme(val label: String) {
    HANNOVER("Hannover Rot-Schwarz"),
    CLASSIC("Klassisch Blau"),
}

/** Farben eines Aussehens. */
data class Palette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val accent: Color,
    val accentSoft: Color,
    val live: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val progress: Color,
) {
    companion object {
        val CLASSIC = Palette(
            background = Color(0xFF0B0F14),
            surface = Color(0xFF151B23),
            surfaceRaised = Color(0xFF1E2631),
            accent = Color(0xFF3DA9FC),
            accentSoft = Color(0xFF235F8E),
            live = Color(0xFFE5484D),
            textPrimary = Color(0xFFF2F5F8),
            textSecondary = Color(0xFF9AA7B4),
            progress = Color(0xFF3DA9FC),
        )

        /**
         * Rot wie das Trikot, Schwarz, Weiß und ein Hauch Grün aus den Vereinsfarben. Fokus in kräftigem Rot,
         * auf dem Fernseher von weitem zu sehen; Fortschritt in Rasengrün.
         */
        val HANNOVER = Palette(
            background = Color(0xFF0C0909),
            surface = Color(0xFF1A1213),
            surfaceRaised = Color(0xFF2A1A1C),
            accent = Color(0xFFFF3B3F),
            accentSoft = Color(0xFFB0141B),
            live = Color(0xFFFF5A3C),
            textPrimary = Color(0xFFFFF7F5),
            textSecondary = Color(0xFFBFAEAD),
            progress = Color(0xFF1FB45A),
        )

        fun of(theme: UiTheme) = when (theme) {
            UiTheme.HANNOVER -> HANNOVER
            UiTheme.CLASSIC -> CLASSIC
        }
    }
}

/**
 * Die Farben der App. Wechselt das Aussehen, zeichnet Compose alles neu, was eine Farbe liest
 * (die Palette ist ein beobachteter Zustand).
 */
object AyuColors {
    var palette by mutableStateOf(Palette.HANNOVER)
    var theme by mutableStateOf(UiTheme.HANNOVER)
        private set

    fun use(next: UiTheme) {
        theme = next
        palette = Palette.of(next)
    }

    val Background get() = palette.background
    val Surface get() = palette.surface
    val SurfaceRaised get() = palette.surfaceRaised
    val Accent get() = palette.accent
    // Fokusfläche: hell genug, dass man auf dem Fernseher sofort sieht, wo man ist.
    val AccentSoft get() = palette.accentSoft
    val Live get() = palette.live
    val TextPrimary get() = palette.textPrimary
    val TextSecondary get() = palette.textSecondary
    val Progress get() = palette.progress

    /** Vereinsfarben für Zierstreifen. */
    val Green = Color(0xFF1FB45A)
}

@Composable
fun AyuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = AyuColors.Accent,
            onPrimary = Color.Black,
            primaryContainer = AyuColors.AccentSoft,
            background = AyuColors.Background,
            onBackground = AyuColors.TextPrimary,
            surface = AyuColors.Surface,
            onSurface = AyuColors.TextPrimary,
            surfaceVariant = AyuColors.SurfaceRaised,
            onSurfaceVariant = AyuColors.TextSecondary,
            border = AyuColors.Accent,
        ),
    ) {
        // Ohne umgebende Surface wäre Text sonst schwarz auf dunklem Hintergrund.
        CompositionLocalProvider(LocalContentColor provides AyuColors.TextPrimary, content = content)
    }
}

/**
 * Hintergrund der Menüs. Im Hannover-Design ein Stadionabend: tiefrot nach schwarz, zwei Flutlichtkegel, eine breite
 * Schärpe quer über den Bildschirm (Rot mit Linien in Weiß und Grün), eine riesige, angeschnittene 96 als Umriss,
 * ein Fanschal-Streifen oben und unten die Linie in Schwarz-Weiß-Grün. Eigenes Hintergrundbild und Logo
 * ([ThemeAssets]) kommen darunter bzw. groß als Wasserzeichen dazu. Klassisch: schlichtes Dunkelblau.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(AyuColors.Background)) {
        if (AyuColors.theme == UiTheme.HANNOVER) {
            val photo = ThemeAssets.background
            val logo = ThemeAssets.logo
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                if (photo != null) {
                    // Eigenes Bild bildschirmfüllend (zugeschnitten), abgedunkelt, damit Schrift lesbar bleibt
                    val scale = maxOf(w / photo.width, h / photo.height)
                    val dw = photo.width * scale
                    val dh = photo.height * scale
                    drawImage(
                        photo,
                        dstOffset = androidx.compose.ui.unit.IntOffset(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()),
                    )
                    drawRect(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.45f))))
                } else {
                    // Abendhimmel: oben dunkelrot, nach unten schwarz
                    drawRect(Brush.verticalGradient(listOf(Color(0xFF45090E), Color(0xFF1A0709), Color(0xFF0C0909)), endY = h))
                }
                // Flutlichtkegel von den Masten oben links und rechts
                for ((x, dir) in listOf(w * 0.08f to 1f, w * 0.92f to -1f)) {
                    val cone = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x, -h * 0.02f)
                        lineTo(x + dir * w * 0.42f, h * 1.05f)
                        lineTo(x + dir * w * 0.12f, h * 1.05f)
                        close()
                    }
                    drawPath(cone, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.09f), Color.Transparent), startY = 0f, endY = h))
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), center = Offset(x, 0f), radius = h * 0.22f),
                        radius = h * 0.22f,
                        center = Offset(x, 0f),
                    )
                }
                // Schärpe: breites rotes Band von unten links nach oben rechts, mit weißer und grüner Linie
                val band = h * 0.34f
                fun sash(offset: Float, thickness: Float, color: Color) {
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(-w * 0.1f, h * 1.05f + offset)
                        lineTo(w * 1.1f, h * 0.05f + offset)
                        lineTo(w * 1.1f, h * 0.05f + offset + thickness)
                        lineTo(-w * 0.1f, h * 1.05f + offset + thickness)
                        close()
                    }
                    drawPath(path, color)
                }
                sash(-band / 2, band, Color(0xFFD2121A).copy(alpha = if (photo != null) 0.30f else 0.42f))
                sash(-band / 2 - h * 0.018f, h * 0.008f, Color.White.copy(alpha = 0.55f))
                sash(band / 2 + h * 0.010f, h * 0.008f, AyuColors.Green.copy(alpha = 0.65f))
                // Glut in der Kurve unten
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFF3B1F).copy(alpha = 0.20f), Color.Transparent), center = Offset(w * 0.5f, h * 1.15f), radius = w * 0.6f),
                    radius = w * 0.6f,
                    center = Offset(w * 0.5f, h * 1.15f),
                )
                // Trikot-Nadelstreifen
                rotate(-18f) {
                    var x = -h
                    while (x < w + h) {
                        drawLine(Color.White.copy(alpha = 0.022f), Offset(x, -h), Offset(x, h * 2), strokeWidth = 2f)
                        x += 38f
                    }
                }
                val native = drawContext.canvas.nativeCanvas
                // Eigenes Logo groß und schräg rechts, angeschnitten, oder sonst eine riesige 96 als Umriss
                if (logo != null) {
                    val side = h * 0.95f
                    val ratio = logo.width.toFloat() / logo.height
                    val lw = if (ratio >= 1) side else side * ratio
                    val lh = if (ratio >= 1) side / ratio else side
                    rotate(-10f, pivot = Offset(w * 0.80f, h * 0.55f)) {
                        drawImage(
                            logo,
                            dstOffset = androidx.compose.ui.unit.IntOffset((w * 0.80f - lw / 2).toInt(), (h * 0.55f - lh / 2).toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(lw.toInt(), lh.toInt()),
                            alpha = 0.16f,
                        )
                    }
                } else {
                    native.save()
                    native.rotate(-10f, w * 0.82f, h * 0.6f)
                    val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(26, 210, 18, 26)
                        textSize = h * 0.95f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val outline = android.graphics.Paint(fill).apply {
                        color = android.graphics.Color.argb(46, 255, 255, 255)
                        style = android.graphics.Paint.Style.STROKE
                        strokeWidth = h * 0.006f
                    }
                    native.drawText("96", w * 0.86f, h * 0.92f, fill)
                    native.drawText("96", w * 0.86f, h * 0.92f, outline)
                    native.restore()
                }
                // Fanschal oben: Blöcke in Schwarz, Weiß, Grün mit „HANNOVER“
                val scarf = h * 0.026f
                val block = scarf * 9
                var x = 0f
                var i = 0
                val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = scarf * 0.72f
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    textAlign = android.graphics.Paint.Align.CENTER
                    letterSpacing = 0.25f
                }
                while (x < w) {
                    val (bg, fg) = when (i % 3) {
                        0 -> Color(0xFF111111) to android.graphics.Color.WHITE
                        1 -> Color(0xFFF2F2F2) to android.graphics.Color.rgb(17, 17, 17)
                        else -> Color(0xFF178F47) to android.graphics.Color.WHITE
                    }
                    drawRect(bg.copy(alpha = 0.9f), Offset(x, 0f), androidx.compose.ui.geometry.Size(block, scarf))
                    label.color = fg
                    native.drawText(if (i % 2 == 0) "HANNOVER" else "96", x + block / 2, scarf * 0.76f, label)
                    x += block
                    i++
                }
                // Linie in Schwarz-Weiß-Grün am unteren Rand
                val line = h * 0.006f
                drawRect(Color.Black, Offset(0f, h - line * 3), androidx.compose.ui.geometry.Size(w, line))
                drawRect(Color.White.copy(alpha = 0.85f), Offset(0f, h - line * 2), androidx.compose.ui.geometry.Size(w, line))
                drawRect(AyuColors.Green, Offset(0f, h - line), androidx.compose.ui.geometry.Size(w, line))
            }
        }
        content()
    }
}

/**
 * Stadion-Intro beim Start (nur im Hannover-Design): Flutlicht geht an, Logo bzw. 96 kommt groß herein, dann
 * blendet alles in die App über. Knapp zwei Sekunden, die App lädt währenddessen schon dahinter.
 */
@Composable
fun StadiumIntro(onDone: () -> Unit) {
    val lights = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
    val badge = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0.4f) }
    val fade = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.coroutineScope {
            launch { lights.animateTo(1f, androidx.compose.animation.core.tween(500)) }
            kotlinx.coroutines.delay(250)
            launch { badge.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 260f)) }
        }
        kotlinx.coroutines.delay(650)
        fade.animateTo(0f, androidx.compose.animation.core.tween(450))
        onDone()
    }
    val logo = ThemeAssets.logo
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val a = fade.value
        drawRect(Color.Black.copy(alpha = a))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6A0A12).copy(alpha = 0.9f * a * lights.value), Color.Transparent), endY = h))
        for (x in listOf(w * 0.08f, w * 0.92f)) {
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f * a * lights.value), Color.Transparent), center = Offset(x, 0f), radius = h * 0.5f),
                radius = h * 0.5f,
                center = Offset(x, 0f),
            )
        }
        val scale = badge.value
        if (logo != null) {
            val side = h * 0.45f * scale
            val ratio = logo.width.toFloat() / logo.height
            val lw = if (ratio >= 1) side else side * ratio
            val lh = if (ratio >= 1) side / ratio else side
            drawImage(
                logo,
                dstOffset = androidx.compose.ui.unit.IntOffset((w / 2 - lw / 2).toInt(), (h / 2 - lh / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(lw.toInt(), lh.toInt()),
                alpha = a,
            )
        } else {
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.argb((255 * a).toInt(), 230, 30, 38)
                textSize = h * 0.42f * scale
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.drawText("96", w / 2, h / 2 + h * 0.14f * scale, paint)
        }
    }
}

/** Gut sichtbare Fokusmarkierung für Listen und Raster: heller Rahmen zusätzlich zur Fokusfarbe. */
object AyuFocus {
    val border: ClickableSurfaceBorder
        @Composable get() = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AyuColors.Accent)),
        )
}
