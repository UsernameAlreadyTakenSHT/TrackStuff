package com.example.trackstuff.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import java.text.Normalizer

/**
 * Title imported from the omdb.org CSV dumps (Open Media Database, free license).
 * Serves as an extra source (after TMDB, TVDB, OMDb API and IMDb) and as an offline search base.
 */
@Entity(tableName = "omdb_title", indices = [Index("nameNorm"), Index("imdbId")])
data class OmdbTitleEntity(
    @PrimaryKey val id: Int,
    val name: String,
    /** Normalized title (lower case, no accents) for search. */
    val nameNorm: String,
    val isSeries: Boolean,
    val year: Int?,
    val imdbId: String?,
    val imageId: Int?,
    val imageVersion: Int?,
    /** Comma-separated omdb.org genre ids (16 = Animation, 34 = Anime, 99 = Documentary). */
    val genreIds: String,
    val abstractEn: String?,
    /** Synopsis in the app language (fr, de…), when available. */
    val abstractLocal: String?,
    val runtime: Int? = null,
    /** Comma-separated ISO-2 country codes (movie_countries). */
    val countries: String = "",
    /** omdb.org community votes (all_votes): average out of 10 and count. */
    val voteAvg: Float? = null,
    val voteCount: Int? = null,
) {
    /** `default` is the largest size served by omdb.org (200×277); `medium` is only 92×127. */
    val posterUrl: String? get() = imageId?.let { "https://www.omdb.org/image/default/$it.jpeg" + (imageVersion?.let { v -> "?v=$v" } ?: "") }
    val genreIdList: List<Int> get() = genreIds.split(',').mapNotNull { it.toIntOrNull() }
}

/** Credited person (all_casts + all_people); role = director, writer, producer, actor. */
@Entity(tableName = "omdb_cast", indices = [Index("titleId")])
data class OmdbCastEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: Int,
    val name: String,
    val role: String,
    val character: String?,
    val position: Int,
)

/** Titre alternatif / traduit (all_movie_aliases_iso.csv). */
@Entity(tableName = "omdb_alias", indices = [Index("nameNorm"), Index("titleId")])
data class OmdbAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleId: Int,
    val name: String,
    val nameNorm: String,
    val language: String,
    /** 1 = official translation (all_movie_aliases_iso.official_translation), preferred for display. */
    val official: Int = 0,
)

/** Full-text rows for the offline search (see ImdbTitleFts): [id] / [titleId] are stored, not indexed. */
@androidx.room.Fts4(notIndexed = ["id"])
@Entity(tableName = "omdb_title_fts")
data class OmdbTitleFts(val nameNorm: String, val id: Int)

@androidx.room.Fts4(notIndexed = ["titleId"])
@Entity(tableName = "omdb_alias_fts")
data class OmdbAliasFts(val nameNorm: String, val titleId: Int)

@Dao
interface OmdbOrgDao {
    @Query("SELECT COUNT(*) FROM omdb_title")
    suspend fun count(): Int

    @Query("SELECT * FROM omdb_title WHERE id = :id")
    suspend fun byId(id: Int): OmdbTitleEntity?

    @Query("SELECT * FROM omdb_title WHERE imdbId = :imdbId LIMIT 1")
    suspend fun byImdb(imdbId: String): OmdbTitleEntity?


    /** Exact title (or exact alias), optionally filtered by year ±1 and by type. */
    @Query(
        """
        SELECT * FROM omdb_title WHERE isSeries = :isSeries AND id IN (
            SELECT id FROM omdb_title WHERE nameNorm = :q
            UNION
            SELECT titleId FROM omdb_alias WHERE nameNorm = :q
        )
        ORDER BY CASE WHEN :year IS NULL THEN 0 ELSE ABS(IFNULL(year, 0) - :year) END ASC
        LIMIT 1
        """
    )
    suspend fun matchExact(q: String, isSeries: Boolean, year: Int?): OmdbTitleEntity?

