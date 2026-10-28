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
 * Hintergrund der Menüs. Im Hannover-Design: Flutlicht von oben über einem tiefroten Stadionabend, feine
 * Trikot-Nadelstreifen, eine große, kaum sichtbare 96 und unten eine Linie in Schwarz-Weiß-Grün.
 * Klassisch: schlichtes Dunkelblau.
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().background(AyuColors.Background)) {
        if (AyuColors.theme == UiTheme.HANNOVER) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                // Abendhimmel: oben dunkelrot, nach unten schwarz
                drawRect(Brush.verticalGradient(listOf(Color(0xFF3A0B0F), Color(0xFF160708), Color(0xFF0C0909)), endY = h))
                // Zwei Flutlichter oben links und rechts
                for (x in listOf(w * 0.12f, w * 0.88f)) {
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(x, -h * 0.05f), radius = h * 0.75f),
                        radius = h * 0.75f,
                        center = Offset(x, -h * 0.05f),
                    )
                }
                // Glut in der Mitte unten, wie Pyro in der Kurve
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFB0141B).copy(alpha = 0.22f), Color.Transparent), center = Offset(w * 0.5f, h * 1.1f), radius = w * 0.55f),
                    radius = w * 0.55f,
                    center = Offset(w * 0.5f, h * 1.1f),
                )
                // Nadelstreifen schräg, sehr dezent
                rotate(-18f) {
                    var x = -h
                    while (x < w + h) {
                        drawLine(Color.White.copy(alpha = 0.018f), Offset(x, -h), Offset(x, h * 2), strokeWidth = 2f)
                        x += 42f
                    }
                }
                // Große 96 unten rechts, kaum sichtbar
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(14, 255, 255, 255)
                        textSize = h * 0.62f
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD_ITALIC)
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                    drawText("96", w * 0.98f, h * 0.96f, paint)
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

/** Gut sichtbare Fokusmarkierung für Listen und Raster: heller Rahmen zusätzlich zur Fokusfarbe. */
object AyuFocus {
    val border: ClickableSurfaceBorder
        @Composable get() = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AyuColors.Accent)),
        )
}
