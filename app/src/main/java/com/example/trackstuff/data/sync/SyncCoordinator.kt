package com.example.trackstuff.data.sync

import android.util.Log
import com.example.trackstuff.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "Sync"

enum class SyncService(val key: String, val label: String) { TRAKT("trakt", "Trakt"), SIMKL("simkl", "Simkl") }

/** Sign-in in progress: code to enter on the site, then the result. */
data class AuthProgress(val service: SyncService, val code: DeviceCode? = null, val connected: Boolean? = null, val error: String? = null) {
    val inProgress get() = connected == null && error == null
}

data class SyncState(
    /** Service currently syncing, null when idle. */
    val running: SyncService? = null,
    /** Last result per service (short message). */
    val lastResults: Map<SyncService, String> = emptyMap(),
    /** Code-based sign-in in progress or finished (survives navigation between tabs). */
    val auth: AuthProgress? = null,
)

/**
 * Orchestrates Trakt / Simkl syncs, automatic and manual:
 *  - when the app comes to the foreground (at most once every [FOREGROUND_INTERVAL_MS]): pull + push;
 *  - [LOCAL_CHANGE_DELAY_MS] after a local change (groups changes made in quick succession);
 *  - never in the background while the app is not in use (Simkl rule).
 *
 * Conflict resolution: a title changed locally since its last sync wins over the service.
 * When both services are connected, the "primary" one is synced first: its state becomes the local
 * state, which is then pushed to the second one — so the primary has the last word.
 */
class SyncCoordinator(
    private val settingsRepo: SettingsRepository,
    private val trakt: TraktSyncService,
    private val simkl: SimklSyncService,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state
    private var lastForegroundAt = 0L
    private var pendingLocal: Job? = null
    private var authJob: Job? = null
    @Volatile private var pulling = false
    /** Set when a sync failed (typically offline); the next network availability retries it. */
    @Volatile private var retryPending = false

    /** Called when the app returns to the foreground. */
    fun onForeground() {
        val now = System.currentTimeMillis()
        if (now - lastForegroundAt < FOREGROUND_INTERVAL_MS) return
        lastForegroundAt = now
        scope.launch { syncAll() }
    }

    /** Called after every local tracking change (status, progress, addition, removal). */
    fun onLocalChange() {
        if (pulling) return // change caused by a pull in progress, not by the user
        pendingLocal?.cancel()
        pendingLocal = scope.launch {
            delay(LOCAL_CHANGE_DELAY_MS)
            syncAll()
        }
    }

    /** True while the app has a started activity; the network callback only syncs in that case (Simkl rule). */
    @Volatile var inForeground = false
    private var networkRetry: Job? = null

    /** Called when a network with internet access becomes available: retries a sync that failed while offline. */
    fun onNetworkAvailable() {
        if (!retryPending || !inForeground) return
        networkRetry?.cancel()
        networkRetry = scope.launch {
            delay(NETWORK_SETTLE_MS)
            syncAll()
        }
    }

    // ------------------------------------------------------------------ Code-based sign-in

    /** Requests a code, exposes it in [state] and waits for validation on the site (application scope). */
    fun connect(service: SyncService) {
        authJob?.cancel()
        authJob = scope.launch {
            _state.update { it.copy(auth = AuthProgress(service)) }
            try {
                val ok = when (service) {
                    SyncService.TRAKT -> {
                        val (code, deviceCode) = trakt.requestCode()
                        _state.update { it.copy(auth = AuthProgress(service, code)) }
                        trakt.waitForAuthorization(deviceCode, 5, code.expiresInSeconds)
                    }
                    SyncService.SIMKL -> {
                        val (code, interval) = simkl.requestCode()
                        _state.update { it.copy(auth = AuthProgress(service, code)) }
                        simkl.waitForAuthorization(code.userCode, interval, code.expiresInSeconds)
                    }
                }
                _state.update { it.copy(auth = AuthProgress(service, connected = ok)) }
                // First sync in its own job: cancelling the sign-in UI must not cancel a sync half-way.
                if (ok) scope.launch { syncAll() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(auth = AuthProgress(service, error = e.message ?: "error")) }
            }
        }
    }

    fun cancelAuth() {
        authJob?.cancel()
        _state.update { it.copy(auth = null) }
    }

    /** Syncs every connected service, the primary one first. Returns the messages per service. */
    suspend fun syncAll(): Map<SyncService, String> {
        val tokens = settingsRepo.currentTokens()
        val connected = listOfNotNull(
            SyncService.TRAKT.takeIf { tokens.traktConnected },
            SyncService.SIMKL.takeIf { tokens.simklConnected },
        )
        if (connected.isEmpty()) return emptyMap()
        val primary = settingsRepo.current().syncPrimary
        val ordered = connected.sortedBy { if (it.key == primary) 0 else 1 }
        val results = LinkedHashMap<SyncService, String>()
        retryPending = false // set again by any service that fails below
        for (service in ordered) results[service] = sync(service)
        return results
    }

    /** Sync of a single service ("Sync" button). */
    suspend fun sync(service: SyncService): String = mutex.withLock {
        _state.update { it.copy(running = service) }
        val msg = try {
            pulling = true
            val report = when (service) { SyncService.TRAKT -> trakt.sync(); SyncService.SIMKL -> simkl.sync() }
            if (report.errors.isNotEmpty()) retryPending = true
            report.summary(service.label)
        } catch (e: kotlinx.coroutines.CancellationException) {
            _state.update { it.copy(running = null) }
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "${service.label} sync failed", e)
            retryPending = true
            "${service.label}: ${e.message}"
        } finally {
            pulling = false
        }
        settingsRepo.saveLastSyncAt(System.currentTimeMillis())
        _state.update { it.copy(running = null, lastResults = it.lastResults + (service to msg)) }
        msg
    }

    companion object {
        const val FOREGROUND_INTERVAL_MS = 15 * 60 * 1000L
        const val LOCAL_CHANGE_DELAY_MS = 60 * 1000L
        /** Short pause after the network comes back, so that DNS / captive portals settle before retrying. */
        const val NETWORK_SETTLE_MS = 5 * 1000L
    }
}
