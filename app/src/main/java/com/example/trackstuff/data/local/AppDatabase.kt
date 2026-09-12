package com.example.trackstuff.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** The user's library. */
@Database(entities = [MediaEntity::class], version = 7, exportSchema = false)
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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "trackstuff.db")
                .addMigrations(MIGRATION_6_7)
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
