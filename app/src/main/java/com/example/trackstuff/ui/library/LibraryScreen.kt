package com.example.trackstuff.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trackstuff.R
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.WatchStatus
import com.example.trackstuff.domain.nextEpisode
import com.example.trackstuff.ui.components.PosterRow
import com.example.trackstuff.ui.components.RowItem
import com.example.trackstuff.ui.components.formatDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (Long) -> Unit, onOpenSettings: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val failure by vm.syncFailure.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failureText = failure?.let { stringResource(R.string.sync_failed, it.service.label, it.message) }
    LaunchedEffect(failure) {
        val f = failure ?: return@LaunchedEffect
        snackbar.showSnackbar(failureText ?: return@LaunchedEffect)
        vm.consumeSyncFailure(f)
    }
    val nextLabel = stringResource(R.string.library_next)
    // Row items (with their click lambdas) are rebuilt only when the library changes.
    val rows = remember(state) {
        LibraryRows(
            continueWatching = state.continueWatching.map { it.toRowItem(onOpen, subtitle = it.nextSubtitle(nextLabel), action = { vm.advance(it.localId) }) },
            upcoming = state.upcoming.map { it.toRowItem(onOpen, subtitle = formatDate(it.details.nextAired)) },
            startWatching = state.startWatching.map { it.toRowItem(onOpen) },
            history = state.history.map { it.toRowItem(onOpen) },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "continue") {
                PosterRow(
                    title = stringResource(R.string.library_continue),
                    items = rows.continueWatching,
                    emptyText = stringResource(R.string.library_empty_continue),
                    actionLabel = "+1",
                )
            }
            if (rows.upcoming.isNotEmpty()) item(key = "upcoming") {
                PosterRow(title = stringResource(R.string.library_upcoming), items = rows.upcoming)
            }
            item(key = "start") {
                Column {
                    PosterRow(
                        title = stringResource(R.string.library_start),
                        items = rows.startWatching,
                        emptyText = stringResource(if (state.total == 0) R.string.library_empty_start_none else R.string.library_empty_start),
                    )
                    // First launch: nothing works without at least one database key.
                    if (state.total == 0 && !state.configured) {
                        Text(
                            stringResource(R.string.setup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        TextButton(onClick = onOpenSettings, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.setup_open_settings)) }
                    }
                }
            }
            item(key = "history") {
                PosterRow(
                    title = stringResource(R.string.library_history),
                    items = rows.history,
                    emptyText = stringResource(R.string.library_empty_history),
                )
            }
            item(key = "footer") {
                Text(
                    pluralStringResource(R.plurals.library_count, state.total, state.total),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private class LibraryRows(val continueWatching: List<RowItem>, val upcoming: List<RowItem>, val startWatching: List<RowItem>, val history: List<RowItem>)

/** "Next: S2E4" for a series in progress; the last watched position when the next one is not known. */
private fun LibraryItem.nextSubtitle(nextLabel: String): String? {
    if (!details.isSeries) return null
    val next = nextEpisode(tracking, details.seasonEpisodes)
    return if (next != null) String.format(nextLabel, next.label) else "S${tracking.currentSeason}E${tracking.currentEpisode}"
}

private fun LibraryItem.toRowItem(onOpen: (Long) -> Unit, subtitle: String? = null, action: (() -> Unit)? = null): RowItem {
    val d = details
    val t = tracking
    return RowItem(
        key = localId,
        title = d.title,
        year = d.year,
        posterUrl = d.posterUrl,
        kind = d.kind,
        status = t.status,
        subtitle = subtitle,
        onClick = { onOpen(localId) },
        action = action,
    )
}
