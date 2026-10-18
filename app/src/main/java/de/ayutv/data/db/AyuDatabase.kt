package de.ayutv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        ChannelEntity::class,
        ProgrammeEntity::class,
        VodEntity::class,
        SeriesEntity::class,
        FavoriteEntity::class,
        HistoryEntity::class,
        HiddenGroupEntity::class,
    ],
    views = [VisibleChannelView::class, VisibleVodView::class, VisibleSeriesView::class],
    version = 1,
    exportSchema = true,
)
abstract class AyuDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun channels(): ChannelDao
    abstract fun programmes(): ProgrammeDao
    abstract fun vod(): VodDao
    abstract fun series(): SeriesDao
    abstract fun favorites(): FavoriteDao
    abstract fun history(): HistoryDao
    abstract fun hiddenGroups(): HiddenGroupDao

    companion object {
        fun build(context: Context): AyuDatabase =
            Room.databaseBuilder(context, AyuDatabase::class.java, "ayu.db")
                // WAL erzwingen: Die Oberfläche kann lesen, während im Hintergrund EPG geschrieben wird.
                // Fire TV Sticks melden sich oft als "low RAM", dann würde Room sonst TRUNCATE wählen.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
