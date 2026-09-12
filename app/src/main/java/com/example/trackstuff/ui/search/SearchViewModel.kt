package com.example.trackstuff.ui.search

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
)

class SearchViewModel(private val metadata: MetadataRepository, private val library: LibraryRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state
    private var job: Job? = null

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        job?.cancel()
        if (q.trim().length < 2) {
            _state.update { it.copy(results = emptyList(), searched = false, problems = emptyList()) }
            return
        }
        // Automatic search after a short typing pause.
        job = viewModelScope.launch {
            delay(450)
            search()
        }
    }

    fun search() {
        val q = _state.value.query.trim()
        if (q.length < 2) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val outcome = try {
                metadata.search(q)
            } catch (e: Exception) {
                com.example.trackstuff.data.repository.SearchOutcome(emptyList(), null, listOf(e.message ?: "Erreur"))
            }
            val inLib = outcome.results.mapNotNull { r -> library.findExisting(r.ids, r.isSeries)?.let { r to it.localId } }.toMap()
            _state.update { it.copy(results = outcome.results, source = outcome.source, problems = outcome.problems, loading = false, inLibrary = inLib, searched = true) }
        }
    }
}
