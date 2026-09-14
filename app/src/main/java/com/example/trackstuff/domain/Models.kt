package com.example.trackstuff.domain

import androidx.annotation.StringRes
import com.example.trackstuff.R

/** Category shown in the library. Detected automatically. */
enum class MediaKind(@StringRes val labelRes: Int) {
    MOVIE(R.string.kind_movie),
    SERIES(R.string.kind_series),
    DOCUMENTARY(R.string.kind_documentary),
    ANIME(R.string.kind_anime);

    /** True when the media is episodic (series, anime, docu-series). */
    val isEpisodic: Boolean get() = this == SERIES || this == ANIME
}

/**
 * Tracking status. WATCHING is derived from progress (at least one episode marked) rather than chosen:
 * a title is planned until it is started, then watching, then completed. Movies are planned or completed.
 */
enum class WatchStatus(@StringRes val labelRes: Int) {
    PLANNED(R.string.status_planned),
    WATCHING(R.string.status_watching),
    COMPLETED(R.string.status_completed);
}

/** Origin of a piece of data: used to show where the poster / description came from. */
enum class DataSource(val label: String) {
    TMDB("TMDB"),
    TVDB("TVDB"),
    /** omdb.org (Open Media Database), imported locally from the CSV dumps. */
    OMDB_ORG("omdb.org"),
    /** omdbapi.com: used for IMDb / Rotten Tomatoes / Metacritic ratings and as a poster / synopsis fallback. */
    OMDB("OMDb API"),
    /** Official IMDb datasets imported locally (ratings, votes, Top 250). */
    IMDB("IMDb"),
    TRAKT("Trakt"),
    SIMKL("Simkl"),
}

/** Known external ids of a title. A single one is enough to find the others. */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class ExternalIds(
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val traktId: Int? = null,
    val simklId: Int? = null,
    val omdbOrgId: Int? = null,
) {
    val isEmpty: Boolean get() = tmdbId == null && imdbId == null && tvdbId == null && traktId == null && simklId == null && omdbOrgId == null

    fun merge(other: ExternalIds) = ExternalIds(
        tmdbId = tmdbId ?: other.tmdbId,
        imdbId = imdbId ?: other.imdbId,
        tvdbId = tvdbId ?: other.tvdbId,
        traktId = traktId ?: other.traktId,
        simklId = simklId ?: other.simklId,
        omdbOrgId = omdbOrgId ?: other.omdbOrgId,
    )
}

/** Lightweight search result (before enrichment). */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class MediaSummary(
    val ids: ExternalIds,
    val isSeries: Boolean,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val posterUrl: String? = null,
    val source: DataSource,
    /** Hints used to guess the category (TMDB genres, origin country…). */
    val kindHint: MediaKind? = null,
)

/** Aggregated ratings: TMDB (0-10), IMDb (0-10), Rotten Tomatoes (0-100 %), Metacritic (0-100). */
data class Ratings(
    val tmdb: Double? = null,
    val tmdbVotes: Int? = null,
    val imdb: Double? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoes: Int? = null,
    val metacritic: Int? = null,
)

/** An actor and their role. */
data class CastMember(val name: String, val character: String? = null)

/** Main credits: directors (or creators for a series), producers, leading actors. */
data class Credits(
    val directors: List<String> = emptyList(),
    val writers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val cast: List<CastMember> = emptyList(),
) {
    val isEmpty: Boolean get() = directors.isEmpty() && writers.isEmpty() && producers.isEmpty() && cast.isEmpty()

    /** Fills each empty field with the value from another source. */
    fun fillMissingFrom(o: Credits) = Credits(
        directors = directors.ifEmpty { o.directors },
        writers = writers.ifEmpty { o.writers },
        producers = producers.ifEmpty { o.producers },
        cast = cast.ifEmpty { o.cast },
    )
}

/** Full page of a title, as stored locally. */
data class MediaDetails(
    val ids: ExternalIds,
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
    val ratings: Ratings,
    val credits: Credits = Credits(),
    val posterSource: DataSource?,
    val overviewSource: DataSource?,
    val certification: String? = null,
    val status: String? = null,
    /** Release date (movie) or first-air date (series), ISO yyyy-MM-dd. */
    val releaseDate: String? = null,
    /** Pays d'origine, noms affichables. */
    val countries: List<String> = emptyList(),
    /** Studios (movie) or network then studios (series). */
    val studios: List<String> = emptyList(),
    /** Series: date of the next episode, ISO yyyy-MM-dd. */
    val nextAired: String? = null,
    /** Series: number of episodes of each season (index 0 = season 1). */
    val seasonEpisodes: List<Int> = emptyList(),
) {
    val imdbUrl: String? get() = ids.imdbId?.let { "https://www.imdb.com/title/$it/" }
    val tmdbUrl: String? get() = ids.tmdbId?.let { "https://www.themoviedb.org/${if (isSeries) "tv" else "movie"}/$it" }
    val rottenTomatoesUrl: String get() = "https://www.rottentomatoes.com/search?search=" + java.net.URLEncoder.encode(title, "UTF-8")
    val metacriticUrl: String get() = "https://www.metacritic.com/search/" + java.net.URLEncoder.encode(title, "UTF-8") + "/"
    val traktUrl: String? get() = ids.traktId?.let { "https://trakt.tv/${if (isSeries) "shows" else "movies"}/$it" }
    val simklUrl: String? get() = ids.simklId?.let { "https://simkl.com/${if (isSeries) "tv" else "movies"}/$it" }
    val omdbOrgUrl: String? get() = ids.omdbOrgId?.let { "https://www.omdb.org/movie/$it" }
}

