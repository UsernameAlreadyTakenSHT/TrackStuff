package com.example.trackstuff.ui.search

import com.example.trackstuff.data.remote.describeError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.repository.MetadataRepository
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.MediaSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<MediaSummary> = emptyList(),
    val loading: Boolean = false,
    val source: DataSource? = null,
    val problems: List<String> = emptyList(),
    /** Results already in the library → local id. */
    val inLibrary: Map<MediaSummary, Long> = emptyMap(),
    val searched: Boolean = false,
    /** Source to query; null = automatic cascade (first source with results). */
    val only: DataSource? = null,
)

class SearchViewModel(private val metadata: MetadataRepository, private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        job?.cancel()
        if (q.trim().length < MIN_QUERY) {
            _state.update { it.copy(results = emptyList(), searched = false, problems = emptyList()) }
            return
        }
        // Automatic search after a short typing pause.
        job = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            search()
        }
    }

    /** Source chip: re-runs the current query on that source (null = automatic). */
    fun setSource(only: DataSource?) {
        if (_state.value.only == only) return
        _state.update { it.copy(only = only) }
        search()
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.length < MIN_QUERY) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val outcome = try {
                metadata.search(q, _state.value.only)
            } catch (e: Exception) {
                com.example.trackstuff.data.repository.SearchOutcome(emptyList(), null, listOf(describeError(e)))
            }
            val inLib = outcome.results.mapNotNull { r -> library.findExisting(r.ids, r.isSeries)?.let { r to it.localId } }.toMap()
            _state.update { it.copy(results = outcome.results, source = outcome.source, problems = outcome.problems, loading = false, inLibrary = inLib, searched = true) }
        }
    }

    companion object {
        /** Fewer intermediate queries while typing: at least 3 characters, 600 ms pause. */
        const val MIN_QUERY = 3
        const val DEBOUNCE_MS = 600L
    }
}
