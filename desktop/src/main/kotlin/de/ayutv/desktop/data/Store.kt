package de.ayutv.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Alles, was die Windows-App weiß, im Speicher (ein PC hat dafür genug Platz) und als Dateien im Datenordner:
 * Playlists, ihre Sender/Filme/Serien, der TV-Guide und was der Nutzer festlegt. Kein Datenbank-Server, nichts zu
 * installieren; die Dateien überstehen Updates.
 */
class Store(val dir: File) {
    @Serializable
    data class Content(
        val channels: List<Channel> = emptyList(),
        val movies: List<Movie> = emptyList(),
        val series: List<Series> = emptyList(),
    )

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _content = MutableStateFlow<Map<Long, Content>>(emptyMap())
    val content: StateFlow<Map<Long, Content>> = _content.asStateFlow()

    /** TV-Guide: Senderschlüssel → Sendungen, nach Beginn sortiert. */
    private val _guide = MutableStateFlow<Map<String, List<Programme>>>(emptyMap())
    val guide: StateFlow<Map<String, List<Programme>>> = _guide.asStateFlow()

    private val _user = MutableStateFlow(UserData())
    val user: StateFlow<UserData> = _user.asStateFlow()

    init {
        dir.mkdirs()
        load()
    }

    // ---------------------------------------------------------------- Lesen

