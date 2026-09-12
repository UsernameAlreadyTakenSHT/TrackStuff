package com.example.trackstuff.data.repository

import android.util.Log
import com.example.trackstuff.data.local.MediaDao
import com.example.trackstuff.data.local.MediaEntity
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Local library (Room). Every page is kept for offline use. */
class LibraryRepository(private val dao: MediaDao, private val metadata: MetadataRepository, private val settings: com.example.trackstuff.data.settings.SettingsRepository) {
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

    /** Adds a title imported from a sync service, without a full page (enriched later). */
    suspend fun addStub(details: MediaDetails, tracking: UserTracking): Long =
        dao.upsert(MediaEntity.from(details, tracking, needsEnrichment = true))

    suspend fun updateTracking(localId: Long, transform: (UserTracking) -> UserTracking) {
        val e = dao.getById(localId) ?: return
        val item = e.toLibraryItem()
        val t = transform(item.tracking).copy(updatedAt = System.currentTimeMillis())
        dao.update(MediaEntity.from(item.details, t, localId, e.needsEnrichment))
        onLocalChange?.invoke()
    }

    suspend fun updateDetails(localId: Long, details: MediaDetails, needsEnrichment: Boolean = false) {
        val e = dao.getById(localId) ?: return
        dao.update(MediaEntity.from(details, e.toLibraryItem().tracking, localId, needsEnrichment))
    }

    suspend fun setKind(localId: Long, kind: MediaKind) {
        val e = dao.getById(localId) ?: return
        dao.update(e.copy(kind = kind, updatedAt = System.currentTimeMillis()))
    }

    suspend fun markSynced(localId: Long, trakt: Boolean, simkl: Boolean, ids: ExternalIds? = null) {
        val e = dao.getById(localId) ?: return
        val now = System.currentTimeMillis()
        dao.update(
            e.copy(
                lastSyncedTrakt = if (trakt) now else e.lastSyncedTrakt,
                lastSyncedSimkl = if (simkl) now else e.lastSyncedSimkl,
                traktId = ids?.traktId ?: e.traktId,
                simklId = ids?.simklId ?: e.simklId,
                omdbOrgId = e.omdbOrgId ?: ids?.omdbOrgId,
                imdbId = e.imdbId ?: ids?.imdbId,
                tmdbId = e.tmdbId ?: ids?.tmdbId,
                tvdbId = e.tvdbId ?: ids?.tvdbId,
            )
        )
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
        val fresh = metadata.details(d.ids, d.isSeries, d.title, d.year)
        // Keep the category chosen by the user when it differs from the automatic guess.
        val kind = if (d.kind != fresh.kind && d.kind != MediaKind.MOVIE && d.kind != MediaKind.SERIES) d.kind else fresh.kind
        updateDetails(localId, fresh.copy(kind = kind, ids = d.ids.merge(fresh.ids)))
        return get(localId)
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
}
