package com.example.trackstuff.data.local

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

    @Query("SELECT * FROM media WHERE needsEnrichment = 1")
    suspend fun getNeedingEnrichment(): List<MediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MediaEntity): Long

    @Update
    suspend fun update(entity: MediaEntity)

    @Delete
    suspend fun delete(entity: MediaEntity)
}
