package de.ayutv.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import de.ayutv.data.parse.XtreamEpisode
import de.ayutv.desktop.data.BackupImport
import de.ayutv.desktop.data.Channel
import de.ayutv.desktop.data.GroupRef
import de.ayutv.desktop.data.Network
import de.ayutv.desktop.data.Series
import de.ayutv.desktop.data.Store
import de.ayutv.desktop.data.Sync
import de.ayutv.desktop.data.TimingMark
import de.ayutv.desktop.input.RemoteInput
import de.ayutv.desktop.player.BufferProfile
import de.ayutv.desktop.player.SubtitleFiles
import de.ayutv.desktop.player.Subtitles
import de.ayutv.desktop.player.VideoPlayer
import de.ayutv.desktop.remote.PhoneRemote
import de.ayutv.player.SpeechSync
import de.ayutv.player.SubtitleTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val label: String, val icon: String) {
    HOME("Start", "⌂"),
    LIVE("Live TV", "📺"),
    GUIDE("TV-Guide", "🗓"),
    SPORT("Sport", "⚽"),
    MOVIES("Filme", "🎬"),
    SERIES("Serien", "🎞"),
    SEARCH("Suche", "🔍"),
    SETTINGS("Einstellungen", "⚙"),
}

/** Was gerade läuft. */
sealed interface Playback {
    val title: String

    /** Live-Sender; [list] ist die Liste, in der gezappt wird. */
    data class Live(val channel: Channel, val list: List<Channel>) : Playback {
        override val title get() = channel.name
    }

    /** Film oder Folge. [key] merkt den Wiedergabestand. */
    data class Vod(
        val key: String,
        override val title: String,
        val url: String,
        val startMs: Long = 0,
        val series: Series? = null,
        val episodes: List<Pair<XtreamEpisode, String>> = emptyList(),
        val episodeId: String? = null,
        /** Aus dem Archiv (Catch-up): kein Wiedergabestand, „Live“ springt zum Sender zurück. */
        val archive: Channel? = null,
    ) : Playback
}

/** Zustand der ganzen App (eine Instanz je Fenster). */
class AppState(val store: Store, val sync: Sync, val scope: CoroutineScope, val input: RemoteInput = RemoteInput()) {
    val player = VideoPlayer()
    val backup = BackupImport(store)
    val subtitles = Subtitles(sync.http, java.io.File(store.dir, "subtitles"))
    val remote = PhoneRemote(store, input) { key -> store.channel(key)?.let { play(Playback.Live(it, store.allVisible())) } }
    var remoteAddress by mutableStateOf<String?>(null); private set
    val updater = de.ayutv.desktop.update.Updater(sync.http)
    val deviceSync = de.ayutv.desktop.data.DeviceSync(store, sync.http)
    val recorder = de.ayutv.desktop.player.Recorder()
    val channelCheck = de.ayutv.desktop.player.ChannelCheck(store)
    /** Zuletzt etwas abgespielt (der Sender-Check wartet danach 5 Minuten). */
    @Volatile private var lastPlaybackAt = System.currentTimeMillis()
    /** Ergebnis des letzten Abgleichs mit dem Fire TV (für die Einstellungen). */
    var deviceSyncStatus by mutableStateOf<String?>(null); private set

    /** Handy-Fernbedienung ein-/ausschalten (Windows fragt beim ersten Mal nach der Firewall-Freigabe). */
    fun setPhoneRemote(on: Boolean) {
        runCatching { if (on) remote.start() else remote.stop() }
            .onFailure { toast("Fernbedienung lässt sich nicht starten: ${it.message}") }
        store.updateUser { it.copy(phoneRemote = on && remote.running) }
        remoteAddress = remote.address()
    }

    var screen by mutableStateOf(if (store.playlists.value.isEmpty()) Screen.SETTINGS else Screen.HOME)
    var playing by mutableStateOf<Playback?>(null); private set
    /** Geöffnete Serie (Folgenansicht), auch aus der Suche heraus. */
    var openSeries by mutableStateOf<Series?>(null)
    var message by mutableStateOf<String?>(null); private set
    private var messageJob: Job? = null
    /** Live TV: zuletzt gewählte Gruppe und Sender (nach dem Schauen geht es dort weiter). */
    var liveGroup: GroupRef? = null
    var liveFocus: String? = null
    /** Erinnerung, die gerade fällig ist (Dialog „Umschalten?“). */
    var dueReminder by mutableStateOf<de.ayutv.desktop.data.Reminder?>(null)
    private val shownReminders = HashSet<String>()
    /** Profilauswahl zeigen (beim Start, wenn es mehrere Profile gibt, und aus der Navigation). */
    var choosingProfile by mutableStateOf(store.profiles.value.size > 1)

