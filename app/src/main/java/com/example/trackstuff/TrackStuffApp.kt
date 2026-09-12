package com.example.trackstuff

import android.app.Application
import android.content.Context
import com.example.trackstuff.data.imdb.ImdbRepository
import com.example.trackstuff.data.local.AppDatabase
import com.example.trackstuff.data.local.ImdbDatabase
import com.example.trackstuff.data.local.OmdbOrgDatabase
import com.example.trackstuff.data.omdborg.OmdbOrgRepository
import com.example.trackstuff.data.remote.Network
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.data.sync.SimklSyncService
import com.example.trackstuff.data.sync.SyncCoordinator
import kotlinx.coroutines.launch
import com.example.trackstuff.data.sync.TraktSyncService

/** Dependency container (manual injection, one instance per application). */
class AppContainer(context: Context) {
    val settings = SettingsRepository(context)
    val database = AppDatabase.build(context)
    val omdbOrgDatabase = OmdbOrgDatabase.build(context)
    val omdbOrg = OmdbOrgRepository(context, omdbOrgDatabase.omdbOrgDao(), settings)
    val imdbDatabase = ImdbDatabase.build(context)
    val imdb = ImdbRepository(context, imdbDatabase.imdbDao(), settings, omdbOrg)
    val metadata = MetadataRepository(settings, omdbOrg, imdb)
    val library = LibraryRepository(database.mediaDao(), metadata, settings)
    val backup = com.example.trackstuff.data.repository.LibraryBackup(database.mediaDao(), library)
    val trakt = TraktSyncService(settings, library)
    val simkl = SimklSyncService(settings, library)
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val sync = SyncCoordinator(settings, trakt, simkl, appScope).also { c -> library.onLocalChange = { c.onLocalChange() } }
}

class TrackStuffApp : Application(), coil.ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // The HTTP cache must exist before the first request; synchronous (fast) read of the chosen size.
        val cacheMb = kotlinx.coroutines.runBlocking { container.settings.current().httpCacheMb }
        Network.init(this, cacheMb)
        // Explicit-refresh limits survive restarts.
        com.example.trackstuff.data.remote.RefreshLimiter.load(kotlinx.coroutines.runBlocking { container.settings.refreshLimits() })
        com.example.trackstuff.data.remote.RefreshLimiter.persist = { map -> container.appScope.launch { container.settings.saveRefreshLimits(map) } }
        // Retry a sync that failed offline as soon as the network is back.
        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val request = android.net.NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        cm.registerNetworkCallback(request, object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { container.sync.onNetworkAvailable() }
        })
    }

    /** Coil uses the shared HTTP client: posters and JSON responses in the same cache, same size setting. */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .diskCache(null)
        .callFactory(okhttp3.Call.Factory { request -> Network.client.newCall(request) })
        .respectCacheHeaders(true)
        .build()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as TrackStuffApp).container
