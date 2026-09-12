package com.example.trackstuff.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.trackstuff.BuildConfig
import com.squareup.moshi.Moshi
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shared HTTP client with a disk cache for TMDB, TVDB, OMDb API and posters (Coil goes through this client).
 *
 * - JSON responses kept for [JSON_TTL_HOURS] h, images for [IMAGE_TTL_DAYS] days, whatever the server header says:
 *   reopening a page or repeating a search makes no new request.
 * - Without network, the cache is served even when stale (up to [OFFLINE_MAX_STALE_DAYS] days).
 * - Size adjustable in tiers ([CACHE_TIERS_MB], 0 = unlimited), applied immediately.
 */
object Network {
    /** Offered tiers, in MB; 0 = unlimited. */
    val CACHE_TIERS_MB = listOf(200, 500, 1024, 2048, 5120, 0)
    const val DEFAULT_CACHE_MB = 1024
    private const val JSON_TTL_HOURS = 24
    /** Detail pages and OMDb lookups change slowly; a longer lifetime also protects the OMDb daily quota (1,000 requests). */
    private const val DETAIL_TTL_DAYS = 7
    private const val IMAGE_TTL_DAYS = 30
    private const val OFFLINE_MAX_STALE_DAYS = 30
    private const val UNLIMITED_BYTES = Long.MAX_VALUE / 4

    val moshi: Moshi = Moshi.Builder().add(NullToEmptyListAdapterFactory()).add(com.example.trackstuff.data.remote.tvdb.TvdbCompaniesAdapter()).build()

    private lateinit var appContext: Context
    private lateinit var cacheDir: File
    @Volatile private var cache: Cache? = null

    @Volatile
    var client: OkHttpClient = baseBuilder().build()
        private set

    /** Call once at application start-up. */
    fun init(context: Context, cacheSizeMb: Int) {
        appContext = context.applicationContext
        cacheDir = File(appContext.cacheDir, "http")
        setCacheSize(cacheSizeMb)
    }

    fun labelFor(sizeMb: Int): String = when {
        sizeMb <= 0 -> "Unlimited"
        sizeMb >= 1024 -> "${sizeMb / 1024} Go"
        else -> "$sizeMb Mo"
    }

    private fun bytesFor(sizeMb: Int) = if (sizeMb <= 0) UNLIMITED_BYTES else sizeMb * 1024L * 1024L

    /**
     * Changes the maximum cache size in place. The cache is never closed nor recreated: an in-flight
     * request would otherwise keep writing to a closed cache ("cache is closed"). OkHttp exposes no
     * public setter, so the internal DiskLruCache is used; on failure the size applies at the next
     * start-up.
     */
    @Synchronized
    fun setCacheSize(sizeMb: Int) {
        if (!::cacheDir.isInitialized) return
        val bytes = bytesFor(sizeMb)
        val current = cache
        if (current == null) {
            val created = Cache(cacheDir, bytes)
            cache = created
            client = baseBuilder()
                .cache(created)
                .addInterceptor(offlineInterceptor)
                .addNetworkInterceptor(cacheTtlInterceptor)
                .build()
            return
        }
        if (current.maxSize() == bytes) return
        runCatching {
            val field = Cache::class.java.getDeclaredField("cache").apply { isAccessible = true }
            val disk = field.get(current)
            disk.javaClass.getMethod("setMaxSize", java.lang.Long.TYPE).invoke(disk, bytes)
        }.onFailure { android.util.Log.w("Network", "Cache size: applied at next start-up", it) }
    }

    /** Space currently used on disk, in bytes. */
    fun cacheSizeBytes(): Long = runCatching { cache?.size() ?: 0L }.getOrDefault(0L)

    fun clearCache() {
        runCatching { cache?.evictAll() }
    }

    /**
     * Retrofit factory whose every call goes through the current client (hence the current cache, even
     * after a size change), with an API-specific interceptor (authentication).
     */
    fun retrofit(baseUrl: String, apiInterceptor: Interceptor? = null): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .callFactory(Call.Factory { request ->
            val c = if (apiInterceptor == null) client else client.newBuilder().addInterceptor(apiInterceptor).build()
            c.newCall(request)
        })
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    // ------------------------------------------------------------------ internals

    private fun baseBuilder() = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .apply {
            if (BuildConfig.DEBUG) addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        }

    /** Cached hosts. Trakt and Simkl (sync, pairing) must stay fresh: never cached. */
    private val JSON_HOSTS = setOf("api.themoviedb.org", "api4.thetvdb.com", "www.omdbapi.com")
    private val IMAGE_HOSTS = setOf("image.tmdb.org", "artworks.thetvdb.com", "www.omdb.org", "m.media-amazon.com", "simkl.in")

    private fun isCacheable(request: Request) = request.method == "GET" && (request.url.host in JSON_HOSTS || request.url.host in IMAGE_HOSTS)
    /** List endpoints (charts, search, lookups by name) are refreshed daily; everything else is a detail page. */
    private fun isListRequest(request: Request): Boolean {
        val p = request.url.encodedPath
        return LIST_PATHS.any { it in p } || (request.url.host == "www.omdbapi.com" && request.url.queryParameter("t") != null)
    }
    private val LIST_PATHS = listOf("/trending/", "/popular", "/now_playing", "/on_the_air", "/search", "/filter", "/find/")

    private fun ttlSeconds(request: Request) = when {
        request.url.host in IMAGE_HOSTS -> IMAGE_TTL_DAYS * 86400
        isListRequest(request) -> JSON_TTL_HOURS * 3600
        else -> DETAIL_TTL_DAYS * 86400
    }

    /**
     * Drops the cached responses whose URL matches [predicate], so that the next call hits the network.
     * Used by the explicit "refresh" actions; automatic loads always go through the cache.
     */
    fun evict(predicate: (String) -> Boolean) {
        val cache = client.cache ?: return
        runCatching {
            val it = cache.urls()
            while (it.hasNext()) if (predicate(it.next())) it.remove()
        }.onFailure { android.util.Log.w("Network", "Cache eviction failed", it) }
    }

    /** Enforces a uniform lifetime on GET responses of the metadata databases, overriding server headers. */
    private val cacheTtlInterceptor = Interceptor { chain ->
        val response = chain.proceed(chain.request())
        if (!isCacheable(chain.request())) response.newBuilder().header("Cache-Control", "no-store").build()
        else if (!response.isSuccessful) response
        else response.newBuilder()
            .removeHeader("Pragma")
            .removeHeader("Expires")
            .header("Cache-Control", "public, max-age=${ttlSeconds(chain.request())}")
            .build()
    }

    /** Offline: uses only the cache, even stale. */
    private val offlineInterceptor = Interceptor { chain ->
        val request: Request = if (isOnline() || !isCacheable(chain.request())) chain.request()
        else chain.request().newBuilder()
            .cacheControl(CacheControl.Builder().onlyIfCached().maxStale(OFFLINE_MAX_STALE_DAYS, TimeUnit.DAYS).build())
            .build()
        chain.proceed(request)
    }

    private fun isOnline(): Boolean {
        if (!::appContext.isInitialized) return true
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
