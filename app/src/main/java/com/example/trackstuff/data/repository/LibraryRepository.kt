package com.example.trackstuff.data.repository

import android.util.Log
import com.example.trackstuff.data.local.MediaDao
import com.example.trackstuff.data.local.EpisodeEntity
import com.example.trackstuff.data.local.MediaEntity
import com.example.trackstuff.domain.Episode
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.WatchStatus
import com.example.trackstuff.domain.advanced
import com.example.trackstuff.domain.fillMissingFrom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local library (Room). Every page is kept for offline use. */
class LibraryRepository(
    private val dao: MediaDao,
    private val episodes: com.example.trackstuff.data.local.EpisodeDao,
    private val metadata: MetadataRepository,
    private val settings: com.example.trackstuff.data.settings.SettingsRepository,
) {
    /** Notified after each tracking change made by the user (triggers the automatic sync). */
    var onLocalChange: (() -> Unit)? = null

    val items: Flow<List<LibraryItem>> = dao.observeAll().map { list -> list.map { it.toLibraryItem() } }

    fun observe(localId: Long): Flow<LibraryItem?> = dao.observeById(localId).map { it?.toLibraryItem() }

    suspend fun get(localId: Long): LibraryItem? = dao.getById(localId)?.toLibraryItem()

    suspend fun all(): List<LibraryItem> = dao.getAll().map { it.toLibraryItem() }

    /** Finds an already-present title from any known id. */
    suspend fun findExisting(ids: ExternalIds, isSeries: Boolean): LibraryItem? {
        ids.tmdbId?.let { dao.findByTmdb(it, isSeries)?.let { e -> return e.toLibraryItem() } }
        ids.imdbId?.let { dao.findByImdb(it)?.let { e -> return e.toLibraryItem() } }
        ids.tvdbId?.let { dao.findByTvdb(it, isSeries)?.let { e -> return e.toLibraryItem() } }
        ids.traktId?.let { dao.findByTrakt(it, isSeries)?.let { e -> return e.toLibraryItem() } }
        ids.simklId?.let { dao.findBySimkl(it)?.let { e -> return e.toLibraryItem() } }
        return null
    }

    /** Adds a title (or updates its page). Returns the local id. */
    suspend fun add(details: MediaDetails, status: WatchStatus = WatchStatus.PLANNED): Long {
        val existing = findExisting(details.ids, details.isSeries)
        return if (existing != null) {
            dao.update(MediaEntity.from(details.copy(ids = existing.details.ids.merge(details.ids)), existing.tracking, existing.localId))
            existing.localId
        } else {
            dao.upsert(MediaEntity.from(details, UserTracking(status = status)))
        }.also { onLocalChange?.invoke() }
    }

    /**
     * Adds a title imported from a sync service, without a full page (enriched later). Its timestamps are
     * aligned on the sync time so that the item is neither "locally newer" nor pushed straight back.
     */
    suspend fun addStub(details: MediaDetails, tracking: UserTracking): Long {
        val t = tracking.lastSyncedTrakt ?: tracking.lastSyncedSimkl ?: System.currentTimeMillis()
        val aligned = tracking.copy(
            addedAt = t, updatedAt = t,
            traktSyncedStatus = tracking.status, traktSyncedSeason = tracking.currentSeason, traktSyncedEpisode = tracking.currentEpisode,
            simklSyncedStatus = tracking.status, simklSyncedSeason = tracking.currentSeason, simklSyncedEpisode = tracking.currentEpisode,
        )
        return dao.upsert(MediaEntity.from(details, aligned, needsEnrichment = true))
    }

    suspend fun updateTracking(localId: Long, transform: (UserTracking) -> UserTracking) {
        val e = dao.getById(localId) ?: return
        val item = e.toLibraryItem()
        val t = transform(item.tracking).copy(updatedAt = System.currentTimeMillis())
        dao.update(MediaEntity.from(item.details, t, localId, e.needsEnrichment).copy(episodesAt = e.episodesAt))
        onLocalChange?.invoke()
    }

    /** One more episode watched ("+1" on a library card): see [advanced]. */
    suspend fun advance(localId: Long) {
        val e = dao.getById(localId) ?: return
        val seasons = e.toLibraryItem().details.seasonEpisodes
        updateTracking(localId) { it.advanced(seasons) }
    }

    suspend fun updateDetails(localId: Long, details: MediaDetails, needsEnrichment: Boolean = false) {
        val e = dao.getById(localId) ?: return
        dao.update(MediaEntity.from(details, e.toLibraryItem().tracking, localId, needsEnrichment).copy(episodesAt = e.episodesAt))
    }

    suspend fun setKind(localId: Long, kind: MediaKind) {
        val e = dao.getById(localId) ?: return
        dao.update(e.copy(kind = kind, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Records a successful sync with a service. [at] is the time the pushed snapshot was taken: an edit made
     * while the requests were in flight keeps `updatedAt > lastSynced` and is pushed next time. When
     * [pushed] is given (a push), the status / position sent become the reference for the next delta.
     */
    suspend fun markSynced(localId: Long, trakt: Boolean, simkl: Boolean, ids: ExternalIds? = null, at: Long = System.currentTimeMillis(), pushed: UserTracking? = null) {
        val e = dao.getById(localId) ?: return
        val now = at
        dao.update(
            e.copy(
                lastSyncedTrakt = if (trakt) now else e.lastSyncedTrakt,
                lastSyncedSimkl = if (simkl) now else e.lastSyncedSimkl,
                traktSyncedStatus = if (trakt && pushed != null) pushed.status else e.traktSyncedStatus,
                traktSyncedSeason = if (trakt && pushed != null) pushed.currentSeason else e.traktSyncedSeason,
                traktSyncedEpisode = if (trakt && pushed != null) pushed.currentEpisode else e.traktSyncedEpisode,
                simklSyncedStatus = if (simkl && pushed != null) pushed.status else e.simklSyncedStatus,
                simklSyncedSeason = if (simkl && pushed != null) pushed.currentSeason else e.simklSyncedSeason,
                simklSyncedEpisode = if (simkl && pushed != null) pushed.currentEpisode else e.simklSyncedEpisode,
                traktId = ids?.traktId ?: e.traktId,
                simklId = ids?.simklId ?: e.simklId,
                omdbOrgId = e.omdbOrgId ?: ids?.omdbOrgId,
                imdbId = e.imdbId ?: ids?.imdbId,
                tmdbId = e.tmdbId ?: ids?.tmdbId,
                tvdbId = e.tvdbId ?: ids?.tvdbId,
            )
        )
    }

    /** Forgets the sync state with a service (disconnect): items are neither pushed as changes nor reconciled as removals. */
    suspend fun clearSyncState(trakt: Boolean, simkl: Boolean) {
        if (trakt) dao.clearTraktSync()
        if (simkl) dao.clearSimklSync()
    }

    /** Removal by the user: the removal is also scheduled on the services the title had been synced with. */
    suspend fun remove(localId: Long) {
        val e = dao.getById(localId) ?: return
        val item = e.toLibraryItem()
        val t = item.tracking
        dao.delete(e)
        if (!item.details.ids.isEmpty && (t.lastSyncedTrakt != null || t.lastSyncedSimkl != null)) {
            settings.addPendingRemoval(com.example.trackstuff.data.sync.PendingRemoval.of(item.details.ids, item.details.isSeries, trakt = t.lastSyncedTrakt != null, simkl = t.lastSyncedSimkl != null))
            onLocalChange?.invoke()
        }
    }

    /**
     * Removal decided by a service (title removed from the remote list): deleted here, and also removed from
     * the other service if it was synced there — without sending anything back to the originating service.
     */
    suspend fun removeFromService(localId: Long, fromTrakt: Boolean) {
        val e = dao.getById(localId) ?: return
        val item = e.toLibraryItem()
        val t = item.tracking
        dao.delete(e)
        val trakt = !fromTrakt && t.lastSyncedTrakt != null
        val simkl = fromTrakt && t.lastSyncedSimkl != null
        if (!item.details.ids.isEmpty && (trakt || simkl)) {
            settings.addPendingRemoval(com.example.trackstuff.data.sync.PendingRemoval.of(item.details.ids, item.details.isSeries, trakt = trakt, simkl = simkl))
        }
    }

    /** Reloads the page from the online sources (poster, description, ratings). */
    suspend fun refresh(localId: Long): LibraryItem? {
        val item = get(localId) ?: return null
        val d = item.details
        // Explicit refresh: drop this title's cached responses so the sources are actually re-queried.
        val markers = listOfNotNull(
            d.ids.tmdbId?.let { (if (d.isSeries) "/tv/" else "/movie/") + it },
            d.ids.tvdbId?.let { (if (d.isSeries) "/series/" else "/movies/") + it },
            d.ids.imdbId?.let { "i=" + it },
        )
        if (markers.isNotEmpty() && com.example.trackstuff.data.remote.RefreshLimiter.tryAcquire("title:$localId")) com.example.trackstuff.data.remote.Network.evict { url -> markers.any { it in url } }
        // A source that is down today must not degrade the stored page: fresh values win, old ones fill the gaps.
        val fresh = metadata.details(d.ids, d.isSeries, d.title, d.year).fillMissingFrom(d)
        // Keep the category chosen by the user when it differs from the automatic guess.
        val kind = if (d.kind != fresh.kind && d.kind != MediaKind.MOVIE && d.kind != MediaKind.SERIES) d.kind else fresh.kind
        updateDetails(localId, fresh.copy(kind = kind, ids = d.ids.merge(fresh.ids)))
        if (d.isSeries) try { refreshEpisodes(localId, force = true) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { Log.w("Library", "Episodes refresh failed for ${d.title}", e) }
        return get(localId)
    }

    // ---- Episodes

    fun observeEpisodes(localId: Long): Flow<List<Episode>> = episodes.observe(localId).map { list -> list.map { it.toEpisode() } }

    /** Next episode to air per series (local id → episode), for the Upcoming row. */
    fun observeUpcomingEpisodes(): Flow<Map<Long, Episode>> = episodes.observeUpcoming(java.time.LocalDate.now().toString())
        .map { list -> list.associate { it.localId to Episode(it.season, it.number, null, it.airDate) } }

    /**
     * Fetches the episode list of a series (titles, air dates) unless it was fetched less than
     * [EPISODES_TTL_MS] ago ([force] ignores that). The season sizes of the page follow the list.
     */
    suspend fun refreshEpisodes(localId: Long, force: Boolean = false) {
        val e = dao.getById(localId) ?: return
        if (!e.isSeries) return
        if (!force && e.episodesAt != null && System.currentTimeMillis() - e.episodesAt < EPISODES_TTL_MS) return
        val item = e.toLibraryItem()
        val list = metadata.episodes(item.details.ids, item.details.numberOfSeasons, item.details.title)
        if (list.isEmpty()) return
        episodes.replace(localId, list.map { EpisodeEntity.from(localId, it) })
        episodes.markFetched(localId, System.currentTimeMillis())
        val counts = list.groupBy { it.season }.let { g -> (1..(g.keys.maxOrNull() ?: 0)).map { s -> g[s]?.size ?: 0 } }
        if (counts.isNotEmpty() && counts != item.details.seasonEpisodes) updateDetails(localId, item.details.copy(seasonEpisodes = counts, numberOfSeasons = item.details.numberOfSeasons ?: counts.size))
    }

    private @Volatile var staleSweepAt = 0L

    /**
     * Refreshes the episode lists that are older than a week for the series in progress (the ones whose next
     * episode matters), at most once an hour. Called when the app comes to the foreground.
     */
    suspend fun refreshStaleEpisodes() {
        val now = System.currentTimeMillis()
        if (now - staleSweepAt < STALE_SWEEP_MS) return
        staleSweepAt = now
        for (e in dao.getAll()) {
            if (!e.isSeries || e.status == WatchStatus.COMPLETED) continue
            if (e.episodesAt != null && now - e.episodesAt < EPISODES_TTL_MS) continue
            try { refreshEpisodes(e.localId) } catch (ex: kotlinx.coroutines.CancellationException) { throw ex } catch (ex: Exception) { Log.w("Library", "Episodes refresh failed for ${e.title}", ex) }
        }
    }

    /** Enriches imported pages (Trakt/Simkl) that have no poster or description yet. */
    suspend fun enrichPending(): Int {
        var count = 0
        for (e in dao.getNeedingEnrichment()) {
            try {
                refresh(e.localId)
                count++
            } catch (ex: Exception) {
                Log.w("Library", "Enrichment failed for ${e.title}", ex)
            }
        }
        return count
    }

    companion object {
        /** Episode lists follow the series page lifetime: a week. */
        const val EPISODES_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        const val STALE_SWEEP_MS = 60 * 60 * 1000L
    }
}
