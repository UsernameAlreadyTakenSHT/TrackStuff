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
import com.example.trackstuff.data.remote.trakt.TraktSyncResult
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
                400 -> Unit // pending
                429 -> interval += 5
                404, 409, 410, 418 -> throw SyncAuthException("Trakt: authorization denied or expired (${resp.code()})")
                else -> throw SyncAuthException("Trakt: error ${resp.code()}")
            }
        }
        return false
    }

    suspend fun disconnect() {
        settingsRepo.clearTrakt()
        // Items are no longer "synced with Trakt": another account must not reconcile them away.
        library.clearSyncState(trakt = true, simkl = false)
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
            // No reconciliation on the first pull after (re)connecting: nothing local has been pushed there yet.
            if (complete && since.isNotBlank()) pulled += reconcileRemovals(present)
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
     * watched episodes).
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
            val lastSeason = e.seasons.filter { it.number > 0 }.maxByOrNull { it.number }
            val progress = lastSeason?.let { s -> s.number to (s.episodes.maxOfOrNull { it.number } ?: 0) }
            // Specials (season 0) are not tracked as progress.
            val watchedCount = e.seasons.filter { it.number > 0 }.sumOf { it.episodes.size }
            val aired = media.airedEpisodes

            if (existing == null) {
                val completed = !isShow || (aired != null && aired > 0 && watchedCount >= aired)
                val tracking = UserTracking(
                    status = if (completed) WatchStatus.COMPLETED else WatchStatus.WATCHING,
                    currentSeason = progress?.first ?: 0,
                    currentEpisode = progress?.second ?: 0,
                    lastSyncedTrakt = now,
                )
                present += library.addStub(stubDetails(ids, isShow, media.title, media.year), tracking)
                n++
            } else if (!existing.locallyNewer()) {
                present += existing.localId
                val total = existing.details.numberOfEpisodes ?: aired
                val completed = !isShow || (total != null && total > 0 && watchedCount >= total)
                val newSeason = progress?.first ?: existing.tracking.currentSeason
                val newEpisode = progress?.second ?: existing.tracking.currentEpisode
                library.updateTracking(existing.localId) { t ->
                    t.copy(
                        status = when {
                            completed -> WatchStatus.COMPLETED
                            t.status == WatchStatus.PLANNED -> WatchStatus.WATCHING
                            else -> t.status
                        },
                        currentSeason = newSeason,
                        currentEpisode = newEpisode,
                        lastSyncedTrakt = now,
                    )
                }
                // The pulled state is also the reference for the next delta push.
                val pulledState = existing.tracking.copy(status = if (completed) WatchStatus.COMPLETED else WatchStatus.WATCHING, currentSeason = newSeason, currentEpisode = newEpisode)
                library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids, pushed = pulledState)
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

    private var lastPostAt = 0L

    /** Trakt limits POSTs to one per second. */
    private suspend fun <T> post(call: suspend () -> T): T {
        val wait = lastPostAt + POST_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        return try { call() } finally { lastPostAt = System.currentTimeMillis() }
    }

    /** True when a Trakt "not_found" entry designates this item (any shared id). */
    private fun TraktIds.matches(i: LibraryItem): Boolean {
        val d = i.details.ids
        return (trakt != null && trakt == d.traktId) || (imdb != null && imdb == d.imdbId) || (tmdb != null && tmdb == d.tmdbId) || (tvdb != null && tvdb == d.tvdbId)
    }

    /**
     * Pushes local changes. Only the delta since the last push is sent (episodes between the pushed position
     * and the current one; history removal only on an actual move back or a return to "plan to watch"), so
     * plays are never duplicated and rewatches recorded elsewhere are preserved.
     */
    private suspend fun pushLocal(api: TraktApi): Int {
        val removed = pushRemovals(api)
        val snapshotAt = System.currentTimeMillis()
        val toPush = library.all().filter { item ->
            val ids = item.details.ids
            val usable = ids.traktId != null || ids.imdbId != null || ids.tmdbId != null || ids.tvdbId != null
            usable && (item.tracking.lastSyncedTrakt == null || item.tracking.updatedAt > item.tracking.lastSyncedTrakt)
        }
        if (toPush.isEmpty()) return removed

        fun ids(i: LibraryItem) = i.details.ids.toTrakt()
        /** Reference state for the delta: what was pushed to Trakt before, or nothing if never pushed there. */
        fun pushedBefore(i: LibraryItem) = if (i.tracking.lastSyncedTrakt != null) i.tracking.traktSyncedStatus else null
        val movies = toPush.filter { !it.details.isSeries }
        val shows = toPush.filter { it.details.isSeries }
        val notFound = mutableListOf<TraktIds>()
        fun collect(r: TraktSyncResult) { r.notFound?.let { notFound += it.all() } }

        // Back to "plan to watch" from a watched state: plays go, the title returns to the watchlist.
        val backToPlanned = toPush.filter { it.tracking.status == WatchStatus.PLANNED && pushedBefore(it).let { p -> p != null && p != WatchStatus.PLANNED } }
        if (backToPlanned.isNotEmpty()) {
            collect(post { api.removeFromHistory(TraktSyncBody(backToPlanned.filter { !it.details.isSeries }.map { TraktSyncMovie(ids(it)) }, backToPlanned.filter { it.details.isSeries }.map { TraktSyncShow(ids(it)) })) })
        }
        // Watchlist: planned titles never sent, or coming back from a watched state.
        val toWatchlist = toPush.filter { it.tracking.status == WatchStatus.PLANNED && pushedBefore(it) != WatchStatus.PLANNED }
        if (toWatchlist.isNotEmpty()) {
            collect(post { api.addToWatchlist(TraktSyncBody(toWatchlist.filter { !it.details.isSeries }.map { TraktSyncMovie(ids(it)) }, toWatchlist.filter { it.details.isSeries }.map { TraktSyncShow(ids(it)) })) })
        }

        // History: completed movies not yet recorded, shows completed now, shows that moved forward.
        val histMovies = movies.filter { it.tracking.status == WatchStatus.COMPLETED && pushedBefore(it) != WatchStatus.COMPLETED }
        val histShows = shows.mapNotNull { s ->
            val t = s.tracking
            when {
                t.status == WatchStatus.COMPLETED -> if (pushedBefore(s) != WatchStatus.COMPLETED) TraktSyncShow(ids(s)) else null // whole show
                t.status == WatchStatus.WATCHING && t.currentSeason > 0 -> {
                    val from = if (pushedBefore(s) == WatchStatus.WATCHING) t.traktSyncedSeason to t.traktSyncedEpisode else 0 to 0
                    val delta = episodesBetween(from.first, from.second, t.currentSeason, t.currentEpisode, s.details.seasonEpisodes)
                    if (delta.isEmpty()) null else TraktSyncShow(ids(s), seasons = delta.map { (num, eps) -> TraktSeasonRef(num, eps.map { TraktEpisodeRef(it) }) })
                }
                else -> null
            }
        }
        if (histMovies.isNotEmpty() || histShows.isNotEmpty()) {
            collect(post { api.addToHistory(TraktSyncBody(histMovies.map { TraktSyncMovie(ids(it)) }, histShows)) })
        }

        // Shows that moved back (still watching): un-mark what is after the new position. Never an empty list,
        // which Trakt would read as "the whole show".
        val movedBack = shows.mapNotNull { s ->
            val t = s.tracking
            if (t.status != WatchStatus.WATCHING || pushedBefore(s) == null) return@mapNotNull null
            val wasAhead = pushedBefore(s) == WatchStatus.COMPLETED || t.traktSyncedSeason > t.currentSeason || (t.traktSyncedSeason == t.currentSeason && t.traktSyncedEpisode > t.currentEpisode)
            if (!wasAhead) return@mapNotNull null
            val after = episodesAfter(t.currentSeason, t.currentEpisode, s.details.seasonEpisodes)
            if (after.isEmpty()) null else TraktSyncShow(ids(s), seasons = after.map { (num, eps) -> TraktSeasonRef(num, eps.map { TraktEpisodeRef(it) }) })
        }
        if (movedBack.isNotEmpty()) collect(post { api.removeFromHistory(TraktSyncBody(emptyList(), movedBack)) })

        // Titles Trakt could not resolve stay unsynced (and are never reconciled away).
        var pushed = 0
        for (item in toPush) {
            if (notFound.any { it.matches(item) }) { Log.w(TAG, "Trakt does not know ${item.details.title}, left unsynced"); continue }
            library.markSynced(item.localId, trakt = true, simkl = false, at = snapshotAt, pushed = item.tracking)
            pushed++
        }
        return removed + pushed
    }

    companion object {
        private const val POST_INTERVAL_MS = 1100L
    }
}