    /** Zu einem anderen Profil wechseln (Wiedergabe endet, Startseite). false = falsche PIN. */
    fun switchProfile(id: Long, pin: String = ""): Boolean {
        if (id == store.profile.value) { choosingProfile = false; return true }
        if (playing != null) stopPlayback()
        if (!store.switchProfile(id, pin)) return false
        liveGroup = null
        liveFocus = null
        openSeries = null
        applyBuffer()
        applyProxy()
        screen = Screen.HOME
        choosingProfile = false
        toast("Profil: ${store.currentProfile().name}")
        return true
    }

    /** Länderauswahl anbieten (nach dem ersten Laden). */
    var askCountries by mutableStateOf(false)

    /** Zurück-Aktionen, die neueste zuerst (Dialog, Unterseite, Player). */
    private val backStack = mutableStateListOf<() -> Boolean>()

    init {
        if (store.user.value.phoneRemote) setPhoneRemote(true)
        applyBuffer()
        // Updates: kurz nach dem Start bei GitHub nachsehen, danach alle 6 Stunden.
        scope.launch {
            delay(20_000)
            while (true) {
                updater.check()?.let { toast("Update auf ${it.version} verfügbar: Einstellungen → Update") }
                delay(6 * 3_600_000L)
            }
        }
        // Sender-Check in der Ruhezeit (nichts läuft seit 5 Minuten), höchstens alle 12 Stunden.
        scope.launch {
            delay(5 * 60_000L)
            while (true) {
                fun idle() = playing == null && System.currentTimeMillis() - lastPlaybackAt > 5 * 60_000L &&
                    store.user.value.recordings.none { it.state == de.ayutv.desktop.data.Recording.RUNNING }
                if (idle() && System.currentTimeMillis() - channelCheck.lastRun > de.ayutv.desktop.player.ChannelCheck.EVERY_MS) {
                    runCatching { channelCheck.run(::idle) }.onFailure { de.ayutv.desktop.Log.warn("Sender-Check", it) }
                }
                delay(5 * 60_000L)
            }
        }
        // Abgleich mit dem Fire TV alle 30 Minuten (wenn eingeschaltet).
        scope.launch {
            delay(30_000)
            while (true) {
                val u = store.user.value
                if (u.autoSync && u.syncAddress.isNotBlank() && u.syncPin.isNotBlank()) syncDevices(quiet = true)
                delay(30 * 60_000L)
            }
        }
        // Erinnerungen: kurz vor Beginn fragen; Spiele gefolgter Vereine alle 30 Minuten einplanen.
        scope.launch {
            var lastTeams = 0L
            while (true) {
                val now = System.currentTimeMillis()
                if (now - lastTeams > 30 * 60_000L && store.user.value.followedTeams.isNotEmpty()) {
                    lastTeams = now
                    runCatching { planTeamReminders(now) }.onFailure { de.ayutv.desktop.Log.warn("Vereins-Erinnerungen", it) }
                }
                val due = store.user.value.reminders.firstOrNull { r ->
                    r.start - REMINDER_AHEAD_MS <= now && now < r.start + 10 * 60_000L && "${r.channelKey}@${r.start}" !in shownReminders
                }
                if (due != null && dueReminder == null) {
                    shownReminders += "${due.channelKey}@${due.start}"
                    dueReminder = due
                    runCatching { input.window?.toFront() }
                }
                runCatching { withContext(Dispatchers.IO) { runRecordings(now) } }.onFailure { de.ayutv.desktop.Log.warn("Aufnahmen", it) }
                // Vorbei: aufräumen.
                val old = store.user.value.reminders.filter { now > it.start + 30 * 60_000L }
                if (old.isNotEmpty()) store.updateUser { it.copy(reminders = it.reminders - old.toSet()) }
                delay(15_000)
            }
        }
        // Wie auf dem Fire TV: Playlists alle 12 Stunden, TV-Guide alle 8 Stunden nachladen, auch wenn DnA-TV läuft.
        scope.launch {
            delay(1_500)
            while (true) {
                val first = store.playlists.value.any { it.enabled && it.lastSync == 0L }
                val problems = runCatching { sync.refreshDue() }.getOrElse { listOf(it.message ?: "Fehler") }
                applyProxy()
                if (problems.isNotEmpty()) {
                    problems.forEach { de.ayutv.desktop.Log.warn("Aktualisieren: $it") }
                    toast("Aktualisieren: ${problems.first()}")
                }
                if (first && !store.user.value.countriesAsked && store.countries().size > 1) askCountries = true
                delay(REFRESH_CHECK_MS)
            }
        }
    }

