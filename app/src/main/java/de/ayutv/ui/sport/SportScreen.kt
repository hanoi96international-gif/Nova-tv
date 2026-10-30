package de.ayutv.ui.sport

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import de.ayutv.ui.components.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import de.ayutv.data.parse.ProgrammeText
import de.ayutv.data.parse.Teams
import de.ayutv.data.parse.pairing
import de.ayutv.data.parse.sportHeadline
import de.ayutv.data.parse.headline
import de.ayutv.data.repo.ContentRepository.SportEvent
import de.ayutv.player.PlayerActivity
import de.ayutv.ui.LocalContainer
import de.ayutv.ui.components.ChannelLogo
import de.ayutv.ui.components.EmptyHint
import de.ayutv.ui.components.NoticeBar
import de.ayutv.ui.components.formatTime
import de.ayutv.ui.components.rememberMinuteTicker
import de.ayutv.ui.theme.AyuColors
import de.ayutv.ui.theme.AyuFocus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

private enum class SportFilter(val label: String) { ALL("Alles"), FOOTBALL("Fußball"), TEAMS("Meine Vereine") }

/**
 * Sport auf einen Blick: was jetzt live läuft und was heute und morgen kommt, über alle Sender.
 * OK schaltet um, OK halten folgt einem Verein (Erinnerung an alle seine Spiele).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SportScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val minute by rememberMinuteTicker()
    var events by remember { mutableStateOf<List<SportEvent>?>(null) }
    var filter by rememberSaveable { mutableStateOf(SportFilter.ALL) }
    var choosing by remember { mutableStateOf<SportEvent?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val teams = settings?.followedTeams.orEmpty()

    LaunchedEffect(minute) { events = container.content.sportEvents(hours = 48) }
    // Spielpläne und Stände (OpenLigaDB), jede Minute: für die Paarung, wenn das EPG nur „Live: 2. BL“ nennt, und
    // (wenn eingeschaltet) für Stand und Spielminute.
    var scores by remember { mutableStateOf<List<de.ayutv.data.sport.LiveScores.Match>>(emptyList()) }
    LaunchedEffect(minute) {
        scores = runCatching { container.liveScores.current() }.getOrDefault(emptyList())
    }
    /** Das Spiel zur Sendung: über die Paarung, sonst über Stadion, Stadt oder Verein in der Beschreibung. */
    fun matchOf(e: SportEvent): de.ayutv.data.sport.LiveScores.Match? {
        val p = e.programme
        return container.liveScores.find(scores, p.pairing(), p.start, p.title)
            ?: if (p.pairing() == null) container.liveScores.byVenue(scores, p.title, p.subtitle, p.description, p.start, p.stop) else null
    }
    /** Stand nur, wenn Live-Ergebnisse eingeschaltet sind. */
    fun scoreOf(e: SportEvent) = matchOf(e)?.takeIf { settings?.liveScores == true }
    /** Paarung aus dem EPG, sonst aus dem Spielplan. */
    fun pairingOf(e: SportEvent): String? = e.programme.pairing() ?: matchOf(e)?.pairing
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            notice = null
        }
    }

    fun follow(team: String) {
        scope.launch {
            container.settings.update { it.copy(followedTeams = it.followedTeams + team) }
            val added = container.reminders.ensure(container.content.teamReminders(setOf(team)))
            notice = "Du folgst $team. " + if (added.isEmpty()) "Sobald ein Spiel im TV-Guide steht, kommt eine Erinnerung." else "${added.size} Spiel(e) vorgemerkt."
        }
    }
    fun unfollow(team: String) {
        scope.launch {
            container.settings.update { it.copy(followedTeams = it.followedTeams - team) }
            notice = "$team: keine neuen Erinnerungen mehr (gesetzte bleiben im TV-Guide sichtbar)"
        }
    }

    val now = minute
    val shown = events.orEmpty().filter { e ->
        val p = e.programme
        when (filter) {
            SportFilter.ALL -> true
            SportFilter.FOOTBALL -> Regex("(?i)fu(ß|ss)ball|bundesliga|liga|league|pokal|\\bBL\\b").containsMatchIn(p.title + " " + p.category.orEmpty())
            SportFilter.TEAMS -> pairingOf(e)?.let { pairing -> teams.any { Teams.plays(it, pairing, p.title) } } == true
        }
    }
    val live = shown.filter { it.programme.start <= now && it.programme.stop > now }
    val later = shown.filter { it.programme.start > now }
    val tomorrow = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    Box(Modifier.fillMaxSize()) {
        // Solange der Dialog offen ist, steht nur er auf dem Bildschirm (der Fokus kann nicht in die Liste fallen).
        if (choosing == null) Column(Modifier.fillMaxSize().padding(start = 32.dp, top = 24.dp, end = 32.dp)) {
            Text("Sport", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Spiele und Live-Übertragungen aller Sender · OK: einschalten · OK halten: anderer Sender oder Verein folgen",
                color = AyuColors.TextSecondary, fontSize = 14.sp,
            )
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.focusRestorer()) {
                items(SportFilter.entries) { f -> Pill(f.label, selected = f == filter) { filter = f } }
                items(teams.sorted()) { team -> Pill("★ $team  ✕", selected = false) { unfollow(team) } }
            }
            Spacer(Modifier.height(12.dp))
            when {
                events == null -> Unit
                shown.isEmpty() -> EmptyHint(
                    if (filter == SportFilter.TEAMS && teams.isEmpty()) "Noch keinem Verein gefolgt" else "Gerade keine Spiele im TV-Guide",
                    if (filter == SportFilter.TEAMS && teams.isEmpty()) "Auf einem Spiel OK gedrückt halten und einen der beiden Vereine wählen."
                    else "Sport erscheint hier, sobald der TV-Guide ihn kennt (Einstellungen → Anzeige → TV-Guide aus dem Internet).",
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().focusRestorer(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fun section(title: String, list: List<SportEvent>) {
                        if (list.isEmpty()) return
                        item(key = "h-$title") {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = AyuColors.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(list, key = { "${it.programme.id}-${it.channel.key}" }) { e ->
                            EventRow(
                                e, now, scoreOf(e), headlineOf(e.programme, pairingOf(e)),
                                onClick = { PlayerActivity.startLive(context, e.channel.key, e.channel.groupTitle) },
                                onLongClick = { choosing = e },
                            )
                        }
                    }
                    section("● Jetzt live", live)
                    section("Heute", later.filter { it.programme.start < tomorrow })
                    section("Morgen", later.filter { it.programme.start >= tomorrow && it.programme.start < tomorrow + DAY })
                    section("Später", later.filter { it.programme.start >= tomorrow + DAY })
                }
            }
        }
        choosing?.let { e ->
            val pairing = pairingOf(e)
            val sides = pairing?.let { Teams.ofPairing(it) }.orEmpty()
            FollowDialog(
                title = headlineOf(e.programme, pairing),
                teams = sides,
                followed = teams,
                channels = listOf(e.channel) + e.alsoOn,
                onPick = { team ->
                    choosing = null
                    if (team in teams) unfollow(team) else follow(team)
                },
                onWatch = { channel ->
                    choosing = null
                    PlayerActivity.startLive(context, channel.key, channel.groupTitle)
                },
                onDismiss = { choosing = null },
            )
        }
        notice?.let { NoticeBar(it) }
    }
}

