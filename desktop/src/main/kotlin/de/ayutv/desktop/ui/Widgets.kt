package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ayutv.desktop.data.Theme

/** Farben eines Designs (wie in der Fire-TV-App). */
class Colors(
    val background: Color,
    val surface: Color,
    val raised: Color,
    val accent: Color,
    val accentSoft: Color,
    val live: Color,
    val text: Color,
    val secondary: Color,
    /** Hintergrund der Menüs (Verlauf). */
    val backdrop: Brush,
)

/** Farben des gewählten Designs ([use] schaltet um, alles zeichnet sich dann neu). */
object Palette {
    private val STANDARD = Colors(
        background = Color(0xFF0B0F14), surface = Color(0xFF151B23), raised = Color(0xFF1E2631),
        accent = Color(0xFF3DA9FC), accentSoft = Color(0xFF235F8E), live = Color(0xFFE5484D),
        text = Color(0xFFF2F5F8), secondary = Color(0xFF9AA7B4),
        backdrop = Brush.verticalGradient(listOf(Color(0xFF101823), Color(0xFF0B0F14))),
    )
    /** Nordlicht: Nachthimmel in Tiefblau bis Violett, Polarlicht in Türkis. */
    private val NORDLICHT = Colors(
        background = Color(0xFF070912), surface = Color(0xFF121628), raised = Color(0xFF1C2244),
        accent = Color(0xFF5EEAD4), accentSoft = Color(0xFF4F46E5), live = Color(0xFFFF4D6D),
        text = Color(0xFFF4F6FF), secondary = Color(0xFFA3AAC8),
        backdrop = Brush.linearGradient(listOf(Color(0xFF0E1330), Color(0xFF15124A), Color(0xFF0B2A33), Color(0xFF05060C))),
    )
    /** Hannover 96: Stadionabend, tiefrot nach schwarz. */
    private val HANNOVER = Colors(
        background = Color(0xFF0C0909), surface = Color(0xFF1A1213), raised = Color(0xFF2A1A1C),
        accent = Color(0xFFFF3B3F), accentSoft = Color(0xFFB0141B), live = Color(0xFFFF5A3C),
        text = Color(0xFFFFF7F5), secondary = Color(0xFFBFAEAD),
        backdrop = Brush.verticalGradient(listOf(Color(0xFF45090E), Color(0xFF1A0709), Color(0xFF0C0909))),
    )

    private var current by mutableStateOf(STANDARD)

    fun use(theme: Theme) {
        current = when (theme) {
            Theme.STANDARD -> STANDARD
            Theme.NORDLICHT -> NORDLICHT
            Theme.HANNOVER -> HANNOVER
        }
    }

    val background get() = current.background
    val surface get() = current.surface
    val raised get() = current.raised
    val accent get() = current.accent
    val accentSoft get() = current.accentSoft
    val live get() = current.live
    val text get() = current.text
    val secondary get() = current.secondary
    val backdrop get() = current.backdrop
}

object Type {
    val title get() = TextStyle(color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val heading get() = TextStyle(color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val body get() = TextStyle(color = Palette.text, fontSize = 15.sp)
    val small get() = TextStyle(color = Palette.secondary, fontSize = 13.sp)
    val value get() = TextStyle(color = Palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun Label(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, maxLines: Int = 1, color: Color? = null) =
    BasicText(text, modifier, style = if (color != null) style.copy(color = color) else style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * Wählbares Element für alle Eingaben: Maus (Klick, Rechtsklick = [onMenu]), Tastatur und Bluetooth-Fernbedienung
 * (Pfeiltasten wechseln den Fokus, Enter = OK, Menü-/Kontexttaste oder lange Enter = [onMenu]).
 */
@Composable
fun Selectable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onMenu: (() -> Unit)? = null,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    shape: RoundedCornerShape = RoundedCornerShape(8.dp),
    content: @Composable RowScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        focused -> Palette.accentSoft
        selected -> Palette.raised
        hovered -> Palette.surface
        else -> Color.Transparent
    }
    val downAt = remember { longArrayOf(0L) }
    Row(
        modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        if (e.type == KeyEventType.KeyDown) {
                            if (downAt[0] == 0L) downAt[0] = System.currentTimeMillis()
                        } else if (e.type == KeyEventType.KeyUp) {
                            val held = System.currentTimeMillis() - downAt[0]
                            downAt[0] = 0L
                            if (held >= LONG_PRESS_MS && onMenu != null) onMenu() else onClick()
                        }
                        true
                    }
                    Key.Menu -> {
                        if (e.type == KeyEventType.KeyUp) onMenu?.invoke()
                        onMenu != null
                    }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (onMenu != null && e.buttons.isSecondaryPressed) onMenu()
            }
            .background(background, shape)
            .border(if (focused) 2.dp else 0.dp, if (focused) Palette.accent else Color.Transparent, shape)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

private const val LONG_PRESS_MS = 600L

@Composable
fun TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, focusRequester: FocusRequester? = null) =
    Selectable(onClick, modifier.background(if (primary) Palette.accentSoft.copy(alpha = 0.5f) else Palette.raised, RoundedCornerShape(8.dp)), focusRequester = focusRequester) {
        Label(text, Type.body)
    }

/** Zeile mit Titel, Zusatz und optional Wert rechts. */
@Composable
fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    selected: Boolean = false,
    onMenu: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
) = Selectable(onClick, modifier.fillMaxWidth(), onMenu, selected, focusRequester) {
    if (leading != null) {
        leading()
        Spacer(Modifier.width(12.dp))
    }
    Column(Modifier.weight(1f)) {
        Label(title, Type.body)
        if (!subtitle.isNullOrEmpty()) Label(subtitle, Type.small)
    }
    if (trailing != null) {
        Spacer(Modifier.width(8.dp))
        Label(trailing, Type.small)
    }
}

/** Eingabefeld (mit Maus und Tastatur). */
@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSubmit: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Label(label, Type.small)
        Spacer(Modifier.height(2.dp))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = Type.body,
            cursorBrush = SolidColor(Palette.accent),
            interactionSource = interaction,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth()
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .onPreviewKeyEvent { e ->
                    if (onSubmit != null && e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter)) {
                        onSubmit(); true
                    } else false
                }
                .background(Palette.raised, RoundedCornerShape(6.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) Palette.accent else Palette.surface, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Schaltet beim Erscheinen den Fokus auf [requester]. */
@Composable
fun FocusOnShow(requester: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { runCatching { requester.requestFocus() } }
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Box(modifier.background(Palette.surface, RoundedCornerShape(12.dp)).padding(12.dp)) { content() }
