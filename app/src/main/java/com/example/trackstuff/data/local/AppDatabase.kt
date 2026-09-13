package com.example.trackstuff.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The user's library. */
@Database(entities = [MediaEntity::class], version = 10, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    companion object {
        /** Statuses reduced to planned / watching / completed: on hold becomes watching, dropped becomes planned. */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("UPDATE media SET status = 'WATCHING' WHERE status = 'ON_HOLD'")
                db.execSQL("UPDATE media SET status = 'PLANNED', currentSeason = 0, currentEpisode = 0 WHERE status = 'DROPPED'")
            }
        }

        /** State as last pushed to the services, for delta pushes and status-transition rules. */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN syncedStatus TEXT")
                db.execSQL("ALTER TABLE media ADD COLUMN syncedSeason INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media ADD COLUMN syncedEpisode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** The pushed reference is per service (Trakt columns keep the v8 names). */
        private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN simklSyncedStatus TEXT")
                db.execSQL("ALTER TABLE media ADD COLUMN simklSyncedSeason INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE media ADD COLUMN simklSyncedEpisode INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Rating and notes dropped from tracking (never used by the app). SQLite on API 29 has no
         * DROP COLUMN: the table is rebuilt without the two columns.
         */
        private val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_new` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `tmdbId` INTEGER, `imdbId` TEXT, `tvdbId` INTEGER, `traktId` INTEGER, `simklId` INTEGER, `omdbOrgId` INTEGER, `kind` TEXT NOT NULL, `isSeries` INTEGER NOT NULL, `title` TEXT NOT NULL, `originalTitle` TEXT, `year` INTEGER, `overview` TEXT, `posterUrl` TEXT, `backdropUrl` TEXT, `genres` TEXT NOT NULL, `runtimeMinutes` INTEGER, `numberOfSeasons` INTEGER, `numberOfEpisodes` INTEGER, `ratingTmdb` REAL, `ratingTmdbVotes` INTEGER, `ratingImdb` REAL, `ratingImdbVotes` INTEGER, `ratingRt` INTEGER, `ratingMetacritic` INTEGER, `directors` TEXT NOT NULL, `producers` TEXT NOT NULL, `cast` TEXT NOT NULL, `posterSource` TEXT, `overviewSource` TEXT, `certification` TEXT, `productionStatus` TEXT, `writers` TEXT NOT NULL, `releaseDate` TEXT, `countries` TEXT NOT NULL, `studios` TEXT NOT NULL, `nextAired` TEXT, `seasonEpisodes` TEXT NOT NULL, `status` TEXT NOT NULL, `currentSeason` INTEGER NOT NULL, `currentEpisode` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastSyncedTrakt` INTEGER, `lastSyncedSimkl` INTEGER, `syncedStatus` TEXT, `syncedSeason` INTEGER NOT NULL, `syncedEpisode` INTEGER NOT NULL, `simklSyncedStatus` TEXT, `simklSyncedSeason` INTEGER NOT NULL, `simklSyncedEpisode` INTEGER NOT NULL, `needsEnrichment` INTEGER NOT NULL)"
                )
                val cols = "`localId`, `tmdbId`, `imdbId`, `tvdbId`, `traktId`, `simklId`, `omdbOrgId`, `kind`, `isSeries`, `title`, `originalTitle`, `year`, `overview`, `posterUrl`, `backdropUrl`, `genres`, `runtimeMinutes`, `numberOfSeasons`, `numberOfEpisodes`, `ratingTmdb`, `ratingTmdbVotes`, `ratingImdb`, `ratingImdbVotes`, `ratingRt`, `ratingMetacritic`, `directors`, `producers`, `cast`, `posterSource`, `overviewSource`, `certification`, `productionStatus`, `writers`, `releaseDate`, `countries`, `studios`, `nextAired`, `seasonEpisodes`, `status`, `currentSeason`, `currentEpisode`, `addedAt`, `updatedAt`, `lastSyncedTrakt`, `lastSyncedSimkl`, `syncedStatus`, `syncedSeason`, `syncedEpisode`, `simklSyncedStatus`, `simklSyncedSeason`, `simklSyncedEpisode`, `needsEnrichment`"
                db.execSQL("INSERT INTO `media_new` ($cols) SELECT $cols FROM `media`")
                db.execSQL("DROP TABLE `media`")
                db.execSQL("ALTER TABLE `media_new` RENAME TO `media`")
                for (c in listOf("tmdbId", "imdbId", "tvdbId", "status", "kind")) db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_$c` ON `media` (`$c`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "trackstuff.db")
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/**
 * Imported omdb.org database, deliberately separate: a schema change of the library
 * must never wipe the ~12 MB of dumps (downloadable at most once a month).
 */
@Database(entities = [OmdbTitleEntity::class, OmdbAliasEntity::class, OmdbCastEntity::class], version = 4, exportSchema = false)
abstract class OmdbOrgDatabase : RoomDatabase() {
    abstract fun omdbOrgDao(): OmdbOrgDao

    companion object {
        /** Adds the votes without losing the import. */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE omdb_title ADD COLUMN voteAvg REAL")
                db.execSQL("ALTER TABLE omdb_title ADD COLUMN voteCount INTEGER")
            }
        }
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE omdb_alias ADD COLUMN official INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun build(context: Context): OmdbOrgDatabase =
            Room.databaseBuilder(context, OmdbOrgDatabase::class.java, "omdb_org.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
