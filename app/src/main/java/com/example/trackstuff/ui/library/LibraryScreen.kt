package com.example.trackstuff.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.example.trackstuff.ui.components.PosterRow
import com.example.trackstuff.ui.components.RowItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: LibraryViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Row items (with their click lambdas) are rebuilt only when the library changes.
    val rows = remember(state) { LibraryRows(state.continueWatching.map { it.toRowItem(onOpen) }, state.startWatching.map { it.toRowItem(onOpen) }, state.history.map { it.toRowItem(onOpen) }) }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.library_title)) }) }) { padding ->
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
                )
            }
            item(key = "start") {
                PosterRow(
                    title = stringResource(R.string.library_start),
                    items = rows.startWatching,
                    emptyText = stringResource(if (state.total == 0) R.string.library_empty_start_none else R.string.library_empty_start),
                )
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

private class LibraryRows(val continueWatching: List<RowItem>, val startWatching: List<RowItem>, val history: List<RowItem>)

private fun LibraryItem.toRowItem(onOpen: (Long) -> Unit): RowItem {
    val d = details
    val t = tracking
    val progress = if (d.isSeries && t.currentSeason > 0 && t.status != WatchStatus.COMPLETED) "S${t.currentSeason}E${t.currentEpisode}" else null
    return RowItem(
        key = localId,
        title = d.title,
        year = d.year,
        posterUrl = d.posterUrl,
        kind = d.kind,
        status = t.status,
        subtitle = progress,
        onClick = { onOpen(localId) },
    )
}