    /** Proxy geändert (Einstellungen, Sicherung) oder neue Sender geladen: Wege neu festlegen. */
    fun applyProxy() = Network.apply(store)

    /** Pufferprofil aus den Einstellungen an den Player geben. */
    fun applyBuffer() {
        player.networkCacheMs = BufferProfile.of(store.user.value.bufferProfile).cacheMs
        player.hdrMode = store.user.value.hdrMode
        player.videoLayout = store.user.value.videoLayout
    }

    fun toast(text: String) {
        message = text
        messageJob?.cancel()
        messageJob = scope.launch {
            delay(4_000)
            message = null
        }
    }

    // ---------------------------------------------------------------- Wiedergabe

    /** Vorher geschauter Sender (◀ bzw. „Letzter Sender“ springt zurück). */
    var previousChannel by mutableStateOf<Channel?>(null); private set
    /** Was der Player gerade versucht (Neu verbinden, Ausweichen); null = nichts. */
    var playerStatus by mutableStateOf<String?>(null); private set
    /** Fassungen, die für den laufenden Sender schon versucht wurden. */
    private var triedVariants = mutableSetOf<String>()
    private var watchdog: Job? = null

    fun play(playback: Playback) {
        stopAligning()
        val before = (playing as? Playback.Live)?.channel
        // Ton nur abgreifen, wenn Untertitel ausgerichtet werden sollen (sonst gibt VLC den Ton selbst aus).
        player.tapAudio = false
        playerStatus = null
        liveDelayMs = 0
        pausedAt = 0
        if (playback is Playback.Live) {
            if (before != null && before.key != playback.channel.key) previousChannel = before
            triedVariants = mutableSetOf(playback.channel.key)
        }
        lastPlaybackAt = System.currentTimeMillis()
        (playing as? Playback.Live)?.let { previous ->
            player.streamInfo()?.let { info -> if (info.height > 0) de.ayutv.data.parse.ChannelQuality.recordPlayback(previous.channel.key, info.height, info.startupMs) }
        }
        playing = playback
        start(playback)
        watch(playback)
    }

    private fun start(playback: Playback, atMs: Long? = null) = when (playback) {
        is Playback.Live -> {
            store.rememberWatched(playback.channel.key)
            player.play(playback.channel.url, Sync.USER_AGENT, 0, *Network.vlcOptions(playback.channel.url).toTypedArray())
        }
        is Playback.Vod -> player.play(playback.url, Sync.USER_AGENT, atMs ?: playback.startMs, *Network.vlcOptions(playback.url).toTypedArray())
    }

