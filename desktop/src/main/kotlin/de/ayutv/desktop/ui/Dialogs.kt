package de.ayutv.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Eintrag in einem Auswahlmenü. */
data class MenuItem(val label: String, val action: () -> Unit)

/** Abgedunkelte Fläche mit Kasten in der Mitte; Klick daneben oder Esc schließt. */
@Composable
fun Overlay(app: AppState, onDismiss: () -> Unit, width: Int = 420, content: @Composable () -> Unit) {
    BackHandler(app) { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(width.dp).heightIn(max = 620.dp)
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .background(Palette.surface, RoundedCornerShape(14.dp)).padding(16.dp),
        ) { content() }
    }
}

/**
 * Auswahlmenü. OK wird erst angenommen, wenn die Taste losgelassen wurde, nachdem das Menü offen ist (sonst wählt
 * das Loslassen des langen Drückens, das das Menü geöffnet hat, gleich den ersten Eintrag).
 */
@Composable
fun MenuDialog(app: AppState, title: String, items: List<MenuItem>, onDismiss: () -> Unit) {
    Overlay(app, onDismiss) {
        Column {
            Label(title, Type.heading, Modifier.padding(start = 12.dp, bottom = 10.dp), maxLines = 2)
            val first = remember { FocusRequester() }
            val opened = remember { System.currentTimeMillis() }
            LazyColumn {
                itemsIndexed(items) { index, item ->
                    ListRow(
                        item.label,
                        onClick = {
                            if (System.currentTimeMillis() - opened < 250) return@ListRow
                            onDismiss()
                            item.action()
                        },
                        focusRequester = if (index == 0) first else null,
                    )
                }
            }
            FocusOnShow(first)
        }
    }
}

/** Namen eingeben (neue Gruppe, Umbenennen). */
@Composable
fun NameDialog(app: AppState, title: String, initial: String = "", onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    Overlay(app, onDismiss) {
        Column {
            Label(title, Type.heading)
            Spacer(Modifier.height(8.dp))
            val submit = {
                if (name.isNotBlank()) {
                    onDismiss()
                    onDone(name.trim())
                }
            }
            Field("Name", name, { name = it }, focusRequester = field, onSubmit = submit)
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton("Speichern", submit, primary = true)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
    FocusOnShow(field)
}

/** Ja/Nein-Frage. */
@Composable
fun ConfirmDialog(app: AppState, question: String, yes: String = "Ja", onYes: () -> Unit, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Overlay(app, onDismiss) {
        Column {
            Label(question, Type.body, maxLines = 4)
            Spacer(Modifier.height(14.dp))
            Row {
                TextButton(yes, { onDismiss(); onYes() }, primary = true, focusRequester = first)
                Spacer(Modifier.width(8.dp))
                TextButton("Abbrechen", onDismiss)
            }
        }
    }
    FocusOnShow(first)
}
