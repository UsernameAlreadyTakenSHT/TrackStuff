package com.example.trackstuff.data.sync

import android.util.Log
import com.example.trackstuff.data.remote.trakt.TraktApi
import com.example.trackstuff.data.remote.trakt.TraktDeviceCodeRequest
import com.example.trackstuff.data.remote.trakt.TraktDeviceTokenRequest
import com.example.trackstuff.data.remote.trakt.TraktEntry
import com.example.trackstuff.data.remote.trakt.TraktEpisodeRef
import com.example.trackstuff.data.remote.trakt.TraktIds
import com.example.trackstuff.data.remote.trakt.TraktMedia
import com.example.trackstuff.data.remote.trakt.TraktRefreshRequest
import com.example.trackstuff.data.remote.trakt.TraktSeasonRef
import com.example.trackstuff.data.remote.trakt.TraktSyncBody
import com.example.trackstuff.data.remote.trakt.TraktSyncMovie
import com.example.trackstuff.data.remote.trakt.TraktSyncShow
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.delay

private const val TAG = "TraktSync"

/**
 * Trakt.tv sync ("device code" OAuth, no server needed).
 *
 * Status mapping:
 *  - Plan to watch  ↔ watchlist
 *  - Watching       → history of watched episodes (current S/E)
 *  - Completed      ↔ history (watched movie / whole show)
 *  - On hold / Dropped → removed from the watchlist (no Trakt equivalent)
 *  - Note        ↔ ratings (1-10)
 */