    /**
     * Watchdog wie auf dem Fire TV: Fehler oder stehendes Bild (Dauer-Puffern) → neu verbinden, bis zu drei Mal mit
     * wachsender Pause; danach bei Live-TV auf die nächste Fassung desselben Senders ausweichen („RTL HD“ → „RTL 4K“).
     */
    private fun watch(playback: Playback) {
        watchdog?.cancel()
        watchdog = scope.launch {
            var attempts = 0
            var healthySince = System.currentTimeMillis()
            while (playing === playback) {
                delay(1_000)
                if (playing !== playback) break
                val now = System.currentTimeMillis()
                // Radiosender haben kein Bild: die zählen nicht als eingefroren, solange der Ton läuft.
                val stalled = !player.paused && now - player.lastFrameAt > STALL_MS && !(player.playing && !player.hasVideo)
                val failed = player.error != null
                if (!failed && !stalled) {
                    if (player.playing && now - healthySince > HEALTHY_MS) attempts = 0
                    if (player.playing && playerStatus != null && now - player.lastFrameAt < 1_000) playerStatus = null
                    continue
                }
                healthySince = now
                de.ayutv.desktop.Log.warn("Watchdog: ${playback.title}: ${player.error ?: "Bild steht"} (Versuch ${attempts + 1})")
                if (attempts < MAX_RETRIES) {
                    attempts++
                    playerStatus = (if (failed) "Stream unterbrochen" else "Bild steht") + " – verbinde neu ($attempts/$MAX_RETRIES) …"
                    delay(500L shl (attempts - 1))
                    if (playing !== playback) break
                    start(playback, atMs = (playback as? Playback.Vod)?.let { player.positionMs })
                    continue
                }
                val live = playback as? Playback.Live
                val next = live?.let { l -> store.alternatives(l.channel).firstOrNull { it.key !in triedVariants } }
                live?.let { de.ayutv.data.parse.ChannelQuality.recordFailure(it.channel.key) }
                if (live != null && next != null) {
                    de.ayutv.desktop.Log.info("Ausweichen: ${live.channel.name} → ${next.name}")
                    triedVariants.add(next.key)
                    playerStatus = "Weiche aus auf „${next.name}“ …"
                    // In der Liste bleibt der ursprüngliche Sender stehen (Zappen geht von dort weiter).
                    val switched = Playback.Live(next, live.list.map { if (it.key == live.channel.key) next else it })
                    playing = switched
                    start(switched)
                    watch(switched)
                    return@launch
                }
                playerStatus = null
                if (player.error == null) player.stopWithError("Der Stream liefert kein Bild. Später noch einmal versuchen oder einen anderen Sender wählen.")
                break
            }
        }
    }

    /** Ziel beim schnellen Umschalten (wird erst nach kurzer Pause gestartet). */
    var zapTarget by mutableStateOf<Channel?>(null); private set
    private var zapJob: Job? = null
    private var lastZapAt = 0L

    /** Umschalten in der laufenden Liste; schnell hintereinander gedrückt wird nur der letzte Sender gestartet. */
    fun zap(step: Int) {
        val live = playing as? Playback.Live ?: return
        val list = live.list
        if (list.size < 2) return
        val from = zapTarget ?: live.channel
        val index = list.indexOfFirst { it.key == from.key }.coerceAtLeast(0)
        val target = list[(index + step).mod(list.size)]
        val now = System.currentTimeMillis()
        val burst = now - lastZapAt < ZAP_BURST_MS
        lastZapAt = now
        if (!burst && zapJob == null) {
            play(live.copy(channel = target))
            return
        }
        zapTarget = target
        zapJob?.cancel()
        zapJob = scope.launch {
            delay(ZAP_SETTLE_MS)
            zapJob = null
            zapTarget = null
            val current = playing as? Playback.Live ?: return@launch
            play(current.copy(channel = target))
        }
    }

    /** Spiele der gefolgten Vereine in den nächsten 7 Tagen als Erinnerungen eintragen (wie auf dem Fire TV). */
    private suspend fun planTeamReminders(now: Long) {
        val teams = store.user.value.followedTeams
        val events = withContext(Dispatchers.Default) {
            de.ayutv.desktop.data.SportEvents.of(store.listedChannels(), store.guide.value, store.user.value.favorites.toSet(), hours = 7 * 24, now = now)
        }
        val fresh = events.filter { e ->
            val pairing = e.pairing ?: return@filter false
            e.programme.start > now && teams.any { de.ayutv.data.parse.Teams.plays(it, pairing, e.programme.title + " " + e.programme.subtitle.orEmpty()) }
        }.map { de.ayutv.desktop.data.Reminder(it.channel.key, it.channel.name, it.headline, it.programme.start, team = true) }
        if (fresh.isEmpty()) return
        store.updateUser { u ->
            val known = u.reminders.map { it.channelKey to it.start }.toSet()
            u.copy(reminders = u.reminders + fresh.filter { (it.channelKey to it.start) !in known })
        }
    }

