package io.github.usernamealreadytakensht.trackstuff.data.remote.tmdb

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbPage<T>(
    val page: Int = 1,
    val results: List<T> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1,
)

/** Result of /search/multi (movies, series and people mixed). */
@JsonClass(generateAdapter = true)
data class TmdbSearchResult(
    val id: Int,
    @Json(name = "media_type") val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int> = emptyList(),
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbExternalIds(
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "tvdb_id") val tvdbId: Int? = null,
)


@JsonClass(generateAdapter = true)
data class TmdbCast(val name: String, val character: String? = null, val order: Int? = null)

@JsonClass(generateAdapter = true)
data class TmdbCrew(val name: String, val job: String? = null)

@JsonClass(generateAdapter = true)
data class TmdbCredits(val cast: List<TmdbCast> = emptyList(), val crew: List<TmdbCrew> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbCreator(val name: String)

@JsonClass(generateAdapter = true)
data class TmdbCompany(val name: String)

@JsonClass(generateAdapter = true)
data class TmdbCountry(@Json(name = "iso_3166_1") val iso: String)

@JsonClass(generateAdapter = true)
data class TmdbEpisodeRef(@Json(name = "air_date") val airDate: String? = null)

@JsonClass(generateAdapter = true)
data class TmdbSeasonSummary(@Json(name = "season_number") val seasonNumber: Int, @Json(name = "episode_count") val episodeCount: Int = 0)

@JsonClass(generateAdapter = true)
data class TmdbKeyword(val id: Int, val name: String)

@JsonClass(generateAdapter = true)
data class TmdbKeywords(
    val keywords: List<TmdbKeyword> = emptyList(),
    val results: List<TmdbKeyword> = emptyList(),
) {
    val all get() = keywords + results
}

@JsonClass(generateAdapter = true)
data class TmdbReleaseDate(val certification: String? = null, val type: Int? = null)

@JsonClass(generateAdapter = true)
data class TmdbReleaseDatesEntry(@Json(name = "iso_3166_1") val country: String, @Json(name = "release_dates") val releaseDates: List<TmdbReleaseDate> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbReleaseDates(val results: List<TmdbReleaseDatesEntry> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbContentRating(@Json(name = "iso_3166_1") val country: String, val rating: String? = null)

@JsonClass(generateAdapter = true)
data class TmdbContentRatings(val results: List<TmdbContentRating> = emptyList())

@JsonClass(generateAdapter = true)
data class TmdbMovie(
    val id: Int,
    val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val runtime: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null,
    val status: String? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    val keywords: TmdbKeywords? = null,
    @Json(name = "release_dates") val releaseDates: TmdbReleaseDates? = null,
    @Json(name = "production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    @Json(name = "production_countries") val productionCountries: List<TmdbCountry> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TmdbTv(
    val id: Int,
    val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @Json(name = "episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    @Json(name = "number_of_episodes") val numberOfEpisodes: Int? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "origin_country") val originCountry: List<String> = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null,
    val status: String? = null,
    @Json(name = "external_ids") val externalIds: TmdbExternalIds? = null,
    val credits: TmdbCredits? = null,
    @Json(name = "created_by") val createdBy: List<TmdbCreator> = emptyList(),
    val keywords: TmdbKeywords? = null,
    @Json(name = "content_ratings") val contentRatings: TmdbContentRatings? = null,
    val networks: List<TmdbCompany> = emptyList(),
    @Json(name = "production_companies") val productionCompanies: List<TmdbCompany> = emptyList(),
    @Json(name = "next_episode_to_air") val nextEpisodeToAir: TmdbEpisodeRef? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

/** Response of /find/{external_id}. */
@JsonClass(generateAdapter = true)
data class TmdbFindResult(
    @Json(name = "movie_results") val movieResults: List<TmdbSearchResult> = emptyList(),
    @Json(name = "tv_results") val tvResults: List<TmdbSearchResult> = emptyList(),
)

// ---- Seasons and episodes ----

@JsonClass(generateAdapter = true)
data class TmdbSeason(
    @Json(name = "season_number") val seasonNumber: Int = 0,
    val episodes: List<TmdbEpisode> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "season_number") val seasonNumber: Int = 0,
    val name: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
)

// ---- Watch providers (JustWatch data) ----

@JsonClass(generateAdapter = true)
data class TmdbWatchProviders(val results: Map<String, TmdbRegionProviders> = emptyMap())

@JsonClass(generateAdapter = true)
data class TmdbRegionProviders(
    val link: String? = null,
    val flatrate: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList(),
    val ads: List<TmdbProvider> = emptyList(),
    val rent: List<TmdbProvider> = emptyList(),
    val buy: List<TmdbProvider> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TmdbProvider(
    @Json(name = "provider_name") val name: String,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "display_priority") val priority: Int = 0,
)
