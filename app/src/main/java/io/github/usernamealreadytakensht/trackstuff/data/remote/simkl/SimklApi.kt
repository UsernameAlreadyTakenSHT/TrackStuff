package io.github.usernamealreadytakensht.trackstuff.data.remote.simkl

import io.github.usernamealreadytakensht.trackstuff.data.remote.Network
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.Interceptor
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

// ---- OAuth (PIN) ----

@JsonClass(generateAdapter = true)
data class SimklPin(
    val result: String? = null,
    @Json(name = "device_code") val deviceCode: String? = null,
    @Json(name = "user_code") val userCode: String? = null,
    @Json(name = "verification_url") val verificationUrl: String? = null,
    @Json(name = "expires_in") val expiresIn: Int? = null,
    val interval: Int? = null,
    val message: String? = null,
    @Json(name = "access_token") val accessToken: String? = null,
)

// ---- Sync objects ----

@JsonClass(generateAdapter = true)
data class SimklIds(
    val simkl: Int? = null,
    val slug: String? = null,
    val imdb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
) {
    fun tmdbInt() = tmdb?.toIntOrNull()
    fun tvdbInt() = tvdb?.toIntOrNull()
}

@JsonClass(generateAdapter = true)
data class SimklMedia(val title: String? = null, val year: Int? = null, val ids: SimklIds, val poster: String? = null)

@JsonClass(generateAdapter = true)
data class SimklEpisodePos(val season: Int? = null, val episode: Int? = null)

@JsonClass(generateAdapter = true)
data class SimklItem(
    val status: String? = null,
    @Json(name = "user_rating") val userRating: Int? = null,
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "last_watched") val lastWatched: String? = null,
    @Json(name = "watched_episodes_count") val watchedEpisodes: Int? = null,
    @Json(name = "total_episodes_count") val totalEpisodes: Int? = null,
    val movie: SimklMedia? = null,
    val show: SimklMedia? = null,
)

@JsonClass(generateAdapter = true)
data class SimklAllItems(
    val movies: List<SimklItem> = emptyList(),
    val shows: List<SimklItem> = emptyList(),
    val anime: List<SimklItem> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class SimklEpisodeRef(val number: Int)

@JsonClass(generateAdapter = true)
data class SimklSeasonRef(val number: Int, val episodes: List<SimklEpisodeRef>? = null)

@JsonClass(generateAdapter = true)
data class SimklSyncItem(
    val ids: SimklIds,
    val to: String? = null,
    val rating: Int? = null,
    val seasons: List<SimklSeasonRef>? = null,
)

@JsonClass(generateAdapter = true)
data class SimklSyncBody(
    val movies: List<SimklSyncItem> = emptyList(),
    val shows: List<SimklSyncItem> = emptyList(),
    val anime: List<SimklSyncItem> = emptyList(),
)

/** GET /sync/activities: timestamps of the latest changes (only `all` and the removal stamps are kept). */
@JsonClass(generateAdapter = true)
data class SimklActivities(
    val all: String? = null,
    @Json(name = "tv_shows") val tvShows: SimklTypeActivities? = null,
    val anime: SimklTypeActivities? = null,
    val movies: SimklTypeActivities? = null,
) {
    /** Fingerprint of the removal timestamps: when it changes, titles were removed from the lists. */
    val removedStamp: String get() = listOf(tvShows?.removedFromList, anime?.removedFromList, movies?.removedFromList).joinToString("|") { it ?: "" }
}

@JsonClass(generateAdapter = true)
data class SimklTypeActivities(@Json(name = "removed_from_list") val removedFromList: String? = null)

// Responses of the POST sync/… endpoints: `added` and `not_found` hold lists of objects (not counters).
@JsonClass(generateAdapter = true)
data class SimklSyncResult(val added: Map<String, Any?>? = null, @Json(name = "not_found") val notFound: Map<String, Any?>? = null) {
    fun notFoundCount(key: String) = (notFound?.get(key) as? List<*>)?.size ?: 0
}

interface SimklApi {
    @GET("oauth/pin")
    suspend fun requestPin(@Query("redirect") redirect: String = "urn:ietf:wg:oauth:2.0:oob"): SimklPin

    @GET("oauth/pin/{userCode}")
    suspend fun pollPin(@Path("userCode") userCode: String): SimklPin

    @GET("sync/activities")
    suspend fun activities(): SimklActivities

    /** Initial sync: one type at a time (shows / movies / anime), without date_from. */
    @GET("sync/all-items/{type}")
    suspend fun allItemsOf(@Path("type") type: String, @Query("extended") extended: String? = "full"): SimklAllItems?

    /** Later syncs: only the changes since the `all` timestamp of /sync/activities. */
    @GET("sync/all-items/")
    suspend fun allItemsSince(@Query("date_from") dateFrom: String, @Query("extended") extended: String = "full"): SimklAllItems?

    @POST("sync/add-to-list")
    suspend fun addToList(@Body body: SimklSyncBody): SimklSyncResult

    @POST("sync/history")
    suspend fun addHistory(@Body body: SimklSyncBody): SimklSyncResult

    @POST("sync/ratings")
    suspend fun addRatings(@Body body: SimklSyncBody): SimklSyncResult

    /** Removes the title from the history and from every list. */
    @POST("sync/history/remove")
    suspend fun removeFromHistory(@Body body: SimklSyncBody): SimklSyncResult

    companion object {
        const val BASE_URL = "https://api.simkl.com/"

        fun posterUrl(poster: String?) = poster?.let { "https://simkl.in/posters/${it}_m.jpg" }

        const val APP_NAME = "TrackStuff"

        fun create(clientId: String, appVersion: String, tokenProvider: () -> String?): SimklApi {
            // Simkl requires client_id, app-name and app-version as URL parameters and a descriptive
            // User-Agent on every request.
            val headers = Interceptor { chain ->
                val req = chain.request()
                val url = req.url.newBuilder()
                    .setQueryParameter("client_id", clientId)
                    .setQueryParameter("app-name", APP_NAME)
                    .setQueryParameter("app-version", appVersion)
                    .build()
                val b = req.newBuilder().url(url)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "$APP_NAME/$appVersion (Android)")
                    .header("simkl-api-key", clientId)
                tokenProvider()?.takeIf { it.isNotBlank() }?.let { b.header("Authorization", "Bearer $it") }
                chain.proceed(b.build())
            }
            return Network.retrofit(BASE_URL, headers).create(SimklApi::class.java)
        }
    }
}