    /** Mit dem Fire TV abgleichen (Favoriten, Verlauf). */
    suspend fun syncDevices(quiet: Boolean = false) {
        val u = store.user.value
        deviceSyncStatus = "Gleiche ab …"
        runCatching { deviceSync.withFireTv(u.syncAddress, u.syncPin) }
            .onSuccess { r ->
                deviceSyncStatus = "${clock(System.currentTimeMillis())}: ${r.text}"
                if (!quiet || r.favorites + r.history > 0) toast("Abgleich mit dem Fire TV: ${r.text}")
            }
            .onFailure {
                de.ayutv.desktop.Log.warn("Abgleich mit dem Fire TV", it)
                deviceSyncStatus = "Fehlgeschlagen: ${it.message}"
                if (!quiet) toast("Abgleich fehlgeschlagen: ${it.message}")
            }
    }

    // ---------------------------------------------------------------- Aufnahmen

    /** Sendung aufnehmen (geplant oder laufend). Läuft sie schon, beginnt die Aufnahme sofort. */
    fun record(channel: Channel, programme: de.ayutv.desktop.data.Programme) {
        val r = de.ayutv.desktop.data.Recording(
            id = System.currentTimeMillis(),
            channelKey = channel.key, channelName = channel.name, title = programme.title,
            start = programme.start, stop = programme.stop,
            file = recorder.fileFor(channel.name, programme.title, programme.start).absolutePath,
        )
        store.updateUser { it.copy(recordings = it.recordings + r) }
        toast(if (programme.start <= System.currentTimeMillis()) "⏺ Aufnahme läuft: ${programme.title}" else "⏺ Aufnahme geplant: ${programme.title} um ${clock(programme.start)}")
        scope.launch { withContext(Dispatchers.IO) { runRecordings(System.currentTimeMillis()) } }
    }

    /** Aufnahme beenden bzw. Planung löschen; [deleteFile] löscht auch die Datei. */
    fun removeRecording(r: de.ayutv.desktop.data.Recording, deleteFile: Boolean) {
        recorder.stop(r.id)
        if (!deleteFile && r.state == de.ayutv.desktop.data.Recording.RUNNING) {
            // Laufende Aufnahme beenden: was bisher aufgenommen ist, bleibt.
            store.updateUser { u -> u.copy(recordings = u.recordings.map { if (it.id == r.id) it.copy(state = de.ayutv.desktop.data.Recording.DONE, stop = System.currentTimeMillis()) else it }) }
            return
        }
        if (deleteFile) java.io.File(r.file).delete()
        store.updateUser { u -> u.copy(recordings = u.recordings.filterNot { it.id == r.id }) }
    }

    fun playRecording(r: de.ayutv.desktop.data.Recording) {
        val file = java.io.File(r.file)
        if (!file.isFile) return toast("Datei fehlt: ${file.name}")
        play(Playback.Vod("rec:${r.id}", "${r.channelName}: ${r.title}", file.absolutePath, store.user.value.positions["rec:${r.id}"] ?: 0))
    }

    /** Fällige Aufnahmen starten (1 Minute vorher) und beenden (2 Minuten nach Ende). */
    private fun runRecordings(now: Long) {
        val list = store.user.value.recordings
        if (list.isEmpty()) return
        val changes = HashMap<Long, String>()
        for (r in list) {
            when (r.state) {
                de.ayutv.desktop.data.Recording.PLANNED -> if (r.start - 60_000 <= now && now < r.stop) {
                    val channel = store.channel(r.channelKey)
                    val ok = channel != null && recorder.start(r, channel.url, Sync.USER_AGENT, Network.vlcOptions(channel.url))
                    changes[r.id] = if (ok) de.ayutv.desktop.data.Recording.RUNNING else de.ayutv.desktop.data.Recording.FAILED
                    if (!ok) toast("Aufnahme „${r.title}“ ließ sich nicht starten")
                } else if (now >= r.stop) changes[r.id] = de.ayutv.desktop.data.Recording.FAILED
                de.ayutv.desktop.data.Recording.RUNNING -> {
                    if (now > r.stop + 120_000) {
                        recorder.stop(r.id)
                        val size = java.io.File(r.file).length()
                        changes[r.id] = if (size > 100_000) de.ayutv.desktop.data.Recording.DONE else de.ayutv.desktop.data.Recording.FAILED
                        toast(if (size > 100_000) "⏺ Aufnahme fertig: ${r.title}" else "Aufnahme „${r.title}“ ist leer geblieben")
                    } else if (!recorder.isRunning(r.id)) {
                        // Nach einem Neustart von DnA-TV weiter aufnehmen (in eine neue Datei).
                        val channel = store.channel(r.channelKey)
                        if (channel != null) recorder.start(r.copy(file = recorder.fileFor(r.channelName, r.title, now).absolutePath), channel.url, Sync.USER_AGENT, Network.vlcOptions(channel.url))
                    }
                }
            }
        }
        if (changes.isNotEmpty()) store.updateUser { u -> u.copy(recordings = u.recordings.map { r -> changes[r.id]?.let { r.copy(state = it) } ?: r }) }
    }

