package com.example.trackstuff.ui.settings

import androidx.lifecycle.ViewModel
import com.example.trackstuff.R
import androidx.lifecycle.viewModelScope
import com.example.trackstuff.data.settings.AppSettings
import com.example.trackstuff.data.settings.AuthTokens
import com.example.trackstuff.data.imdb.ImdbInfo
import com.example.trackstuff.data.imdb.ImdbRepository
import com.example.trackstuff.data.omdborg.OmdbImportState
import com.example.trackstuff.data.omdborg.OmdbOrgInfo
import com.example.trackstuff.data.omdborg.OmdbOrgRepository
import com.example.trackstuff.data.remote.Network
import com.example.trackstuff.data.settings.SettingsRepository
import com.example.trackstuff.data.sync.DeviceCode
import com.example.trackstuff.data.sync.SimklSyncService
import com.example.trackstuff.data.sync.TraktSyncService
import com.example.trackstuff.data.sync.SyncService
import com.example.trackstuff.data.sync.SyncState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State of a connection to a sync service. */
data class ServiceState(
    val connecting: Boolean = false,
    val code: DeviceCode? = null,
    val syncing: Boolean = false,
    val lastResult: String? = null,
    /** Short message from resources (connected, timed out…). */
    @androidx.annotation.StringRes val lastResultRes: Int? = null,
)

data class SettingsUiState(
    val form: AppSettings = AppSettings(),
    val loaded: Boolean = false,
    val saved: Boolean = false,
    val trakt: ServiceState = ServiceState(),
    val simkl: ServiceState = ServiceState(),
    val omdbOrgInfo: OmdbOrgInfo = OmdbOrgInfo(0, null),
    val imdbInfo: ImdbInfo = ImdbInfo(0, null),
    /** Bytes used by the cache (responses + posters). */
    val cacheUsedBytes: Long = 0,
    /** Result of the last export / import, shown once in a snackbar. */
    @androidx.annotation.StringRes val backupMessageRes: Int? = null,
    val backupMessageArg: String? = null,
)

