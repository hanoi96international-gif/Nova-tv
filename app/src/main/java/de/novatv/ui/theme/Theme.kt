package de.novatv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object NovaColors {
    val Background = Color(0xFF0B0F14)
    val Surface = Color(0xFF151B23)
    val SurfaceRaised = Color(0xFF1E2631)
    val Accent = Color(0xFF3DA9FC)
    val AccentSoft = Color(0xFF1B4A6E)
    val Live = Color(0xFFE5484D)
    val TextPrimary = Color(0xFFF2F5F8)
    val TextSecondary = Color(0xFF9AA7B4)
    val Progress = Color(0xFF3DA9FC)
}

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NovaColors.Accent,
            onPrimary = Color.Black,
            primaryContainer = NovaColors.AccentSoft,
            background = NovaColors.Background,
            onBackground = NovaColors.TextPrimary,
            surface = NovaColors.Surface,
            onSurface = NovaColors.TextPrimary,
            surfaceVariant = NovaColors.SurfaceRaised,
            onSurfaceVariant = NovaColors.TextSecondary,
            border = NovaColors.Accent,
        ),
    ) {
        // Ohne umgebende Surface wäre Text sonst schwarz auf dunklem Hintergrund.
        CompositionLocalProvider(LocalContentColor provides NovaColors.TextPrimary, content = content)
    }
}
