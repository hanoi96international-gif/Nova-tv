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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import de.ayutv.desktop.input.RemoteButton
import de.ayutv.desktop.input.remote
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
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

/** Farben wie in der Fire-TV-App (Standard-Design). */
object Palette {
    val background = Color(0xFF0B0F14)
    val surface = Color(0xFF151B23)
    val raised = Color(0xFF1E2631)
    val accent = Color(0xFF3DA9FC)
    val accentSoft = Color(0xFF235F8E)
    val live = Color(0xFFE5484D)
    val text = Color(0xFFF2F5F8)
    val secondary = Color(0xFF9AA7B4)
}

object Type {
    val display = TextStyle(color = Palette.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    val title = TextStyle(color = Palette.text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    val heading = TextStyle(color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(color = Palette.text, fontSize = 15.sp)
    val small = TextStyle(color = Palette.secondary, fontSize = 13.sp)
}

@Composable
fun Label(text: String, style: TextStyle = Type.body, modifier: Modifier = Modifier, maxLines: Int = 1, color: Color? = null) =
    BasicText(text, modifier, style = if (color != null) style.copy(color = color) else style, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/**
 * Wählbares Element für alle Eingaben: Maus (Klick, Rechtsklick = [onMenu]), Tastatur und Fire-TV-Fernbedienung
 * (Pfeiltasten wechseln den Fokus, OK/Enter wählt, ☰ Menü, Kontextmenü-Taste oder OK lange drücken = [onMenu]).
 * OK zählt erst beim Loslassen und nur, wenn es auf diesem Element auch gedrückt wurde (sonst würde das Loslassen
 * nach einem Seitenwechsel gleich das nächste Element auslösen).
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
                when (e.remote()) {
                    RemoteButton.OK -> {
                        if (e.type == KeyEventType.KeyDown) {
                            if (downAt[0] == 0L) downAt[0] = System.currentTimeMillis()
                        } else if (e.type == KeyEventType.KeyUp) {
                            val pressedAt = downAt[0]
                            downAt[0] = 0L
                            if (pressedAt != 0L) {
                                val held = System.currentTimeMillis() - pressedAt
                                if (held >= LONG_PRESS_MS && onMenu != null) onMenu() else onClick()
                            }
                        }
                        true
                    }
                    RemoteButton.MENU -> {
                        if (e.type == KeyEventType.KeyDown) onMenu?.invoke()
                        onMenu != null
                    }
                    else -> false
                }
            }
            .onFocusChanged { if (!it.isFocused) downAt[0] = 0L }
            .onPointerEvent(PointerEventType.Press) { e ->
                if (onMenu != null && e.buttons.isSecondaryPressed) onMenu()
            }
            .background(background, shape)
            .border(if (focused) 3.dp else 0.dp, if (focused) Palette.accent else Color.Transparent, shape)
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
    val focusManager = LocalFocusManager.current
    // Ausgewähltes Feld: Text von der Handy-Fernbedienung kommt hier an.
    val currentChange by androidx.compose.runtime.rememberUpdatedState(onChange)
    val currentSubmit by androidx.compose.runtime.rememberUpdatedState(onSubmit)
    val target = remember { TextTarget.Entry({ currentChange(it) }, { currentSubmit?.invoke() }) }
    androidx.compose.runtime.LaunchedEffect(focused) { if (focused) TextTarget.current = target else if (TextTarget.current === target) TextTarget.current = null }
    androidx.compose.runtime.DisposableEffect(target) { onDispose { if (TextTarget.current === target) TextTarget.current = null } }
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
                    when {
                        onSubmit != null && e.type == KeyEventType.KeyUp && (e.key == Key.Enter || e.key == Key.NumPadEnter) -> { onSubmit(); true }
                        // ↑/↓ verlassen das Feld (sonst bliebe man mit der Fernbedienung darin hängen).
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        else -> false
                    }
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

@Composable
fun Progress(fraction: Float, modifier: Modifier = Modifier, height: Int = 4) {
    Box(modifier.fillMaxWidth().height(height.dp).background(Palette.raised, RoundedCornerShape((height / 2).dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).background(Palette.accent, RoundedCornerShape((height / 2).dp)))
    }
}

/** Kopfzeile einer Seite: Titel, optional Hinweis rechts (z. B. Tastenhilfe). */
@Composable
fun ScreenHeader(title: String, hint: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label(title, Type.title, Modifier.weight(1f))
        if (hint != null) Label(hint, Type.small, maxLines = 2)
    }
}

/**
 * Kachel für die Startseite (Sender, Film, Serie): Bild, Titel, Zeile darunter, optional Fortschritt.
 * Fokus und Mausrahmen wie bei [Selectable].
 */
@Composable
fun Tile(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    wide: Boolean = true,
    onMenu: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val width = if (wide) 240.dp else 150.dp
    Selectable(onClick, modifier.width(width), onMenu = onMenu, focusRequester = focusRequester, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth()) {
            val picture = rememberImage(image)
            Box(
                Modifier.fillMaxWidth().height(if (wide) 110.dp else 210.dp).background(Palette.raised, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    androidx.compose.foundation.Image(
                        picture, null,
                        Modifier.fillMaxWidth().height(if (wide) 110.dp else 210.dp).padding(if (wide) 18.dp else 0.dp),
                        contentScale = if (wide) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Label(title.take(18), Type.heading, Modifier.padding(8.dp), maxLines = 2)
                }
            }
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                Progress(progress)
            }
            Spacer(Modifier.height(6.dp))
            Label(title, Type.body)
            if (!subtitle.isNullOrEmpty()) Label(subtitle, Type.small)
        }
    }
}

/** Textfeld, das gerade ausgewählt ist (für Text von der Handy-Fernbedienung). Nur auf dem Oberflächen-Thread. */
object TextTarget {
    class Entry(val set: (String) -> Unit, val submit: () -> Unit)
    var current: Entry? = null
}
