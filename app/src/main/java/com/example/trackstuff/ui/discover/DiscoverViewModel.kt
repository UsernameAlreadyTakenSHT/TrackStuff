package com.example.trackstuff.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.DiscoverSection
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.MediaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

enum class MediaFilter(@androidx.annotation.StringRes val labelRes: Int) { MOVIES(com.example.trackstuff.R.string.discover_movies), SERIES(com.example.trackstuff.R.string.discover_series) }

data class DiscoverUiState(
    val loading: Boolean = true,
    val sections: List<DiscoverSection> = emptyList(),
    val problems: List<String> = emptyList(),
    /** Titles already in the library → local id. */
    val inLibrary: Map<MediaSummary, Long> = emptyMap(),
    /** Displayed source. */
    val source: DataSource = DataSource.TMDB,
    /** Movies by default. */
    val media: MediaFilter = MediaFilter.MOVIES,
    /** Posters resolved on demand for titles without one (IMDb Top 250), by IMDb id. */
    val posters: Map<String, String?> = emptyMap(),
    /** Minutes to wait before the next forced refresh; set when the button is tapped too soon. */
    val refreshWaitMinutes: Long? = null,
)

/** Discover screen: trending, popular and new titles, per source (TMDB, TVDB, IMDb, omdb.org). */
class DiscoverViewModel(private val metadata: MetadataRepository, private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    init { load(force = false) }

    fun setSource(source: DataSource) = _state.update { it.copy(source = source) }

    private val posterJobs = HashSet<String>()
    private val posterLimit = kotlinx.coroutines.sync.Semaphore(4)

    /** Fetches the poster of a visible card without one (once per title). */
    fun ensurePoster(r: MediaSummary) {
        val id = r.ids.imdbId ?: return
        if (r.posterUrl != null || id in posterJobs) return
        posterJobs += id
        viewModelScope.launch {
            val url = posterLimit.withPermit { metadata.posterByImdb(id, r.isSeries) }
            _state.update { it.copy(posters = it.posters + (id to url)) }
        }
    }

    fun setMedia(media: MediaFilter) = _state.update { it.copy(media = media) }

    /** Refresh button: bypasses the memory and HTTP caches for the chart lists, at most once an hour. */
    fun refresh() {
        val wait = com.example.trackstuff.data.remote.RefreshLimiter.waitMinutes("discover")
        if (wait != null) { _state.update { it.copy(refreshWaitMinutes = wait) }; return }
        load(force = true)
    }

    fun consumeMessage() = _state.update { it.copy(refreshWaitMinutes = null) }

    private fun load(force: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val outcome = try {
                metadata.discover(force)
            } catch (e: Exception) {
                com.example.trackstuff.data.repository.DiscoverOutcome(emptyList(), listOf(e.message ?: "Error"))
            }
            val inLib = outcome.sections.flatMap { it.items }
                .mapNotNull { r -> library.findExisting(r.ids, r.isSeries)?.let { r to it.localId } }.toMap()
            // Without a TMDB key, switch to TVDB automatically.
            val available = outcome.sections.map { it.source }.distinct()
            _state.update { st ->
                st.copy(
                    loading = false, sections = outcome.sections, problems = outcome.problems, inLibrary = inLib,
                    source = if (st.source in available || available.isEmpty()) st.source else available.first(),
                )
            }
        }
    }
}
