package de.ayutv.data.repo

import android.os.Process
import android.util.Log
import android.util.Xml
import de.ayutv.data.LiveFormat
import de.ayutv.data.SettingsStore
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.EpgChannel
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.db.VodEntity
import de.ayutv.data.parse.EpgMatcher
import de.ayutv.data.parse.EpgSources
import de.ayutv.data.parse.M3uParser
import de.ayutv.data.parse.XmltvParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import de.ayutv.data.parse.XtreamParsing
import java.util.concurrent.TimeUnit
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * Lädt Playlists und EPG. Der Import läuft blockierend auf einem IO-Thread innerhalb einer
 * einzigen Transaktion: Die Oberfläche sieht nie einen halb importierten Zustand, und die
 * Streaming-Parser können Zeilen direkt in die Datenbank schreiben.
 */
class PlaylistRepository(
    private val db: AyuDatabase,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    cacheDir: File,
) {
    /** Downloads landen hier vollständig, bevor importiert wird (keine offene Verbindung während der Transaktion). */
    private val importDir = File(cacheDir, "import")

    /** Für Importe: eingestellter User-Agent (viele Anbieter sperren den von OkHttp) und langer Lese-Timeout. */
    @Volatile
    private var importHttp: OkHttpClient = http

    private suspend fun prepareImportClient() {
        val userAgent = settings.current().userAgent
        importHttp = http.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") == null) request.newBuilder().header("User-Agent", userAgent).build() else request)
            }
            .build()
    }
    private val mutex = Mutex()

    /**
     * Eigener Thread mit Hintergrund-Priorität: Ein großer EPG-Import soll der laufenden
     * Wiedergabe auf dem schwachen Fire-TV-Prozessor keine Rechenzeit wegnehmen.
     */
    private val importDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            // Knapp über "Hintergrund": nicht in die stark gedrosselte Hintergrundgruppe von Fire OS,
            // gibt der Wiedergabe aber trotzdem Vorrang.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            task.run()
        }, "ayu-import")
    }.asCoroutineDispatcher()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    val playlists = db.playlists().observeAll()

    suspend fun add(playlist: PlaylistEntity): Long = db.playlists().insert(playlist)

    /** Playlist ein- oder ausschalten. Daten bleiben erhalten; eine nie geladene Playlist wird jetzt geladen. */
    suspend fun setEnabled(id: Long, enabled: Boolean) {
        db.playlists().setEnabled(id, enabled)
        if (enabled && db.playlists().byId(id)?.lastSync == 0L) sync(id)
    }

    suspend fun delete(id: Long) = mutex.withLock {
        withContext(Dispatchers.IO) {
            db.runInTransaction(Runnable {
                db.channels().deleteForPlaylist(id)
                db.programmes().deleteForPlaylist(id)
                db.vod().deleteForPlaylist(id)
                db.series().deleteForPlaylist(id)
                db.hiddenGroups().deleteForPlaylist(id)
                db.playlists().delete(id)
            })
        }
    }

    /** Prüft Xtream-Zugangsdaten, bevor die Playlist gespeichert wird. Liefert eine Fehlermeldung oder null. */
    suspend fun verifyXtream(server: String, username: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val account = XtreamClient(http, server, username, password).account()
                ?: return@withContext "Anmeldung fehlgeschlagen. Bitte Benutzername und Passwort prüfen."
            if (account.status != null && !account.status.equals("Active", ignoreCase = true)) {
                "Konto ist nicht aktiv (Status: ${account.status})."
            } else {
                null
            }
        } catch (e: Exception) {
            "Server nicht erreichbar: ${e.message}"
        }
    }

    /** Nach Änderung der Zeitverschiebung: alle TV-Guides neu laden. */
    suspend fun reloadAllEpg() {
        for (playlist in db.playlists().all()) if (playlist.enabled) syncEpg(playlist.id)
    }

    suspend fun syncAll(force: Boolean = false) {
        val now = System.currentTimeMillis()
        for (playlist in db.playlists().all()) {
            // Ausgeschaltete Playlists kosten keine Zeit und keine Verbindung beim Anbieter.
            if (!playlist.enabled) continue
            // Nach neuen Sendern immer auch den TV-Guide neu zuordnen: Beim Neuladen der Senderliste gehen
            // Kennungen verloren, die erst über den Namen gefunden wurden.
            if (force || now - playlist.lastSync > CONTENT_MAX_AGE) sync(playlist.id, withEpg = true)
            val fresh = db.playlists().byId(playlist.id) ?: continue
            if (now - fresh.lastEpgSync > EPG_MAX_AGE) syncEpg(fresh.id)
        }
        val archiveDays = db.channels().maxCatchupDaysAll().coerceIn(0, MAX_ARCHIVE_DAYS)
        db.programmes().deleteEndedBefore(now - maxOf(6 * HOUR, archiveDays * 24 * HOUR))
    }

    /** Playlists, die gerade geladen werden: ein zweiter Klick auf „Aktualisieren“ startet keinen zweiten Import. */
    private val syncing = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    suspend fun sync(playlistId: Long, withEpg: Boolean = true) {
        if (!syncing.add(playlistId)) return
        try {
            syncNow(playlistId, withEpg)
        } finally {
            syncing.remove(playlistId)
        }
    }

    private suspend fun syncNow(playlistId: Long, withEpg: Boolean) {
        val playlist = db.playlists().byId(playlistId) ?: return
        val ok = mutex.withLock {
            prepareImportClient()
            _state.value = SyncState.Running("${playlist.name}: Sender werden geladen …")
            try {
                val updated = withContext(importDispatcher) {
                    when (playlist.type) {
                        PlaylistType.M3U -> importM3u(playlist)
                        PlaylistType.XTREAM -> importXtream(playlist)
                    }
                }
                // Während des Imports umgeschaltet? Den aktuellen Schalterstand behalten.
                val enabled = db.playlists().byId(playlistId)?.enabled ?: updated.enabled
                db.playlists().update(updated.copy(enabled = enabled))
                _state.value = SyncState.Idle
                true
            } catch (e: Exception) {
                Log.w(TAG, "Sync fehlgeschlagen", e)
                _state.value = SyncState.Failed("${playlist.name}: ${e.message ?: e.javaClass.simpleName}")
                false
            }
        }
        if (ok && withEpg) syncEpg(playlistId)
    }

    private fun importM3u(playlist: PlaylistEntity): PlaylistEntity {
        var detectedEpg: String? = null
        val source = fetch(playlist.url, "playlist-${playlist.id}.m3u")
        try { db.runInTransaction(Runnable {
            db.channels().deleteForPlaylist(playlist.id)
            db.vod().deleteForPlaylist(playlist.id)
            val channels = ArrayList<ChannelEntity>(BATCH)
            val vod = ArrayList<VodEntity>(BATCH)
            var position = 0
            openFile(source).bufferedReader().use { reader ->
                val header = M3uParser.parse(reader) { entry ->
                    position++
                    if (entry.isVod) {
                        vod += VodEntity(
                            key = "${playlist.id}:v:${entry.url}",
                            playlistId = playlist.id,
                            name = entry.name,
                            category = entry.groupTitle.ifEmpty { "Filme" },
                            poster = entry.logo,
                            rating = null,
                            url = entry.url,
                            added = 0,
                        )
                        if (vod.size >= BATCH) db.vod().insertAll(vod.drain())
                    } else {
                        channels += ChannelEntity(
                            key = "${playlist.id}:c:${entry.tvgId ?: entry.name}:${entry.url}",
                            playlistId = playlist.id,
                            name = entry.name,
                            groupTitle = entry.groupTitle.ifEmpty { "Ohne Gruppe" },
                            logo = entry.logo,
                            url = entry.url,
                            epgId = entry.tvgId,
                            number = entry.number ?: position,
                            catchupDays = entry.catchupDays,
                            userAgent = entry.userAgent,
                            catchupType = entry.catchupType,
                            catchupSource = entry.catchupSource,
                        )
                        if (channels.size >= BATCH) db.channels().insertAll(channels.drain())
                    }
                    if (position % 5000 == 0) _state.value = SyncState.Running("${playlist.name}: $position Einträge …")
                }
                detectedEpg = header.epgUrls.joinToString(",").ifEmpty { null }
            }
            db.channels().insertAll(channels)
            db.vod().insertAll(vod)
        }) } finally { discard(source) }
        return playlist.copy(lastSync = System.currentTimeMillis(), epgUrl = playlist.epgUrl ?: detectedEpg)
    }

    private fun importXtream(playlist: PlaylistEntity): PlaylistEntity {
        val client = XtreamClient.of(importHttp, playlist)
        val liveCategories = client.liveCategories()
        val vodCategories = runCatching { client.vodCategories() }.getOrDefault(emptyMap())
        val seriesCategories = runCatching { client.seriesCategories() }.getOrDefault(emptyMap())

        // Erst alles herunterladen. Scheitern Filme oder Serien, bleiben die bisherigen erhalten,
        // statt dass die Bibliothek bis zum nächsten Abgleich leer ist.
        val liveFile = fetch(client.apiUrl("get_live_streams"), "xtream-${playlist.id}-live.json")
        _state.value = SyncState.Running("${playlist.name}: Filme werden geladen …")
        val vodFile = runCatching { fetch(client.apiUrl("get_vod_streams"), "xtream-${playlist.id}-vod.json") }
            .onFailure { Log.w(TAG, "Filme konnten nicht geladen werden", it) }.getOrNull()
        _state.value = SyncState.Running("${playlist.name}: Serien werden geladen …")
        val seriesFile = runCatching { fetch(client.apiUrl("get_series"), "xtream-${playlist.id}-series.json") }
            .onFailure { Log.w(TAG, "Serien konnten nicht geladen werden", it) }.getOrNull()

        try { db.runInTransaction(Runnable {
            db.channels().deleteForPlaylist(playlist.id)

            val channels = ArrayList<ChannelEntity>(BATCH)
            var position = 0
            openFile(liveFile).use { input -> XtreamParsing.liveStreams(input) { live ->
                position++
                channels += ChannelEntity(
                    key = "${playlist.id}:c:${live.streamId}",
                    playlistId = playlist.id,
                    name = live.name,
                    groupTitle = liveCategories[live.categoryId] ?: "Ohne Gruppe",
                    logo = live.icon,
                    url = client.liveUrl(live.streamId, LiveFormat.TS),
                    epgId = live.epgId,
                    number = live.number ?: position,
                    catchupDays = live.archiveDays,
                )
                if (channels.size >= BATCH) db.channels().insertAll(channels.drain())
            } }
            db.channels().insertAll(channels)

            if (vodFile != null) {
                db.vod().deleteForPlaylist(playlist.id)
                val vod = ArrayList<VodEntity>(BATCH)
                openFile(vodFile).use { input -> XtreamParsing.vodStreams(input) { item ->
                    vod += VodEntity(
                        key = "${playlist.id}:v:${item.streamId}",
                        playlistId = playlist.id,
                        name = item.name,
                        category = vodCategories[item.categoryId] ?: "Filme",
                        poster = item.icon,
                        rating = item.rating,
                        url = client.vodUrl(item.streamId, item.extension),
                        added = item.added,
                    )
                    if (vod.size >= BATCH) db.vod().insertAll(vod.drain())
                } }
                db.vod().insertAll(vod)
            }

            if (seriesFile != null) {
                db.series().deleteForPlaylist(playlist.id)
                val series = ArrayList<SeriesEntity>(BATCH)
                openFile(seriesFile).use { input -> XtreamParsing.series(input) { item ->
                    series += SeriesEntity(
                        key = "${playlist.id}:s:${item.seriesId}",
                        playlistId = playlist.id,
                        seriesId = item.seriesId,
                        name = item.name,
                        category = seriesCategories[item.categoryId] ?: "Serien",
                        cover = item.cover,
                        plot = item.plot,
                        rating = item.rating,
                        added = item.added,
                    )
                    if (series.size >= BATCH) db.series().insertAll(series.drain())
                } }
                db.series().insertAll(series)
            }
        }) } finally {
            discard(liveFile)
            vodFile?.let(::discard)
            seriesFile?.let(::discard)
        }
        // Zeitzone des Servers merken: Archiv-Adressen werden in Serverzeit angegeben.
        val timezone = runCatching { client.account()?.timezone }.getOrNull() ?: playlist.timezone
        return playlist.copy(lastSync = System.currentTimeMillis(), timezone = timezone)
    }

    suspend fun syncEpg(playlistId: Long) {
        mutex.withLock {
            // Erst hier lesen: Während auf die Sperre gewartet wurde, kann die Playlist geändert oder gelöscht worden sein.
            val playlist = db.playlists().byId(playlistId) ?: return
            val sources = when (playlist.type) {
                PlaylistType.M3U -> EpgSources.forM3u(playlist.epgUrl, playlist.url)
                PlaylistType.XTREAM -> EpgSources.forXtream(playlist.epgUrl, XtreamClient.of(http, playlist).epgUrl)
            }
            if (sources.isEmpty()) return
            prepareImportClient()
            _state.value = SyncState.Running("${playlist.name}: TV-Guide wird geladen …")
            try {
                val channels = db.channels().forEpg(playlist.id)
                val shift = settings.current().epgShiftHours * HOUR
                withContext(importDispatcher) { importEpg(playlist, sources, channels, shift) }
                db.playlists().setLastEpgSync(playlist.id, System.currentTimeMillis())
                _state.value = SyncState.Idle
            } catch (e: Exception) {
                Log.w(TAG, "EPG fehlgeschlagen", e)
                _state.value = SyncState.Failed("TV-Guide: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Quellen nacheinander: Die erste ersetzt den alten TV-Guide, jede weitere füllt nur Sender, für die
     * noch nichts da ist. Eine Quelle, die nicht lädt, hält die anderen nicht auf.
     */
    private fun importEpg(playlist: PlaylistEntity, sources: List<String>, channels: List<EpgChannel>, shift: Long) {
        val covered = HashSet<Long>()
        val taken = HashSet<String>()
        var cleared = false
        var firstError: Exception? = null
        for ((index, url) in sources.withIndex()) {
            val open = channels.filter { it.id !in covered }
            if (open.isEmpty()) break
            val source = try {
                fetch(url, "epg-${playlist.id}-$index.xml")
            } catch (e: IOException) {
                Log.w(TAG, "EPG-Quelle ${index + 1} nicht erreichbar", e)
                firstError = firstError ?: e
                continue
            }
            try {
                val found = HashSet<Long>()
                val used = HashSet<String>()
                db.runInTransaction(Runnable {
                    if (!cleared) db.programmes().deleteForPlaylist(playlist.id)
                    importEpgSource(playlist, source, open, shift, taken, found, used)
                })
                cleared = true
                covered += found
                taken += used
                Log.i(TAG, "EPG-Quelle ${index + 1}: ${found.size} von ${open.size} Sendern")
            } catch (e: Exception) {
                Log.w(TAG, "EPG-Quelle ${index + 1} nicht lesbar", e)
                firstError = firstError ?: e
            } finally {
                discard(source)
            }
        }
        if (!cleared) throw firstError ?: IOException("Kein TV-Guide geladen")
    }

    /**
     * Eine Quelle einlesen. [taken]: Kennungen, unter denen eine frühere Quelle schon Sendungen gespeichert hat;
     * zurück kommen die versorgten Sender ([found]) und die hier belegten Kennungen ([used]).
     */
    private fun importEpgSource(
        playlist: PlaylistEntity,
        source: File,
        open: List<EpgChannel>,
        shift: Long,
        taken: Set<String>,
        found: MutableSet<Long>,
        used: MutableSet<String>,
    ) {
        val now = System.currentTimeMillis()
        // Mit Archiv (Catch-up) auch vergangene Tage behalten, sonst nur die letzten 2 Stunden.
        val archiveDays = db.channels().maxCatchupDays(playlist.id).coerceIn(0, MAX_ARCHIVE_DAYS)
        val from = now - maxOf(2 * HOUR, archiveDays * 24 * HOUR)
        val until = now + EPG_DAYS * 24 * HOUR
        val parser = XmltvParser(Xml.newPullParser())

        // EPG-Sender → Kennungen, unter denen seine Sendungen gespeichert werden: die Kennung der Playlist
        // (auch wenn sie anders lautet als im EPG), bei Sendern ohne Kennung die des EPGs.
        val targets = HashMap<String, MutableList<String>>()
        val owner = HashMap<String, String>()
        val channelsOf = HashMap<String, MutableList<Long>>()
        val assign = ArrayList<Pair<Long, String>>()
        fun link(guideId: String, channel: EpgChannel) {
            // Genau die gespeicherte Kennung des Senders: Unter ihr sucht die Oberfläche die Sendungen.
            val own = channel.epgId?.takeIf { it.isNotBlank() }
            val storage = own ?: guideId
            if (storage in taken) return
            // Eine Kennung gehört genau einem EPG-Sender, sonst mischten sich zwei Programme.
            val current = owner.getOrPut(storage) { guideId.also { targets.getOrPut(it) { ArrayList(1) } += storage } }
            if (own == null) {
                if (current != guideId) return
                assign += channel.id to guideId
            }
            channelsOf.getOrPut(storage) { ArrayList(1) } += channel.id
        }
        // <channel> steht in XMLTV vor den Programmen: Beim ersten Programm sind alle Namen bekannt.
        var prepared = false
        val byLowerId = open.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!!.trim().lowercase() }
        val unknown = HashSet<String>()
        fun storageFor(id: String): List<String>? {
            if (!prepared) {
                prepared = true
                val matcher = EpgMatcher()
                for ((guideId, names) in parser.channels) matcher.addGuideChannel(guideId, names)
                if (!matcher.isEmpty) for (channel in open) matcher.match(channel.epgId, channel.name)?.let { link(it, channel) }
            }
            targets[id]?.let { return it }
            if (id in unknown) return null
            // Programme eines Senders ohne <channel>-Eintrag: nur über die Kennung.
            byLowerId[id.lowercase()]?.forEach { link(id, it) }
            return targets[id] ?: null.also { unknown += id }
        }

        val batch = ArrayList<ProgrammeEntity>(BATCH)
        var count = 0
        openFile(source).use { input ->
            parser.parse(
                input,
                wanted = { id, start, stop -> stop + shift > from && start + shift < until && storageFor(id) != null },
            ) { p ->
                for (storage in storageFor(p.channelId).orEmpty()) {
                    batch += ProgrammeEntity(
                        playlistId = playlist.id,
                        channelEpgId = storage,
                        start = p.start + shift,
                        stop = p.stop + shift,
                        title = p.title,
                        description = p.description,
                        category = p.category,
                    )
                    channelsOf[storage]?.let(found::addAll)
                    used += storage
                }
                if (batch.size >= BATCH) {
                    count += batch.size
                    db.programmes().insertAll(batch.drain())
                    if (count % 10_000 < BATCH) _state.value = SyncState.Running("${playlist.name}: TV-Guide, $count Sendungen …")
                }
            }
        }
        db.programmes().insertAll(batch)
        for ((channelId, guideId) in assign) if (channelId in found) db.channels().setEpgId(channelId, guideId)
    }

    private fun <T> ArrayList<T>.drain(): List<T> = toList().also { clear() }

    /**
     * Lädt eine Adresse vollständig in eine Datei (bis zu 3 Versuche mit Pause). So bricht ein kurzer
     * Netzaussetzer nicht den ganzen Import ab, und die Datenbank-Transaktion hält keine Verbindung offen.
     * Lokale Pfade werden direkt verwendet.
     */
    private fun fetch(url: String, name: String): File {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return File(url.removePrefix("file://"))
        importDir.mkdirs()
        val target = File(importDir, name)
        var wait = 2_000L
        repeat(3) { attempt ->
            try {
                importHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} beim Laden von ${url.substringBefore('?')}")
                    target.outputStream().use { out -> response.body.byteStream().copyTo(out, 64 * 1024) }
                }
                return target
            } catch (e: IOException) {
                if (attempt == 2) {
                    target.delete()
                    throw e
                }
                Thread.sleep(wait)
                wait *= 3
            }
        }
        throw IOException("Download fehlgeschlagen")
    }

    /** Heruntergeladene Zwischendateien wieder löschen (eigene Dateien des Nutzers bleiben). */
    private fun discard(file: File) {
        if (file.parentFile == importDir) file.delete()
    }

    /** Öffnet eine Datei und entpackt gzip automatisch anhand der Magic Bytes. */
    private fun openFile(file: File): InputStream {
        val buffered = BufferedInputStream(file.inputStream(), 64 * 1024)
        buffered.mark(2)
        val b1 = buffered.read()
        val b2 = buffered.read()
        buffered.reset()
        return if (b1 == 0x1f && b2 == 0x8b) BufferedInputStream(GZIPInputStream(buffered, 64 * 1024), 64 * 1024) else buffered
    }

    companion object {
        private const val TAG = "PlaylistRepository"
        private const val BATCH = 1000
        private const val HOUR = 3_600_000L
        private const val EPG_DAYS = 4L
        private const val MAX_ARCHIVE_DAYS = 7
        private const val CONTENT_MAX_AGE = 12 * HOUR
        private const val EPG_MAX_AGE = 8 * HOUR
    }
}
