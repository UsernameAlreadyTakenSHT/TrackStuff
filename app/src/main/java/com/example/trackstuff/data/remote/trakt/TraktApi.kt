package com.example.trackstuff.data.remote.trakt

import com.example.trackstuff.data.remote.Network
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.Interceptor
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ---- OAuth (device code) ----

@JsonClass(generateAdapter = true)
data class TraktDeviceCodeRequest(@Json(name = "client_id") val clientId: String)

@JsonClass(generateAdapter = true)
data class TraktDeviceCode(
    @Json(name = "device_code") val deviceCode: String,
    @Json(name = "user_code") val userCode: String,
    @Json(name = "verification_url") val verificationUrl: String,
    @Json(name = "expires_in") val expiresIn: Int,
    val interval: Int,
)

@JsonClass(generateAdapter = true)
data class TraktDeviceTokenRequest(
    val code: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String,
)

@JsonClass(generateAdapter = true)
data class TraktRefreshRequest(
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "client_id") val clientId: String,
    @Json(name = "client_secret") val clientSecret: String,
    @Json(name = "redirect_uri") val redirectUri: String = "urn:ietf:wg:oauth:2.0:oob",
    @Json(name = "grant_type") val grantType: String = "refresh_token",
)

@JsonClass(generateAdapter = true)
data class TraktToken(
    @Json(name = "access_token") val accessToken: String,
    @Json(name = "refresh_token") val refreshToken: String,
    @Json(name = "expires_in") val expiresIn: Long,
    @Json(name = "created_at") val createdAt: Long,
)

// ---- Sync objects ----

@JsonClass(generateAdapter = true)
data class TraktIds(
    val trakt: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
    val tvdb: Int? = null,
)

@JsonClass(generateAdapter = true)
data class TraktMedia(val title: String? = null, val year: Int? = null, val ids: TraktIds, @Json(name = "aired_episodes") val airedEpisodes: Int? = null)

@JsonClass(generateAdapter = true)
data class TraktEpisodeRef(val number: Int, val plays: Int? = null)

@JsonClass(generateAdapter = true)
data class TraktSeasonRef(val number: Int, val episodes: List<TraktEpisodeRef> = emptyList())

/** Generic entry of the watchlist / watched / ratings lists. */
@JsonClass(generateAdapter = true)
data class TraktEntry(
    val type: String? = null,
    val rating: Int? = null,
    val plays: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    val movie: TraktMedia? = null,
    val show: TraktMedia? = null,
    val seasons: List<TraktSeasonRef> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TraktSyncMovie(val ids: TraktIds, val rating: Int? = null)

@JsonClass(generateAdapter = true)
data class TraktSyncShow(val ids: TraktIds, val rating: Int? = null, val seasons: List<TraktSeasonRef>? = null)

@JsonClass(generateAdapter = true)
data class TraktSyncBody(
    val movies: List<TraktSyncMovie> = emptyList(),
    val shows: List<TraktSyncShow> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TraktSyncResult(val added: Map<String, Int>? = null, val existing: Map<String, Int>? = null, val deleted: Map<String, Int>? = null, @Json(name = "not_found") val notFound: TraktNotFound? = null)

/** Items Trakt could not resolve from the ids sent. */
@JsonClass(generateAdapter = true)
data class TraktNotFound(val movies: List<TraktIdsHolder> = emptyList(), val shows: List<TraktIdsHolder> = emptyList()) {
    fun all(): List<TraktIds> = (movies + shows).map { it.ids }
}

@JsonClass(generateAdapter = true)
data class TraktIdsHolder(val ids: TraktIds)

@JsonClass(generateAdapter = true)
data class TraktActivities(val all: String? = null)

interface TraktApi {
    @POST("oauth/device/code")
    suspend fun deviceCode(@Body body: TraktDeviceCodeRequest): TraktDeviceCode

    @POST("oauth/device/token")
    suspend fun deviceToken(@Body body: TraktDeviceTokenRequest): Response<TraktToken>

    @POST("oauth/token")
    suspend fun refresh(@Body body: TraktRefreshRequest): TraktToken

    /** Timestamps of the latest account changes; the lists are only read when `all` moved. */
    /** Summary by Trakt id or URL slug; no sign-in needed. */
    @GET("shows/{id}")
    suspend fun show(@Path("id") id: String): TraktMedia

    @GET("movies/{id}")
    suspend fun movie(@Path("id") id: String): TraktMedia

    @GET("sync/last_activities")
    suspend fun lastActivities(): TraktActivities

    @GET("sync/watchlist/movies")
    suspend fun watchlistMovies(): List<TraktEntry>

    @GET("sync/watchlist/shows")
    suspend fun watchlistShows(): List<TraktEntry>

    @GET("sync/watched/movies")
    suspend fun watchedMovies(): List<TraktEntry>

    @GET("sync/watched/shows")
    suspend fun watchedShows(@Query("extended") extended: String = "noseasons"): List<TraktEntry>

    @GET("sync/ratings/movies")
    suspend fun ratingsMovies(): List<TraktEntry>

    @GET("sync/ratings/shows")
    suspend fun ratingsShows(): List<TraktEntry>

    @POST("sync/watchlist")
    suspend fun addToWatchlist(@Body body: TraktSyncBody): TraktSyncResult

    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(@Body body: TraktSyncBody): TraktSyncResult

    @POST("sync/history")
    suspend fun addToHistory(@Body body: TraktSyncBody): TraktSyncResult

    @POST("sync/ratings")
    suspend fun addRatings(@Body body: TraktSyncBody): TraktSyncResult

    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: TraktSyncBody): TraktSyncResult

    companion object {
        const val BASE_URL = "https://api.trakt.tv/"

        fun create(clientId: String, tokenProvider: () -> String?): TraktApi {
            val headers = Interceptor { chain ->
                val b = chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", clientId)
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
                chain.proceed(b.build())
            }
            return Network.retrofit(BASE_URL, headers).create(TraktApi::class.java)
        }
    }
}
