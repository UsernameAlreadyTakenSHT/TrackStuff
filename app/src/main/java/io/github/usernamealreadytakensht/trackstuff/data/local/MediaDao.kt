package io.github.usernamealreadytakensht.trackstuff.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE localId = :id")
    fun observeById(id: Long): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE localId = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media")
    suspend fun getAll(): List<MediaEntity>

    @Query("SELECT * FROM media WHERE tmdbId = :tmdbId AND isSeries = :isSeries LIMIT 1")
    suspend fun findByTmdb(tmdbId: Int, isSeries: Boolean): MediaEntity?

    @Query("SELECT * FROM media WHERE imdbId = :imdbId LIMIT 1")
    suspend fun findByImdb(imdbId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE tvdbId = :tvdbId AND isSeries = :isSeries LIMIT 1")
    suspend fun findByTvdb(tvdbId: Int, isSeries: Boolean): MediaEntity?

    @Query("SELECT * FROM media WHERE traktId = :traktId AND isSeries = :isSeries LIMIT 1")
    suspend fun findByTrakt(traktId: Int, isSeries: Boolean): MediaEntity?

    @Query("SELECT * FROM media WHERE simklId = :simklId LIMIT 1")
    suspend fun findBySimkl(simklId: Int): MediaEntity?

    @Query("SELECT * FROM media WHERE omdbOrgId = :omdbOrgId LIMIT 1")
    suspend fun findByOmdbOrg(omdbOrgId: Int): MediaEntity?

    /** A title stored without any external id (e.g. an "Unknown title" page), matched by title, year and type. */
    @Query("SELECT * FROM media WHERE title = :title AND year IS :year AND isSeries = :isSeries AND tmdbId IS NULL AND imdbId IS NULL AND tvdbId IS NULL AND traktId IS NULL AND simklId IS NULL AND omdbOrgId IS NULL LIMIT 1")
    suspend fun findWithoutIds(title: String, year: Int?, isSeries: Boolean): MediaEntity?

    @Query("SELECT * FROM media WHERE needsEnrichment = 1")
    suspend fun getNeedingEnrichment(): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaEntity): Long

    @Update
    suspend fun update(entity: MediaEntity)

    /**
     * Reads a row, applies [transform] and writes the result back in one transaction, so that two writers
     * (a sync and the user, a refresh and a sync) cannot overwrite each other's columns. Returns the new row,
     * or null when the title no longer exists.
     */
    @androidx.room.Transaction
    suspend fun modify(id: Long, transform: (MediaEntity) -> MediaEntity): MediaEntity? {
        val e = getById(id) ?: return null
        val n = transform(e)
        if (n != e) update(n)
        return n
    }

    @Query("UPDATE media SET lastSyncedTrakt = NULL, traktId = NULL")
    suspend fun clearTraktSync()

    @Query("UPDATE media SET lastSyncedSimkl = NULL, simklId = NULL")
    suspend fun clearSimklSync()

    @Delete
    suspend fun delete(entity: MediaEntity)
}

@Dao
interface EpisodeDao {
    @Query("SELECT * FROM episode WHERE localId = :localId ORDER BY season, number")
    fun observe(localId: Long): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episode WHERE localId = :localId ORDER BY season, number")
    suspend fun of(localId: Long): List<EpisodeEntity>

    /** First episode airing on or after [today] for every series that has one. */
    @Query("SELECT localId, season, number, MIN(airDate) AS airDate FROM episode WHERE airDate >= :today GROUP BY localId")
    fun observeUpcoming(today: String): Flow<List<UpcomingEpisode>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<EpisodeEntity>)

    @Query("DELETE FROM episode WHERE localId = :localId")
    suspend fun clear(localId: Long)

    @androidx.room.Transaction
    suspend fun replace(localId: Long, rows: List<EpisodeEntity>) {
        clear(localId)
        insertAll(rows)
    }

    @Query("UPDATE media SET episodesAt = :at WHERE localId = :localId")
    suspend fun markFetched(localId: Long, at: Long)
}
