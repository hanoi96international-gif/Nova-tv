package de.ayutv.data.remote

/**
 * Abgleich zwischen Geräten (Handy ↔ Fernseher), ohne Android: Playlists einander zuordnen und entscheiden,
 * welche Einträge übernommen werden. Schlüssel beginnen mit der Playlist-Nummer des jeweiligen Geräts.
 */
object SyncMerge {
    data class PlaylistRef(val id: Long, val type: String, val url: String, val username: String?)

    /** Nummern der anderen Seite → eigene Nummern, für Playlists mit gleichem Anbieter und Zugang. */
    fun idMap(remote: List<PlaylistRef>, local: List<PlaylistRef>): Map<Long, Long> {
        val byIdentity = local.associateBy { identity(it) }
        return remote.mapNotNull { r -> byIdentity[identity(r)]?.let { r.id to it.id } }.toMap()
    }

    private fun identity(p: PlaylistRef) = Triple(p.type, p.url.trim().trimEnd('/').lowercase(), p.username?.trim().orEmpty())

    /** Schlüssel der anderen Seite auf die eigenen Playlist-Nummern umschreiben; null, wenn es die Playlist hier nicht gibt. */
    fun remapKey(key: String, ids: Map<Long, Long>): String? {
        val old = key.substringBefore(':').toLongOrNull() ?: return null
        val new = ids[old] ?: return null
        return "$new:" + key.substringAfter(':')
    }

    /** Einträge von [incoming], die neuer sind als die eigenen (nach Zeitpunkt [time]). */
    fun <T> newer(local: Map<String, Long>, incoming: List<T>, key: (T) -> String, time: (T) -> Long): List<T> =
        incoming.filter { item -> time(item) > (local[key(item)] ?: Long.MIN_VALUE) }
}
