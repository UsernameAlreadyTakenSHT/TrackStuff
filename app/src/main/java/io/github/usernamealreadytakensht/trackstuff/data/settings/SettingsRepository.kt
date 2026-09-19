package io.github.usernamealreadytakensht.trackstuff.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.usernamealreadytakensht.trackstuff.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// A corrupt preferences file must not crash the app at every start: it is replaced by empty preferences.
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
)

/**
 * Secrets (API keys, client secrets, sign-in tokens) live in their own file, outside `datastore/`, so that
 * the Android cloud backup and device transfer leave them out (see backup_rules.xml). Values saved by earlier
 * versions in the settings file are moved over once, at the first access.
 */
private object SecretStore {
    private const val FILE = "secrets/secrets.preferences_pb"
    @Volatile private var instance: DataStore<Preferences>? = null

    fun get(context: Context): DataStore<Preferences> = instance ?: synchronized(this) {
        instance ?: androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() },
            migrations = listOf(MoveSecretsMigration(context.applicationContext)),
            produceFile = { java.io.File(context.applicationContext.filesDir, FILE) },
        ).also { instance = it }
    }
}

/** One-time move of the secret keys from the settings file to the secrets file. */
private class MoveSecretsMigration(private val context: Context) : androidx.datastore.core.DataMigration<Preferences> {
    private suspend fun leftovers(): Preferences = context.dataStore.data.first()

    override suspend fun shouldMigrate(currentData: Preferences): Boolean = leftovers().asMap().keys.any { it in SECRET_KEYS }

    @Suppress("UNCHECKED_CAST")
    override suspend fun migrate(currentData: Preferences): Preferences {
        val old = leftovers()
        val m = currentData.toMutablePreferences()
        for (k in SECRET_KEYS) {
            val v = old[k] ?: continue
            if (!m.contains(k)) m[k as Preferences.Key<Any>] = v
        }
        return m
    }

    override suspend fun cleanUp() {
        context.dataStore.edit { p -> SECRET_KEYS.forEach { p.remove(it) } }
    }
}

private fun String?.orDefault(default: String): String = if (isNullOrBlank()) default else this

/** Default page language: the phone's (e.g. fr-FR, en-US). */
fun defaultLanguage(): String = java.util.Locale.getDefault().let { l -> if (l.country.isBlank()) l.language else "${l.language}-${l.country}" }

/** Default region: the phone's, otherwise US. */
fun defaultRegion(): String = java.util.Locale.getDefault().country.ifBlank { "US" }

/** API keys and preferences. BuildConfig values (local.properties) serve as defaults. */
@com.squareup.moshi.JsonClass(generateAdapter = true)
data class AppSettings(
    val tmdbApiKey: String = "",
    val tvdbApiKey: String = "",
    val tvdbPin: String = "",
    val omdbApiKey: String = "",
    val language: String = defaultLanguage(),
    val region: String = defaultRegion(),
    val traktClientId: String = "",
    val traktClientSecret: String = "",
    val simklClientId: String = "",
    val simklClientSecret: String = "",
    /** Maximum HTTP cache size (MB). */
    val httpCacheMb: Int = io.github.usernamealreadytakensht.trackstuff.data.remote.Network.DEFAULT_CACHE_MB,
    /** IMDb datasets: true = all seven files (cast, translated titles), false = ratings, titles, episodes. */
    val imdbFullDatasets: Boolean = false,
    /** Service that has the last word when Trakt and Simkl are both connected ("trakt" or "simkl"). */
    val syncPrimary: String = "trakt",
) {
    val hasTmdb get() = tmdbApiKey.isNotBlank()
    val hasTvdb get() = tvdbApiKey.isNotBlank()
    val hasOmdb get() = omdbApiKey.isNotBlank()
    val hasTrakt get() = traktClientId.isNotBlank() && traktClientSecret.isNotBlank()
    val hasSimkl get() = simklClientId.isNotBlank() && simklClientSecret.isNotBlank()
}

