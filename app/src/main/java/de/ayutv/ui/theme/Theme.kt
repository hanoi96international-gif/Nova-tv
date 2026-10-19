package de.ayutv.ui.theme

import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.Border
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object AyuColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF151B23)
    val SurfaceRaised = Color(0xFF1E2631)
    val Accent = Color(0xFF3DA9FC)
    // Fokusfläche: hell genug, dass man auf dem Fernseher sofort sieht, wo man ist.
    val AccentSoft = Color(0xFF235F8E)
    val Live = Color(0xFFE5484D)
    val TextPrimary = Color(0xFFF2F5F8)
    val TextSecondary = Color(0xFF9AA7B4)
    val Progress = Color(0xFF3DA9FC)
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

/** Gut sichtbare Fokusmarkierung für Listen und Raster: heller Rahmen zusätzlich zur Fokusfarbe. */
object AyuFocus {
    val border: ClickableSurfaceBorder
        @Composable get() = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, AyuColors.Accent)),
        )
}
