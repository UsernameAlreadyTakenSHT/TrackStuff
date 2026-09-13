package com.example.trackstuff.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Title from the official IMDb datasets (title.basics + title.ratings), filtered to movies and series
 * with at least [com.example.trackstuff.data.imdb.ImdbRepository.MIN_VOTES] votes.
 */
@Entity(tableName = "imdb_title", indices = [Index("votes"), Index("isSeries"), Index("nameNorm")])
data class ImdbTitleEntity(
    @PrimaryKey val imdbId: String,
    val title: String,
    val originalTitle: String,
    val nameNorm: String,
    val isSeries: Boolean,
    val year: Int?,
    val endYear: Int?,
    val runtime: Int?,
    /** Comma-separated IMDb genres (Drama,Comedy…). */
    val genres: String,
    val rating: Float,
    val votes: Int,
    /** Votes at the previous import: the growth over a month is the popularity measure ("MovieMeter"). */
    val prevVotes: Int? = null,
)

/** Number of episodes per season (title.episode). */
@Entity(tableName = "imdb_season", primaryKeys = ["seriesId", "season"])
data class ImdbSeasonEntity(val seriesId: String, val season: Int, val episodes: Int)

/** Credited person of a title (title.crew + title.principals); role = director, writer, creator, actor. */
@Entity(tableName = "imdb_crew", indices = [Index("tconst")])
data class ImdbCrewEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tconst: String,
    val nconst: String,
    val role: String,
    val character: String?,
    val ordering: Int,
)

/** Person name (name.basics), only those referenced by imdb_crew. */
@Entity(tableName = "imdb_person")
data class ImdbPersonEntity(@PrimaryKey val nconst: String, val name: String)

/** Titre traduit / alternatif (title.akas). */
@Entity(tableName = "imdb_alias", indices = [Index("nameNorm"), Index("tconst")])
data class ImdbAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tconst: String,
    val title: String,
    val nameNorm: String,
    val region: String,
)

/**
 * Poster URL resolved online (OMDb API, TVDB) for a title known only by its IMDb id (Top 250, Popular now).
 * Kept for good so that the lookup is never repeated; an empty [url] records a failed lookup, retried after a while.
 */
@Entity(tableName = "imdb_poster")
data class ImdbPosterEntity(@PrimaryKey val imdbId: String, val url: String, val checkedAt: Long)

data class ImdbVotes(val imdbId: String, val votes: Int)

/** Credit row joined with the name. */
data class ImdbCredit(val role: String, val name: String, val character: String?, val ordering: Int)

@Dao
interface ImdbDao {
    @Query("SELECT COUNT(*) FROM imdb_title")
    suspend fun count(): Int

    @Query("SELECT * FROM imdb_title WHERE imdbId = :imdbId")
    suspend fun byId(imdbId: String): ImdbTitleEntity?

    @Query("SELECT AVG(rating) FROM imdb_title WHERE isSeries = :isSeries AND votes >= :minVotes")
    suspend fun meanRating(isSeries: Boolean, minVotes: Int): Double?

    /**
     * IMDb weighted ranking: WR = v/(v+m)·R + m/(v+m)·C, where m = minimum votes and C = mean rating.
     */
    @Query(
        """
        SELECT * FROM imdb_title WHERE isSeries = :isSeries AND votes >= :minVotes
        ORDER BY (votes * 1.0 / (votes + :minVotes)) * rating + (:minVotes * 1.0 / (votes + :minVotes)) * :mean DESC
        LIMIT :limit
        """
    )
    suspend fun top(isSeries: Boolean, minVotes: Int, mean: Double, limit: Int): List<ImdbTitleEntity>

    /**
     * Titles (or aliases) starting with the query. [glob] is the normalized query followed by `*`: a GLOB
     * with a bound, wildcard-free prefix uses the `nameNorm` indexes (a range scan), unlike `LIKE '%…%'`.
     */
    @Query(
        """
        SELECT * FROM imdb_title WHERE nameNorm GLOB :glob
            OR imdbId IN (SELECT tconst FROM imdb_alias WHERE nameNorm GLOB :glob)
        ORDER BY votes DESC LIMIT :limit
        """
    )
    suspend fun searchPrefix(glob: String, limit: Int): List<ImdbTitleEntity>

