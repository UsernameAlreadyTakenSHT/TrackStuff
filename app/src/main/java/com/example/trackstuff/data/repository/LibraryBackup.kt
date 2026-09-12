package com.example.trackstuff.data.repository

import com.example.trackstuff.data.local.MediaDao
import com.example.trackstuff.data.local.MediaEntity
import com.example.trackstuff.domain.ExternalIds
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/**
 * JSON export / import of the whole library (pages and tracking), for backups and phone changes.
 * Import merges: a title already present keeps whichever tracking is the most recently updated.
 */
class LibraryBackup(private val dao: MediaDao, private val library: LibraryRepository) {

    @JsonClass(generateAdapter = true)
    data class File(val app: String = "TrackStuff", val version: Int = FORMAT_VERSION, val exportedAt: Long, val items: List<MediaEntity>)

    data class ImportResult(val added: Int, val updated: Int, val skipped: Int)

    private val adapter = Moshi.Builder().build().adapter(File::class.java).indent("  ")

    suspend fun export(): String = adapter.toJson(File(exportedAt = System.currentTimeMillis(), items = dao.getAll()))

    suspend fun import(json: String): ImportResult {
        val file = adapter.fromJson(json) ?: throw IllegalArgumentException("Not a TrackStuff export")
        if (file.app != "TrackStuff") throw IllegalArgumentException("Not a TrackStuff export")
        var added = 0; var updated = 0; var skipped = 0
        for (e in file.items) {
            val ids = ExternalIds(tmdbId = e.tmdbId, imdbId = e.imdbId, tvdbId = e.tvdbId, traktId = e.traktId, simklId = e.simklId, omdbOrgId = e.omdbOrgId)
            val existing = library.findExisting(ids, e.isSeries)
            when {
                existing == null -> { dao.upsert(e.copy(localId = 0, lastSyncedTrakt = null, lastSyncedSimkl = null)); added++ }
                e.updatedAt > existing.tracking.updatedAt -> { dao.update(e.copy(localId = existing.localId, lastSyncedTrakt = null, lastSyncedSimkl = null)); updated++ }
                else -> skipped++
            }
        }
        if (added + updated > 0) library.onLocalChange?.invoke()
        return ImportResult(added, updated, skipped)
    }

    companion object { const val FORMAT_VERSION = 1 }
}