    @Query("SELECT name FROM omdb_alias WHERE titleId = :titleId AND language = :language ORDER BY official DESC LIMIT 1")
    suspend fun localizedName(titleId: Int, language: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertTitleRows(titles: List<OmdbTitleEntity>)
    @Insert suspend fun insertTitleFts(rows: List<OmdbTitleFts>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAliasRows(aliases: List<OmdbAliasEntity>)
    @Insert suspend fun insertAliasFts(rows: List<OmdbAliasFts>)
    @Query("DELETE FROM omdb_title_fts") suspend fun clearTitleFts()
    @Query("DELETE FROM omdb_alias_fts") suspend fun clearAliasFts()

    /** Titles / aliases and their full-text rows, together (the importer always clears before inserting). */
    @androidx.room.Transaction
    suspend fun insertTitles(titles: List<OmdbTitleEntity>) {
        insertTitleRows(titles)
        insertTitleFts(titles.map { OmdbTitleFts(it.nameNorm, it.id) })
    }

    @androidx.room.Transaction
    suspend fun insertAliases(aliases: List<OmdbAliasEntity>) {
        insertAliasRows(aliases)
        insertAliasFts(aliases.map { OmdbAliasFts(it.nameNorm, it.titleId) })
    }

    /** Full-text search: [match] is an FTS query such as `break* bad*`; exact titles first, then newest. */
    @Query(
        """
        SELECT * FROM omdb_title WHERE id IN (SELECT id FROM omdb_title_fts WHERE omdb_title_fts MATCH :match)
            OR id IN (SELECT titleId FROM omdb_alias_fts WHERE omdb_alias_fts MATCH :match)
        ORDER BY (nameNorm = :q) DESC, (year IS NULL) ASC, year DESC
        LIMIT :limit
        """
    )
    suspend fun searchFts(q: String, match: String, limit: Int): List<OmdbTitleEntity>

    /** Titles most voted by the omdb.org community. */
    @Query("SELECT * FROM omdb_title WHERE isSeries = :isSeries AND voteCount IS NOT NULL AND imageId IS NOT NULL ORDER BY voteCount DESC, voteAvg DESC LIMIT :limit")
    suspend fun mostVoted(isSeries: Boolean, limit: Int): List<OmdbTitleEntity>

    /** Top rated, weighted average (m minimum votes, C = global mean). */
    @Query("SELECT * FROM omdb_title WHERE isSeries = :isSeries AND voteCount >= :minVotes AND imageId IS NOT NULL ORDER BY (voteCount * 1.0 / (voteCount + :minVotes)) * voteAvg + (:minVotes * 1.0 / (voteCount + :minVotes)) * :mean DESC LIMIT :limit")
    suspend fun topRated(isSeries: Boolean, minVotes: Int, mean: Double, limit: Int): List<OmdbTitleEntity>

    @Query("SELECT AVG(voteAvg) FROM omdb_title WHERE voteCount >= :minVotes")
    suspend fun meanVote(minVotes: Int): Double?

    @Query("SELECT * FROM omdb_cast WHERE titleId = :titleId ORDER BY position")
    suspend fun cast(titleId: Int): List<OmdbCastEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCast(rows: List<OmdbCastEntity>)

    @Query("DELETE FROM omdb_cast")
    suspend fun clearCast()

    @Query("DELETE FROM omdb_title") suspend fun clearTitleRows()
    @Query("DELETE FROM omdb_alias") suspend fun clearAliasRows()
    @androidx.room.Transaction suspend fun clearTitles() { clearTitleRows(); clearTitleFts() }
    @androidx.room.Transaction suspend fun clearAliases() { clearAliasRows(); clearAliasFts() }
}

/** Normalization shared by import and search: lower case, no accents nor superfluous punctuation. */
private val DIACRITICS = Regex("\\p{M}+")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

fun normalizeTitle(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase()
        .replace(NON_ALNUM, " ")
        .trim()

/**
 * FTS4 MATCH expression for a search: every word of the normalized query as a prefix ("break* bad*"), so
 * that a word matches wherever it stands in the title. Null when the query has no word.
 */
fun ftsQuery(normalized: String): String? = normalized.split(' ').filter { it.isNotBlank() }.joinToString(" ") { "$it*" }.ifBlank { null }
