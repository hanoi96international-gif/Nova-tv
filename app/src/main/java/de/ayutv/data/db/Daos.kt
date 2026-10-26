package de.ayutv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET password = :password WHERE id = :id")
    suspend fun setPassword(id: Long, password: String?)

    @Query("UPDATE playlists SET lastEpgSync = :time WHERE id = :id")
    suspend fun setLastEpgSync(id: Long, time: Long)

    @Query("UPDATE playlists SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM playlists WHERE id = :id")
    fun delete(id: Long)
}

@Dao
interface ChannelDao {
    // Blockierende Varianten für den Import, der komplett in runInTransaction läuft.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT groupTitle AS name, COUNT(*) AS count FROM visible_channels GROUP BY groupTitle ORDER BY MIN(id)")
    fun observeGroups(): Flow<List<GroupCount>>

    @Query("SELECT * FROM visible_channels WHERE groupTitle = :group ORDER BY playlistId, number, id")
    fun observeInGroup(group: String): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM visible_channels ORDER BY playlistId, number, id")
    fun observeAll(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM visible_channels WHERE groupTitle = :group ORDER BY playlistId, number, id")
    suspend fun inGroup(group: String): List<ChannelEntity>

    @Query("SELECT * FROM visible_channels ORDER BY playlistId, number, id")
    suspend fun all(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE `key` = :key")
    suspend fun byKey(key: String): ChannelEntity?

    @Query("SELECT * FROM visible_channels WHERE number = :number ORDER BY id LIMIT 1")
    suspend fun byNumber(number: Int): ChannelEntity?

    @Query("SELECT c.* FROM visible_channels c INNER JOIN favorites f ON f.`key` = c.`key` ORDER BY f.addedAt")
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query("SELECT c.* FROM visible_channels c INNER JOIN favorites f ON f.`key` = c.`key` ORDER BY f.addedAt")
    suspend fun favorites(): List<ChannelEntity>

    @Query(
        """SELECT * FROM visible_channels WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, length(name), number
           LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM visible_channels WHERE groupTitle IN (:groups) AND `key` NOT IN (:exclude) ORDER BY playlistId, number, id LIMIT :limit")
    suspend fun inGroupsExcluding(groups: List<String>, exclude: List<String>, limit: Int): List<ChannelEntity>

    @Query("SELECT * FROM visible_channels WHERE epgId IN (:epgIds) GROUP BY epgId")
    suspend fun byEpgIds(epgIds: List<String>): List<ChannelEntity>

    /** Alle Sender (auch alle Fassungen) mit diesen EPG-Kennungen. */
    @Query("SELECT * FROM visible_channels WHERE epgId IN (:epgIds)")
    suspend fun allByEpgIds(epgIds: List<String>): List<ChannelEntity>

    @Query("SELECT id, name, epgId, groupTitle FROM channels WHERE playlistId = :playlistId ORDER BY number, id")
    suspend fun forEpg(playlistId: Long): List<EpgChannel>

    @Query("SELECT * FROM visible_channels WHERE playlistId = :playlistId")
    suspend fun visibleInPlaylist(playlistId: Long): List<ChannelEntity>

    /** Sender, die irgendwo erscheinen (ohne ausgeblendete Gruppen). */
    @Query("SELECT id FROM visible_channels WHERE playlistId = :playlistId")
    suspend fun visibleIds(playlistId: Long): List<Long>

    @Query("UPDATE channels SET epgId = :epgId WHERE id = :id")
    fun setEpgId(id: Long, epgId: String)

    @Query("SELECT id, `key` FROM channels WHERE playlistId = :playlistId")
    suspend fun keysFor(playlistId: Long): List<ChannelKeyId>

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM channels WHERE playlistId = :playlistId")
    fun maxCatchupDays(playlistId: Long): Int

    @Query("SELECT COALESCE(MAX(catchupDays), 0) FROM visible_channels")
    suspend fun maxCatchupDaysAll(): Int


    @Query("SELECT COUNT(*) FROM visible_channels")
    fun observeCount(): Flow<Int>
}

@Dao
interface ProgrammeDao {
    @Insert
    fun insertAll(programmes: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query(
        """SELECT * FROM programmes WHERE channelEpgId IN (:epgIds) AND stop > :from AND start < :to
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start"""
    )
    suspend fun inRange(epgIds: List<String>, from: Long, to: Long): List<ProgrammeEntity>

    /** Alles, was gerade läuft: eine Abfrage statt vieler IN-Listen bei großen Senderlisten. */
    @Query("SELECT * FROM programmes WHERE stop > :now AND start <= :now AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1)")
    suspend fun airingAt(now: Long): List<ProgrammeEntity>

    /** Mögliche Sportsendungen im Zeitraum auf sichtbaren Sendern (grob; genau prüft ProgrammeText.isSportEvent). */
    @Query(
        """SELECT * FROM programmes WHERE stop > :from AND start < :to AND (
           category LIKE '%ball%' OR category LIKE '%sport%' OR category LIKE '%tennis%' OR category LIKE '%darts%'
           OR category LIKE '%hockey%' OR category LIKE '%formel%' OR category LIKE '%box%' OR category LIKE '%golf%'
           OR title LIKE '%ball%' OR title LIKE '%liga%' OR title LIKE '%league%' OR title LIKE '%sport%' OR title LIKE '%live%'
           OR title LIKE '%pokal%' OR title LIKE '%tennis%' OR title LIKE '%darts%' OR title LIKE '%formel%' OR title LIKE '%NFL%'
           OR title LIKE '%NBA%' OR title LIKE '%NHL%' OR title LIKE '%UFC%' OR title LIKE '%box%' OR subtitle LIKE '% – %'
           OR subtitle LIKE '% - %')
           AND channelEpgId IN (SELECT epgId FROM visible_channels WHERE epgId IS NOT NULL AND epgId != '')
           ORDER BY start LIMIT :limit"""
    )
    suspend fun sportBetween(from: Long, to: Long, limit: Int): List<ProgrammeEntity>

    @Query(
        """SELECT * FROM programmes WHERE channelEpgId = :epgId AND stop > :from
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start LIMIT :limit"""
    )
    suspend fun upcoming(epgId: String, from: Long, limit: Int): List<ProgrammeEntity>

    @Query("""SELECT * FROM programmes WHERE (title LIKE '%' || :query || '%' OR subtitle LIKE '%' || :query || '%') AND stop > :now
           AND playlistId IN (SELECT id FROM playlists WHERE enabled = 1) ORDER BY start LIMIT :limit""")
    suspend fun search(query: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT COUNT(*) FROM programmes")
    fun observeCount(): Flow<Int>
}

@Dao
interface VodDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<VodEntity>)

    @Query("DELETE FROM vod WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT category AS name, COUNT(*) AS count FROM visible_vod GROUP BY category ORDER BY MIN(id)")
    fun observeCategories(): Flow<List<GroupCount>>

    @Query("SELECT * FROM visible_vod WHERE category = :category ORDER BY id")
    fun observeInCategory(category: String): Flow<List<VodEntity>>

    @Query("SELECT * FROM visible_vod ORDER BY added DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<VodEntity>>

    @Query(
        """SELECT * FROM visible_vod WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<VodEntity>
}

@Dao
interface SeriesDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAll(items: List<SeriesEntity>)

    @Query("DELETE FROM series WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT category AS name, COUNT(*) AS count FROM visible_series GROUP BY category ORDER BY MIN(id)")
    fun observeCategories(): Flow<List<GroupCount>>

    @Query("SELECT * FROM visible_series WHERE category = :category ORDER BY id")
    fun observeInCategory(category: String): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM visible_series ORDER BY added DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series WHERE `key` = :key")
    suspend fun byKey(key: String): SeriesEntity?

    @Query(
        """SELECT * FROM visible_series WHERE name LIKE '%' || :query || '%'
           ORDER BY CASE WHEN name LIKE :query || '%' THEN 0 ELSE 1 END, added DESC LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<SeriesEntity>
}

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("SELECT `key` FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Query("SELECT `key` FROM favorites")
    suspend fun keys(): List<String>

    @Query("SELECT * FROM favorites")
    suspend fun all(): List<FavoriteEntity>

    @Query("DELETE FROM favorites")
    suspend fun clear()

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE `key` = :key)")
    suspend fun isFavorite(key: String): Boolean
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun upsert(entry: HistoryEntity)

    @Query("SELECT * FROM history WHERE `key` = :key")
    suspend fun byKey(key: String): HistoryEntity?

    @Query("SELECT * FROM history WHERE kind = 'LIVE' ORDER BY watchedAt DESC LIMIT :limit")
    fun observeRecentLive(limit: Int): Flow<List<HistoryEntity>>

    /** Angefangen, aber nicht zu Ende geschaut (weniger als 95 %). */
    @Query(
        """SELECT * FROM history WHERE kind != 'LIVE' AND positionMs > 60000
           AND (durationMs = 0 OR positionMs < durationMs * 0.95)
           ORDER BY watchedAt DESC LIMIT :limit"""
    )
    fun observeContinueWatching(limit: Int): Flow<List<HistoryEntity>>

    @Query(
        """SELECT groupTitle FROM history WHERE kind = 'LIVE' AND groupTitle IS NOT NULL AND groupTitle != ''
           GROUP BY groupTitle ORDER BY SUM(playCount) DESC LIMIT :limit"""
    )
    suspend fun topLiveGroups(limit: Int): List<String>

    @Query("SELECT `key` FROM history WHERE kind = 'LIVE' ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recentLiveKeys(limit: Int): List<String>

    /** Verlauf aller Folgen einer Serie (Schlüssel „<Serie>:e:…“): gesehen, angefangen. */
    @Query("SELECT * FROM history WHERE `key` LIKE :prefix || '%'")
    fun observeWithPrefix(prefix: String): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE `key` = :key")
    fun observeByKey(key: String): Flow<HistoryEntity?>

    /** Für den Abgleich mit anderen Geräten: die zuletzt gesehenen Einträge. */
    @Query("SELECT * FROM history ORDER BY watchedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface HiddenGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hide(groups: List<HiddenGroupEntity>)

    @Query("DELETE FROM hidden_groups WHERE playlistId = :playlistId AND kind = :kind AND name IN (:names)")
    suspend fun show(playlistId: Long, kind: GroupKind, names: List<String>)

    @Query("DELETE FROM hidden_groups WHERE playlistId = :playlistId")
    fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT * FROM hidden_groups")
    suspend fun all(): List<HiddenGroupEntity>

    /** Blendet eine Gruppe in allen Playlists aus, die sie enthalten (Schnellaktion in den Listen). */
    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'LIVE', groupTitle FROM channels WHERE groupTitle = :name"""
    )
    suspend fun hideLiveEverywhere(name: String)

    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'VOD', category FROM vod WHERE category = :name"""
    )
    suspend fun hideVodEverywhere(name: String)

    @Query(
        """INSERT OR IGNORE INTO hidden_groups (playlistId, kind, name)
           SELECT DISTINCT playlistId, 'SERIES', category FROM series WHERE category = :name"""
    )
    suspend fun hideSeriesEverywhere(name: String)

    @Query(
        """SELECT c.groupTitle AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'LIVE' AND h.name = c.groupTitle) AS hidden
           FROM channels c WHERE c.playlistId = :playlistId GROUP BY c.groupTitle ORDER BY MIN(c.id)"""
    )
    fun observeLive(playlistId: Long): Flow<List<GroupToggle>>

    @Query(
        """SELECT v.category AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'VOD' AND h.name = v.category) AS hidden
           FROM vod v WHERE v.playlistId = :playlistId GROUP BY v.category ORDER BY MIN(v.id)"""
    )
    fun observeVod(playlistId: Long): Flow<List<GroupToggle>>

    @Query(
        """SELECT s.category AS name, COUNT(*) AS count,
           EXISTS(SELECT 1 FROM hidden_groups h WHERE h.playlistId = :playlistId AND h.kind = 'SERIES' AND h.name = s.category) AS hidden
           FROM series s WHERE s.playlistId = :playlistId GROUP BY s.category ORDER BY MIN(s.id)"""
    )
    fun observeSeries(playlistId: Long): Flow<List<GroupToggle>>

    @Query("SELECT COUNT(*) FROM hidden_groups")
    fun observeHiddenCount(): Flow<Int>
}

@Dao
interface LockedGroupDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(groups: List<LockedGroupEntity>)

    @Query("DELETE FROM locked_groups")
    suspend fun clear()
}
