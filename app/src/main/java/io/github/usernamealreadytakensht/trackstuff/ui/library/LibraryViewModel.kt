package io.github.usernamealreadytakensht.trackstuff.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.usernamealreadytakensht.trackstuff.data.repository.LibraryRepository
import io.github.usernamealreadytakensht.trackstuff.data.settings.SettingsRepository
import io.github.usernamealreadytakensht.trackstuff.data.sync.SyncCoordinator
import io.github.usernamealreadytakensht.trackstuff.data.sync.SyncFailure
import io.github.usernamealreadytakensht.trackstuff.domain.Episode
import io.github.usernamealreadytakensht.trackstuff.domain.LibraryItem
import io.github.usernamealreadytakensht.trackstuff.domain.MediaKind
import io.github.usernamealreadytakensht.trackstuff.domain.WatchStatus
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
    /** Active filter: free text (title) and category, applied to every row. */
    val query: String = "",
    val kind: MediaKind? = null,
    /** Next episode to air per series (from the stored episode lists), for the Upcoming row. */
    val upcomingEpisodes: Map<Long, Episode> = emptyMap(),
)

class LibraryViewModel(private val library: LibraryRepository, private val settings: SettingsRepository, private val sync: SyncCoordinator) : ViewModel() {
    /** First launch with an empty library: offer to import the backup files of an earlier install (once). */
    val showWelcome: StateFlow<Boolean> = combine(settings.welcomeSeen, library.items) { seen, items -> !seen && items.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissWelcome() { viewModelScope.launch { settings.setWelcomeSeen() } }

    /** True while a service is syncing (pull-to-refresh indicator). */
    val syncing: StateFlow<Boolean> = sync.state.map { it.running != null }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun syncNow() = sync.syncNow()

    private val query = MutableStateFlow("")
    private val kind = MutableStateFlow<MediaKind?>(null)

    fun setQuery(q: String) { query.value = q }
    fun setKind(k: MediaKind?) { kind.value = k }

    val state: StateFlow<LibraryUiState> = combine(library.items, settings.settings, query, kind, library.observeUpcomingEpisodes()) { all, s, q, k, nextEps ->
        val needle = q.trim()
        val items = all.filter { (k == null || it.details.kind == k) && (needle.isEmpty() || it.details.title.contains(needle, ignoreCase = true) || it.details.originalTitle?.contains(needle, ignoreCase = true) == true) }
        val byRecent = items.sortedByDescending { it.tracking.updatedAt }
        val today = java.time.LocalDate.now().toString()
        LibraryUiState(
            continueWatching = byRecent.filter { it.tracking.status == WatchStatus.WATCHING },
            // ISO dates compare as strings.
            upcoming = items.filter { it.details.isSeries && it.tracking.status != WatchStatus.COMPLETED && ((it.details.nextAired ?: "") >= today || nextEps.containsKey(it.localId)) }
                .sortedBy { nextEps[it.localId]?.airDate ?: it.details.nextAired },
            upcomingEpisodes = nextEps,
            startWatching = byRecent.filter { it.tracking.status == WatchStatus.PLANNED },
            history = byRecent.filter { it.tracking.status == WatchStatus.COMPLETED },
            total = all.size,
            configured = s.hasTmdb || s.hasTvdb,
            query = q, kind = k,
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
