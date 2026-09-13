package com.example.trackstuff.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.repository.LibraryRepository
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.data.sync.SyncCoordinator
import com.example.trackstuff.data.sync.SyncFailure
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.WatchStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library in four rows:
 *  - Continue watching: in progress, most recently changed first;
 *  - Upcoming: series of the library with a known next air date, soonest first;
 *  - Start watching: planned;
 *  - History: completed, most recent first.
 */
data class LibraryUiState(
    val continueWatching: List<LibraryItem> = emptyList(),
    val upcoming: List<LibraryItem> = emptyList(),
    val startWatching: List<LibraryItem> = emptyList(),
    val history: List<LibraryItem> = emptyList(),
    val total: Int = 0,
    /** False until at least one online database key is set: the empty library then points to Settings. */
    val configured: Boolean = true,
)

class LibraryViewModel(private val library: LibraryRepository, settings: SettingsRepository, sync: SyncCoordinator) : ViewModel() {
    val state: StateFlow<LibraryUiState> = combine(library.items, settings.settings) { items, s ->
        val byRecent = items.sortedByDescending { it.tracking.updatedAt }
        val today = java.time.LocalDate.now().toString()
        LibraryUiState(
            continueWatching = byRecent.filter { it.tracking.status == WatchStatus.WATCHING },
            // ISO dates compare as strings.
            upcoming = items.filter { it.details.isSeries && it.tracking.status != WatchStatus.COMPLETED && (it.details.nextAired ?: "") >= today }
                .sortedBy { it.details.nextAired },
            startWatching = byRecent.filter { it.tracking.status == WatchStatus.PLANNED },
            history = byRecent.filter { it.tracking.status == WatchStatus.COMPLETED },
            total = items.size,
            configured = s.hasTmdb || s.hasTvdb,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Sync failures not shown yet: each one once, and only those after this screen was created. */
    private val seenFailure = MutableStateFlow(System.currentTimeMillis())
    val syncFailure: StateFlow<SyncFailure?> = combine(sync.state, seenFailure) { st, seen -> st.lastFailure?.takeIf { it.at > seen } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun consumeSyncFailure(f: SyncFailure) { seenFailure.value = maxOf(seenFailure.value, f.at) }

    /** "+1" on a Continue watching card. */
    fun advance(localId: Long) { viewModelScope.launch { library.advance(localId) } }
}