    /** Sender aller eingeschalteten Playlists. */
    fun channels(): List<Channel> {
        val enabled = _playlists.value.filter { it.enabled }.map { it.id }.toSet()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.channels }
    }

    fun movies(): List<Movie> {
        val enabled = _playlists.value.filter { it.enabled }.map { it.id }.toSet()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.movies }
    }

    fun series(): List<Series> {
        val enabled = _playlists.value.filter { it.enabled }.map { it.id }.toSet()
        return _content.value.filterKeys { it in enabled }.values.flatMap { it.series }
    }

    fun channel(key: String): Channel? = _content.value.values.firstNotNullOfOrNull { c -> c.channels.firstOrNull { it.key == key } }

    /** Gruppen des Anbieters (Live), die nicht ausgeblendet sind, mit Anzahl, in der Reihenfolge der Playlist. */
    fun liveGroups(): List<Pair<String, Int>> {
        val hidden = _user.value.hiddenGroups
        return channels().filter { "${it.playlistId}|${it.group}" !in hidden }
            .groupingBy { it.group }.eachCount().toList()
    }

    /** Sender einer Gruppe des Anbieters, in eigener Reihenfolge (falls sortiert), sonst wie beim Anbieter. */
    fun inGroup(group: String): List<Channel> {
        val hidden = _user.value.hiddenGroups
        val list = channels().filter { it.group == group && "${it.playlistId}|${it.group}" !in hidden }
        return Ordering.apply(list, _user.value.groupOrder[group].orEmpty()) { it.key }
    }

    /** Alle sichtbaren Sender (ohne ausgeblendete Gruppen). */
    fun allVisible(): List<Channel> {
        val hidden = _user.value.hiddenGroups
        return channels().filter { "${it.playlistId}|${it.group}" !in hidden }
    }

    /** Favoriten in ihrer Reihenfolge; auch Sender aus ausgeblendeten Gruppen. */
    fun favorites(): List<Channel> = byKeys(_user.value.favorites)

    fun inCustom(id: Long): List<Channel> = byKeys(_user.value.customGroups.firstOrNull { it.id == id }?.keys.orEmpty())

    private fun byKeys(keys: List<String>): List<Channel> {
        val all = channels().associateBy { it.key }
        return keys.mapNotNull { all[it] }
    }

    fun nowPlaying(channel: Channel, at: Long = System.currentTimeMillis()): Programme? =
        _guide.value[channel.key]?.firstOrNull { it.start <= at && it.stop > at }

    fun upcoming(channel: Channel, count: Int, at: Long = System.currentTimeMillis()): List<Programme> =
        _guide.value[channel.key].orEmpty().filter { it.stop > at }.take(count)

    fun programmes(channel: Channel): List<Programme> = _guide.value[channel.key].orEmpty()

    // ---------------------------------------------------------------- Ändern (Nutzer)

    fun updateUser(transform: (UserData) -> UserData) {
        _user.update(transform)
        save(USER, UserData.serializer(), _user.value)
    }

    fun toggleFavorite(key: String): Boolean {
        var now = false
        updateUser { u ->
            now = key !in u.favorites
            u.copy(favorites = if (now) u.favorites + key else u.favorites - key)
        }
        return now
    }

    fun addFavorites(keys: List<String>): Int {
        var added = 0
        updateUser { u ->
            val fresh = keys.filter { it !in u.favorites }.distinct()
            added = fresh.size
            u.copy(favorites = u.favorites + fresh)
        }
        return added
    }

    fun createGroup(name: String, keys: List<String> = emptyList()): Long {
        val id = (_user.value.customGroups.maxOfOrNull { it.id } ?: 0) + 1
        updateUser { it.copy(customGroups = it.customGroups + CustomGroup(id, name.trim(), keys.distinct())) }
        return id
    }

    fun renameGroup(id: Long, name: String) = updateUser { u ->
        u.copy(customGroups = u.customGroups.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    fun deleteGroup(id: Long) = updateUser { u -> u.copy(customGroups = u.customGroups.filterNot { it.id == id }) }

    fun moveGroup(id: Long, delta: Int) = updateUser { u ->
        val list = u.customGroups.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from >= 0 && from != to) list.add(to, list.removeAt(from))
        u.copy(customGroups = list)
    }

    /** Sender in eine eigene Gruppe aufnehmen (schon enthaltene bleiben). Liefert die Anzahl neuer. */
    fun addToGroup(id: Long, keys: List<String>): Int {
        var added = 0
        updateUser { u ->
            u.copy(customGroups = u.customGroups.map { g ->
                if (g.id != id) g else {
                    val fresh = keys.filter { it !in g.keys }.distinct()
                    added = fresh.size
                    g.copy(keys = g.keys + fresh)
                }
            })
        }
        return added
    }

    fun removeFromGroup(id: Long, keys: Collection<String>) = updateUser { u ->
        u.copy(customGroups = u.customGroups.map { g -> if (g.id == id) g.copy(keys = g.keys - keys.toSet()) else g })
    }

    fun removeFavorites(keys: Collection<String>) = updateUser { u -> u.copy(favorites = u.favorites - keys.toSet()) }

    /** Von Hand sortierte Reihenfolge speichern. */
    fun saveOrder(list: GroupRef, keys: List<String>) = updateUser { u ->
        when (list) {
            GroupRef.Favorites -> u.copy(favorites = Ordering.reorder(u.favorites, keys))
            is GroupRef.Custom -> u.copy(customGroups = u.customGroups.map { g -> if (g.id == list.id) g.copy(keys = Ordering.reorder(g.keys, keys)) else g })
            is GroupRef.Provider -> u.copy(groupOrder = u.groupOrder + (list.name to keys))
            GroupRef.All -> u
        }
    }

    fun hideGroup(name: String) = updateUser { u ->
        val pairs = channels().filter { it.group == name }.map { "${it.playlistId}|$name" }.toSet()
        u.copy(hiddenGroups = u.hiddenGroups + pairs)
    }

    fun showAllGroups() = updateUser { it.copy(hiddenGroups = emptySet()) }

    fun rememberWatched(key: String) = updateUser { u -> u.copy(recent = (listOf(key) + u.recent.filter { it != key }).take(30)) }

    fun savePosition(key: String, positionMs: Long, durationMs: Long) = updateUser { u ->
        u.copy(positions = u.positions + (key to positionMs), durations = if (durationMs > 0) u.durations + (key to durationMs) else u.durations)
    }

    // ---------------------------------------------------------------- Ändern (Playlists, Inhalte)

    fun nextPlaylistId(): Long = (_playlists.value.maxOfOrNull { it.id } ?: 0) + 1

    fun savePlaylist(playlist: Playlist) {
        _playlists.update { list -> if (list.any { it.id == playlist.id }) list.map { if (it.id == playlist.id) playlist else it } else list + playlist }
        save(PLAYLISTS, ListSerializer(Playlist.serializer()), _playlists.value)
    }

    fun deletePlaylist(id: Long) {
        _playlists.update { list -> list.filterNot { it.id == id } }
        _content.update { it - id }
        save(PLAYLISTS, ListSerializer(Playlist.serializer()), _playlists.value)
        File(dir, "content-$id.json.gz").delete()
    }

    fun setContent(playlistId: Long, content: Content) {
        _content.update { it + (playlistId to content) }
        save("content-$playlistId.json.gz", Content.serializer(), content)
    }

    fun setGuide(guide: Map<String, List<Programme>>) {
        _guide.value = guide
        save(GUIDE, MapSerializer(String.serializer(), ListSerializer(Programme.serializer())), guide)
    }

    // ---------------------------------------------------------------- Dateien

    private fun load() {
        _playlists.value = read(PLAYLISTS, ListSerializer(Playlist.serializer())) ?: emptyList()
        _user.value = read(USER, UserData.serializer()) ?: UserData()
        _content.value = _playlists.value.mapNotNull { p -> read("content-${p.id}.json.gz", Content.serializer())?.let { p.id to it } }.toMap()
        // Vergangenes nicht wieder laden.
        val now = System.currentTimeMillis()
        _guide.value = read(GUIDE, MapSerializer(String.serializer(), ListSerializer(Programme.serializer())))
            ?.mapValues { (_, list) -> list.filter { it.stop > now - DAY } }.orEmpty()
    }

    private fun <T> read(name: String, serializer: KSerializer<T>): T? = runCatching {
        val file = File(dir, name)
        if (!file.isFile) return null
        val text = if (name.endsWith(".gz")) GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } else file.readText()
        JSON.decodeFromString(serializer, text)
    }.getOrNull()

    @Synchronized
    private fun <T> save(name: String, serializer: KSerializer<T>, value: T) {
        val target = File(dir, name)
        val temp = File(dir, "$name.tmp")
        val text = JSON.encodeToString(serializer, value)
        if (name.endsWith(".gz")) GZIPOutputStream(temp.outputStream()).bufferedWriter().use { it.write(text) } else temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    companion object {
        private const val PLAYLISTS = "playlists.json"
        private const val USER = "user.json"
        private const val GUIDE = "guide.json.gz"
        private const val DAY = 24 * 3_600_000L
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        /** Datenordner: unter Windows %APPDATA%\DnA-TV, sonst ~/.dna-tv. */
        fun defaultDir(): File =
            System.getenv("APPDATA")?.let { File(it, "DnA-TV") } ?: File(System.getProperty("user.home"), ".dna-tv")
    }
}

/** Welche Liste gerade gezeigt wird (für Sortieren und Zappen). */
sealed interface GroupRef {
    data object All : GroupRef
    data object Favorites : GroupRef
    data class Custom(val id: Long) : GroupRef
    data class Provider(val name: String) : GroupRef
}

/** Reihenfolgen: von Hand sortierte Schlüssel zuerst, der Rest wie gehabt dahinter. */
object Ordering {
    fun <T> apply(list: List<T>, order: List<String>, keyOf: (T) -> String): List<T> {
        if (order.isEmpty()) return list
        val position = order.withIndex().associate { (i, k) -> k to i }
        return list.withIndex().sortedWith(compareBy({ position[keyOf(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }

    /** [current] (alle Schlüssel) so umstellen, dass die in [shown] genannten in deren Reihenfolge stehen. */
    fun reorder(current: List<String>, shown: List<String>): List<String> {
        val shownSet = shown.toSet()
        val rest = current.filterNot { it in shownSet }
        return shown.filter { it in current } + rest
    }
}