class TraktSyncService(
    private val settingsRepo: SettingsRepository,
    private val library: LibraryRepository,
) {
    @Volatile private var token: String? = null
    private var api: TraktApi? = null
    private var clientIdUsed: String? = null

    private suspend fun api(): TraktApi {
        val s = settingsRepo.current()
        if (!s.hasTrakt) throw SyncAuthException("Trakt: client ID / secret not set")
        if (api == null || clientIdUsed != s.traktClientId) {
            api = TraktApi.create(s.traktClientId) { token }
            clientIdUsed = s.traktClientId
        }
        token = settingsRepo.currentTokens().traktAccessToken
        return api!!
    }

    // ------------------------------------------------------------------ Auth

    /** Requests a device code to show to the user. */
    suspend fun requestCode(): Pair<DeviceCode, String> {
        val s = settingsRepo.current()
        val code = api().deviceCode(TraktDeviceCodeRequest(s.traktClientId))
        return DeviceCode(code.userCode, code.verificationUrl, code.expiresIn) to code.deviceCode
    }

    /** Waits for the user to validate the code. Returns true when connected. */
    suspend fun waitForAuthorization(deviceCode: String, intervalSeconds: Int, expiresInSeconds: Int): Boolean {
        val s = settingsRepo.current()
        val api = api()
        val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
        var interval = intervalSeconds.coerceAtLeast(5)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val resp = api.deviceToken(TraktDeviceTokenRequest(deviceCode, s.traktClientId, s.traktClientSecret))
            when (resp.code()) {
                200 -> {
                    val t = resp.body() ?: return false
                    settingsRepo.saveTraktTokens(t.accessToken, t.refreshToken, (t.createdAt + t.expiresIn) * 1000L)
                    token = t.accessToken
                    return true
                }
                400 -> Unit // en attente
                429 -> interval += 5
                404, 409, 410, 418 -> throw SyncAuthException("Trakt: authorization denied or expired (${resp.code()})")
                else -> throw SyncAuthException("Trakt: error ${resp.code()}")
            }
        }
        return false
    }

    suspend fun disconnect() {
        settingsRepo.clearTrakt()
        token = null
    }

    private suspend fun ensureFreshToken() {
        val s = settingsRepo.current()
        val t = settingsRepo.currentTokens()
        if (!t.traktConnected) throw SyncAuthException("Trakt: not connected")
        if (System.currentTimeMillis() > t.traktExpiresAt - 60_000 && t.traktRefreshToken.isNotBlank()) {
            val fresh = api().refresh(TraktRefreshRequest(t.traktRefreshToken, s.traktClientId, s.traktClientSecret))
            settingsRepo.saveTraktTokens(fresh.accessToken, fresh.refreshToken, (fresh.createdAt + fresh.expiresIn) * 1000L)
            token = fresh.accessToken
        } else token = t.traktAccessToken
    }

    // ------------------------------------------------------------------ Sync

    suspend fun sync(): SyncReport {
        ensureFreshToken()
        val api = api()
        val errors = mutableListOf<String>()
        var pulled = 0
        var pushed = 0

        // ---- Pull: only when something changed on Trakt since the last sync.
        var activities: String? = null
        val since = settingsRepo.currentTokens().traktActivitiesAt
        try { activities = api.lastActivities().all } catch (e: Exception) { Log.w(TAG, e) }
        val changed = activities == null || since.isBlank() || activities != since
        if (changed) {
            // Local ids seen in the Trakt lists: anything synced before but no longer there was removed on Trakt.
            val present = HashSet<Long>()
            var complete = true
            try { pulled += pullWatchlist(api.watchlistMovies() + api.watchlistShows(), present) } catch (e: Exception) { complete = false; errors += "Watchlist: ${e.message}"; Log.w(TAG, e) }
            try { pulled += pullWatched(api.watchedMovies(), isShow = false, present) } catch (e: Exception) { complete = false; errors += "Watched movies: ${e.message}"; Log.w(TAG, e) }
            try { pulled += pullWatched(api.watchedShows(extended = "full"), isShow = true, present) } catch (e: Exception) { complete = false; errors += "Watched shows: ${e.message}"; Log.w(TAG, e) }
            if (complete) pulled += reconcileRemovals(present)
        }

        // ---- Push
        try { pushed = pushLocal(api) } catch (e: Exception) { errors += "Push: ${e.message}"; Log.w(TAG, e) }

        // Resume point: the timestamp after our pushes, so our own changes are not read back.
        if (errors.isEmpty()) try {
            val after = if (pushed > 0) api.lastActivities().all else activities
            if (after != null) settingsRepo.saveTraktActivitiesAt(after)
        } catch (e: Exception) { Log.w(TAG, e) }

        val enriched = try { library.enrichPending() } catch (e: Exception) { 0 }
        return SyncReport(pushed, pulled, enriched, errors)
    }

    private fun TraktMedia.ids() = ExternalIds(tmdbId = ids.tmdb, imdbId = ids.imdb, tvdbId = ids.tvdb, traktId = ids.trakt)

    /** Was a local item changed since its last sync? If so, it wins over Trakt. */
    private fun LibraryItem.locallyNewer() = tracking.lastSyncedTrakt?.let { tracking.updatedAt > it } ?: (tracking.lastSyncedTrakt == null && tracking.updatedAt != tracking.addedAt)

    /**
     * Removes locally the titles synced with Trakt that are no longer in the watchlist nor in the history.
     * Only statuses that imply a presence on Trakt are concerned (plan to watch, completed, watching with
     * watched episodes): "on hold" and "dropped" do not exist on Trakt and are already absent there.
     */
    private suspend fun reconcileRemovals(present: Set<Long>): Int {
        var removed = 0
        for (item in library.all()) {
            val t = item.tracking
            if (t.lastSyncedTrakt == null || item.locallyNewer() || item.localId in present) continue
            val expectedOnTrakt = t.status == WatchStatus.PLANNED || t.status == WatchStatus.COMPLETED || (t.status == WatchStatus.WATCHING && t.currentSeason > 0)
            if (!expectedOnTrakt) continue
            library.removeFromService(item.localId, fromTrakt = true); removed++
        }
        return removed
    }

    private suspend fun pullWatchlist(entries: List<TraktEntry>, present: MutableSet<Long>): Int {
        var n = 0
        for (e in entries) {
            val media = e.movie ?: e.show ?: continue
            val isShow = e.show != null
            val ids = media.ids()
            val existing = library.findExisting(ids, isShow)
            if (existing == null) {
                present += library.addStub(stubDetails(ids, isShow, media.title, media.year), UserTracking(status = WatchStatus.PLANNED, lastSyncedTrakt = System.currentTimeMillis()))
                n++
            } else {
                present += existing.localId
                library.markSynced(existing.localId, trakt = false, simkl = false, ids = ids)
            }
        }
        return n
    }

    private suspend fun pullWatched(entries: List<TraktEntry>, isShow: Boolean, present: MutableSet<Long>): Int {
        var n = 0
        val now = System.currentTimeMillis()
        for (e in entries) {
            val media = (if (isShow) e.show else e.movie) ?: continue
            val ids = media.ids()
            val existing = library.findExisting(ids, isShow)
            // Progress derived from watched episodes (last season / last episode).
            val lastSeason = e.seasons.maxByOrNull { it.number }
            val progress = lastSeason?.let { s -> s.number to (s.episodes.maxOfOrNull { it.number } ?: 0) }
            val watchedCount = e.seasons.sumOf { it.episodes.size }

            if (existing == null) {
                val tracking = UserTracking(
                    status = if (isShow) WatchStatus.WATCHING else WatchStatus.COMPLETED,
                    currentSeason = progress?.first ?: 0,
                    currentEpisode = progress?.second ?: 0,
                    lastSyncedTrakt = now,
                )
                present += library.addStub(stubDetails(ids, isShow, media.title, media.year), tracking)
                n++
            } else if (!existing.locallyNewer()) {
                present += existing.localId
                val total = existing.details.numberOfEpisodes
                val completed = !isShow || (total != null && total > 0 && watchedCount >= total)
                library.updateTracking(existing.localId) { t ->
                    t.copy(
                        status = when {
                            completed -> WatchStatus.COMPLETED
                            t.status == WatchStatus.PLANNED -> WatchStatus.WATCHING
                            else -> t.status
                        },
                        currentSeason = progress?.first ?: t.currentSeason,
                        currentEpisode = progress?.second ?: t.currentEpisode,
                        lastSyncedTrakt = now,
                    )
                }
                library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids)
            } else present += existing.localId
        }
        return n
    }

    private fun ExternalIds.toTrakt() = TraktIds(trakt = traktId, imdb = imdbId, tmdb = tmdbId, tvdb = tvdbId)

    /** Titles removed locally: removed from the Trakt watchlist and history. */
    private suspend fun pushRemovals(api: TraktApi): Int {
        val pending = settingsRepo.pendingRemovals().filter { it.trakt && !it.ids.isEmpty }
        if (pending.isEmpty()) return 0
        val body = TraktSyncBody(pending.filter { !it.isSeries }.map { TraktSyncMovie(it.ids.toTrakt()) }, pending.filter { it.isSeries }.map { TraktSyncShow(it.ids.toTrakt()) })
        api.removeFromWatchlist(body)
        api.removeFromHistory(body)
        val done = pending.toSet()
        settingsRepo.setPendingRemovals(settingsRepo.pendingRemovals().mapNotNull { r -> if (r in done) r.copy(trakt = false).takeIf { it.simkl } else r })
        return pending.size
    }

    private suspend fun pushLocal(api: TraktApi): Int {
        val removed = pushRemovals(api)
        val toPush = library.all().filter { item ->
            !item.details.ids.isEmpty && (item.tracking.lastSyncedTrakt == null || item.tracking.updatedAt > item.tracking.lastSyncedTrakt)
        }
        if (toPush.isEmpty()) return removed

        fun ids(i: LibraryItem) = i.details.ids.toTrakt()
        val movies = toPush.filter { !it.details.isSeries }
        val shows = toPush.filter { it.details.isSeries }

        // Watchlist
        val wlMovies = movies.filter { it.tracking.status == WatchStatus.PLANNED }
        val wlShows = shows.filter { it.tracking.status == WatchStatus.PLANNED }
        if (wlMovies.isNotEmpty() || wlShows.isNotEmpty()) {
            api.addToWatchlist(TraktSyncBody(wlMovies.map { TraktSyncMovie(ids(it)) }, wlShows.map { TraktSyncShow(ids(it)) }))
        }
        val rmMovies = movies.filter { it.tracking.status == WatchStatus.ON_HOLD || it.tracking.status == WatchStatus.DROPPED }
        val rmShows = shows.filter { it.tracking.status == WatchStatus.ON_HOLD || it.tracking.status == WatchStatus.DROPPED }
        if (rmMovies.isNotEmpty() || rmShows.isNotEmpty()) {
            api.removeFromWatchlist(TraktSyncBody(rmMovies.map { TraktSyncMovie(ids(it)) }, rmShows.map { TraktSyncShow(ids(it)) }))
        }

        // Historique
        val histMovies = movies.filter { it.tracking.status == WatchStatus.COMPLETED }
        val histShows = shows.filter { it.tracking.status == WatchStatus.COMPLETED || (it.tracking.status == WatchStatus.WATCHING && it.tracking.currentSeason > 0) }
        if (histMovies.isNotEmpty() || histShows.isNotEmpty()) {
            api.addToHistory(
                TraktSyncBody(
                    histMovies.map { TraktSyncMovie(ids(it)) },
                    histShows.map { s ->
                        val seasons = if (s.tracking.status == WatchStatus.COMPLETED) null
                        else episodesUpTo(s.tracking.currentSeason, s.tracking.currentEpisode, s.details.seasonEpisodes)
                            .map { (num, eps) -> TraktSeasonRef(num, eps.map { TraktEpisodeRef(it) }) }
                        TraktSyncShow(ids(s), seasons = seasons)
                    },
                )
            )
        }

        toPush.forEach { library.markSynced(it.localId, trakt = true, simkl = false) }
        return removed + toPush.size
    }
}