    /** Fällige Erinnerung annehmen: zum Sender umschalten. */
    fun acceptReminder(reminder: de.ayutv.desktop.data.Reminder) {
        dueReminder = null
        val channel = store.channel(reminder.channelKey) ?: return toast("Sender „${reminder.channelName}“ gibt es nicht mehr")
        play(Playback.Live(channel, store.allVisible().takeIf { list -> list.any { it.key == channel.key } } ?: listOf(channel)))
    }

    /** Sendung aus dem Archiv (Catch-up) abspielen; false = Sender hat kein Archiv dafür. */
    fun playArchive(channel: Channel, programme: de.ayutv.desktop.data.Programme): Boolean {
        val url = store.archiveUrl(channel, programme.start, programme.stop) ?: return false
        play(Playback.Vod("archive:${channel.key}:${programme.start}", "${channel.name}: ${programme.title}", url, archive = channel))
        return true
    }

    /** Zeitversetzt (Live angehalten und weiter): so weit liegt das Bild hinter dem Sender. */
    var liveDelayMs by mutableStateOf(0L); private set
    private var pausedAt = 0L

    /** Live anhalten/fortsetzen (VLC puffert dabei weiter, danach läuft das Bild zeitversetzt). */
    fun toggleLivePause() {
        if (player.paused) {
            if (pausedAt > 0) liveDelayMs += System.currentTimeMillis() - pausedAt
            pausedAt = 0
            player.resume()
        } else {
            pausedAt = System.currentTimeMillis()
            player.pause()
        }
    }

    /** Zurück zum Live-Bild (nach Pause) bzw. bei Archiv zum laufenden Sender. */
    fun backToLive(): Boolean {
        val live = playing as? Playback.Live
        if (live != null && (liveDelayMs > 0 || player.paused)) {
            play(live)
            return true
        }
        val archive = (playing as? Playback.Vod)?.archive ?: return false
        play(Playback.Live(archive, store.allVisible()))
        return true
    }

    /** Zum vorher geschauten Sender (und wieder zurück). */
    fun backZap(): Boolean {
        val live = playing as? Playback.Live ?: return false
        val previous = previousChannel?.takeIf { it.key != live.channel.key } ?: return false
        val list = if (live.list.any { it.key == previous.key }) live.list else store.allVisible()
        play(Playback.Live(previous, list))
        toast("◀ Zurück: ${previous.name}")
        return true
    }

    /** Eingetippte Sendernummer (Zifferntasten im Bild). */
    var typedNumber by mutableStateOf("")
        private set
    private var numberJob: Job? = null

    fun typeDigit(digit: Int) {
        val live = playing as? Playback.Live ?: return
        typedNumber = (typedNumber + digit).takeLast(4)
        val typed = typedNumber
        val numbers = live.list.mapIndexed { i, c -> (c.number.takeIf { it > 0 } ?: (i + 1)) to c }
        val canGrow = numbers.any { (n, _) -> n.toString().let { it.length > typed.length && it.startsWith(typed) } }
        numberJob?.cancel()
        numberJob = scope.launch {
            if (canGrow) delay(1_500)
            val wanted = typed.toIntOrNull()
            typedNumber = ""
            val target = numbers.firstOrNull { it.first == wanted }?.second
            if (target == null) toast("Kein Sender mit der Nummer $typed") else if ((playing as? Playback.Live)?.channel?.key != target.key) {
                play(live.copy(channel = target))
            }
        }
    }

