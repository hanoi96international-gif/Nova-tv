package de.ayutv.desktop.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.ayutv.desktop.data.Choice

/** Einstellung wie auf dem Fire TV: Titel, Erklärung darunter, rechts der aktuelle Wert. */
@Composable
fun SettingRow(
    title: String,
    value: String = "",
    description: String = "",
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Selectable(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).background(Palette.surface, RoundedCornerShape(10.dp)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp)) {
            Label(title, Type.body, color = if (enabled) null else Palette.secondary)
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Label(description, Type.small, maxLines = 6)
            }
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(16.dp))
            Label(value, Type.value, maxLines = 2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
        Spacer(Modifier.width(6.dp))
    }
}

/** Ein/Aus mit Schalter rechts. */
@Composable
fun ToggleRow(title: String, checked: Boolean, description: String = "", enabled: Boolean = true, onToggle: (Boolean) -> Unit) =
    SettingRow(title, description = description, enabled = enabled, trailing = { Switch(checked && enabled) }) { onToggle(!checked) }

@Composable
fun Switch(checked: Boolean) {
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp)
    Box(
        Modifier.width(44.dp).height(24.dp)
            .background(if (checked) Palette.accent else Palette.raised, RoundedCornerShape(12.dp))
            .padding(2.dp),
    ) {
        Box(Modifier.offset(x = knob).size(20.dp).background(if (checked) Color.White else Palette.secondary, CircleShape))
    }
}

/** Auswahl aus festen Werten: Klick öffnet die Liste (✓ = aktuell). */
@Composable
fun <T : Choice> ChoiceRow(
    app: AppState,
    title: String,
    current: T,
    options: List<T>,
    description: String = "",
    show: ((@Composable () -> Unit)?) -> Unit,
    onPick: (T) -> Unit,
) = SettingRow(title, current.label, description) {
    show {
        MenuDialog(
            app, title,
            options.map { option -> MenuItem((if (option == current) "✓  " else "     ") + option.label) { onPick(option) } },
            onDismiss = { show(null) },
        )
    }
}

/** Überschrift einer Gruppe von Einstellungen. */
@Composable
fun SettingGroup(title: String) {
    Label(title.uppercase(), Type.small.copy(color = Palette.accent), Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp))
}

/** Hinweis-Kasten (Warnung oder Erklärung). */
@Composable
fun Note(text: String, warning: Boolean = false) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .background(if (warning) Palette.live.copy(alpha = 0.18f) else Palette.raised, RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) { Label(text, Type.small.copy(color = if (warning) Palette.text else Palette.secondary), maxLines = 8) }
}

/** Bereich in der linken Spalte der Einstellungen. */
@Composable
fun PageButton(icon: String, title: String, selected: Boolean, onClick: () -> Unit) {
    Selectable(onClick, Modifier.fillMaxWidth().padding(vertical = 2.dp), selected = selected, shape = RoundedCornerShape(10.dp)) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) { Label(icon, Type.body) }
        Spacer(Modifier.width(8.dp))
        Label(title, if (selected) Type.body.copy(color = Palette.accent) else Type.body)
    }
}