/** What the user entered for a title in their library. */
data class UserTracking(
    val status: WatchStatus = WatchStatus.PLANNED,
    val currentSeason: Int = 0,
    val currentEpisode: Int = 0,
    val addedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastSyncedTrakt: Long? = null,
    val lastSyncedSimkl: Long? = null,
    /** Status and position as last pushed to / pulled from each service (null status = never): drives delta pushes. */
    val traktSyncedStatus: WatchStatus? = null,
    val traktSyncedSeason: Int = 0,
    val traktSyncedEpisode: Int = 0,
    val simklSyncedStatus: WatchStatus? = null,
    val simklSyncedSeason: Int = 0,
    val simklSyncedEpisode: Int = 0,
)

data class LibraryItem(
    val localId: Long,
    val details: MediaDetails,
    val tracking: UserTracking,
)

/** Guesses the category from genres and origin. */
fun guessKind(isSeries: Boolean, genreIds: List<Int>, genreNames: List<String>, originCountries: List<String>, originalLanguage: String?): MediaKind {
    val names = genreNames.map { it.lowercase() }
    // 16 = Animation (TMDB and omdb.org), 34 = Anime (omdb.org), 99 = Documentary (TMDB and omdb.org).
    val isAnimation = 16 in genreIds || names.any { it.contains("animation") || it.contains("anime") }
    val isAnimeGenre = 34 in genreIds || names.any { it == "anime" }
    val isJapanese = originCountries.any { it.equals("JP", true) || it.equals("JPN", true) } ||
        originalLanguage?.lowercase()?.let { it == "ja" || it == "jpn" } == true
    val isDoc = 99 in genreIds || names.any { it.contains("documentaire") || it.contains("documentary") }
    return when {
        isAnimeGenre || (isAnimation && isJapanese) -> MediaKind.ANIME
        isDoc -> MediaKind.DOCUMENTARY
        isSeries -> MediaKind.SERIES
        else -> MediaKind.MOVIE
    }
}

/** Fills the empty fields of `this` with those of `other` (never overwriting existing values). */
fun MediaDetails.fillMissingFrom(other: MediaDetails) = copy(
    ids = ids.merge(other.ids),
    overview = overview?.takeIf { it.isNotBlank() } ?: other.overview,
    overviewSource = if (!overview.isNullOrBlank()) overviewSource else other.overviewSource,
    posterUrl = posterUrl ?: other.posterUrl,
    posterSource = if (posterUrl != null) posterSource else other.posterSource,
    backdropUrl = backdropUrl ?: other.backdropUrl,
    genres = genres.ifEmpty { other.genres },
    runtimeMinutes = runtimeMinutes ?: other.runtimeMinutes,
    numberOfSeasons = numberOfSeasons ?: other.numberOfSeasons,
    numberOfEpisodes = numberOfEpisodes ?: other.numberOfEpisodes,
    year = year ?: other.year,
    originalTitle = originalTitle ?: other.originalTitle,
    certification = certification ?: other.certification,
    status = status ?: other.status,
    credits = credits.fillMissingFrom(other.credits),
    releaseDate = releaseDate ?: other.releaseDate,
    countries = countries.ifEmpty { other.countries },
    studios = studios.ifEmpty { other.studios },
    nextAired = nextAired ?: other.nextAired,
    seasonEpisodes = seasonEpisodes.ifEmpty { other.seasonEpisodes },
    ratings = ratings.copy(
        tmdb = ratings.tmdb ?: other.ratings.tmdb,
        tmdbVotes = ratings.tmdbVotes ?: other.ratings.tmdbVotes,
        imdb = ratings.imdb ?: other.ratings.imdb,
        imdbVotes = ratings.imdbVotes ?: other.ratings.imdbVotes,
        rottenTomatoes = ratings.rottenTomatoes ?: other.ratings.rottenTomatoes,
        metacritic = ratings.metacritic ?: other.ratings.metacritic,
    ),
    // A TMDB "Movie" page can be reclassified as Anime/Documentary thanks to TVDB/omdb.org genres.
    kind = if (kind == MediaKind.MOVIE || kind == MediaKind.SERIES) other.kind.takeIf { it == MediaKind.ANIME || it == MediaKind.DOCUMENTARY } ?: kind else kind,
)

/** One episode of a series (title and air date may be unknown). Air dates are ISO `yyyy-MM-dd`. */
data class Episode(val season: Int, val number: Int, val title: String? = null, val airDate: String? = null) {
    val ref: EpisodeRef get() = EpisodeRef(season, number)
}

/** A streaming / rental / purchase service offering a title in the user's region. */
data class WatchProvider(val name: String, val logoUrl: String?)

/** Where a title can be watched in one region (TMDB, data by JustWatch); [link] opens the JustWatch page. */
data class WatchProviders(
    val region: String,
    val link: String?,
    val stream: List<WatchProvider> = emptyList(),
    val free: List<WatchProvider> = emptyList(),
    val rent: List<WatchProvider> = emptyList(),
    val buy: List<WatchProvider> = emptyList(),
) {
    val isEmpty: Boolean get() = stream.isEmpty() && free.isEmpty() && rent.isEmpty() && buy.isEmpty()
}
