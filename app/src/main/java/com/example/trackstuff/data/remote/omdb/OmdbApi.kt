package com.example.trackstuff.data.remote.omdb

import com.example.trackstuff.data.remote.Network
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class OmdbRating(@Json(name = "Source") val source: String, @Json(name = "Value") val value: String)

/** OMDb record. Missing fields hold the string "N/A". */
@JsonClass(generateAdapter = true)
data class OmdbTitle(
    @Json(name = "Response") val response: String = "False",
    @Json(name = "Error") val error: String? = null,
    @Json(name = "Title") val title: String? = null,
    @Json(name = "Year") val year: String? = null,
    @Json(name = "Rated") val rated: String? = null,
    @Json(name = "Runtime") val runtime: String? = null,
    @Json(name = "Genre") val genre: String? = null,
    @Json(name = "Director") val director: String? = null,
    @Json(name = "Writer") val writer: String? = null,
    @Json(name = "Actors") val actors: String? = null,
    @Json(name = "Released") val released: String? = null,
    @Json(name = "Country") val country: String? = null,
    @Json(name = "Production") val production: String? = null,
    @Json(name = "Plot") val plot: String? = null,
    @Json(name = "Poster") val poster: String? = null,
    @Json(name = "Ratings") val ratings: List<OmdbRating> = emptyList(),
    @Json(name = "Metascore") val metascore: String? = null,
    @Json(name = "imdbRating") val imdbRating: String? = null,
    @Json(name = "imdbVotes") val imdbVotes: String? = null,
    @Json(name = "imdbID") val imdbId: String? = null,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "totalSeasons") val totalSeasons: String? = null,
) {
    val ok get() = response.equals("True", ignoreCase = true)

    fun imdbScore(): Double? = imdbRating?.toDoubleOrNull()
    fun imdbVoteCount(): Int? = imdbVotes?.replace(",", "")?.toIntOrNull()
    fun rottenTomatoes(): Int? = ratings.firstOrNull { it.source.contains("Rotten", true) }?.value?.removeSuffix("%")?.toIntOrNull()
    fun metacriticScore(): Int? = metascore?.toIntOrNull()
        ?: ratings.firstOrNull { it.source.contains("Metacritic", true) }?.value?.substringBefore("/")?.toIntOrNull()
    fun runtimeMinutes(): Int? = runtime?.filter { it.isDigit() }?.toIntOrNull()
    fun firstYear(): Int? = year?.take(4)?.toIntOrNull()
    fun posterUrl(): String? = poster?.takeIf { it.startsWith("http") }
    fun plotText(): String? = plot?.takeIf { it != "N/A" && it.isNotBlank() }
    fun genres(): List<String> = genre?.takeIf { it != "N/A" }?.split(",")?.map { it.trim() } ?: emptyList()
    fun directors(): List<String> = director?.takeIf { it != "N/A" }?.split(",")?.map { it.trim() } ?: emptyList()
    fun actorNames(): List<String> = actors?.takeIf { it != "N/A" }?.split(",")?.map { it.trim() } ?: emptyList()
    /** OMDb suffixes writers: "James Cameron (screenplay), …" — the parenthesis is stripped. */
    fun writers(): List<String> = writer?.takeIf { it != "N/A" }?.split(",")?.map { it.substringBefore("(").trim() }?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
    fun countries(): List<String> = country?.takeIf { it != "N/A" }?.split(",")?.map { it.trim() } ?: emptyList()
    fun studios(): List<String> = production?.takeIf { it != "N/A" }?.split(",")?.map { it.trim() } ?: emptyList()
    /** "19 Dec 1997" → "1997-12-19". */
    fun releaseIso(): String? = released?.takeIf { it != "N/A" }?.let {
        runCatching { java.time.LocalDate.parse(it, java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH)).toString() }.getOrNull()
    }
}

@JsonClass(generateAdapter = true)
data class OmdbSearchItem(
    @Json(name = "Title") val title: String,
    @Json(name = "Year") val year: String? = null,
    @Json(name = "imdbID") val imdbId: String,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "Poster") val poster: String? = null,
)

@JsonClass(generateAdapter = true)
data class OmdbSearchResponse(
    @Json(name = "Search") val search: List<OmdbSearchItem> = emptyList(),
    @Json(name = "Response") val response: String = "False",
    @Json(name = "Error") val error: String? = null,
)

interface OmdbApi {
    @GET("/")
    suspend fun byImdbId(@Query("apikey") apiKey: String, @Query("i") imdbId: String, @Query("plot") plot: String = "full"): OmdbTitle

    @GET("/")
    suspend fun byTitle(
        @Query("apikey") apiKey: String,
        @Query("t") title: String,
        @Query("y") year: Int? = null,
        @Query("type") type: String? = null,
        @Query("plot") plot: String = "full",
    ): OmdbTitle

    @GET("/")
    suspend fun search(
        @Query("apikey") apiKey: String,
        @Query("s") query: String,
        @Query("type") type: String? = null,
        @Query("page") page: Int = 1,
    ): OmdbSearchResponse

    companion object {
        fun create(): OmdbApi = Network.retrofit("https://www.omdbapi.com/").create(OmdbApi::class.java)
    }
}
