package com.example.trackstuff.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.Ratings
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.CastMember
import com.example.trackstuff.domain.Credits
import com.example.trackstuff.domain.WatchStatus
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types

/**
 * A library title, with the whole page cached for offline use.
 * A title is identified by tmdbId+isSeries when available, otherwise imdbId / tvdbId.
 */
@Entity(
    tableName = "media",
    indices = [Index("tmdbId"), Index("imdbId"), Index("tvdbId"), Index("status"), Index("kind")],
)
@TypeConverters(MediaConverters::class)
@JsonClass(generateAdapter = true)
data class MediaEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val tmdbId: Int?,
    val imdbId: String?,
    val tvdbId: Int?,
    val traktId: Int?,
    val simklId: Int?,
    val omdbOrgId: Int?,
    val kind: MediaKind,
    val isSeries: Boolean,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val genres: List<String>,
    val runtimeMinutes: Int?,
    val numberOfSeasons: Int?,
    val numberOfEpisodes: Int?,
    val ratingTmdb: Double?,
    val ratingTmdbVotes: Int?,
    val ratingImdb: Double?,
    val ratingImdbVotes: Int?,
    val ratingRt: Int?,
    val ratingMetacritic: Int?,
    val directors: List<String>,
    val producers: List<String>,
    val cast: List<CastJson>,
    val posterSource: DataSource?,
    val overviewSource: DataSource?,
    val certification: String?,
    val productionStatus: String?,
    val writers: List<String>,
    val releaseDate: String?,
    val countries: List<String>,
    val studios: List<String>,
    val nextAired: String?,
    val seasonEpisodes: List<Int>,
    // User tracking
    val status: WatchStatus,
    val userRating: Int?,
    val notes: String,
    val currentSeason: Int,
    val currentEpisode: Int,
    val addedAt: Long,
    val updatedAt: Long,
    val lastSyncedTrakt: Long?,
    val lastSyncedSimkl: Long?,
    /** True when the page has not been enriched by TMDB/TVDB/OMDb yet (e.g. imported from Trakt). */
    val needsEnrichment: Boolean = false,
) {
    fun toLibraryItem() = LibraryItem(
        localId = localId,
        details = MediaDetails(
            ids = ExternalIds(tmdbId, imdbId, tvdbId, traktId, simklId, omdbOrgId),
            kind = kind,
            isSeries = isSeries,
            title = title,
            originalTitle = originalTitle,
            year = year,
            overview = overview,
            posterUrl = posterUrl,
            backdropUrl = backdropUrl,
            genres = genres,
            runtimeMinutes = runtimeMinutes,
            numberOfSeasons = numberOfSeasons,
            numberOfEpisodes = numberOfEpisodes,
            ratings = Ratings(ratingTmdb, ratingTmdbVotes, ratingImdb, ratingImdbVotes, ratingRt, ratingMetacritic),
            credits = Credits(directors, writers, producers, cast.map { CastMember(it.name, it.character) }),
            releaseDate = releaseDate,
            countries = countries,
            studios = studios,
            nextAired = nextAired,
            seasonEpisodes = seasonEpisodes,
            posterSource = posterSource,
            overviewSource = overviewSource,
            certification = certification,
            status = productionStatus,
        ),
        tracking = UserTracking(
            status = status,
            userRating = userRating,
            notes = notes,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            addedAt = addedAt,
            updatedAt = updatedAt,
            lastSyncedTrakt = lastSyncedTrakt,
            lastSyncedSimkl = lastSyncedSimkl,
        ),
    )

    companion object {
        fun from(details: MediaDetails, tracking: UserTracking, localId: Long = 0, needsEnrichment: Boolean = false) = MediaEntity(
            localId = localId,
            tmdbId = details.ids.tmdbId,
            imdbId = details.ids.imdbId,
            tvdbId = details.ids.tvdbId,
            traktId = details.ids.traktId,
            simklId = details.ids.simklId,
            omdbOrgId = details.ids.omdbOrgId,
            kind = details.kind,
            isSeries = details.isSeries,
            title = details.title,
            originalTitle = details.originalTitle,
            year = details.year,
            overview = details.overview,
            posterUrl = details.posterUrl,
            backdropUrl = details.backdropUrl,
            genres = details.genres,
            runtimeMinutes = details.runtimeMinutes,
            numberOfSeasons = details.numberOfSeasons,
            numberOfEpisodes = details.numberOfEpisodes,
            ratingTmdb = details.ratings.tmdb,
            ratingTmdbVotes = details.ratings.tmdbVotes,
            ratingImdb = details.ratings.imdb,
            ratingImdbVotes = details.ratings.imdbVotes,
            ratingRt = details.ratings.rottenTomatoes,
            ratingMetacritic = details.ratings.metacritic,
            directors = details.credits.directors,
            producers = details.credits.producers,
            cast = details.credits.cast.map { CastJson(it.name, it.character) },
            posterSource = details.posterSource,
            overviewSource = details.overviewSource,
            certification = details.certification,
            productionStatus = details.status,
            writers = details.credits.writers,
            releaseDate = details.releaseDate,
            countries = details.countries,
            studios = details.studios,
            nextAired = details.nextAired,
            seasonEpisodes = details.seasonEpisodes,
            status = tracking.status,
            userRating = tracking.userRating,
            notes = tracking.notes,
            currentSeason = tracking.currentSeason,
            currentEpisode = tracking.currentEpisode,
            addedAt = tracking.addedAt,
            updatedAt = tracking.updatedAt,
            lastSyncedTrakt = tracking.lastSyncedTrakt,
            lastSyncedSimkl = tracking.lastSyncedSimkl,
            needsEnrichment = needsEnrichment,
        )
    }
}

@JsonClass(generateAdapter = true)
data class CastJson(val name: String, val character: String?)

class MediaConverters {
    private val moshi = Moshi.Builder().build()
    private val stringList = moshi.adapter<List<String>>(Types.newParameterizedType(List::class.java, String::class.java))
    private val castList = moshi.adapter<List<CastJson>>(Types.newParameterizedType(List::class.java, CastJson::class.java))

    @TypeConverter fun stringListToJson(v: List<String>): String = stringList.toJson(v)
    @TypeConverter fun jsonToStringList(v: String): List<String> = stringList.fromJson(v) ?: emptyList()
    @TypeConverter fun intListToString(v: List<Int>): String = v.joinToString(",")
    @TypeConverter fun stringToIntList(v: String): List<Int> = v.split(",").mapNotNull { it.toIntOrNull() }
    @TypeConverter fun castToJson(v: List<CastJson>): String = castList.toJson(v)
    @TypeConverter fun jsonToCast(v: String): List<CastJson> = castList.fromJson(v) ?: emptyList()
    @TypeConverter fun kindToString(v: MediaKind): String = v.name
    @TypeConverter fun stringToKind(v: String): MediaKind = MediaKind.valueOf(v)
    @TypeConverter fun statusToString(v: WatchStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): WatchStatus = WatchStatus.valueOf(v)
    @TypeConverter fun sourceToString(v: DataSource?): String? = v?.name
    @TypeConverter fun stringToSource(v: String?): DataSource? = v?.let { DataSource.valueOf(it) }
}
