package de.ayutv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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
        /** 1 → 2: Playlists ein-/ausschaltbar. Views neu anlegen, damit sie ausgeschaltete Playlists ausblenden. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `playlists` ADD COLUMN `enabled` INTEGER NOT NULL DEFAULT 1")
                recreateViews(db)
            }
        }

        private fun recreateViews(db: SupportSQLiteDatabase) {
            listOf(
                "visible_channels" to VISIBLE_CHANNELS_SQL,
                "visible_vod" to VISIBLE_VOD_SQL,
                "visible_series" to VISIBLE_SERIES_SQL,
            ).forEach { (name, sql) ->
                db.execSQL("DROP VIEW IF EXISTS `$name`")
                db.execSQL("CREATE VIEW `$name` AS $sql")
            }
        }

        fun build(context: Context): AyuDatabase =
            Room.databaseBuilder(context, AyuDatabase::class.java, "ayu.db")
                // WAL erzwingen: Die Oberfläche kann lesen, während im Hintergrund EPG geschrieben wird.
                // Fire TV Sticks melden sich oft als "low RAM", dann würde Room sonst TRUNCATE wählen.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2)
                // Nur für unbekannte Versionssprünge: Playlists sollen Updates sonst immer überstehen.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