    /** Titles (or aliases) containing the query anywhere: a full scan, used only when the prefix search finds little. */
    @Query(
        """
        SELECT * FROM imdb_title WHERE (nameNorm LIKE '%' || :q || '%'
            OR imdbId IN (SELECT tconst FROM imdb_alias WHERE nameNorm LIKE '%' || :q || '%'))
            AND imdbId NOT IN (:exclude)
        ORDER BY votes DESC LIMIT :limit
        """
    )
    suspend fun searchContains(q: String, exclude: List<String>, limit: Int): List<ImdbTitleEntity>

    @Query("SELECT episodes FROM imdb_season WHERE seriesId = :seriesId AND season >= 1 ORDER BY season")
    suspend fun seasons(seriesId: String): List<Int>

    @Query("SELECT c.role AS role, p.name AS name, c.character AS character, c.ordering AS ordering FROM imdb_crew c JOIN imdb_person p ON p.nconst = c.nconst WHERE c.tconst = :tconst ORDER BY c.ordering")
    suspend fun credits(tconst: String): List<ImdbCredit>

    @Query("SELECT COUNT(*) FROM imdb_crew")
    suspend fun crewCount(): Int

    /** Current votes of all titles, kept as a reference for the next import. */
    @Query("SELECT imdbId, votes FROM imdb_title")
    suspend fun allVotes(): List<ImdbVotes>

    @Query("SELECT COUNT(*) FROM imdb_title WHERE prevVotes IS NOT NULL")
    suspend fun historyCount(): Int

    /** Titles that gained the most votes since the previous import. */
    @Query("SELECT * FROM imdb_title WHERE isSeries = :isSeries AND prevVotes IS NOT NULL AND votes > prevVotes ORDER BY (votes - prevVotes) DESC LIMIT :limit")
    suspend fun popularByVelocity(isSeries: Boolean, limit: Int): List<ImdbTitleEntity>

    /** Fallback without history: most-voted recent releases. */
    @Query("SELECT * FROM imdb_title WHERE isSeries = :isSeries AND year >= :minYear ORDER BY votes DESC LIMIT :limit")
    suspend fun popularRecent(isSeries: Boolean, minYear: Int, limit: Int): List<ImdbTitleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertSeasons(rows: List<ImdbSeasonEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertCrew(rows: List<ImdbCrewEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPersons(rows: List<ImdbPersonEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAliases(rows: List<ImdbAliasEntity>)
    @Query("DELETE FROM imdb_season") suspend fun clearSeasons()
    @Query("DELETE FROM imdb_crew") suspend fun clearCrew()
    @Query("DELETE FROM imdb_person") suspend fun clearPersons()
    @Query("DELETE FROM imdb_alias") suspend fun clearAliases()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(titles: List<ImdbTitleEntity>)

    @Query("DELETE FROM imdb_title")
    suspend fun clear()

    @Query("SELECT * FROM imdb_poster WHERE imdbId = :imdbId")
    suspend fun poster(imdbId: String): ImdbPosterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePoster(p: ImdbPosterEntity)
}

/** Separate IMDb database, like omdb.org: never touched by the library migrations. */
@Database(entities = [ImdbTitleEntity::class, ImdbSeasonEntity::class, ImdbCrewEntity::class, ImdbPersonEntity::class, ImdbAliasEntity::class, ImdbPosterEntity::class], version = 4, exportSchema = false)
abstract class ImdbDatabase : RoomDatabase() {
    abstract fun imdbDao(): ImdbDao

    companion object {
        /** Adds prevVotes without losing the import (2 GB downloaded in Full mode). */
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE imdb_title ADD COLUMN prevVotes INTEGER")
            }
        }

        /** Adds the resolved-poster table. */
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `imdb_poster` (`imdbId` TEXT NOT NULL, `url` TEXT NOT NULL, `checkedAt` INTEGER NOT NULL, PRIMARY KEY(`imdbId`))")
            }
        }

        fun build(context: Context): ImdbDatabase =
            Room.databaseBuilder(context, ImdbDatabase::class.java, "imdb.db")
                .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
