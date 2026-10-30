package de.ayutv.desktop.data

import kotlinx.serialization.Serializable

/** Art einer Playlist: M3U-Adresse oder Xtream-Codes-Zugang. */
enum class PlaylistType { M3U, XTREAM }

@Serializable
data class Playlist(
    val id: Long,
    val name: String,
    val type: PlaylistType,
    val url: String,
    val username: String? = null,
    val password: String? = null,
    val epgUrl: String? = null,
    val enabled: Boolean = true,
    val lastSync: Long = 0,
)

/**
 * Sender. Die Schlüssel sind dieselben wie in der Fire-TV-App („<Playlist>:c:<Stream>“ bzw. bei M3U
 * „<Playlist>:c:<Kennung oder Name>:<Adresse>“), damit Favoriten und Gruppen aus einer Sicherung passen.
 */
@Serializable
data class Channel(
    val key: String,
    val playlistId: Long,
    val name: String,
    val group: String,
    val logo: String? = null,
    val url: String,
    val epgId: String? = null,
    val number: Int = 0,
    val catchupDays: Int = 0,
)

@Serializable
data class Movie(
    val key: String,
    val playlistId: Long,
    val name: String,
    val category: String,
    val poster: String? = null,
    val rating: String? = null,
    val url: String,
    val added: Long = 0,
)

@Serializable
data class Series(
    val key: String,
    val playlistId: Long,
    val seriesId: String,
    val name: String,
    val category: String,
    val cover: String? = null,
    val plot: String? = null,
    val rating: String? = null,
    val added: Long = 0,
)

@Serializable
data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val category: String? = null,
)

/** Eigene Sendergruppe (Sender aus allen Playlists, in eigener Reihenfolge). */
@Serializable
data class CustomGroup(val id: Long, val name: String, val keys: List<String> = emptyList())

/** Was der Nutzer festlegt: Favoriten, eigene Gruppen, Reihenfolgen, ausgeblendete Gruppen, Einstellungen. */
@Serializable
data class UserData(
    val favorites: List<String> = emptyList(),
    val customGroups: List<CustomGroup> = emptyList(),
    /** Eigene Reihenfolge in Gruppen des Anbieters: Gruppenname → Senderschlüssel. */
    val groupOrder: Map<String, List<String>> = emptyMap(),
    /** Ausgeblendete Gruppen des Anbieters (Live), je Playlist: "<Playlist>|<Gruppe>". */
    val hiddenGroups: Set<String> = emptySet(),
    /** Zuletzt geschaute Sender (neuester zuerst). */
    val recent: List<String> = emptyList(),
    /** Wiedergabestand von Filmen und Folgen: Schlüssel → Position (ms). */
    val positions: Map<String, Long> = emptyMap(),
    /** Länge von Filmen und Folgen (ms), für „gesehen“ und Fortschrittsbalken. */
    val durations: Map<String, Long> = emptyMap(),
    val openSubtitlesKey: String = "",
    val deeplKey: String = "",
    val subtitleLanguage: String = "de",
)
