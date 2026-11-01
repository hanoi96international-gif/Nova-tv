package de.ayutv.data.repo

import android.os.Process
import android.util.Log
import android.util.Xml
import de.ayutv.data.LiveFormat
import de.ayutv.data.SettingsStore
import de.ayutv.data.db.ChannelEntity
import de.ayutv.data.db.ChannelRef
import de.ayutv.data.db.EpgChannel
import de.ayutv.data.db.AyuDatabase
import de.ayutv.data.db.PlaylistEntity
import de.ayutv.data.db.PlaylistType
import de.ayutv.data.db.ProgrammeEntity
import de.ayutv.data.db.SeriesEntity
import de.ayutv.data.db.VodEntity
import de.ayutv.data.parse.EpgMatcher
import de.ayutv.data.parse.KeyRescue
import de.ayutv.data.parse.EpgSources
import de.ayutv.data.parse.M3uParser
import de.ayutv.data.parse.XmltvParser
import kotlinx.coroutines.Dispatchers
import de.ayutv.data.security.Credentials
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
    private val overrides: de.ayutv.data.EpgOverrides? = null,
    private val limits: de.ayutv.data.AccountLimits? = null,
) {
    /** Downloads landen hier vollständig, bevor importiert wird (keine offene Verbindung während der Transaktion). */
    private val importDir = File(cacheDir, "import")
    /** Freie EPG-Dateien bleiben einen Tag liegen (nicht bei jedem Laden neu holen). */
    private val publicDir = File(cacheDir, "epg-public")
    private val statusDir = File(cacheDir, "epg-status")

    /** Für Importe: eingestellter User-Agent (viele Anbieter sperren den von OkHttp) und langer Lese-Timeout. */
    @Volatile
    private var importHttp: OkHttpClient = http

    private suspend fun prepareImportClient() {
        val userAgent = settings.current().userAgent
        importHttp = http.newBuilder()
            .readTimeout(120, TimeUnit.SECONDS)
            // Obergrenze je Datei: Ein Server, der nur tröpfelt, hält den Import sonst ewig fest.
            .callTimeout(20, TimeUnit.MINUTES)
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

    /**
     * Playlist speichern. Gibt es denselben Zugang schon (gleicher Server und Benutzer bzw. gleiche Adresse, siehe
     * [de.ayutv.data.parse.PlaylistIdentity]), wird der vorhandene aktualisiert (Passwort, EPG-Adresse, eingeschaltet)
     * und seine Nummer zurückgegeben: Eigene Gruppen, Favoriten und Ausblendungen bleiben so an ihm hängen.
     */
    suspend fun add(playlist: PlaylistEntity): Long {
        val password = withContext(Dispatchers.Default) { Credentials.protect(playlist.password) }
        val same = findSame(playlist)
        if (same == null) return db.playlists().insert(playlist.copy(password = password))
        db.playlists().update(
            same.copy(
                password = password ?: same.password,
                epgUrl = playlist.epgUrl ?: same.epgUrl,
                enabled = true,
            )
        )
        return same.id
    }

    /** Vorhandene Playlist mit demselben Zugang, sonst null. */
    suspend fun findSame(playlist: PlaylistEntity): PlaylistEntity? = db.playlists().all().firstOrNull {
        de.ayutv.data.parse.PlaylistIdentity.same(it.type.name, it.url, it.username, playlist.type.name, playlist.url, playlist.username)
    }

    /** Einmalig: Passwörter aus älteren Versionen verschlüsselt ablegen. */
    suspend fun protectStoredPasswords() = withContext(Dispatchers.Default) {
        for (p in db.playlists().all()) {
            if (p.password.isNullOrEmpty() || Credentials.isProtected(p.password)) continue
            val protected = Credentials.protect(p.password)
            if (Credentials.isProtected(protected)) db.playlists().setPassword(p.id, protected)
        }
    }

    /** Playlist ein- oder ausschalten. Daten bleiben erhalten; eine nie geladene Playlist wird jetzt geladen. */
    /** Playlist umbenennen (nur der angezeigte Name; Zugang und Inhalte bleiben). */
    suspend fun rename(id: Long, name: String) {
        val trimmed = name.trim().take(60)
        if (trimmed.isEmpty()) return
        val playlist = db.playlists().byId(id) ?: return
        db.playlists().update(playlist.copy(name = trimmed))
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        db.playlists().setEnabled(id, enabled)
        if (!enabled) {
            // Ausgeschaltet: auch ihr Programm weg, sonst erscheinen Sendungen doppelt (gleiche Sender-Kennung).
            withContext(Dispatchers.IO) { db.programmes().deleteForPlaylist(id) }
            db.playlists().setLastEpgSync(id, 0)
            return
        }
        val playlist = db.playlists().byId(id) ?: return
        if (playlist.lastSync == 0L) sync(id) else if (playlist.lastEpgSync == 0L) syncEpg(id)
    }

    /**
     * Playlist löschen. Lädt sie gerade (oder hängt beim Laden), wird das Laden abgebrochen. Lädt eine andere,
     * wird nicht darauf gewartet: Das Löschen ist eine eigene Datenbank-Transaktion.
     */
    suspend fun delete(id: Long) {
        if (importing == id) cancelImport()
        if (importing == null || importing == id) mutex.withLock { deleteNow(id) } else deleteNow(id)
    }

    /** Laufenden Import abbrechen (auch blockierende Downloads). */
    private fun cancelImport() {
        importJob?.cancel()
        importHttp.dispatcher.cancelAll()
    }

    /** Playlist, die gerade lädt (Sender oder TV-Guide), und der Ladevorgang selbst. */
    @Volatile private var importing: Long? = null
    @Volatile private var importJob: kotlinx.coroutines.Job? = null

    private suspend fun deleteNow(id: Long) {
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
        // Neue Version mit besserer Senderzuordnung: TV-Guide sofort neu zuordnen statt erst nach 8 Stunden.
        val versionFile = File(statusDir, "match-version")
        val rematch = runCatching { versionFile.readText().trim() }.getOrNull() != EPG_MATCH_VERSION
        for (playlist in db.playlists().all()) {
            // Ausgeschaltete Playlists kosten keine Zeit und keine Verbindung beim Anbieter.
            if (!playlist.enabled) continue
            // Nach neuen Sendern immer auch den TV-Guide neu zuordnen: Beim Neuladen der Senderliste gehen
            // Kennungen verloren, die erst über den Namen gefunden wurden.
            if (force || now - playlist.lastSync > CONTENT_MAX_AGE) sync(playlist.id, withEpg = true)
            val fresh = db.playlists().byId(playlist.id) ?: continue
            if (rematch || now - fresh.lastEpgSync > EPG_MAX_AGE) syncEpg(fresh.id)
        }
        if (rematch) runCatching { statusDir.mkdirs(); versionFile.writeText(EPG_MATCH_VERSION) }
        // Programm ausgeschalteter Playlists entfernen (ältere Versionen ließen es liegen: doppelte Sendungen).
        for (playlist in db.playlists().all()) if (!playlist.enabled) withContext(Dispatchers.IO) { db.programmes().deleteForPlaylist(playlist.id) }
        val archiveDays = db.channels().maxCatchupDaysAll().coerceIn(0, MAX_ARCHIVE_DAYS)
        db.programmes().deleteEndedBefore(now - maxOf(6 * HOUR, archiveDays * 24 * HOUR))
    }

    /** Nach einem erfolgreichen Import (z. B. kurzer Sender-Check). */
    @Volatile var onImported: (() -> Unit)? = null

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
        val ok = mutex.withLock {
            // Erst hier lesen: Während auf die Sperre gewartet wurde, kann die Playlist gelöscht worden sein.
            val playlist = db.playlists().byId(playlistId) ?: return
            importing = playlistId
            importJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            prepareImportClient()
            _state.value = SyncState.Running("${playlist.name}: Sender werden geladen …")
            try {
                val updated = withContext(importDispatcher) {
                    when (playlist.type) {
                        PlaylistType.M3U -> importM3u(playlist)
                        PlaylistType.XTREAM -> importXtream(playlist)
                    }
                }
                // Während des Imports umgeschaltet oder umbenannt? Den aktuellen Stand behalten.
                val now = db.playlists().byId(playlistId)
                db.playlists().update(updated.copy(enabled = now?.enabled ?: updated.enabled, name = now?.name ?: updated.name))
                _state.value = SyncState.Idle
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sync fehlgeschlagen", e)
                // Abgebrochen, weil die Playlist gelöscht wurde: keine Fehlermeldung.
                _state.value = if (db.playlists().byId(playlistId) == null) SyncState.Idle
                else SyncState.Failed("${playlist.name}: ${e.message ?: e.javaClass.simpleName}")
                false
            } finally {
                importing = null
                importJob = null
            }
        }
        if (ok) onImported?.invoke()
        if (ok && withEpg) syncEpg(playlistId)
    }

    /**
     * Eigene Gruppen, Favoriten und Reihenfolgen behalten ihre Sender, auch wenn der Anbieter beim neuen Laden
     * Adresse, Token oder Stream-Nummer geändert hat (sonst sähen eigene Gruppen nach dem Aktualisieren leer aus).
     */
    private fun rescueKeys(before: List<ChannelRef>, playlistId: Long) {
        if (before.isEmpty()) return
        fun ChannelRef.ref() = KeyRescue.Ref(key, name, epgId, groupTitle)
        val moves = KeyRescue.remap(before.map { it.ref() }, db.channels().refs(playlistId).map { it.ref() })
        for ((old, new) in moves) {
            db.channels().moveFavorite(old, new)
            db.channels().moveCustomItem(old, new)
            db.channels().moveOrder(old, new)
        }
        if (moves.isNotEmpty()) Log.i(TAG, "${moves.size} Einträge in Favoriten/eigenen Gruppen neuen Sendern zugeordnet")
    }

    private fun importM3u(playlist: PlaylistEntity): PlaylistEntity {
        var detectedEpg: String? = null
        val source = fetch(playlist.url, "playlist-${playlist.id}.m3u")
        try { db.runInTransaction(Runnable {
            val referenced = db.channels().referenced(playlist.id)
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
            rescueKeys(referenced, playlist.id)
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
        val liveFile = fetch(client.apiUrl("get_live_streams"), "xtream-${playlist.id}-live.json", "${playlist.name}: Sender werden geladen")
        _state.value = SyncState.Running("${playlist.name}: Filme werden geladen …")
        val vodFile = runCatching { fetch(client.apiUrl("get_vod_streams"), "xtream-${playlist.id}-vod.json", "${playlist.name}: Filme werden geladen") }
            .onFailure { Log.w(TAG, "Filme konnten nicht geladen werden", it) }.getOrNull()
        _state.value = SyncState.Running("${playlist.name}: Serien werden geladen …")
        val seriesFile = runCatching { fetch(client.apiUrl("get_series"), "xtream-${playlist.id}-series.json", "${playlist.name}: Serien werden geladen") }
            .onFailure { Log.w(TAG, "Serien konnten nicht geladen werden", it) }.getOrNull()

        try { db.runInTransaction(Runnable {
            val referenced = db.channels().referenced(playlist.id)
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
            rescueKeys(referenced, playlist.id)

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
        val account = runCatching { client.account() }.getOrNull()
        val timezone = account?.timezone ?: playlist.timezone
        // Verbindungslimit merken: Davor warnt die App, bevor eine Aufnahme die einzige Verbindung belegt.
        account?.maxConnections?.let { limits?.set(playlist.id, it) }
        return playlist.copy(lastSync = System.currentTimeMillis(), timezone = timezone)
    }

    suspend fun syncEpg(playlistId: Long) {
        mutex.withLock {
            // Erst hier lesen: Während auf die Sperre gewartet wurde, kann die Playlist geändert oder gelöscht worden sein.
            val playlist = db.playlists().byId(playlistId) ?: return
            val current = settings.current()
            // Standard: nur der eigene TV-Guide (freies EPG). Der des Anbieters hat oft falsche Zeiten
            // (z. B. eine Stunde daneben nach der Zeitumstellung) und wird dann gar nicht geladen.
            val own = if (current.ownGuideOnly) emptyList() else when (playlist.type) {
                PlaylistType.M3U -> EpgSources.forM3u(playlist.epgUrl, playlist.url)
                PlaylistType.XTREAM -> EpgSources.forXtream(playlist.epgUrl, XtreamClient.of(http, playlist).epgUrl)
            }
            val extra = if (current.ownGuideOnly || current.internetGuide) EpgSources.PUBLIC.filter { it !in own } else emptyList()
            if (own.isEmpty() && extra.isEmpty()) return
            prepareImportClient()
            _state.value = SyncState.Running("${playlist.name}: TV-Guide wird geladen …")
            importing = playlistId
            importJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            try {
                // Von Hand zugeordnete Sender: Ihre Kennung gilt, egal was der Anbieter liefert.
                val chosen = overrides?.all().orEmpty()
                val forced = HashMap<Long, String>()
                if (chosen.isNotEmpty()) for (k in db.channels().keysFor(playlist.id)) chosen[k.key]?.let { forced[k.id] = it }
                val channels = db.channels().forEpg(playlist.id).map { c -> forced[c.id]?.let { c.copy(epgId = it) } ?: c }
                val visible = db.channels().visibleIds(playlist.id).toHashSet()
                val shift = current.epgShiftHours * HOUR
                withContext(importDispatcher) {
                    for ((id, guideId) in forced) db.channels().setEpgId(id, guideId)
                    importEpg(playlist, own, extra, channels, visible, shift, current.ownGuideOnly || current.internetGuide && current.preferInternetGuide, forced.keys)
                }
                db.playlists().setLastEpgSync(playlist.id, System.currentTimeMillis())
                _state.value = SyncState.Idle
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "EPG fehlgeschlagen", e)
                _state.value = if (db.playlists().byId(playlistId) == null) SyncState.Idle
                else SyncState.Failed("TV-Guide: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                importing = null
                importJob = null
            }
        }
    }

    /** Wie gut der TV-Guide einer Playlist zu ihren Sendern passt (nach dem letzten Laden). */
    data class EpgStatus(
        val withGuide: Int,
        val total: Int,
        val sources: List<Pair<String, Int>>,
        /** Sender ohne Programm (Zeile in der Datenbank beim letzten Laden, Gruppe, Name). */
        val missingChannels: List<MissingChannel>,
    ) {
        val missingInGroups: List<Pair<String, String>> get() = missingChannels.map { it.group to it.name }
        val missing: List<String> get() = missingChannels.map { it.name }.distinct()
    }

    data class MissingChannel(val rowId: Long?, val group: String, val name: String)

    fun epgStatus(playlistId: Long): EpgStatus? = runCatching {
        val lines = File(statusDir, "epg-$playlistId.txt").readLines()
        val (withGuide, total) = lines.first().split('\t').map { it.toInt() }
        EpgStatus(
            withGuide, total,
            lines.filter { it.startsWith("S\t") }.map { it.split('\t').let { p -> p[1] to p[2].toInt() } },
            lines.filter { it.startsWith("M\t") }.map {
                it.split('\t').let { p -> if (p.size >= 3) MissingChannel(p.getOrNull(3)?.toLongOrNull(), p[1], p[2]) else MissingChannel(null, "", p.last()) }
            },
        )
    }.getOrNull()

    /**
     * Quellen nacheinander: Die erste ersetzt den alten TV-Guide, jede weitere füllt nur Sender, für die
     * noch nichts da ist. Eine Quelle, die nicht lädt, hält die anderen nicht auf. Die freien Quellen
     * ([extra]) kommen zuletzt, nur wenn noch Sender aus dem deutschsprachigen Raum fehlen, und werden
     * höchstens einmal am Tag geladen.
     */
    private fun importEpg(
        playlist: PlaylistEntity,
        own: List<String>,
        extra: List<String>,
        channels: List<EpgChannel>,
        visible: Set<Long>,
        shift: Long,
        preferExtra: Boolean = false,
        forced: Set<Long> = emptySet(),
    ) {
        val covered = HashSet<Long>()
        // Alle EPG-Sender aller Quellen, für die Auswahl „Programm zuordnen“.
        val guideIndex = ArrayList<String>()
        val taken = HashSet<String>()
        // Sender, deren Kennung beim Anbieter nicht zum Namen passt: Das freie EPG darf sie neu zuordnen.
        val suspects = HashSet<Long>()
        val counts = ArrayList<Pair<String, Int>>()
        var cleared = false
        var firstError: Exception? = null
        // Mit „Internet bevorzugen“ kommt das freie EPG zuerst, der Anbieter füllt nur noch auf.
        val sources = if (preferExtra) extra.map { it to true } + own.map { it to false } else own.map { it to false } + extra.map { it to true }
        for ((index, entry) in sources.withIndex()) {
            val (url, isPublic) = entry
            val open = channels.filter { it.id !in covered } +
                // Ohne die fragliche Kennung: Ein Treffer im freien EPG bekommt dessen Kennung (setEpgId).
                if (isPublic) channels.filter { it.id in suspects }.map { it.copy(epgId = null) } else emptyList()
            if (open.isEmpty()) break
            if (isPublic && !EpgSources.relevant(url, open.asSequence().filter { it.id in visible }.map { it.name }.asIterable())) continue
            val source = try {
                if (isPublic) fetchCached(url, PUBLIC_MAX_AGE) else fetch(url, "epg-${playlist.id}-$index.xml")
            } catch (e: IOException) {
                Log.w(TAG, "EPG-Quelle ${index + 1} nicht erreichbar", e)
                if (!isPublic) firstError = firstError ?: e
                continue
            }
            try {
                val found = HashSet<Long>()
                val used = HashSet<String>()
                db.runInTransaction(Runnable {
                    if (!cleared) db.programmes().deleteForPlaylist(playlist.id)
                    importEpgSource(playlist, source, open, shift, taken, found, used, if (isPublic) null else suspects, forced, guideIndex, EpgSources.label(url))
                })
                cleared = true
                covered += found
                taken += used
                if (isPublic) suspects -= found
                counts += EpgSources.label(url) to found.count { it in visible }
                Log.i(TAG, "EPG-Quelle ${index + 1} (${EpgSources.label(url)}): ${found.size} von ${open.size} Sendern")
            } catch (e: Exception) {
                Log.w(TAG, "EPG-Quelle ${index + 1} nicht lesbar", e)
                if (!isPublic) firstError = firstError ?: e
            } finally {
                discard(source)
            }
        }
        if (!cleared) {
            // Nichts zu tun (keine Live-Sender, keine eigene Quelle, keine passende freie): kein Fehler.
            if (firstError == null) return
            throw firstError ?: IOException("Kein TV-Guide geladen")
        }
        writeStatus(playlist.id, channels, visible, covered, counts)
        runCatching { File(statusDir, "epg-guide-${playlist.id}.tsv").writeText(guideIndex.joinToString("\n")) }
    }

    /** Ein Sender aus einem der geladenen EPGs (für die Auswahl „Programm zuordnen“). */
    data class GuideChannel(val id: String, val name: String, val source: String)

    /** Alle EPG-Sender, die beim letzten Laden für diese Playlist in den Quellen standen. */
    fun guideChannels(playlistId: Long): List<GuideChannel> = runCatching {
        File(statusDir, "epg-guide-$playlistId.tsv").readLines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 3) GuideChannel(p[0], p[1], p[2]) else null
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun writeStatus(playlistId: Long, channels: List<EpgChannel>, visible: Set<Long>, covered: Set<Long>, counts: List<Pair<String, Int>>) {
        val shown = channels.filter { it.id in visible }
        val missing = shown.filter { it.id !in covered }.distinctBy { it.groupTitle.trim() to it.name.trim() }
        runCatching {
            statusDir.mkdirs()
            File(statusDir, "epg-$playlistId.txt").writeText(buildString {
                append("${shown.count { it.id in covered }}\t${shown.size}\n")
                for ((label, count) in counts) append("S\t${label.replace('\t', ' ')}\t$count\n")
                for (c in missing.take(MAX_MISSING_LISTED)) append("M\t${c.groupTitle.trim().clean()}\t${c.name.trim().clean()}\t${c.id}\n")
            })
        }
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
        /** Hier eintragen, wessen Kennung nicht zum Sendernamen passt (null = nicht prüfen). */
        suspects: MutableSet<Long>? = null,
        /** Von Hand zugeordnete Sender: nur über genau ihre Kennung, nie über den Namen. */
        forced: Set<Long> = emptySet(),
        guideIndex: MutableList<String>? = null,
        sourceLabel: String = "",
        /** Zweiter Durchgang: nur diese EPG-Sender (die mit Sendungen) zuordnen. */
        onlyGuides: Set<String>? = null,
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
        // Welchem EPG-Sender jeder Sender zugeordnet wurde, und welche EPG-Sender im Zeitraum Sendungen haben.
        val matchedGuide = HashMap<Long, String>()
        val withProgrammes = HashSet<String>()
        // Zeitversatz-Sender ohne eigenes Programm („Sky Cinema Premieren +24“, „RTL +1“): das Programm des Hauptsenders,
        // verschoben. EPG-Sender → (Kennung, unter der gespeichert wird; Versatz in ms).
        val shiftedTargets = HashMap<String, MutableList<Pair<String, Long>>>()
        val byLowerId = open.filter { !it.epgId.isNullOrBlank() }.groupBy { it.epgId!!.trim().lowercase() }
        val unknown = HashSet<String>()
        fun storageFor(id: String): List<String>? {
            if (!prepared) {
                prepared = true
                val matcher = EpgMatcher()
                for ((guideId, names) in parser.channels) {
                    if (onlyGuides != null && guideId !in onlyGuides) continue
                    matcher.addGuideChannel(guideId, names)
                    guideIndex?.add("${guideId.clean()}\t${(names.firstOrNull() ?: guideId).clean()}\t${sourceLabel.clean()}")
                }
                if (!matcher.isEmpty) for (channel in open) {
                    val guideId = matcher.match(channel.epgId, channel.name)
                    if (guideId == null) {
                        val (hours, base) = EpgMatcher.timeShift(channel.name) ?: continue
                        if (channel.id in forced) continue
                        val baseId = matcher.match(null, base) ?: continue
                        val storage = channel.epgId?.takeIf { it.isNotBlank() } ?: "$baseId+${hours}h"
                        if (storage in taken || storage in owner) continue
                        shiftedTargets.getOrPut(baseId) { ArrayList(1) } += storage to hours * HOUR
                        channelsOf.getOrPut(storage) { ArrayList(1) } += channel.id
                        if (channel.epgId.isNullOrBlank()) assign += channel.id to storage
                        matchedGuide[channel.id] = baseId
                        continue
                    }
                    if (channel.id in forced && guideId != channel.epgId) continue
                    matchedGuide[channel.id] = guideId
                    link(guideId, channel)
                    if (suspects != null && channel.id !in forced && !channel.epgId.isNullOrBlank() && !matcher.fitsName(guideId, channel.name)) {
                        Log.i(TAG, "Kennung „${channel.epgId}“ passt nicht zu „${channel.name}“ (EPG-Sender $guideId)")
                        suspects += channel.id
                    }
                }
            }
            targets[id]?.let { return it }
            if (id in unknown) return null
            if (onlyGuides != null) return null.also { unknown += id }
            // Programme eines Senders ohne <channel>-Eintrag: nur über die Kennung.
            byLowerId[id.lowercase()]?.forEach { link(id, it) }
            return targets[id] ?: null.also { unknown += id }
        }

        val batch = ArrayList<ProgrammeEntity>(BATCH)
        var count = 0
        openFile(source).use { input ->
            parser.parse(
                input,
                wanted = { id, start, stop ->
                    val inRange = stop + shift > from && start + shift < until
                    if (inRange) withProgrammes += id
                    // Zuerst zuordnen (auch für die Zeitversatz-Sender, deren Sendungen außerhalb des Zeitraums liegen können).
                    val own = storageFor(id)
                    (inRange && own != null) ||
                        shiftedTargets[id]?.any { (_, offset) -> stop + shift + offset > from && start + shift + offset < until } == true
                },
            ) { p ->
                fun emit(storage: String, offset: Long) {
                    batch += ProgrammeEntity(
                        playlistId = playlist.id,
                        channelEpgId = storage,
                        start = p.start + shift + offset,
                        stop = p.stop + shift + offset,
                        title = p.title,
                        description = p.description,
                        category = p.category,
                        subtitle = p.subtitle,
                        icon = p.icon,
                        flags = p.flags,
                    )
                    channelsOf[storage]?.let(found::addAll)
                    used += storage
                }
                if (p.stop + shift > from && p.start + shift < until) {
                    for (storage in storageFor(p.channelId).orEmpty()) emit(storage, 0L)
                }
                shiftedTargets[p.channelId]?.forEach { (storage, offset) ->
                    if (p.stop + shift + offset > from && p.start + shift + offset < until) emit(storage, offset)
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
        // Einem leeren EPG-Eintrag zugeordnet (z. B. „sky.replay.de“ ohne Sendungen neben „Sky Replay HD“ mit
        // Programm): noch einmal nur unter den Einträgen mit Sendungen suchen.
        if (onlyGuides == null) {
            val retry = open.filter { it.id !in found && it.id !in forced && matchedGuide[it.id]?.let { g -> g !in withProgrammes } == true }
            if (retry.isNotEmpty() && withProgrammes.isNotEmpty()) {
                importEpgSource(
                    playlist, source, retry, shift, taken + used, found, used, null, forced, null, sourceLabel,
                    onlyGuides = withProgrammes.toHashSet(),
                )
            }
        }
    }

    private fun <T> ArrayList<T>.drain(): List<T> = toList().also { clear() }

    /**
     * Lädt eine Adresse vollständig in eine Datei (bis zu 3 Versuche mit Pause). So bricht ein kurzer
     * Netzaussetzer nicht den ganzen Import ab, und die Datenbank-Transaktion hält keine Verbindung offen.
     * Lokale Pfade werden direkt verwendet.
     */
    private fun fetch(url: String, name: String, progress: String? = null): File {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return File(url.removePrefix("file://"))
        importDir.mkdirs()
        val target = File(importDir, name)
        var wait = 2_000L
        repeat(3) { attempt ->
            try {
                importHttp.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} beim Laden von ${url.substringBefore('?')}")
                    target.outputStream().use { out ->
                        val input = response.body.byteStream()
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            total += n
                            // Fortschritt zeigen: Große Listen brauchen Minuten, so sieht man, dass es weitergeht.
                            if (progress != null && total - shown >= 1_000_000) {
                                shown = total
                                _state.value = SyncState.Running("$progress … ${"%.1f".format(java.util.Locale.GERMANY, total / 1e6)} MB")
                            }
                        }
                    }
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

    /** Freie Quelle aus dem Zwischenspeicher, wenn jünger als [maxAge], sonst neu laden (alte Datei bleibt bei Fehlern). */
    private fun fetchCached(url: String, maxAge: Long): File {
        publicDir.mkdirs()
        // Reste der früheren Quellen MagentaTV und Dyn (bis 0.45.2) wegräumen.
        for (old in listOf("magentatv-guide.xml", "dyn-guide.xml")) File(publicDir, old).takeIf { it.exists() }?.delete()
        val target = File(publicDir, de.ayutv.data.parse.EpgSources.cacheName(url))
        if (target.length() > 0 && System.currentTimeMillis() - target.lastModified() < maxAge) return target
        val downloaded = try {
            fetch(url, "public-" + target.name)
        } catch (e: IOException) {
            if (target.length() > 0) return target
            throw e
        }
        if (!downloaded.renameTo(target)) {
            downloaded.copyTo(target, overwrite = true)
            downloaded.delete()
        }
        return target
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
        private const val EPG_DAYS = 7L
        private const val MAX_ARCHIVE_DAYS = 7
        private const val CONTENT_MAX_AGE = 12 * HOUR
        private const val EPG_MAX_AGE = 8 * HOUR
        private const val PUBLIC_MAX_AGE = 20 * HOUR
        private const val MAX_MISSING_LISTED = 20_000
        private fun String.clean() = replace('\t', ' ').replace('\n', ' ')
        /** Erhöhen, wenn sich die Senderzuordnung verbessert: Dann wird nach dem Update neu zugeordnet. */
        private const val EPG_MATCH_VERSION = "7"
    }
}
