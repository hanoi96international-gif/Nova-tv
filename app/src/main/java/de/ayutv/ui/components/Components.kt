package de.ayutv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.ui.theme.AyuColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ThreadLocal.withInitial gibt es erst ab API 26.
private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("HH:mm", Locale.GERMANY)
}
fun formatTime(millis: Long): String = timeFormat.get()!!.format(Date(millis))

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun ProgrammeEntity.progress(now: Long = System.currentTimeMillis()): Float =
    if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = AyuColors.TextPrimary,
        modifier = modifier.padding(start = 4.dp, bottom = 10.dp),
    )
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, color: Color = AyuColors.Progress) {
    Box(
        modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.15f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
                .background(color)
        )
    }
}

@Composable
fun ChannelLogo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(AyuColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            Text(
                text = name.take(3).uppercase(),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = AyuColors.TextSecondary,
            )
        } else {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(6.dp),
            )
        }
    }
}

/** Sender-Kachel für horizontale Reihen (Startseite, Empfehlungen). */
@Composable
fun ChannelCard(
    channel: ChannelEntity,
    now: ProgrammeEntity?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.width(220.dp),
        colors = CardDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
        border = CardDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, AyuColors.Accent))),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ChannelLogo(channel.logo, channel.name, Modifier.size(width = 64.dp, height = 40.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                now?.title?.ifEmpty { null } ?: "Keine Programminfo",
                style = MaterialTheme.typography.bodySmall,
                color = AyuColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            ProgressLine(now?.progress() ?: 0f)
        }
    }
}

/** Poster-Kachel für Filme und Serien. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    subtitle: String? = null,
) {
    Column(modifier.width(150.dp)) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            colors = CardDefaults.colors(containerColor = AyuColors.Surface),
            border = CardDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, AyuColors.Accent))),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (image.isNullOrBlank()) {
                    Text(
                        title,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 4,
                    )
                } else {
                    AsyncImage(
                        model = image,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (progress != null) {
                    ProgressLine(progress, Modifier.align(Alignment.BottomCenter).padding(6.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = AyuColors.TextSecondary, maxLines = 1)
        }
    }
}

/**
 * Textfeld für die Fernbedienung. Im Ruhezustand ist es ein normales Element, an dem man
 * vorbeinavigieren kann, ohne dass die Bildschirmtastatur aufgeht und die Tasten schluckt.
 * Erst OK öffnet die Eingabe; "Fertig" oder Zurück beendet sie.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val editRequester = remember { FocusRequester() }
    val viewRequester = remember { FocusRequester() }
    // Nach dem Tippen den Fokus wieder auf das Feld setzen, sonst wäre er verloren.
    LaunchedEffect(editing) {
        if (editing) wasEditing = true else if (wasEditing) runCatching { viewRequester.requestFocus() }
    }
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AyuColors.TextSecondary)
        Spacer(Modifier.height(4.dp))
        if (editing) {
            var hadFocus by remember { mutableStateOf(false) }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = AyuColors.TextPrimary, fontSize = 18.sp),
                cursorBrush = SolidColor(AyuColors.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onAny = {
                    editing = false
                    onImeAction()
                }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(editRequester)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true else if (hadFocus) editing = false
                    }
                    .clip(shape)
                    .background(AyuColors.SurfaceRaised)
                    .border(2.dp, AyuColors.Accent, shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
            LaunchedEffect(Unit) { runCatching { editRequester.requestFocus() } }
        } else {
            Surface(
                onClick = { editing = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(viewRequester)
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.SurfaceRaised),
                border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, AyuColors.Accent))),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                val shown = when {
                    value.isEmpty() -> ""
                    password -> "•".repeat(value.length.coerceAtMost(16))
                    else -> value
                }
                Text(
                    shown.ifEmpty { "OK drücken zum Eingeben" },
                    color = if (shown.isEmpty()) AyuColors.TextSecondary else AyuColors.TextPrimary,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
fun EmptyHint(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = AyuColors.TextSecondary)
    }
}
