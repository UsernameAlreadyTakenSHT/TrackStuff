package com.example.trackstuff.data.remote.tmdb

import com.example.trackstuff.data.remote.Network
import okhttp3.Interceptor
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    @GET("search/multi")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("language") language: String,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false,
    ): TmdbPage<TmdbSearchResult>

    @GET("movie/{id}")
    suspend fun movie(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String = "external_ids,credits,keywords,release_dates",
    ): TmdbMovie

    @GET("tv/{id}")
    suspend fun tv(
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String = "external_ids,credits,keywords,content_ratings",
    ): TmdbTv

    @GET("trending/all/{window}")
    suspend fun trending(@Path("window") window: String = "week", @Query("language") language: String, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("movie/popular")
    suspend fun popularMovies(@Query("language") language: String, @Query("region") region: String?, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("tv/popular")
    suspend fun popularTv(@Query("language") language: String, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("movie/now_playing")
    suspend fun nowPlaying(@Query("language") language: String, @Query("region") region: String?, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("tv/on_the_air")
    suspend fun onTheAir(@Query("language") language: String, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("find/{externalId}")
    suspend fun find(
        @Path("externalId") externalId: String,
        @Query("external_source") source: String,
        @Query("language") language: String,
    ): TmdbFindResult

    companion object {
        const val BASE_URL = "https://api.themoviedb.org/3/"
        const val IMAGE_BASE = "https://image.tmdb.org/t/p/"

        fun posterUrl(path: String?, size: String = "w500") = path?.let { "$IMAGE_BASE$size$it" }
        fun backdropUrl(path: String?) = path?.let { "${IMAGE_BASE}w1280$it" }
        fun logoUrl(path: String?) = path?.let { "${IMAGE_BASE}w92$it" }

        /**
         * TMDB accepts either a v3 key (`api_key=`) or a v4 access token (Bearer JWT).
         * The JWT is detected by its "eyJ" prefix.
         */
        fun create(apiKey: String): TmdbApi {
            val auth = Interceptor { chain ->
                val req = chain.request()
                val newReq = if (apiKey.startsWith("eyJ")) {
                    req.newBuilder().header("Authorization", "Bearer $apiKey").build()
                } else {
                    val url = req.url.newBuilder().addQueryParameter("api_key", apiKey).build()
                    req.newBuilder().url(url).build()
                }
                chain.proceed(newReq)
            }
            return Network.retrofit(BASE_URL, auth).create(TmdbApi::class.java)
        }
    }
}
