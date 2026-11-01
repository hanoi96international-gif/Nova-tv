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
        events = withContext(Dispatchers.Default) { SportEvents.of(store.listedChannels(), guide, user.favorites.toSet(), now = now) }
    }

    var dialog by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    // ☰ / Rechtsklick auf ein Spiel: erinnern, Verein folgen (wie auf dem Fire TV: OK halten = Verein folgen).
    fun eventMenu(e: SportEvent) {
        val items = buildList {
            if (e.programme.start > System.currentTimeMillis()) {
                val set = store.hasReminder(e.channel, e.programme.start)
                add(MenuItem(if (set) "⏰ Erinnerung entfernen" else "⏰ Erinnern") {
                    app.toast(if (store.toggleReminder(e.channel, e.programme)) "Erinnerung um ${clock(e.programme.start)}" else "Erinnerung entfernt")
                })
            }
            e.pairing?.let { de.ayutv.data.parse.Teams.ofPairing(it) }.orEmpty().forEach { team ->
                val follows = team in user.followedTeams
                add(MenuItem(if (follows) "Nicht mehr folgen: $team" else "Verein folgen: $team") {
                    app.toast(if (store.toggleTeam(team)) "Du folgst $team: vor jedem Spiel kommt eine Erinnerung" else "$team entfolgt")
                })
            }
            e.alsoOn.forEach { c -> add(MenuItem("▶ Auf ${c.name}") { app.play(Playback.Live(c, listOf(e.channel) + e.alsoOn)) }) }
        }
        if (items.isNotEmpty()) dialog = { MenuDialog(app, e.headline, items, onDismiss = { dialog = null }) }
    }

    val list = events
    // Spiel zu jeder Sendung (Paarung, Stadion, sonst Ausschluss), einmal für die ganze Liste.
    val assigned = androidx.compose.runtime.remember(list, matches) { assign(scores, matches, list.orEmpty()) }
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
                    section("Jetzt live", running, app, assigned, now, ::eventMenu)
                    section("Heute", todayList, app, assigned, now, ::eventMenu)
                    section("Morgen", tomorrow, app, assigned, now, ::eventMenu)
                }
            }
        }
    }
    dialog?.invoke()
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(title: String, events: List<SportEvent>, app: AppState, assigned: Map<SportEvent, LiveScores.Match?>, now: Long, onMenu: (SportEvent) -> Unit) {
    if (events.isEmpty()) return
    item(key = "h$title") { Label(title, Type.heading, Modifier.padding(top = 14.dp, bottom = 4.dp)) }
    items(events, key = { "$title|${it.channel.key}|${it.programme.start}" }) { e ->
        val p = e.programme
        val match = assigned[e]
        val score = match?.let { m ->
            when {
                m.live(now) -> "  ${m.score} (${m.minute(now)})"
                m.finished -> "  ${m.score} Ende"
                else -> ""
            }
        }.orEmpty()
        val also = if (e.alsoOn.isEmpty()) "" else " · auch auf ${e.alsoOn.take(3).joinToString { it.name }}${if (e.alsoOn.size > 3) " …" else ""}"
        ListRow(
            // „Live: 2. BL“ ohne Paarung im TV-Guide: das Spiel aus dem Spielplan dazuschreiben.
            title = (if (p.start <= now) "🔴 " else "") + (if (e.pairing == null && match != null) "${match.pairing} (${e.headline})" else e.headline) + score,
            subtitle = "${clock(p.start)} – ${clock(p.stop)} · ${e.channel.name}$also",
            onClick = {
                val best = de.ayutv.desktop.data.SportEvents.bestFor(e.channel, app.store.channels())
                app.play(Playback.Live(best, listOf(best) + e.alsoOn))
            },
            onMenu = { onMenu(e) },
            leading = { Logo(e.channel.logo) },
        )
    }
}

/**
 * Spiel zu jeder Sendung: über die Paarung, sonst über Stadion/Stadt in der Beschreibung (Sky „Live: 2. BL“), sonst per
 * Ausschluss (das einzige übrige Spiel der Liga zu dieser Zeit, wenn die anderen schon anderen Sendern gehören).
 */
private fun assign(scores: LiveScores, matches: List<LiveScores.Match>, events: List<SportEvent>): Map<SportEvent, LiveScores.Match?> {
    if (matches.isEmpty()) return emptyMap()
    val direct = events.map { e ->
        val p = e.programme
        scores.find(matches, e.pairing, p.start, p.title)
            ?: if (e.pairing == null) scores.byVenue(matches, p.title, p.subtitle, p.description, p.start, p.stop) else null
    }
    val open = events.indices.filter { direct[it] == null && events[it].pairing == null }
    val found = scores.byElimination(
        matches,
        open.map { i -> events[i].programme.let { LiveScores.Open(it.title, it.subtitle, it.start, it.stop) } },
        direct.filterNotNull().toSet(),
    )
    val result = direct.toMutableList()
    for ((k, m) in found) result[open[k]] = m
    return events.indices.associate { events[it] to result[it] }
}
