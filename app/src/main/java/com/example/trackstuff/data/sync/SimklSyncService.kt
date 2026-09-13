package com.example.trackstuff.data.sync

import android.util.Log
import com.example.trackstuff.data.remote.simkl.SimklApi
import com.example.trackstuff.data.remote.simkl.SimklEpisodeRef
import com.example.trackstuff.data.remote.simkl.SimklIds
import com.example.trackstuff.data.remote.simkl.SimklItem
import com.example.trackstuff.data.remote.simkl.SimklMedia
import com.example.trackstuff.data.remote.simkl.SimklSeasonRef
import com.example.trackstuff.data.remote.simkl.SimklSyncBody
import com.example.trackstuff.data.remote.simkl.SimklSyncItem
import com.example.trackstuff.data.remote.simkl.SimklSyncResult
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.delay

private const val TAG = "SimklSync"
private const val POST_INTERVAL_MS = 1100L

/**
 * Simkl sync ("PIN" OAuth).
 * Simkl has exactly the same statuses as the app: plantowatch / watching / completed / hold / dropped.
 */
class SimklSyncService(
    private val settingsRepo: SettingsRepository,
    private val library: LibraryRepository,
) {
    @Volatile private var token: String? = null
    private var api: SimklApi? = null
    private var clientIdUsed: String? = null

    private suspend fun api(): SimklApi {
        val s = settingsRepo.current()
        if (!s.hasSimkl) throw SyncAuthException("Simkl: client ID / secret not set")
        if (api == null || clientIdUsed != s.simklClientId) {
            api = SimklApi.create(s.simklClientId, com.example.trackstuff.BuildConfig.VERSION_NAME) { token }
            clientIdUsed = s.simklClientId
        }
        token = settingsRepo.currentTokens().simklAccessToken
        return api!!
    }

    // ------------------------------------------------------------------ Auth

    suspend fun requestCode(): Pair<DeviceCode, Int> {
        val s = settingsRepo.current()
        val pin = api().requestPin()
        val userCode = pin.userCode ?: throw SyncAuthException("Simkl: ${pin.message ?: "invalid response"}")
        return DeviceCode(userCode, pin.verificationUrl ?: "https://simkl.com/pin", pin.expiresIn ?: 900) to (pin.interval ?: 5)
    }

    suspend fun waitForAuthorization(userCode: String, intervalSeconds: Int, expiresInSeconds: Int): Boolean {
        val s = settingsRepo.current()
        val api = api()
        val deadline = System.currentTimeMillis() + expiresInSeconds * 1000L
        var interval = intervalSeconds.coerceAtLeast(5)
        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            val r = api.pollPin(userCode)
            val access = r.accessToken
            if (r.result == "OK" && !access.isNullOrBlank()) {
                settingsRepo.saveSimklToken(access)
                token = access
                return true
            }
            if (r.message?.contains("slow", true) == true) interval += 5
        }
        return false
    }

    suspend fun disconnect() {
        settingsRepo.clearSimkl()
        // Items are no longer "synced with Simkl": another account must not reconcile them away.
        library.clearSyncState(trakt = false, simkl = true)
        token = null
    }

    // ------------------------------------------------------------------ Sync

    suspend fun sync(): SyncReport {
        val api = api()
        if (settingsRepo.currentTokens().simklAccessToken.isBlank()) throw SyncAuthException("Simkl: not connected")
        val errors = mutableListOf<String>()
        var pulled = 0
        var pushed = 0

        // Simkl rules: /sync/activities first; the initial sync goes type by type, sequentially; afterwards
        // only the delta via all-items?date_from=<the `all` timestamp returned>, and nothing when nothing moved.
        // List removals are not in the delta: when `removed_from_list` changes, the full lists are re-read
        // (without extended) to delete locally what is no longer there.
        var pullOk = false
        var before: com.example.trackstuff.data.remote.simkl.SimklActivities? = null
        try {
            val activities = api.activities()
            before = activities
            val tokens = settingsRepo.currentTokens()
            val since = tokens.simklActivitiesAt
            if (since.isBlank()) {
                for (type in TYPES) {
                    val part = api.allItemsOf(type) ?: continue
                    pulled += pullAll(part)
                }
            } else {
                if (activities.all != null && activities.all != since) {
                    api.allItemsSince(since)?.let { pulled += pullAll(it) }
                }
                if (activities.removedStamp != tokens.simklRemovedStamp) pulled += reconcileRemovals(api)
            }
            pullOk = true
        } catch (e: Exception) { errors += "Pull: ${e.message}"; Log.w(TAG, e) }

        try { pushed = push(api) } catch (e: Exception) { errors += "Push: ${e.message}"; Log.w(TAG, e) }

        // Resume point for the next sync: the `all` timestamp after our pushes (our own changes will not be
        // downloaded again). A still-empty account has no timestamp: use the current time.
        if (pullOk) try {
            val after = if (pushed > 0) api.activities() else before
            settingsRepo.saveSimklActivitiesAt(after?.all ?: before?.all ?: nowIso(), after?.removedStamp ?: before?.removedStamp ?: "")
        } catch (e: Exception) { Log.w(TAG, e) }

        val enriched = try { library.enrichPending() } catch (e: Exception) { 0 }
        return SyncReport(pushed, pulled, enriched, errors)
    }

    /** Removes locally the titles synced with Simkl that no longer appear in any remote list. */
    private suspend fun reconcileRemovals(api: SimklApi): Int {
        val present = HashSet<Long>()
        for (type in TYPES) {
            val part = api.allItemsOf(type, extended = "full") ?: continue
            for (it in part.movies + part.shows + part.anime) {
                val media = it.show ?: it.movie ?: continue
                library.findExisting(media.ids(), isSeries = it.show != null)?.let { e -> present += e.localId }
            }
        }
        var removed = 0
        for (item in library.all()) {
            if (item.tracking.lastSyncedSimkl == null || item.locallyNewer() || item.localId in present) continue
            library.removeFromService(item.localId, fromTrakt = false); removed++
        }
        return removed
    }

    private fun nowIso(): String = java.time.format.DateTimeFormatter.ISO_INSTANT.format(java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    private suspend fun pullAll(all: com.example.trackstuff.data.remote.simkl.SimklAllItems): Int =
        pull(all.movies, isShow = false, anime = false) + pull(all.shows, isShow = true, anime = false) + pull(all.anime, isShow = true, anime = true)

    private var lastPostAt = 0L

    /** Simkl limits POSTs to one per second per client. */
    private suspend fun <T> post(call: suspend () -> T): T {
        val wait = lastPostAt + POST_INTERVAL_MS - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        return try { call() } finally { lastPostAt = System.currentTimeMillis() }
    }

    private fun SimklMedia.ids() = ExternalIds(tmdbId = ids.tmdbInt(), imdbId = ids.imdb, tvdbId = ids.tvdbInt(), simklId = ids.simkl)

    /**
     * Null for a status the app does not track (dropped / not interesting, or a title removed from the list
     * in a delta): left untouched. Simkl's "hold" is a started show, i.e. watching here.
     */
    private fun statusFromSimkl(s: String?): WatchStatus? = when (s) {
        "plantowatch" -> WatchStatus.PLANNED
        "watching", "hold" -> WatchStatus.WATCHING
        "completed" -> WatchStatus.COMPLETED
        else -> null
    }

    private fun statusToSimkl(st: WatchStatus, isMovie: Boolean): String = when (st) {
        WatchStatus.PLANNED -> "plantowatch"
        WatchStatus.WATCHING -> if (isMovie) "plantowatch" else "watching"
        WatchStatus.COMPLETED -> "completed"
    }

    private fun LibraryItem.locallyNewer() = tracking.lastSyncedSimkl?.let { tracking.updatedAt > it } ?: (tracking.lastSyncedSimkl == null && tracking.updatedAt != tracking.addedAt)

    private suspend fun pull(items: List<SimklItem>, isShow: Boolean, anime: Boolean): Int {
        var n = 0
        val now = System.currentTimeMillis()
        for (it in items) {
            val media = (if (isShow) it.show else it.movie) ?: continue
            val ids = media.ids()
            val progress = parseSxxExx(it.lastWatched)
            // "watching" / "hold" without any episode marked is still "plan to watch" here (status follows progress).
            val status = statusFromSimkl(it.status)?.let { st -> if (st == WatchStatus.WATCHING && progress == null) WatchStatus.PLANNED else st } ?: continue
            val existing = library.findExisting(ids, isShow)
            if (existing == null) {
                val tracking = UserTracking(
                    status = status,
                    userRating = it.userRating,
                    currentSeason = progress?.first ?: 0,
                    currentEpisode = progress?.second ?: 0,
                    lastSyncedSimkl = now,
                )
                val kind = if (anime) MediaKind.ANIME else null
                library.addStub(stubDetails(ids, isShow, media.title, media.year, kind, SimklApi.posterUrl(media.poster)), tracking)
                n++
            } else if (!existing.locallyNewer()) {
                library.updateTracking(existing.localId) { t ->
                    t.copy(
                        status = status,
                        userRating = it.userRating ?: t.userRating,
                        currentSeason = progress?.first ?: t.currentSeason,
                        currentEpisode = progress?.second ?: t.currentEpisode,
                    )
                }
                if (anime && existing.details.kind != MediaKind.ANIME) library.setKind(existing.localId, MediaKind.ANIME)
                // The pulled state is also the reference for the next delta push.
                val pulledState = existing.tracking.copy(status = status, currentSeason = progress?.first ?: existing.tracking.currentSeason, currentEpisode = progress?.second ?: existing.tracking.currentEpisode)
                library.markSynced(existing.localId, trakt = false, simkl = true, ids = ids, pushed = pulledState)
            } else {
                library.markSynced(existing.localId, trakt = false, simkl = false, ids = ids)
            }
        }
        return n
    }

    /** True when a Simkl "not_found" entry designates this item (any shared id). */
    private fun Map<*, *>.matches(i: LibraryItem): Boolean {
        val ids = this["ids"] as? Map<*, *> ?: return false
        val d = i.details.ids
        fun same(key: String, v: String?) = v != null && (ids[key]?.toString()?.substringBefore(".") == v)
        return same("simkl", d.simklId?.toString()) || same("imdb", d.imdbId) || same("tmdb", d.tmdbId?.toString()) || same("tvdb", d.tvdbId?.toString())
    }

    /**
     * Pushes local changes as a delta: statuses that changed, new episodes since the last push, removals of
     * what is after a position moved back, and a full reset for titles returned to "plan to watch".
     */
    private suspend fun push(api: SimklApi): Int {
        var n = pushRemovals(api)
        val snapshotAt = System.currentTimeMillis()
        val toPush = library.all().filter { item ->
            val ids = item.details.ids
            val usable = ids.simklId != null || ids.imdbId != null || ids.tmdbId != null || ids.tvdbId != null
            usable && (item.tracking.lastSyncedSimkl == null || item.tracking.updatedAt > item.tracking.lastSyncedSimkl)
        }
        if (toPush.isEmpty()) return n

        fun ids(i: LibraryItem) = i.details.ids.toSimkl()
        fun pushedBefore(i: LibraryItem) = if (i.tracking.lastSyncedSimkl != null) i.tracking.syncedStatus else null
        val notFound = mutableListOf<Map<*, *>>()
        fun collect(r: SimklSyncResult, vararg keys: String) { keys.forEach { k -> (r.notFound?.get(k) as? List<*>)?.filterIsInstance<Map<*, *>>()?.let { notFound += it } } }
        fun isAnime(i: LibraryItem) = i.details.isSeries && i.details.kind == MediaKind.ANIME

        // Back to "plan to watch" from a watched state: history/remove drops the title (and its list entry),
        // add-to-list below puts it back as plantowatch.
        val backToPlanned = toPush.filter { it.tracking.status == WatchStatus.PLANNED && pushedBefore(it).let { p -> p != null && p != WatchStatus.PLANNED } }
        if (backToPlanned.isNotEmpty()) {
            post {
                api.removeFromHistory(
                    SimklSyncBody(
                        movies = backToPlanned.filter { !it.details.isSeries }.map { SimklSyncItem(ids(it)) },
                        shows = backToPlanned.filter { it.details.isSeries }.map { SimklSyncItem(ids(it)) },
                    )
                )
            }
        }

        // Statuses (lists): everything whose status changed since the last push (or never pushed).
        val statusChanged = toPush.filter { pushedBefore(it) != it.tracking.status }
        if (statusChanged.isNotEmpty()) {
            val movies = statusChanged.filter { !it.details.isSeries }
            val anime = statusChanged.filter { isAnime(it) }
            val shows = statusChanged.filter { it.details.isSeries && !isAnime(it) }
            val res = post {
                api.addToList(
                    SimklSyncBody(
                        movies = movies.map { SimklSyncItem(ids(it), to = statusToSimkl(it.tracking.status, true)) },
                        shows = shows.map { SimklSyncItem(ids(it), to = statusToSimkl(it.tracking.status, false)) },
                        anime = anime.map { SimklSyncItem(ids(it), to = statusToSimkl(it.tracking.status, false)) },
                    )
                )
            }
            // Simkl classifies anime and shows itself: retry each not-found series in the other category.
            val retryAsShows = anime.filter { a -> (res.notFound?.get("anime") as? List<*>)?.filterIsInstance<Map<*, *>>()?.any { it.matches(a) } == true }
            val retryAsAnime = shows.filter { s -> (res.notFound?.get("shows") as? List<*>)?.filterIsInstance<Map<*, *>>()?.any { it.matches(s) } == true }
            collect(res, "movies")
            if (retryAsShows.isNotEmpty() || retryAsAnime.isNotEmpty()) {
                val res2 = post {
                    api.addToList(
                        SimklSyncBody(
                            shows = retryAsShows.map { SimklSyncItem(ids(it), to = statusToSimkl(it.tracking.status, false)) },
                            anime = retryAsAnime.map { SimklSyncItem(ids(it), to = statusToSimkl(it.tracking.status, false)) },
                        )
                    )
                }
                collect(res2, "shows", "anime")
            }
        }

        // Episodes watched since the last push (series in progress), sent in the right category.
        val inProgress = toPush.filter { it.details.isSeries && it.tracking.status == WatchStatus.WATCHING && it.tracking.currentSeason > 0 }
        fun seasons(pairs: List<Pair<Int, List<Int>>>) = pairs.map { (num, eps) -> SimklSeasonRef(num, if (eps.isEmpty()) null else eps.map { SimklEpisodeRef(it) }) }
        val forward = inProgress.mapNotNull { s ->
            val t = s.tracking
            val from = if (pushedBefore(s) == WatchStatus.WATCHING) t.syncedSeason to t.syncedEpisode else 0 to 0
            val delta = episodesBetween(from.first, from.second, t.currentSeason, t.currentEpisode, s.details.seasonEpisodes)
            if (delta.isEmpty()) null else s to SimklSyncItem(ids(s), seasons = seasons(delta))
        }
        if (forward.isNotEmpty()) {
            post { api.addHistory(SimklSyncBody(shows = forward.filter { !isAnime(it.first) }.map { it.second }, anime = forward.filter { isAnime(it.first) }.map { it.second })) }
        }
        // Moved back: un-mark what is after the new position (never an empty list, which would drop the title).
        val back = inProgress.mapNotNull { s ->
            val t = s.tracking
            val before = pushedBefore(s) ?: return@mapNotNull null
            val wasAhead = before == WatchStatus.COMPLETED || t.syncedSeason > t.currentSeason || (t.syncedSeason == t.currentSeason && t.syncedEpisode > t.currentEpisode)
            if (!wasAhead) return@mapNotNull null
            val after = episodesAfter(t.currentSeason, t.currentEpisode, s.details.seasonEpisodes)
            if (after.isEmpty()) null else s to SimklSyncItem(ids(s), seasons = seasons(after))
        }
        if (back.isNotEmpty()) {
            post { api.removeFromHistory(SimklSyncBody(shows = back.filter { !isAnime(it.first) }.map { it.second }, anime = back.filter { isAnime(it.first) }.map { it.second })) }
        }

        for (item in toPush) {
            if (notFound.any { it.matches(item) }) { Log.w(TAG, "Simkl does not know ${item.details.title}, left unsynced"); continue }
            library.markSynced(item.localId, trakt = false, simkl = true, at = snapshotAt, pushed = item.tracking)
            n++
        }
        return n
    }

    private suspend fun pushRemovals(api: SimklApi): Int {
        val pending = settingsRepo.pendingRemovals().filter { it.simkl && !it.ids.isEmpty }
        if (pending.isEmpty()) return 0
        post {
            api.removeFromHistory(
                SimklSyncBody(
                    movies = pending.filter { !it.isSeries }.map { SimklSyncItem(it.ids.toSimkl()) },
                    shows = pending.filter { it.isSeries }.map { SimklSyncItem(it.ids.toSimkl()) },
                )
            )
        }
        val done = pending.toSet()
        settingsRepo.setPendingRemovals(settingsRepo.pendingRemovals().mapNotNull { r -> if (r in done) r.copy(simkl = false).takeIf { it.trakt } else r })
        return pending.size
    }

    private fun ExternalIds.toSimkl() = SimklIds(simkl = simklId, imdb = imdbId, tmdb = tmdbId?.toString(), tvdb = tvdbId?.toString())

    companion object {
        private val TYPES = listOf("shows", "movies", "anime")
    }
}
