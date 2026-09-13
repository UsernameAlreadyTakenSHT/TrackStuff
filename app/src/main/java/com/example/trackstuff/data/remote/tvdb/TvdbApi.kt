package com.example.trackstuff.data.remote.tvdb

import com.example.trackstuff.data.remote.Network
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.Interceptor
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ---- TVDB v4 models ----

@JsonClass(generateAdapter = true)
data class TvdbLoginRequest(val apikey: String, val pin: String? = null)

@JsonClass(generateAdapter = true)
data class TvdbLoginData(val token: String)

@JsonClass(generateAdapter = true)
data class TvdbResponse<T>(val status: String? = null, val data: T? = null)

@JsonClass(generateAdapter = true)
data class TvdbRemoteId(val id: String? = null, val type: Int? = null, val sourceName: String? = null)

@JsonClass(generateAdapter = true)
data class TvdbSearchItem(
    @Json(name = "tvdb_id") val tvdbId: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @Json(name = "image_url") val imageUrl: String? = null,
    val year: String? = null,
    val type: String? = null,
    val country: String? = null,
    @Json(name = "primary_language") val primaryLanguage: String? = null,
    val genres: List<String> = emptyList(),
    val overviews: Map<String, String> = emptyMap(),
    val translations: Map<String, String> = emptyMap(),
    @Json(name = "remote_ids") val remoteIds: List<TvdbRemoteId> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TvdbNamed(val name: String? = null)

@JsonClass(generateAdapter = true)
data class TvdbTranslation(val language: String? = null, val name: String? = null, val overview: String? = null)

@JsonClass(generateAdapter = true)
data class TvdbTranslations(
    val nameTranslations: List<TvdbTranslation> = emptyList(),
    val overviewTranslations: List<TvdbTranslation> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TvdbSeason(val number: Int? = null, val type: TvdbNamed? = null)

/** Credited person: peopleType is Actor, Director, Producer, Writer, Creator… */
@JsonClass(generateAdapter = true)
data class TvdbCharacter(
    val name: String? = null,
    val personName: String? = null,
    val peopleType: String? = null,
    val sort: Int? = null,
    val isFeatured: Boolean? = null,
)

@JsonClass(generateAdapter = true)
data class TvdbRelease(val country: String? = null, val date: String? = null)

/**
 * Companies of a title. TVDB returns an object `{studio: [], network: [], …}` for movies but a flat
 * array `[{name, companyType: {companyTypeName}}]` for series: [TvdbCompaniesAdapter] accepts both.
 */
data class TvdbCompanies(val studio: List<TvdbNamed> = emptyList(), val network: List<TvdbNamed> = emptyList(), val production: List<TvdbNamed> = emptyList())

class TvdbCompaniesAdapter {
    @Suppress("UNCHECKED_CAST")
    @com.squareup.moshi.FromJson
    fun fromJson(reader: com.squareup.moshi.JsonReader): TvdbCompanies? {
        fun names(l: Any?) = (l as? List<Map<String, Any?>>)?.mapNotNull { m -> (m["name"] as? String)?.let { TvdbNamed(it) } } ?: emptyList()
        return when (val v = reader.readJsonValue()) {
            is Map<*, *> -> TvdbCompanies(studio = names(v["studio"]), network = names(v["network"]), production = names(v["production"]))
            is List<*> -> {
                val byType = (v as List<Map<String, Any?>>).groupBy { ((it["companyType"] as? Map<String, Any?>)?.get("companyTypeName") as? String) ?: "" }
                TvdbCompanies(
                    studio = names(byType["Studio"]),
                    network = names(byType["Network"]),
                    production = names(byType["Production Company"]),
                )
            }
            else -> null
        }
    }

    @com.squareup.moshi.ToJson
    fun toJson(c: TvdbCompanies): Map<String, Any> = mapOf("studio" to c.studio.map { mapOf("name" to it.name) }, "network" to c.network.map { mapOf("name" to it.name) })
}

@JsonClass(generateAdapter = true)
data class TvdbExtended(
    val id: Int,
    val name: String? = null,
    val image: String? = null,
    val overview: String? = null,
    val year: String? = null,
    val runtime: Int? = null,
    val averageRuntime: Int? = null,
    val status: TvdbNamed? = null,
    val genres: List<TvdbNamed> = emptyList(),
    val originalCountry: String? = null,
    val originalLanguage: String? = null,
    val remoteIds: List<TvdbRemoteId> = emptyList(),
    val translations: TvdbTranslations? = null,
    val seasons: List<TvdbSeason> = emptyList(),
    val characters: List<TvdbCharacter> = emptyList(),
    // Movies
    @Json(name = "first_release") val firstRelease: TvdbRelease? = null,
    val releases: List<TvdbRelease> = emptyList(),
    // Series
    val firstAired: String? = null,
    val nextAired: String? = null,
    val originalNetwork: TvdbNamed? = null,
    val companies: TvdbCompanies? = null,
)

@JsonClass(generateAdapter = true)
data class TvdbRemoteHit(val series: TvdbBaseRecord? = null, val movie: TvdbBaseRecord? = null)

/** Base record returned by the /series/filter and /movies/filter endpoints. */
@JsonClass(generateAdapter = true)
data class TvdbBaseRecord(
    val id: Int,
    val name: String? = null,
    val image: String? = null,
    val year: String? = null,
    val overview: String? = null,
    val originalCountry: String? = null,
    val originalLanguage: String? = null,
    val score: Long? = null,
)

// ---- API ----

interface TvdbApi {
    /** Top-ranked series (TVDB score) for an origin country / language. */
    @GET("series/filter")
    suspend fun seriesFilter(
        @Query("country") country: String,
        @Query("lang") lang: String,
        @Query("sort") sort: String = "score",
        @Query("sortType") sortType: String = "desc",
        @Query("year") year: Int? = null,
    ): TvdbResponse<List<TvdbBaseRecord>>

    @GET("movies/filter")
    suspend fun moviesFilter(
        @Query("country") country: String,
        @Query("lang") lang: String,
        @Query("sort") sort: String = "score",
        @Query("sortType") sortType: String = "desc",
        @Query("year") year: Int? = null,
    ): TvdbResponse<List<TvdbBaseRecord>>

    @POST("login")
    suspend fun login(@Body body: TvdbLoginRequest): TvdbResponse<TvdbLoginData>

    /** Lookup by external id (e.g. tt0133093): returns the matching series or movie. */
    @GET("search/remoteid/{remoteId}")
    suspend fun byRemoteId(@Path("remoteId") remoteId: String): TvdbResponse<List<TvdbRemoteHit>>

    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int = 20,
    ): TvdbResponse<List<TvdbSearchItem>>

    /** Episodes in the default order, 500 per page (`links.next` null on the last page). */
    @GET("series/{id}/episodes/default")
    suspend fun episodes(@Path("id") id: Int, @Query("page") page: Int = 0): TvdbEpisodesResponse

    /** Base record by URL slug (the part after `thetvdb.com/series/`). */
    @GET("series/slug/{slug}")
    suspend fun seriesBySlug(@Path("slug") slug: String): TvdbResponse<TvdbBaseRecord>

    @GET("movies/slug/{slug}")
    suspend fun movieBySlug(@Path("slug") slug: String): TvdbResponse<TvdbBaseRecord>

    @GET("series/{id}/extended")
    suspend fun series(@Path("id") id: Int, @Query("meta") meta: String = "translations"): TvdbResponse<TvdbExtended>

    @GET("movies/{id}/extended")
    suspend fun movie(@Path("id") id: Int, @Query("meta") meta: String = "translations"): TvdbResponse<TvdbExtended>

    companion object {
        const val BASE_URL = "https://api4.thetvdb.com/v4/"

        fun imageUrl(path: String?): String? = when {
            // TVDB returns a real URL for its "missing image" artwork: treat it as absent
            // so the next source can provide the poster.
            path.isNullOrBlank() || path.contains("/images/missing/") -> null
            path.startsWith("http") -> path
            else -> "https://artworks.thetvdb.com" + (if (path.startsWith("/")) path else "/banners/$path")
        }

        /**
         * Thumbnail of a TVDB artwork (`…_t.jpg`, about a third of the full size), for cards and rows.
         * TVDB generates it for every poster; the detail page keeps the full image.
         */
        fun thumbUrl(path: String?): String? = imageUrl(path)?.let { url ->
            val dot = url.lastIndexOf('.')
            if (dot > url.lastIndexOf('/') && !url.substring(0, dot).endsWith("_t")) url.substring(0, dot) + "_t" + url.substring(dot) else url
        }

        /** Converts a TMDB language code (fr-FR) into the 3-letter TVDB code (fra). */
        fun lang3(language: String): String = when (language.substringBefore('-').lowercase()) {
            "fr" -> "fra"; "en" -> "eng"; "de" -> "deu"; "es" -> "spa"; "it" -> "ita"; "pt" -> "por"
            "ja" -> "jpn"; "nl" -> "nld"; "ru" -> "rus"; "zh" -> "zho"; "ko" -> "kor"; else -> "eng"
        }


        fun create(tokenProvider: () -> String?): TvdbApi {
            val auth = Interceptor { chain ->
                val token = tokenProvider()
                val req = if (token.isNullOrBlank()) chain.request()
                else chain.request().newBuilder().header("Authorization", "Bearer $token").build()
                chain.proceed(req)
            }
            return Network.retrofit(BASE_URL, auth).create(TvdbApi::class.java)
        }
    }
}

@JsonClass(generateAdapter = true)
data class TvdbEpisodesResponse(val data: TvdbEpisodesData? = null, val links: TvdbLinks? = null)

@JsonClass(generateAdapter = true)
data class TvdbEpisodesData(val episodes: List<TvdbEpisode> = emptyList())

@JsonClass(generateAdapter = true)
data class TvdbEpisode(
    val seasonNumber: Int? = null,
    val number: Int? = null,
    val name: String? = null,
    /** ISO date. */
    val aired: String? = null,
)

@JsonClass(generateAdapter = true)
data class TvdbLinks(val next: String? = null)
