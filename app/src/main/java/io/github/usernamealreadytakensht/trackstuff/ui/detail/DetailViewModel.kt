package io.github.usernamealreadytakensht.trackstuff.ui.detail

import io.github.usernamealreadytakensht.trackstuff.data.remote.describeError
import androidx.lifecycle.ViewModel
import io.github.usernamealreadytakensht.trackstuff.R
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.trackstuff.data.repository.LibraryRepository
import io.github.usernamealreadytakensht.trackstuff.data.repository.MetadataRepository
import io.github.usernamealreadytakensht.trackstuff.domain.Episode
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.WatchProviders
import kotlinx.coroutines.Job
import io.github.usernamealreadytakensht.trackstuff.domain.LibraryItem
import io.github.usernamealreadytakensht.trackstuff.domain.MediaDetails
import io.github.usernamealreadytakensht.trackstuff.domain.MediaKind
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus
import io.github.usernamealreadytakensht.trackstuff.domain.advanced
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
    /** Where to watch in the user's region; null until known (or when TMDB is not configured). */
    val providers: WatchProviders? = null,
    /** Episodes of a series (library titles: stored; others: fetched for the page only). */
    val episodes: List<Episode> = emptyList(),
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

    private var episodesJob: Job? = null
    private var providersJob: Job? = null

    private fun observeLocal(id: Long) {
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            library.observeEpisodes(id).collect { eps -> _state.update { it.copy(episodes = eps) } }
        }
        // Episode list fetched once a week at most; providers looked up once per page.
        viewModelScope.launch { runCatching { library.refreshEpisodes(id) } }
        viewModelScope.launch { runCatching { library.completeIds(id) } }
        viewModelScope.launch {
            library.observe(id).collect { item ->
                if (item != null && providersJob == null) loadProviders(item.details)
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
                loadProviders(d)
                if (d.isSeries) episodesJob = viewModelScope.launch {
                    val eps = runCatching { metadata.episodes(d.ids, d.numberOfSeasons, d.title) }.getOrDefault(emptyList())
                    _state.update { it.copy(episodes = eps) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = describeError(e)) }
            }
        }
    }

    private fun loadProviders(d: MediaDetails) {
        providersJob = viewModelScope.launch {
            val p = try { metadata.watchProviders(d.ids, d.isSeries) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            _state.update { it.copy(providers = p) }
        }
    }

    /** Tap on an episode: watched up to it; tapping the current position steps back one episode. */
    fun setPosition(season: Int, episode: Int) {
        val t = _state.value.item?.tracking ?: return
        if (t.currentSeason == season && t.currentEpisode == episode) {
            val eps = _state.value.episodes
            val prev = eps.lastOrNull { it.season < season || (it.season == season && it.number < episode) }
            setProgress(prev?.season ?: 0, prev?.number ?: 0)
        } else setProgress(season, episode)
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

    /**
     * Status chosen by the user. "Watching" is never chosen directly — it follows the progress — so setting
     * a series to planned resets its progress, and completing it moves the progress to the last known episode.
     */
    fun setStatus(status: WatchStatus) = updateTracking { t ->
        val seasons = _state.value.item?.details?.seasonEpisodes.orEmpty()
        when (status) {
            WatchStatus.PLANNED -> t.copy(status = status, currentSeason = 0, currentEpisode = 0)
            WatchStatus.COMPLETED -> if (seasons.isNotEmpty()) t.copy(status = status, currentSeason = seasons.size, currentEpisode = seasons.last()) else t.copy(status = status)
            WatchStatus.WATCHING -> t.copy(status = if (t.currentSeason > 0) WatchStatus.WATCHING else t.status)
        }
    }

    /** Progress drives the status: something marked → watching, nothing marked → planned (a completed title stays completed until touched). */
    fun setProgress(season: Int, episode: Int) = updateTracking {
        val s = season.coerceAtLeast(0); val e = episode.coerceAtLeast(0)
        it.copy(currentSeason = s, currentEpisode = e, status = if (s > 0 || e > 0) WatchStatus.WATCHING else WatchStatus.PLANNED)
    }

    /** Next episode: next season at the end of a season, completed after the last known episode (see [advanced]). */
    fun nextEpisode() {
        val seasons = _state.value.item?.details?.seasonEpisodes.orEmpty()
        updateTracking { it.advanced(seasons) }
    }

    fun setKind(kind: MediaKind) {
        val id = localId ?: return
        viewModelScope.launch { library.setKind(id, kind) }
    }

    fun refresh() {
        val id = localId ?: return
        io.github.usernamealreadytakensht.trackstuff.data.remote.RefreshLimiter.waitMinutes("title:$id")?.let { wait ->
            _state.update { it.copy(message = DetailMessage(R.string.refresh_wait, arg = wait.toString())) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            try {
                library.refresh(id)
                _state.update { it.copy(refreshing = false, message = DetailMessage(R.string.detail_updated)) }
            } catch (e: Exception) {
                _state.update { it.copy(refreshing = false, message = DetailMessage(R.string.detail_update_failed, arg = describeError(e))) }
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun updateTracking(transform: (io.github.usernamealreadytakensht.trackstuff.domain.UserTracking) -> io.github.usernamealreadytakensht.trackstuff.domain.UserTracking) {
        val id = localId ?: return
        viewModelScope.launch { library.updateTracking(id, transform) }
    }
}
