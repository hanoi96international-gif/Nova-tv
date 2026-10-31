package de.ayutv.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.ayutv.data.sport.LiveScores
import de.ayutv.desktop.data.SportEvent
import de.ayutv.desktop.data.SportEvents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Sport aus dem TV-Guide: was jetzt läuft (mit Spielstand), heute und morgen. Klick startet den besten Sender. */
@Composable
fun SportScreen(app: AppState) {
    val store = app.store
    val user by store.user.collectAsState()
    val content by store.content.collectAsState()
    val guide by store.guide.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var matches by remember { mutableStateOf<List<LiveScores.Match>>(emptyList()) }
    val scores = remember { LiveScores(app.sync.http) }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            runCatching { matches = scores.current(now) }
            delay(60_000)
        }
    }
    var events by remember { mutableStateOf<List<SportEvent>?>(null) }
    LaunchedEffect(content, guide, user.favorites, now / (5 * 60_000)) {
        events = withContext(Dispatchers.Default) { SportEvents.of(store.allVisible(), guide, user.favorites.toSet(), now = now) }
    }

    val list = events
    when {
        guide.isEmpty() -> Empty("Noch kein TV-Guide. Unter „Einstellungen“ den TV-Guide laden.")
        list == null -> Empty("Sport wird gesucht …")
        list.isEmpty() -> Empty("Im TV-Guide steht in den nächsten 36 Stunden kein Live-Sport.")
        else -> {
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val running = list.filter { it.programme.start <= now }
            val later = list.filter { it.programme.start > now }
            val (todayList, tomorrow) = later.partition { Instant.ofEpochMilli(it.programme.start).atZone(zone).toLocalDate() == today }
            Column(Modifier.fillMaxSize()) {
                Label("Sport", Type.title, Modifier.padding(bottom = 8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    section("Jetzt live", running, app, scores, matches, now)
                    section("Heute", todayList, app, scores, matches, now)
                    section("Morgen", tomorrow, app, scores, matches, now)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, events: List<SportEvent>, app: AppState, scores: LiveScores, matches: List<LiveScores.Match>, now: Long) {
    if (events.isEmpty()) return
    item(key = "h$title") { Label(title, Type.heading, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
    items(events, key = { "$title|${it.channel.key}|${it.programme.start}" }) { e ->
        val p = e.programme
        val match = scoreOf(scores, matches, e)
        val score = match?.let { m ->
            when {
                m.live(now) -> "  ${m.score} (${m.minute(now)})"
                m.finished -> "  ${m.score} Ende"
                else -> ""
            }
        }.orEmpty()
        val also = if (e.alsoOn.isEmpty()) "" else " · auch auf ${e.alsoOn.take(3).joinToString { it.name }}${if (e.alsoOn.size > 3) " …" else ""}"
        ListRow(
            title = (if (p.start <= now) "🔴 " else "") + e.headline + score,
            subtitle = "${clock(p.start)} – ${clock(p.stop)} · ${e.channel.name}$also",
            onClick = { app.play(Playback.Live(e.channel, listOf(e.channel) + e.alsoOn)) },
            leading = { Logo(e.channel.logo) },
        )
    }
}

/** Spielstand zur Sendung: über die Paarung, sonst über Stadion/Stadt in der Beschreibung (Sky „Live: 2. BL“). */
private fun scoreOf(scores: LiveScores, matches: List<LiveScores.Match>, e: SportEvent): LiveScores.Match? {
    if (matches.isEmpty()) return null
    val p = e.programme
    return scores.find(matches, e.pairing, p.start, p.title) ?: scores.byVenue(matches, p.title, p.subtitle, p.description, p.start, p.stop)
}
