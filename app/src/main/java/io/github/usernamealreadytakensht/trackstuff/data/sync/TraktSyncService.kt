package io.github.usernamealreadytakensht.trackstuff.data.sync

import io.github.usernamealreadytakensht.trackstuff.data.remote.describeError
import android.util.Log
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktApi
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktDeviceCodeRequest
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktDeviceTokenRequest
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktEntry
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktEpisodeRef
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktIds
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktMedia
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktRefreshRequest
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktSeasonRef
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktSyncBody
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktSyncMovie
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktSyncResult
import io.github.usernamealreadytakensht.trackstuff.data.remote.trakt.TraktSyncShow
import io.github.usernamealreadytakensht.trackstuff.data.repository.LibraryRepository
import io.github.usernamealreadytakensht.trackstuff.data.settings.SettingsRepository
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.LibraryItem
import io.github.usernamealreadytakensht.trackstuff.domain.UserTracking
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus
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

    /**
     * Access tokens last 90 days: refreshed [REFRESH_AHEAD_MS] before expiry during a normal sync, so the
     * rotation happens while the app is in use rather than on the last day. [force] refreshes at once
     * (after a 401). A refresh refused by Trakt (`invalid_grant`, revoked or already-rotated token)
     * disconnects the account: the user is told to sign in again instead of failing silently for ever.
     */
    private suspend fun ensureFreshToken(force: Boolean = false) {
        val s = settingsRepo.current()
        val t = settingsRepo.currentTokens()
        if (!t.traktConnected) throw SyncAuthException("Trakt: not connected")
        if (!force && System.currentTimeMillis() < t.traktExpiresAt - REFRESH_AHEAD_MS) { token = t.traktAccessToken; return }
        if (t.traktRefreshToken.isBlank()) { disconnect(); throw SyncAuthException("Trakt: sign-in expired, connect again in Settings") }
        try {
            val fresh = api().refresh(TraktRefreshRequest(t.traktRefreshToken, s.traktClientId, s.traktClientSecret))
            settingsRepo.saveTraktTokens(fresh.accessToken, fresh.refreshToken, (fresh.createdAt + fresh.expiresIn) * 1000L)
            token = fresh.accessToken
        } catch (e: retrofit2.HttpException) {
            // 400 invalid_grant / 401: the refresh token is dead. Anything else (5xx) is transient: keep the tokens.
            if (e.code() == 400 || e.code() == 401) { disconnect(); throw SyncAuthException("Trakt: sign-in expired, connect again in Settings") }
            throw e
        }
    }

    // ------------------------------------------------------------------ Sync

    suspend fun sync(): SyncReport {
        ensureFreshToken()
        return try {
            syncOnce()
        } catch (e: retrofit2.HttpException) {
            // Token revoked or rotated elsewhere: one refresh, then the sync runs again.
            if (e.code() != 401) throw e
            ensureFreshToken(force = true)
            syncOnce()
        }
    }

    private suspend fun syncOnce(): SyncReport {
        val api = api()
        val errors = mutableListOf<String>()
        var pulled = 0
        var pushed = 0

        // ---- Pull: only when something changed on Trakt since the last sync.
        var activities: String? = null
        val since = settingsRepo.currentTokens().traktActivitiesAt
        try { activities = api.lastActivities().all } catch (e: Exception) { if (e.mustPropagate()) throw e; Log.w(TAG, e) }
        val changed = activities == null || since.isBlank() || activities != since
        if (changed) {
            // Local ids seen in the Trakt lists: anything synced before but no longer there was removed on Trakt.
            val present = HashSet<Long>()
            var complete = true
            // Titles removed here whose removal has not reached Trakt yet (the push comes after the pull):
            // still in the Trakt lists, they must not be pulled back into the library.
            val removed = settingsRepo.pendingRemovals().filter { it.trakt }
            // Local titles met for the first time in this pull (never synced with Trakt before).
            val firstContacts = HashSet<Long>()
            try { pulled += pullWatchlist(api.watchlistMovies() + api.watchlistShows(), present, removed, firstContacts) } catch (e: Exception) { if (e.mustPropagate()) throw e; complete = false; errors += "Watchlist: ${describeError(e)}"; Log.w(TAG, e) }
            try { pulled += pullWatched(api.watchedMovies(), isShow = false, present, removed, firstContacts) } catch (e: Exception) { if (e.mustPropagate()) throw e; complete = false; errors += "Watched movies: ${describeError(e)}"; Log.w(TAG, e) }
            try { pulled += pullWatched(api.watchedShows(extended = "full"), isShow = true, present, removed, firstContacts) } catch (e: Exception) { if (e.mustPropagate()) throw e; complete = false; errors += "Watched shows: ${describeError(e)}"; Log.w(TAG, e) }
            // No reconciliation on the first pull after (re)connecting: nothing local has been pushed there yet.
            if (complete && since.isNotBlank()) pulled += reconcileRemovals(present)
        }

        // ---- Push
        try { pushed = pushLocal(api) } catch (e: Exception) { if (e.mustPropagate()) throw e; errors += "Push: ${describeError(e)}"; Log.w(TAG, e) }

        // Resume point: the timestamp after our pushes, so our own changes are not read back.
        if (errors.isEmpty()) try {
            val after = if (pushed > 0) api.lastActivities().all else activities
            if (after != null) settingsRepo.saveTraktActivitiesAt(after)
        } catch (e: Exception) { if (e.mustPropagate()) throw e; Log.w(TAG, e) }

        val enriched = try { library.enrichPending() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { 0 }
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

    private suspend fun pullWatchlist(entries: List<TraktEntry>, present: MutableSet<Long>, removed: List<PendingRemoval>, firstContacts: MutableSet<Long>): Int {
        var n = 0
        for (e in entries) {
            val media = e.movie ?: e.show ?: continue
            val isShow = e.show != null
            val ids = media.ids()
            if (removed.any { it.matches(ids, isShow) }) continue
            val existing = library.findExisting(ids, isShow)
            if (existing == null) {
                present += library.addStub(stubDetails(ids, isShow, media.title, media.year), UserTracking(status = WatchStatus.PLANNED, lastSyncedTrakt = System.currentTimeMillis()))
                n++
            } else {
                present += existing.localId
                val t = existing.tracking
                if (t.lastSyncedTrakt == null) firstContacts += existing.localId
                val onTrakt = t.copy(status = WatchStatus.PLANNED, currentSeason = 0, currentEpisode = 0)
                when {
                    t.lastSyncedTrakt != null -> library.markSynced(existing.localId, trakt = false, simkl = false, ids = ids)
                    // First contact (just connected, or imported from a backup), planned on both sides: in sync.
                    t.status == WatchStatus.PLANNED -> library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids, pushed = onTrakt)
                    // First contact, further along here: the watchlist entry is what Trakt has, and the title
                    // stays to push (synced "just before" its last change) so that the history gets the rest.
                    else -> library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids, at = t.updatedAt - 1, pushed = onTrakt)
                }
            }
        }
        return n
    }

    private suspend fun pullWatched(entries: List<TraktEntry>, isShow: Boolean, present: MutableSet<Long>, removed: List<PendingRemoval>, firstContacts: MutableSet<Long>): Int {
        var n = 0
        val now = System.currentTimeMillis()
        for (e in entries) {
            val media = (if (isShow) e.show else e.movie) ?: continue
            val ids = media.ids()
            if (removed.any { it.matches(ids, isShow) }) continue
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
            } else {
                present += existing.localId
                val local = existing.tracking.watchState()
                val total = existing.details.numberOfEpisodes ?: aired
                val completed = !isShow || (total != null && total > 0 && watchedCount >= total)
                val onTrakt = WatchState(if (completed) WatchStatus.COMPLETED else WatchStatus.WATCHING, progress?.first ?: 0, progress?.second ?: 0)
                // Also when the watchlist step of this pull has just seen it: the history entry is what counts.
                val firstContact = existing.tracking.lastSyncedTrakt == null || existing.localId in firstContacts
                if (firstContact && isAhead(local, onTrakt)) {
                    // First contact (just connected, or imported from a backup) and further along here: what
                    // Trakt has becomes the reference and the title stays to push (synced "just before" its
                    // last change), so that only the difference is sent — never the whole history again.
                    library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids, at = existing.tracking.updatedAt - 1,
                        pushed = existing.tracking.copy(status = onTrakt.status, currentSeason = onTrakt.season, currentEpisode = onTrakt.episode))
                } else if (firstContact || !existing.locallyNewer()) {
                    // Trakt wins (on first contact: it is as far or further). Only a real change touches the
                    // title: an unchanged one keeps its place in the library and is not pushed to the other service.
                    val pulledNow = traktPulledState(local, completed, progress)
                    if (pulledNow != local) library.updateTracking(existing.localId, fromSync = true) { t ->
                        t.copy(status = pulledNow.status, currentSeason = pulledNow.season, currentEpisode = pulledNow.episode)
                    }
                    // The pulled state is also the reference for the next delta push.
                    val pulledState = existing.tracking.copy(status = onTrakt.status, currentSeason = pulledNow.season, currentEpisode = pulledNow.episode)
                    library.markSynced(existing.localId, trakt = true, simkl = false, ids = ids, pushed = pulledState)
                }
            }
        }
        return n
    }

    private fun ExternalIds.toTrakt() = TraktIds(trakt = traktId, imdb = imdbId, tmdb = tmdbId, tvdb = tvdbId)

    /** Titles removed locally: removed from the Trakt watchlist and history. */
    private suspend fun pushRemovals(api: TraktApi): Int {
        val pending = settingsRepo.pendingRemovals().filter { it.trakt && !it.ids.isEmpty }
        if (pending.isEmpty()) return 0
        val body = TraktSyncBody(pending.filter { !it.isSeries }.map { TraktSyncMovie(it.ids.toTrakt()) }, pending.filter { it.isSeries }.map { TraktSyncShow(it.ids.toTrakt()) })
        post { api.removeFromWatchlist(body) }
        post { api.removeFromHistory(body) }
        val done = pending.toSet()
        settingsRepo.updatePendingRemovals { list -> list.mapNotNull { r -> if (r in done) r.copy(trakt = false).takeIf { it.simkl } else r } }
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
            val onTrakt = pushedBefore(s)?.let { WatchState(it, t.traktSyncedSeason, t.traktSyncedEpisode) }
            when (val add = showHistoryToAdd(t.watchState(), onTrakt, s.details.seasonEpisodes)) {
                HistoryAdd.None -> null
                HistoryAdd.WholeShow -> TraktSyncShow(ids(s))
                is HistoryAdd.Episodes -> TraktSyncShow(ids(s), seasons = add.seasons.map { (num, eps) -> TraktSeasonRef(num, eps.map { TraktEpisodeRef(it) }) })
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
        /** Refresh the access token this long before it expires (tokens last 90 days). */
        const val REFRESH_AHEAD_MS = 7 * 24 * 60 * 60 * 1000L

        /**
         * Not swallowed by the per-step error handling: a 401, which triggers a token refresh in [sync], and
         * a cancellation, which must stop the sync instead of being reported as a failed step.
         */
        private fun Exception.mustPropagate() = this is kotlinx.coroutines.CancellationException || (this is retrofit2.HttpException && code() == 401)
    }
}
