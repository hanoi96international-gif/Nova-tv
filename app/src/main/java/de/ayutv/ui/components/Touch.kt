package de.ayutv.ui.components

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.CardBorder
import androidx.tv.material3.CardColors
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.SurfaceColors

/*
 * Bedienung per Touchscreen (Handy, Tablet). Die Oberfläche ist für die Fernbedienung gebaut: Ihre
 * Kacheln und Knöpfe reagieren auf OK, nicht auf Tippen. Die gleichnamigen Fassungen hier hängen das
 * Tippen an (fokussieren und auslösen, lange tippen = OK halten). Auf dem Fernseher ändert sich nichts.
 */

/** Gerät mit Touchscreen (Handy/Tablet). Fire TV und Android TV haben keinen. */
fun Context.hasTouchscreen(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

val isTouchDevice: Boolean
    @Composable get() {
        val context = LocalContext.current
        return remember(context) { context.hasTouchscreen() }
    }

/**
 * Handys sind im Querformat niedriger als ein Fernseher (540 dp): alles im gleichen Verhältnis
 * verkleinern, damit die Seiten wie auf dem Fernseher aussehen und nichts abgeschnitten wird.
 */
@Composable
fun FitToScreen(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val factor = (configuration.screenHeightDp / TV_HEIGHT_DP).coerceIn(0.55f, 1f)
    if (factor >= 0.99f) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density * factor, density.fontScale), content = content)
    }
}

private const val TV_HEIGHT_DP = 540f

/**
 * Tippen fokussiert das Element und löst es aus, langes Tippen wirkt wie „OK halten“. Mit [focusFirst]
 * zeigt der erste Tipp nur die Vorschau (wie das Fokussieren mit der Fernbedienung), der zweite löst aus.
 */
fun Modifier.touchClick(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    focusFirst: Boolean = false,
): Modifier = composed {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val hasLong = onLongClick != null
    Modifier
        .focusRequester(requester)
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .pointerInput(enabled, hasLong, focusFirst) {
            if (!enabled) return@pointerInput
            detectTapGestures(
                onLongPress = if (hasLong) { _ ->
                    runCatching { requester.requestFocus() }
                    longClick?.invoke()
                } else null,
                onTap = {
                    val wasFocused = focused
                    runCatching { requester.requestFocus() }
                    if (!focusFirst || wasFocused) click()
                },
            )
        }
}

@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    focusFirst: Boolean = false,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(
    onClick = onClick,
    modifier = modifier.touchClick(onClick, onLongClick, enabled, focusFirst),
    onLongClick = onLongClick,
    enabled = enabled,
    shape = shape,
    colors = colors,
    scale = scale,
    border = border,
    content = content,
)

/** Nicht anklickbare Fläche (Dialoge): unverändert, nur damit beide Formen unter einem Namen erreichbar sind. */
@Composable
fun Surface(
    modifier: Modifier,
    shape: Shape,
    colors: SurfaceColors,
    content: @Composable BoxScope.() -> Unit,
) = androidx.tv.material3.Surface(modifier = modifier, shape = shape, colors = colors, content = content)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.Button(onClick = onClick, modifier = modifier.touchClick(onClick), colors = colors, content = content)

@Composable
fun OutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = OutlinedButtonDefaults.colors(),
    content: @Composable RowScope.() -> Unit,
) = androidx.tv.material3.OutlinedButton(onClick = onClick, modifier = modifier.touchClick(onClick), colors = colors, content = content)

@Composable
fun Card(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    colors: CardColors = CardDefaults.colors(),
    border: CardBorder = CardDefaults.border(),
    content: @Composable ColumnScope.() -> Unit,
) = androidx.tv.material3.Card(
    onClick = onClick,
    modifier = modifier.touchClick(onClick, onLongClick),
    onLongClick = onLongClick,
    colors = colors,
    border = border,
    content = content,
)
