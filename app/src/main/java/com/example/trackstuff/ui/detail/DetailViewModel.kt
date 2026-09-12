package com.example.trackstuff.ui.detail

import androidx.lifecycle.ViewModel
import com.example.trackstuff.R
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Short message shown in a snackbar, resolved in the screen (the ViewModel has no Context). */
data class DetailMessage(@androidx.annotation.StringRes val res: Int, @androidx.annotation.StringRes val argRes: Int? = null, val arg: String? = null)

data class DetailUiState(
    val loading: Boolean = true,
    val error: String? = null,
    /** Displayed page (local or freshly downloaded). */
    val details: MediaDetails? = null,
    /** Library item when the title is in the library. */
    val item: LibraryItem? = null,
    val message: DetailMessage? = null,
    val refreshing: Boolean = false,
) {
    val inLibrary get() = item != null
}

/**
 * Page of a title. Two cases:
 *  - `localId` known: the local database is observed (works offline);
 *  - otherwise: the page is downloaded (TMDB → TVDB → OMDb → IMDb → omdb.org) and adding is offered.
 */
class DetailViewModel(
    private val library: LibraryRepository,
    private val metadata: MetadataRepository,
    private val initialLocalId: Long?,
    private val ids: ExternalIds,
    private val isSeries: Boolean,
    private val title: String?,
    private val year: Int?,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state
    private var localId: Long? = initialLocalId

    init {
        if (initialLocalId != null) observeLocal(initialLocalId) else loadRemote()
    }

    private fun observeLocal(id: Long) {
        viewModelScope.launch {
            library.observe(id).collect { item ->
                if (item == null) _state.update { it.copy(loading = false, item = null) }
                else _state.update { it.copy(loading = false, item = item, details = item.details, error = null) }
            }
        }
    }

    private fun loadRemote() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // Maybe already in the library (e.g. imported from Trakt without a tmdbId at search time).
                library.findExisting(ids, isSeries)?.let { existing ->
                    localId = existing.localId
                    observeLocal(existing.localId)
                    return@launch
                }
                val d = metadata.details(ids, isSeries, title, year)
                _state.update { it.copy(loading = false, details = d) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Error") }
            }
        }
    }

    fun add(status: WatchStatus = WatchStatus.PLANNED) {
        val d = _state.value.details ?: return
        viewModelScope.launch {
            val id = library.add(d, status)
            localId = id
            observeLocal(id)
            _state.update { it.copy(message = DetailMessage(R.string.detail_added_to, argRes = status.labelRes)) }
        }
    }

    fun remove() {
        val id = localId ?: return
        viewModelScope.launch {
            library.remove(id)
            localId = null
            _state.update { it.copy(item = null, message = DetailMessage(R.string.detail_removed)) }
        }
    }

    fun setStatus(status: WatchStatus) = updateTracking { it.copy(status = status) }

    fun setRating(rating: Int?) = updateTracking { it.copy(userRating = rating) }
    fun setNotes(notes: String) = updateTracking { it.copy(notes = notes) }

    fun setProgress(season: Int, episode: Int) = updateTracking {
        it.copy(currentSeason = season.coerceAtLeast(0), currentEpisode = episode.coerceAtLeast(0), status = if (it.status == WatchStatus.PLANNED && season > 0) WatchStatus.WATCHING else it.status)
    }

    /**
     * Next episode. When the number of episodes per season is known, moves to the next season at the
     * end of a season, and marks the series completed after the last episode.
     */
    fun nextEpisode() {
        val item = _state.value.item ?: return
        val t = item.tracking
        val seasons = item.details.seasonEpisodes
        var s = if (t.currentSeason == 0) 1 else t.currentSeason
        var e = t.currentEpisode + 1
        val inSeason = seasons.getOrNull(s - 1)
        if (inSeason != null && e > inSeason) {
            if (s >= seasons.size) {
                updateTracking { it.copy(currentSeason = s, currentEpisode = inSeason, status = WatchStatus.COMPLETED) }
                return
            }
            s += 1; e = 1
        }
        setProgress(s, e)
    }

    fun setKind(kind: MediaKind) {
        val id = localId ?: return
        viewModelScope.launch { library.setKind(id, kind) }
    }

    fun refresh() {
        val id = localId ?: return
        com.example.trackstuff.data.remote.RefreshLimiter.waitMinutes("title:$id")?.let { wait ->
            _state.update { it.copy(message = DetailMessage(R.string.refresh_wait, arg = wait.toString())) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                library.refresh(id)
                _state.update { it.copy(refreshing = false, message = DetailMessage(R.string.detail_updated)) }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, message = DetailMessage(R.string.detail_update_failed, arg = e.message ?: "")) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun updateTracking(transform: (com.example.trackstuff.domain.UserTracking) -> com.example.trackstuff.domain.UserTracking) {
        val id = localId ?: return
        viewModelScope.launch { library.updateTracking(id, transform) }
    }
}
