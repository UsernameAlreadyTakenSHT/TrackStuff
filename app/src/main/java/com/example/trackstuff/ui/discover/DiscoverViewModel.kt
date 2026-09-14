package com.example.trackstuff.ui.discover

import com.example.trackstuff.data.remote.describeError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.DiscoverSection
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

enum class MediaFilter(@androidx.annotation.StringRes val labelRes: Int) { MOVIES(com.example.trackstuff.R.string.discover_movies), SERIES(com.example.trackstuff.R.string.discover_series) }

data class DiscoverUiState(
    val loading: Boolean = true,
    val sections: List<DiscoverSection> = emptyList(),
    val problems: List<String> = emptyList(),
    /** Displayed source. */
    val source: DataSource = DataSource.TMDB,
    /** Movies by default. */
    val media: MediaFilter = MediaFilter.MOVIES,
    /** Minutes to wait before the next forced refresh; set when the button is tapped too soon. */
    val refreshWaitMinutes: Long? = null,
)

/**
 * Library titles indexed by external id, so that a Discover card can be matched without a database
 * query per title. Rebuilt from the Room flow whenever the library changes.
 */
class LibraryIndex private constructor(
    private val byTmdb: Map<Pair<Int, Boolean>, Long>,
    private val byImdb: Map<String, Long>,
    private val byTvdb: Map<Pair<Int, Boolean>, Long>,
    private val byOmdbOrg: Map<Int, Long>,
) {
    /** Local id of the library entry matching [s], if any (same lookup order as `LibraryRepository.findExisting`). */
    fun localIdOf(s: MediaSummary): Long? =
        s.ids.tmdbId?.let { byTmdb[it to s.isSeries] }
            ?: s.ids.imdbId?.let { byImdb[it] }
            ?: s.ids.tvdbId?.let { byTvdb[it to s.isSeries] }
            ?: s.ids.omdbOrgId?.let { byOmdbOrg[it] }

    companion object {
        val EMPTY = of(emptyList())

        fun of(items: List<LibraryItem>): LibraryIndex {
            val tmdb = HashMap<Pair<Int, Boolean>, Long>(); val imdb = HashMap<String, Long>()
            val tvdb = HashMap<Pair<Int, Boolean>, Long>(); val omdb = HashMap<Int, Long>()
            for (it in items) {
                val ids = it.details.ids
                ids.tmdbId?.let { id -> tmdb.putIfAbsent(id to it.details.isSeries, it.localId) }
                ids.imdbId?.let { id -> imdb.putIfAbsent(id, it.localId) }
                ids.tvdbId?.let { id -> tvdb.putIfAbsent(id to it.details.isSeries, it.localId) }
                ids.omdbOrgId?.let { id -> omdb.putIfAbsent(id, it.localId) }
            }
            return LibraryIndex(tmdb, imdb, tvdb, omdb)
        }
    }
}

/** Discover screen: trending, popular and new titles, per source (TMDB, TVDB, IMDb, omdb.org). */
class DiscoverViewModel(private val metadata: MetadataRepository, library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state

    /** Titles already in the library, kept up to date (a title added from its page shows "In my list" on return). */
    val libraryIndex: StateFlow<LibraryIndex> = library.items.map { LibraryIndex.of(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryIndex.EMPTY)

    /**
     * Posters resolved on demand for titles without one (IMDb Top 250), by IMDb id. Separate from [state]
     * so that each resolved poster only redraws the cards that read it, not the whole screen.
     */
    private val _posters = MutableStateFlow<Map<String, String?>>(emptyMap())
    val posters: StateFlow<Map<String, String?>> = _posters

    /** Pages being cached for offline use (done to total), null when idle. */
    val prefetch: StateFlow<Pair<Int, Int>?> = metadata.prefetchProgress

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
            _posters.update { it + (id to url) }
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.example.trackstuff.data.repository.DiscoverOutcome(emptyList(), listOf(describeError(e)))
            }
            // Without a TMDB key, switch to TVDB automatically.
            val available = outcome.sections.map { it.source }.distinct()
            _state.update { st ->
                st.copy(
                    loading = false, sections = outcome.sections, problems = outcome.problems,
                    source = if (st.source in available || available.isEmpty()) st.source else available.first(),
                )
            }
        }
    }
}
