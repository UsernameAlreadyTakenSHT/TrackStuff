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

    /** Trending per type: `type` is `movie` or `tv` (the `all` list mixes people in and is capped at 100 mixed entries). */
    @GET("trending/{type}/{window}")
    suspend fun trending(@Path("type") type: String, @Path("window") window: String = "week", @Query("language") language: String, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("movie/popular")
    suspend fun popularMovies(@Query("language") language: String, @Query("region") region: String?, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    @GET("tv/popular")
    suspend fun popularTv(@Query("language") language: String, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    /** Movies with a theatrical release coming up in [region] (soonest first is not guaranteed: TMDB sorts by popularity). */
    @GET("movie/upcoming")
    suspend fun upcoming(@Query("language") language: String, @Query("region") region: String?, @Query("page") page: Int = 1): TmdbPage<TmdbSearchResult>

    /**
     * Best average vote among widely voted titles. TMDB's own `top_rated` lists use a low vote floor and are
     * dominated by freshly hyped releases; a high [minVotes] gives the classics instead.
     */
    @GET("discover/movie")
    suspend fun topRatedMovies(@Query("language") language: String, @Query("vote_count.gte") minVotes: Int, @Query("page") page: Int = 1, @Query("sort_by") sort: String = "vote_average.desc"): TmdbPage<TmdbSearchResult>

    @GET("discover/tv")
    suspend fun topRatedTv(@Query("language") language: String, @Query("vote_count.gte") minVotes: Int, @Query("page") page: Int = 1, @Query("sort_by") sort: String = "vote_average.desc"): TmdbPage<TmdbSearchResult>

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