/** Tokens obtained at run time (TVDB, Trakt, Simkl) and sync / import bookkeeping. */
data class AuthTokens(
    val tvdbToken: String = "",
    val tvdbTokenExpiresAt: Long = 0,
    val traktAccessToken: String = "",
    val traktRefreshToken: String = "",
    val traktExpiresAt: Long = 0,
    val simklAccessToken: String = "",
    /** `all` timestamp of Simkl /sync/activities at the last sync (empty = initial sync still to do). */
    val simklActivitiesAt: String = "",
    /** `all` timestamp of Trakt /sync/last_activities at the last sync. */
    val traktActivitiesAt: String = "",
    /** Fingerprint of the Simkl `removed_from_list` timestamps at the last sync. */
    val simklRemovedStamp: String = "",
    /** Last completed sync, automatic or manual (0 = never). */
    val lastSyncAt: Long = 0,
    /** Date of the last omdb.org dump import (0 = never). */
    val omdbOrgImportedAt: Long = 0,
    /** Date of the last IMDb dataset import (0 = never). */
    val imdbImportedAt: Long = 0,
    /** True while an import rewrites the local databases; still true after a crash = data incomplete. */
    val imdbIncomplete: Boolean = false,
    val omdbOrgIncomplete: Boolean = false,
    /** Importer format version of the current IMDb data; an older value allows a re-import before the monthly limit. */
    val imdbFormatVersion: Int = 0,
) {
    val traktConnected get() = traktAccessToken.isNotBlank()
    val simklConnected get() = simklAccessToken.isNotBlank()
}

/** Preference keys. Those in [SECRET_KEYS] are stored in the secrets file, the others in the settings file. */
private object Keys {
    val TMDB = stringPreferencesKey("tmdb_api_key")
    val TVDB = stringPreferencesKey("tvdb_api_key")
    val TVDB_PIN = stringPreferencesKey("tvdb_pin")
    val OMDB = stringPreferencesKey("omdb_api_key")
    val LANGUAGE = stringPreferencesKey("language")
    val REGION = stringPreferencesKey("region")
    val TRAKT_ID = stringPreferencesKey("trakt_client_id")
    val TRAKT_SECRET = stringPreferencesKey("trakt_client_secret")
    val SIMKL_ID = stringPreferencesKey("simkl_client_id")
    val SIMKL_SECRET = stringPreferencesKey("simkl_client_secret")
    val HTTP_CACHE_MB = intPreferencesKey("http_cache_mb")
    val IMDB_FULL = androidx.datastore.preferences.core.booleanPreferencesKey("imdb_full_datasets")

    val TVDB_TOKEN = stringPreferencesKey("tvdb_token")
    val TVDB_TOKEN_EXP = longPreferencesKey("tvdb_token_exp")
    val TRAKT_ACCESS = stringPreferencesKey("trakt_access")
    val TRAKT_REFRESH = stringPreferencesKey("trakt_refresh")
    val TRAKT_EXP = longPreferencesKey("trakt_exp")
    val SIMKL_ACCESS = stringPreferencesKey("simkl_access")
    val SIMKL_ACTIVITIES = stringPreferencesKey("simkl_activities")
    val TRAKT_ACTIVITIES = stringPreferencesKey("trakt_activities")
    val SIMKL_REMOVED = stringPreferencesKey("simkl_removed_stamp")
    val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
    val PREFETCH_AT = longPreferencesKey("discover_prefetch_at")
    val SYNC_PRIMARY = stringPreferencesKey("sync_primary")
    val PENDING_REMOVALS = stringPreferencesKey("pending_removals")
    val REFRESH_LIMITS = stringPreferencesKey("refresh_limits")
    val OMDB_ORG_IMPORTED_AT = longPreferencesKey("omdb_org_imported_at")
    val IMDB_IMPORTED_AT = longPreferencesKey("imdb_imported_at")
    val IMDB_FORMAT = androidx.datastore.preferences.core.intPreferencesKey("imdb_format_version")
    val IMDB_INCOMPLETE = androidx.datastore.preferences.core.booleanPreferencesKey("imdb_incomplete")
    val OMDB_ORG_INCOMPLETE = androidx.datastore.preferences.core.booleanPreferencesKey("omdb_org_incomplete")
}

private val SECRET_KEYS: Set<Preferences.Key<*>> = setOf(
    Keys.TMDB, Keys.TVDB, Keys.TVDB_PIN, Keys.OMDB, Keys.TRAKT_ID, Keys.TRAKT_SECRET, Keys.SIMKL_ID, Keys.SIMKL_SECRET,
    Keys.TVDB_TOKEN, Keys.TVDB_TOKEN_EXP, Keys.TRAKT_ACCESS, Keys.TRAKT_REFRESH, Keys.TRAKT_EXP, Keys.SIMKL_ACCESS,
)

class SettingsRepository(private val context: Context) {
    private val secrets: DataStore<Preferences> get() = SecretStore.get(context)