private const val DAY = 24 * 3_600_000L

/** Überschrift einer Sport-Sendung; fehlt die Paarung im EPG, kommt sie aus dem Spielplan nach vorn („A – B (Live: 2. BL)“). */
private fun headlineOf(p: de.ayutv.data.db.ProgrammeEntity, pairing: String?): String =
    if (p.pairing() == null && pairing != null) "$pairing (${p.headline()})" else p.sportHeadline()

/**
 * Eine Zeile pro Spiel. Markiert (Fokus) klappt sie auf: ganzer Titel, Beschreibung und alle Sender,
 * auf denen es läuft. Laufende Spiele zeigen, wie weit sie sind.
 */
@Composable
private fun EventRow(e: SportEvent, now: Long, score: de.ayutv.data.sport.LiveScores.Match?, headline: String, onClick: () -> Unit, onLongClick: () -> Unit) {
    val p = e.programme
    val running = p.start <= now && p.stop > now
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = AyuColors.Surface, focusedContainerColor = AyuColors.AccentSoft),
        border = AyuFocus.border,
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(110.dp)) {
                val live = score?.live(now) == true
                val final = score?.finished == true
                Text(
                    when {
                        live -> "● ${score!!.score}"
                        final -> "Ende ${score!!.score}"
                        running -> "● LIVE"
                        else -> formatTime(p.start)
                    },
                    color = if (running || live) AyuColors.Live else AyuColors.TextPrimary,
                    fontSize = if (live || final) 18.sp else 16.sp,
                    fontWeight = if (live || final) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
                Text(
                    if (live) score!!.minute(now) ?: "" else "bis ${formatTime(p.stop)}",
                    color = AyuColors.TextSecondary, fontSize = 12.sp,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(headline, fontSize = 16.sp, maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis)
                if (running) {
                    val done = ((now - p.start).toFloat() / (p.stop - p.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                    Box(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.5f).height(3.dp).background(AyuColors.TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth(done).height(3.dp).background(AyuColors.Live, RoundedCornerShape(2.dp)))
                    }
                }
                if (focused) {
                    score?.takeIf { it.goalCount > 0 }?.lastGoal?.let {
                        Text("⚽ zuletzt: $it", color = AyuColors.Accent, fontSize = 13.sp, maxLines = 1)
                    }
                    ProgrammeText.description(p.description)?.let {
                        Text(it, color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        e.channel.name + if (e.alsoOn.isNotEmpty()) "  ·  auch auf: " + e.alsoOn.joinToString(", ") { it.name } else "",
                        color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (e.alsoOn.isNotEmpty()) Text("OK halten: anderen Sender wählen oder Verein folgen", color = AyuColors.Accent, fontSize = 12.sp)
                } else {
                    Text(
                        e.channel.name + if (e.otherChannels > 0) "  ·  auch auf ${e.otherChannels} weiteren" else "",
                        color = AyuColors.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            ChannelLogo(e.channel.logo, e.channel.name, Modifier.size(width = 64.dp, height = 40.dp))
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AyuColors.AccentSoft else AyuColors.Surface,
            focusedContainerColor = AyuColors.Accent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 15.sp, maxLines = 1)
    }
}

/** Auf welchem Sender schauen, welchem der beiden Vereine folgen? (Oder nicht mehr folgen, wenn schon gefolgt.) */
@Composable
private fun FollowDialog(
    title: String,
    teams: List<String>,
    followed: Set<String>,
    channels: List<de.ayutv.data.db.ChannelEntity>,
    onPick: (String) -> Unit,
    onWatch: (de.ayutv.data.db.ChannelEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(5) {
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(16)
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.width(560.dp),
            shape = RoundedCornerShape(14.dp),
            colors = SurfaceDefaults.colors(containerColor = AyuColors.SurfaceRaised),
        ) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (channels.size > 1) {
                    Text("Schauen auf", color = AyuColors.TextSecondary, fontSize = 13.sp)
                    channels.forEach { channel ->
                        Pill("▶ ${channel.name}", selected = false, onClick = { onWatch(channel) })
                    }
                }
                Text("Verein folgen", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                Text(
                    "Vor jedem Spiel des Vereins kommt eine Erinnerung, auf welchem Sender es läuft.",
                    color = AyuColors.TextSecondary, fontSize = 13.sp,
                )
                if (teams.isEmpty()) Text("Hier ist keine Paarung erkennbar.", fontSize = 15.sp)
                teams.forEachIndexed { index, team ->
                    Pill(
                        if (team in followed) "★ $team – nicht mehr folgen" else "☆ $team folgen",
                        selected = false,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        onClick = { onPick(team) },
                    )
                }
                Pill("Abbrechen", selected = false, modifier = if (teams.isEmpty()) Modifier.focusRequester(first) else Modifier, onClick = onDismiss)
            }
        }
    }
}