    fun stopPlayback() {
        lastPlaybackAt = System.currentTimeMillis()
        (playing as? Playback.Live)?.let { live ->
            // Beim Schauen gemessen: Auflösung und Startzeit fließen in die Wahl der besten Fassung ein.
            player.streamInfo()?.let { info -> if (info.height > 0) de.ayutv.data.parse.ChannelQuality.recordPlayback(live.channel.key, info.height, info.startupMs) }
            channelCheck.save()
        }
        stopAligning()
        savePosition()
        watchdog?.cancel()
        zapJob?.cancel()
        zapJob = null
        zapTarget = null
        playerStatus = null
        player.stop()
        playing = null
    }

    /** Taste Home: wie auf dem Fire TV zur Startseite (Wiedergabe und offene Dialoge schließen). */
    fun goHome() {
        if (playing != null) stopPlayback()
        openSeries = null
        // Offene Dialoge und Unterseiten schließen (begrenzt: ein Handler bleibt bis zum nächsten Zeichnen aktiv).
        repeat(minOf(backStack.size, 8)) {
            for (i in backStack.indices.reversed()) if (backStack[i]()) break
        }
        screen = Screen.HOME
    }

    /** Wiedergabestand des laufenden Films merken (fast am Ende = gesehen, beim nächsten Mal von vorn). */
    fun savePosition() {
        val vod = playing as? Playback.Vod ?: return
        if (vod.archive != null) return
        val position = player.positionMs
        val duration = player.durationMs
        if (position < 10_000) return
        store.savePosition(vod.key, if (duration > 0 && position > duration - 60_000) 0 else position, duration)
    }

    /** Stand der Ausrichtung am Ton (für die Leiste im Player; null = keine Untertitel geladen). */
    var subtitleSync by mutableStateOf<String?>(null); private set
    private var alignJob: Job? = null

    private fun stopAligning() {
        alignJob?.cancel()
        alignJob = null
        subtitleSync = null
    }

    /**
     * Untertitel in der eingestellten Sprache suchen (OpenSubtitles, sonst DeepL), einblenden und am Ton ausrichten:
     * Wie auf dem Fire TV werden die Sprechpausen des Films mit den Einblendungen verglichen; nach rund 1,5 Minuten
     * Gespräch sitzt der Versatz (auch bei anderer Bildrate). Die Ausrichtung wird je Film gemerkt.
     */
    fun loadSubtitles(vod: Playback.Vod) = scope.launch {
        val user = store.user.value
        runCatching {
            subtitles.find(vod.key, vod.title, user.subtitleLanguage.ifBlank { "de" }, user.openSubtitlesKey, user.deeplKey) { toast(it) }
        }.onSuccess { found ->
            if ((playing as? Playback.Vod)?.key != vod.key) return@onSuccess
            val text = withContext(Dispatchers.IO) { found.file.readText() }
            val cues = SubtitleFiles.cues(text)
            val known = user.subtitleTimings[vod.key]?.let { SubtitleTiming(it.ratio, it.offsetMs) }
            if (known != null) {
                showSubtitles(vod, found.file, text, known)
                toast("Untertitel eingeblendet (${found.source}), wie beim letzten Mal ausgerichtet.")
                subtitleSync = "Ausgerichtet (gemerkt)"
                return@onSuccess
            }
            player.addSubtitleFile(found.file)
            toast("Untertitel eingeblendet (${found.source}). Sie richten sich gleich am Ton aus.")
            align(vod, found.file, text, cues)
        }.onFailure { toast(it.message ?: "Keine Untertitel") }
    }

    private suspend fun showSubtitles(vod: Playback.Vod, file: java.io.File, text: String, timing: SubtitleTiming) {
        val aligned = java.io.File(file.parentFile, file.nameWithoutExtension + ".sync.srt")
        withContext(Dispatchers.IO) { aligned.writeText(SubtitleFiles.shifted(text, timing)) }
        if ((playing as? Playback.Vod)?.key == vod.key) player.addSubtitleFile(aligned)
    }

