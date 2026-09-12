package com.example.trackstuff.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Library in three rows:
 *  - Continue watching: in progress, most recently changed first;
 *  - Start watching: planned;
 *  - History: completed, most recent first.
 */
data class LibraryUiState(
    val continueWatching: List<LibraryItem> = emptyList(),
    val startWatching: List<LibraryItem> = emptyList(),
    val history: List<LibraryItem> = emptyList(),
    val total: Int = 0,
)

class LibraryViewModel(library: LibraryRepository) : ViewModel() {
    val state: StateFlow<LibraryUiState> = library.items.map { items ->
        val byRecent = items.sortedByDescending { it.tracking.updatedAt }
        LibraryUiState(
            continueWatching = byRecent.filter { it.tracking.status == WatchStatus.WATCHING },
            startWatching = byRecent.filter { it.tracking.status == WatchStatus.PLANNED },
            history = byRecent.filter { it.tracking.status == WatchStatus.COMPLETED },
            total = items.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}