class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
    private val traktSync: TraktSyncService,
    private val simklSync: SimklSyncService,
    private val omdbOrg: OmdbOrgRepository,
    private val imdb: ImdbRepository,
    private val sync: com.example.trackstuff.data.sync.SyncCoordinator,
    private val backup: com.example.trackstuff.data.repository.LibraryBackup,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state
    val tokens: StateFlow<AuthTokens> = settingsRepo.tokens.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthTokens())

    val omdbImport: StateFlow<OmdbImportState> = omdbOrg.state
    val imdbImport: StateFlow<OmdbImportState> = imdb.state

    init {
        // Mirrors automatic syncs (foreground, after a change) in the Trakt / Simkl rows.
        viewModelScope.launch {
            sync.state.collect { st ->
                _state.update { it.copy(trakt = it.trakt.from(st, SyncService.TRAKT), simkl = it.simkl.from(st, SyncService.SIMKL)) }
            }
        }
        viewModelScope.launch {
            val s = settingsRepo.current()
            _state.update { it.copy(form = s, loaded = true, omdbOrgInfo = omdbOrg.info(), imdbInfo = imdb.info(), cacheUsedBytes = Network.cacheSizeBytes()) }
        }
        // Refreshes the title count at the end of each import.
        viewModelScope.launch {
            omdbOrg.state.collect { st -> if (st is OmdbImportState.Done || st is OmdbImportState.Idle) _state.update { it.copy(omdbOrgInfo = omdbOrg.info()) } }
        }
        viewModelScope.launch {
            imdb.state.collect { st -> if (st is OmdbImportState.Done || st is OmdbImportState.Idle) _state.update { it.copy(imdbInfo = imdb.info()) } }
        }
    }

    // ------------------------------------------------------------------ Cache

    fun clearCache() { Network.clearCache(); refreshCacheUsage() }
    fun refreshCacheUsage() = _state.update { it.copy(cacheUsedBytes = Network.cacheSizeBytes()) }

    // ------------------------------------------------------------------ Library backup

    /** Writes the library as JSON to [uri] (picked with the system file dialog). */
    fun exportLibrary(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch {
            val msg = try {
                val json = backup.export()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } ?: error("Cannot open file") }
                R.string.backup_exported to null
            } catch (e: Exception) { R.string.backup_failed to (e.message ?: "") }
            _state.update { it.copy(backupMessageRes = msg.first, backupMessageArg = msg.second) }
        }
    }

    /** Reads a JSON export from [uri] and merges it into the library. */
    fun importLibrary(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        viewModelScope.launch {
            val msg = try {
                val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Cannot open file") }
                val r = backup.import(json)
                R.string.backup_imported to "${r.added} / ${r.updated} / ${r.skipped}"
            } catch (e: Exception) { R.string.backup_failed to (e.message ?: "") }
            _state.update { it.copy(backupMessageRes = msg.first, backupMessageArg = msg.second) }
        }
    }

    fun consumeBackupMessage() = _state.update { it.copy(backupMessageRes = null, backupMessageArg = null) }

    // ------------------------------------------------------------------ omdb.org

    fun importOmdbOrg() { viewModelScope.launch { settingsRepo.save(_state.value.form); omdbOrg.startImport() } }
    fun cancelOmdbOrgImport() = omdbOrg.cancelImport()

    // ------------------------------------------------------------------ IMDb

    fun importImdb() { viewModelScope.launch { settingsRepo.save(_state.value.form); imdb.startImport() } }
    fun cancelImdbImport() = imdb.cancelImport()

    private var saveJob: Job? = null

    /** Every change is saved automatically (after a short typing pause). */
    fun edit(transform: (AppSettings) -> AppSettings) {
        _state.update { it.copy(form = transform(it.form), saved = false) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            kotlinx.coroutines.delay(400)
            settingsRepo.save(_state.value.form)
            _state.update { it.copy(saved = true) }
        }
    }

    // ------------------------------------------------------------------ Trakt

    fun connectTrakt() { viewModelScope.launch { settingsRepo.save(_state.value.form); sync.connect(SyncService.TRAKT) } }

    fun disconnectTrakt() = viewModelScope.launch { traktSync.disconnect(); _state.update { it.copy(trakt = ServiceState(lastResultRes = R.string.sync_disconnected)) } }

    fun syncTrakt() { viewModelScope.launch { sync.sync(SyncService.TRAKT) } }

    // ------------------------------------------------------------------ Simkl

    fun connectSimkl() { viewModelScope.launch { settingsRepo.save(_state.value.form); sync.connect(SyncService.SIMKL) } }

    fun disconnectSimkl() = viewModelScope.launch { simklSync.disconnect(); _state.update { it.copy(simkl = ServiceState(lastResultRes = R.string.sync_disconnected)) } }

    fun syncSimkl() { viewModelScope.launch { sync.sync(SyncService.SIMKL) } }

    fun cancelAuth() = sync.cancelAuth()

    /** Projects the coordinator state (sync in progress, results, code-based sign-in) onto a service row. */
    private fun ServiceState.from(st: SyncState, service: SyncService): ServiceState {
        val auth = st.auth?.takeIf { it.service == service }
        return copy(
            syncing = st.running == service,
            lastResult = st.lastResults[service] ?: lastResult,
            connecting = auth?.inProgress == true,
            code = auth?.takeIf { it.inProgress }?.code,
            lastResultRes = when (auth?.connected) {
                true -> if (service == SyncService.TRAKT) R.string.sync_connected_trakt else R.string.sync_connected_simkl
                false -> R.string.sync_timeout
                null -> if (auth?.error != null) null else lastResultRes
            },
        ).let { if (auth?.error != null) it.copy(lastResult = auth.error) else it }
    }
}
