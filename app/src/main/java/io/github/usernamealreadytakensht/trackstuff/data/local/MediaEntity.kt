package io.github.usernamealreadytakensht.trackstuff.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.usernamealreadytakensht.trackstuff.domain.DataSource
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.LibraryItem
import io.github.usernamealreadytakensht.trackstuff.domain.MediaDetails
import io.github.usernamealreadytakensht.trackstuff.domain.MediaKind
import io.github.usernamealreadytakensht.trackstuff.domain.Ratings
import io.github.usernamealreadytakensht.trackstuff.domain.UserTracking
import io.github.usernamealreadytakensht.trackstuff.domain.CastMember
import io.github.usernamealreadytakensht.trackstuff.domain.Credits
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus
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
    val ratingsSource: DataSource? = null,
    val creditsSource: DataSource? = null,
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
    val currentSeason: Int,
    val currentEpisode: Int,
    val addedAt: Long,
    val updatedAt: Long,
    val lastSyncedTrakt: Long?,
    val lastSyncedSimkl: Long?,
    @androidx.room.ColumnInfo(name = "syncedStatus") val traktSyncedStatus: WatchStatus? = null,
    @androidx.room.ColumnInfo(name = "syncedSeason") val traktSyncedSeason: Int = 0,
    @androidx.room.ColumnInfo(name = "syncedEpisode") val traktSyncedEpisode: Int = 0,
    val simklSyncedStatus: WatchStatus? = null,
    val simklSyncedSeason: Int = 0,
    val simklSyncedEpisode: Int = 0,
    /** True when the page has not been enriched by TMDB/TVDB/OMDb yet (e.g. imported from Trakt). */
    val needsEnrichment: Boolean = false,
    /** When the episode list (`episode` table) was last fetched; null = never. */
    val episodesAt: Long? = null,
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
            ratingsSource = ratingsSource,
            creditsSource = creditsSource,
            certification = certification,
            status = productionStatus,
        ),
        tracking = UserTracking(
            status = status,
            currentSeason = currentSeason,
            currentEpisode = currentEpisode,
            addedAt = addedAt,
            updatedAt = updatedAt,
            lastSyncedTrakt = lastSyncedTrakt,
            lastSyncedSimkl = lastSyncedSimkl,
            traktSyncedStatus = traktSyncedStatus,
            traktSyncedSeason = traktSyncedSeason,
            traktSyncedEpisode = traktSyncedEpisode,
            simklSyncedStatus = simklSyncedStatus,
            simklSyncedSeason = simklSyncedSeason,
            simklSyncedEpisode = simklSyncedEpisode,
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
            ratingsSource = details.ratingsSource,
            creditsSource = details.creditsSource,
            certification = details.certification,
            productionStatus = details.status,
            writers = details.credits.writers,
            releaseDate = details.releaseDate,
            countries = details.countries,
            studios = details.studios,
            nextAired = details.nextAired,
            seasonEpisodes = details.seasonEpisodes,
            status = tracking.status,
            currentSeason = tracking.currentSeason,
            currentEpisode = tracking.currentEpisode,
            addedAt = tracking.addedAt,
            updatedAt = tracking.updatedAt,
            lastSyncedTrakt = tracking.lastSyncedTrakt,
            lastSyncedSimkl = tracking.lastSyncedSimkl,
            traktSyncedStatus = tracking.traktSyncedStatus,
            traktSyncedSeason = tracking.traktSyncedSeason,
            traktSyncedEpisode = tracking.traktSyncedEpisode,
            simklSyncedStatus = tracking.simklSyncedStatus,
            simklSyncedSeason = tracking.simklSyncedSeason,
            simklSyncedEpisode = tracking.simklSyncedEpisode,
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
    // Enum converters tolerate unknown values (older/newer app versions) instead of crashing the library screen.
    @TypeConverter fun kindToString(v: MediaKind): String = v.name
    @TypeConverter fun stringToKind(v: String): MediaKind = MediaKind.entries.firstOrNull { it.name == v } ?: MediaKind.MOVIE
    @TypeConverter fun statusToString(v: WatchStatus): String = v.name
    @TypeConverter fun stringToStatus(v: String): WatchStatus = legacyStatus(v)
    @TypeConverter fun optStatusToString(v: WatchStatus?): String? = v?.name
    @TypeConverter fun stringToOptStatus(v: String?): WatchStatus? = v?.let { legacyStatus(it) }
    @TypeConverter fun sourceToString(v: DataSource?): String? = v?.name
    @TypeConverter fun stringToSource(v: String?): DataSource? = v?.let { s -> DataSource.entries.firstOrNull { it.name == s } }

    companion object {
        /** Maps statuses of older versions (on hold, dropped) and unknown values onto the current ones. */
        fun legacyStatus(name: String): WatchStatus = when (name) {
            "ON_HOLD" -> WatchStatus.WATCHING
            "DROPPED" -> WatchStatus.PLANNED
            else -> WatchStatus.entries.firstOrNull { it.name == name } ?: WatchStatus.PLANNED
        }
    }
}

/** Episode of a library series (titles, air dates), refreshed with the page. Specials (season 0) are not kept. */
@Entity(
    tableName = "episode",
    primaryKeys = ["localId", "season", "number"],
    foreignKeys = [androidx.room.ForeignKey(entity = MediaEntity::class, parentColumns = ["localId"], childColumns = ["localId"], onDelete = androidx.room.ForeignKey.CASCADE)],
    indices = [Index("airDate")],
)
data class EpisodeEntity(
    val localId: Long,
    val season: Int,
    val number: Int,
    val title: String?,
    /** ISO `yyyy-MM-dd`. */
    val airDate: String?,
) {
    fun toEpisode() = io.github.usernamealreadytakensht.trackstuff.domain.Episode(season, number, title, airDate)

    companion object {
        fun from(localId: Long, e: io.github.usernamealreadytakensht.trackstuff.domain.Episode) = EpisodeEntity(localId, e.season, e.number, e.title, e.airDate)
    }
}

/** Next episode to air of a library series. */
data class UpcomingEpisode(val localId: Long, val season: Int, val number: Int, val airDate: String)