    val settings: Flow<AppSettings> = kotlinx.coroutines.flow.combine(context.dataStore.data, secrets.data) { p, sec ->
        AppSettings(
            // An empty field in the app falls back to the local.properties value (BuildConfig).
            tmdbApiKey = sec[Keys.TMDB].orDefault(BuildConfig.TMDB_API_KEY),
            tvdbApiKey = sec[Keys.TVDB].orDefault(BuildConfig.TVDB_API_KEY),
            tvdbPin = sec[Keys.TVDB_PIN] ?: "",
            omdbApiKey = sec[Keys.OMDB].orDefault(BuildConfig.OMDB_API_KEY),
            language = p[Keys.LANGUAGE].orDefault(defaultLanguage()),
            region = p[Keys.REGION].orDefault(defaultRegion()),
            traktClientId = sec[Keys.TRAKT_ID].orDefault(BuildConfig.TRAKT_CLIENT_ID),
            traktClientSecret = sec[Keys.TRAKT_SECRET].orDefault(BuildConfig.TRAKT_CLIENT_SECRET),
            simklClientId = sec[Keys.SIMKL_ID].orDefault(BuildConfig.SIMKL_CLIENT_ID),
            simklClientSecret = sec[Keys.SIMKL_SECRET].orDefault(BuildConfig.SIMKL_CLIENT_SECRET),
            httpCacheMb = p[Keys.HTTP_CACHE_MB] ?: io.github.usernamealreadytakensht.trackstuff.data.remote.Network.DEFAULT_CACHE_MB,
            imdbFullDatasets = p[Keys.IMDB_FULL] ?: false,
            syncPrimary = p[Keys.SYNC_PRIMARY] ?: "trakt",
        )
    }

