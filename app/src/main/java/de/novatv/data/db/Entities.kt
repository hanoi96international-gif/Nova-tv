package de.novatv.data.db

import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class PlaylistType { M3U, XTREAM }

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: PlaylistType,
    /** M3U: Playlist-URL. Xtream: Server-Basis-URL. */
    val url: String,
    val epgUrl: String? = null,
    val username: String? = null,
    val password: String? = null,
    val lastSync: Long = 0,
    val lastEpgSync: Long = 0,
)

/**
 * [key] ist über Synchronisierungen hinweg stabil (Playlist + Stream-ID bzw. URL), damit Favoriten
 * und Verlauf erhalten bleiben, obwohl die Zeilen bei jedem Sync neu geschrieben werden.
 */
@Entity(
    tableName = "channels",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("groupTitle"), Index("epgId")],
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val name: String,
    val groupTitle: String,
    val logo: String?,
    val url: String,
    val epgId: String?,
    val number: Int,
    val catchupDays: Int = 0,
    val userAgent: String? = null,
)

@Entity(
    tableName = "programmes",
    indices = [Index(value = ["channelEpgId", "start"]), Index("playlistId"), Index("stop")],
)
data class ProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val channelEpgId: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
    val category: String?,
)

@Entity(
    tableName = "vod",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("category"), Index("added")],
)
data class VodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val name: String,
    val category: String,
    val poster: String?,
    val rating: String?,
    val url: String,
    val added: Long,
)

@Entity(
    tableName = "series",
    indices = [Index(value = ["key"], unique = true), Index("playlistId"), Index("category"), Index("added")],
)
data class SeriesEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val category: String,
    val cover: String?,
    val plot: String?,
    val rating: String?,
    val added: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val key: String,
    val addedAt: Long,
)

enum class HistoryKind { LIVE, VOD, EPISODE }

@Entity(tableName = "history", indices = [Index("watchedAt")])
data class HistoryEntity(
    @PrimaryKey val key: String,
    val kind: HistoryKind,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val groupTitle: String?,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playCount: Int = 1,
    val watchedAt: Long,
)

data class GroupCount(val name: String, val count: Int)

enum class GroupKind { LIVE, VOD, SERIES }

/**
 * Vom Nutzer ausgeblendete Gruppe einer Playlist. Liegt bewusst in einer eigenen Tabelle:
 * Beim Sync werden Sender, Filme und Serien neu geschrieben, die Auswahl bleibt trotzdem erhalten.
 */
@Entity(tableName = "hidden_groups", primaryKeys = ["playlistId", "kind", "name"])
data class HiddenGroupEntity(
    val playlistId: Long,
    val kind: GroupKind,
    val name: String,
)

/** Gruppe mit Anzahl und Sichtbarkeit für die Gruppenverwaltung. */
data class GroupToggle(val name: String, val count: Int, val hidden: Boolean)

// Alle Listen der Oberfläche lesen aus diesen Views. Ausgeblendete Gruppen tauchen so nirgends auf
// (Listen, Suche, TV-Guide, Empfehlungen, Zapping), ohne dass jede Abfrage daran denken muss.
@DatabaseView(
    viewName = "visible_channels",
    value = """SELECT * FROM channels c WHERE NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = c.playlistId AND h.kind = 'LIVE' AND h.name = c.groupTitle)""",
)
data class VisibleChannelView(val id: Long)

@DatabaseView(
    viewName = "visible_vod",
    value = """SELECT * FROM vod v WHERE NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = v.playlistId AND h.kind = 'VOD' AND h.name = v.category)""",
)
data class VisibleVodView(val id: Long)

@DatabaseView(
    viewName = "visible_series",
    value = """SELECT * FROM series s WHERE NOT EXISTS (SELECT 1 FROM hidden_groups h
               WHERE h.playlistId = s.playlistId AND h.kind = 'SERIES' AND h.name = s.category)""",
)
data class VisibleSeriesView(val id: Long)