    private fun align(vod: Playback.Vod, file: java.io.File, text: String, cues: List<Pair<Long, Long>>) {
        alignJob?.cancel()
        alignJob = scope.launch {
            if (cues.size < 20) {
                subtitleSync = "Zu wenige Einblendungen zum Ausrichten"
                return@launch
            }
            // Für die Auswertung den Ton selbst ausgeben: kurz an derselben Stelle neu starten.
            if (!player.tapAudio) {
                player.tapAudio = true
                val position = player.positionMs
                player.play(vod.url, Sync.USER_AGENT, position, *Network.vlcOptions(vod.url).toTypedArray())
                delay(1_500)
                player.addSubtitleFile(file)
            }
            var applied: SubtitleTiming? = null
            var lastHeard = -1
            while (true) {
                delay(10_000)
                if (!player.playing) continue
                val heard = player.speech.seconds()
                if (heard < SpeechSync.MIN_SECONDS) {
                    if (applied == null) subtitleSync = "Hört zu: $heard von ${SpeechSync.MIN_SECONDS} Sekunden Ton"
                    continue
                }
                if (heard == lastHeard) continue
                lastHeard = heard
                val position = player.positionMs
                val fit = withContext(Dispatchers.Default) {
                    SpeechSync.estimate(player.speech.signal(position - WINDOW_MS, position + 5_000), cues)
                }
                if (fit == null) {
                    if (applied == null) subtitleSync = "${heard / 60} min Ton geprüft, noch kein eindeutiger Takt – prüft weiter"
                    continue
                }
                val timing = fit.timing
                // Nur neu einblenden, wenn sich an der aktuellen Stelle wirklich etwas ändert.
                val now = applied
                if (now != null && kotlin.math.abs(timing.toVideo(position) - now.toVideo(position)) < 250) continue
                showSubtitles(vod, file, text, timing)
                applied = timing
                store.updateUser { it.copy(subtitleTimings = it.subtitleTimings + (vod.key to TimingMark(timing.ratio, timing.offsetMs))) }
                val shift = "%+.1f s".format(java.util.Locale.GERMAN, timing.offsetMs / 1000.0) + if (timing.ratio != 1.0) ", Bildrate angepasst" else ""
                subtitleSync = "Am Ton ausgerichtet ($shift)"
                toast("Untertitel am Ton ausgerichtet ($shift)")
            }
        }
    }

    fun back(): Boolean {
        for (i in backStack.indices.reversed()) if (backStack[i]()) return true
        if (playing != null) {
            stopPlayback(); return true
        }
        return false
    }

    /** Zurück auf oberster Ebene: zur Startseite (wie die Zurück-Taste auf dem Fire TV-Startbildschirm). */
    fun backToHome(): Boolean {
        if (back()) return true
        if (screen != Screen.HOME && store.playlists.value.isNotEmpty()) {
            screen = Screen.HOME
            return true
        }
        return false
    }

    internal fun pushBack(handler: () -> Boolean) = backStack.add(handler)
    internal fun popBack(handler: () -> Boolean) = backStack.remove(handler)

    fun syncNow() = scope.launch {
        runCatching { sync.syncAll() }.onFailure { toast("Laden fehlgeschlagen: ${it.message}") }
        applyProxy()
    }

    private companion object {
        /** So viel gehörter Ton (rückwärts ab jetzt) zählt für die Ausrichtung. */
        const val WINDOW_MS = 15 * 60_000L
        const val REFRESH_CHECK_MS = 15 * 60_000L
        /** So lange ohne neues Bild gilt ein Stream als eingefroren. */
        const val STALL_MS = 12_000L
        const val HEALTHY_MS = 30_000L
        const val MAX_RETRIES = 3
        const val ZAP_BURST_MS = 700L
        const val ZAP_SETTLE_MS = 450L
        const val REMINDER_AHEAD_MS = 60_000L
    }

    /** Installer der neuen Version starten und DnA-TV beenden (der Installer ersetzt die App). */
    fun installUpdate(release: de.ayutv.desktop.update.Updater.Release) = scope.launch {
        updater.install(release) {
            close()
            kotlin.system.exitProcess(0)
        }
    }

    fun close() {
        savePosition()
        watchdog?.cancel()
        remote.stop()
        input.stop()
        recorder.release()
        channelCheck.save()
        channelCheck.release()
        player.release()
    }
}

/** Esc/Zurück abfangen, solange [enabled]. */
@Composable
fun BackHandler(app: AppState, enabled: Boolean = true, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    val active by rememberUpdatedState(enabled)
    DisposableEffect(app) {
        val handler: () -> Boolean = { if (active) { current(); true } else false }
        app.pushBack(handler)
        onDispose { app.popBack(handler) }
    }
}