    val tokens: Flow<AuthTokens> = kotlinx.coroutines.flow.combine(context.dataStore.data, secrets.data) { p, sec ->
        AuthTokens(
            tvdbToken = sec[Keys.TVDB_TOKEN] ?: "",
            tvdbTokenExpiresAt = sec[Keys.TVDB_TOKEN_EXP] ?: 0,
            traktAccessToken = sec[Keys.TRAKT_ACCESS] ?: "",
            traktRefreshToken = sec[Keys.TRAKT_REFRESH] ?: "",
            traktExpiresAt = sec[Keys.TRAKT_EXP] ?: 0,
            simklAccessToken = sec[Keys.SIMKL_ACCESS] ?: "",
            simklActivitiesAt = p[Keys.SIMKL_ACTIVITIES] ?: "",
            traktActivitiesAt = p[Keys.TRAKT_ACTIVITIES] ?: "",
            simklRemovedStamp = p[Keys.SIMKL_REMOVED] ?: "",
            lastSyncAt = p[Keys.LAST_SYNC_AT] ?: 0,
            omdbOrgImportedAt = p[Keys.OMDB_ORG_IMPORTED_AT] ?: 0,
            imdbImportedAt = p[Keys.IMDB_IMPORTED_AT] ?: 0,
            imdbFormatVersion = p[Keys.IMDB_FORMAT] ?: 0,
            imdbIncomplete = p[Keys.IMDB_INCOMPLETE] ?: false,
            omdbOrgIncomplete = p[Keys.OMDB_ORG_INCOMPLETE] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()
    suspend fun currentTokens(): AuthTokens = tokens.first()

    suspend fun save(s: AppSettings) {
        secrets.edit { p ->
            p[Keys.TMDB] = s.tmdbApiKey.trim()
            p[Keys.TVDB] = s.tvdbApiKey.trim()
            p[Keys.TVDB_PIN] = s.tvdbPin.trim()
            p[Keys.OMDB] = s.omdbApiKey.trim()
            p[Keys.TRAKT_ID] = s.traktClientId.trim()
            p[Keys.TRAKT_SECRET] = s.traktClientSecret.trim()
            p[Keys.SIMKL_ID] = s.simklClientId.trim()
            p[Keys.SIMKL_SECRET] = s.simklClientSecret.trim()
        }
        context.dataStore.edit { p ->
            p[Keys.LANGUAGE] = s.language.trim()
            p[Keys.REGION] = s.region.trim().uppercase()
            p[Keys.HTTP_CACHE_MB] = s.httpCacheMb
            p[Keys.IMDB_FULL] = s.imdbFullDatasets
            p[Keys.SYNC_PRIMARY] = s.syncPrimary
        }
        io.github.usernamealreadytakensht.trackstuff.data.remote.Network.setCacheSize(s.httpCacheMb)
    }

    suspend fun saveTvdbToken(token: String, expiresAt: Long) {
        secrets.edit { p -> p[Keys.TVDB_TOKEN] = token; p[Keys.TVDB_TOKEN_EXP] = expiresAt }
    }

    // ---- Settings backup (keys, sync credentials, sign-in tokens, preferences)

    suspend fun exportSettings(): String {
        val s = current(); val t = currentTokens()
        return SettingsBackup.encode(SettingsBackup(settings = s, traktAccessToken = t.traktAccessToken, traktRefreshToken = t.traktRefreshToken, traktExpiresAt = t.traktExpiresAt, simklAccessToken = t.simklAccessToken))
    }

    /** Restores keys and preferences, and the sign-in tokens when the file has them (the sync state restarts from scratch). */
    suspend fun importSettings(json: String) {
        val b = SettingsBackup.decode(json)
        save(b.settings)
        if (b.traktAccessToken.isNotBlank()) { clearTrakt(); saveTraktTokens(b.traktAccessToken, b.traktRefreshToken, b.traktExpiresAt) }
        if (b.simklAccessToken.isNotBlank()) { clearSimkl(); saveSimklToken(b.simklAccessToken) }
    }

    suspend fun saveTraktTokens(access: String, refresh: String, expiresAt: Long) {
        secrets.edit { p -> p[Keys.TRAKT_ACCESS] = access; p[Keys.TRAKT_REFRESH] = refresh; p[Keys.TRAKT_EXP] = expiresAt }
    }

    suspend fun clearTrakt() {
        secrets.edit { p -> p.remove(Keys.TRAKT_ACCESS); p.remove(Keys.TRAKT_REFRESH); p.remove(Keys.TRAKT_EXP) }
        context.dataStore.edit { p -> p.remove(Keys.TRAKT_ACTIVITIES) }
    }

    suspend fun saveTraktActivitiesAt(at: String) {
        context.dataStore.edit { p -> p[Keys.TRAKT_ACTIVITIES] = at }
    }

    // ---- Explicit refresh timestamps (once an hour per target)

    suspend fun refreshLimits(): Map<String, Long> = io.github.usernamealreadytakensht.trackstuff.data.remote.RefreshLimiter.decode(context.dataStore.data.first()[Keys.REFRESH_LIMITS] ?: "")

    suspend fun saveRefreshLimits(map: Map<String, Long>) {
        context.dataStore.edit { p -> p[Keys.REFRESH_LIMITS] = io.github.usernamealreadytakensht.trackstuff.data.remote.RefreshLimiter.encode(map) }
    }

    // ---- Removals to propagate to Trakt / Simkl

    suspend fun pendingRemovals(): List<io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval> =
        io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval.decode(context.dataStore.data.first()[Keys.PENDING_REMOVALS] ?: "")

    suspend fun setPendingRemovals(list: List<io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval>) {
        context.dataStore.edit { p -> if (list.isEmpty()) p.remove(Keys.PENDING_REMOVALS) else p[Keys.PENDING_REMOVALS] = io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval.encode(list) }
    }

    suspend fun addPendingRemoval(r: io.github.usernamealreadytakensht.trackstuff.data.sync.PendingRemoval) = setPendingRemovals(pendingRemovals() + r)

    // ---- Discover page prefetch (once every 12 h, across restarts)

    suspend fun prefetchedAt(): Long = context.dataStore.data.first()[Keys.PREFETCH_AT] ?: 0L

    suspend fun savePrefetchedAt(at: Long) {
        context.dataStore.edit { p -> p[Keys.PREFETCH_AT] = at }
    }

    suspend fun saveLastSyncAt(at: Long) {
        context.dataStore.edit { p -> p[Keys.LAST_SYNC_AT] = at }
    }

    suspend fun saveSimklToken(access: String) {
        secrets.edit { p -> p[Keys.SIMKL_ACCESS] = access }
    }

    suspend fun saveSimklActivitiesAt(at: String, removedStamp: String) {
        context.dataStore.edit { p -> p[Keys.SIMKL_ACTIVITIES] = at; p[Keys.SIMKL_REMOVED] = removedStamp }
    }

    suspend fun clearSimkl() {
        secrets.edit { p -> p.remove(Keys.SIMKL_ACCESS) }
        context.dataStore.edit { p -> p.remove(Keys.SIMKL_ACTIVITIES); p.remove(Keys.SIMKL_REMOVED) }
    }

    suspend fun saveOmdbOrgImportedAt(at: Long) {
        context.dataStore.edit { p -> p[Keys.OMDB_ORG_IMPORTED_AT] = at; p[Keys.OMDB_ORG_INCOMPLETE] = false }
    }

    suspend fun saveImdbImportedAt(at: Long, formatVersion: Int) {
        context.dataStore.edit { p -> p[Keys.IMDB_IMPORTED_AT] = at; p[Keys.IMDB_FORMAT] = formatVersion; p[Keys.IMDB_INCOMPLETE] = false }
    }

    /** Marks the local IMDb / omdb.org data as being rewritten (cleared again when the import completes). */
    suspend fun setImportIncomplete(imdb: Boolean? = null, omdbOrg: Boolean? = null) {
        context.dataStore.edit { p -> imdb?.let { p[Keys.IMDB_INCOMPLETE] = it }; omdbOrg?.let { p[Keys.OMDB_ORG_INCOMPLETE] = it } }
    }
}
