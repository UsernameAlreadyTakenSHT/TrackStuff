package com.example.trackstuff.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.trackstuff.R
import com.example.trackstuff.data.repository.DiscoverSection
import com.example.trackstuff.data.repository.SectionMedia
import com.example.trackstuff.domain.DataSource
import com.example.trackstuff.domain.MediaSummary
import com.example.trackstuff.ui.components.PosterRow
import com.example.trackstuff.ui.components.RowItem

/** Offered sources, in order. */
private val SOURCES = listOf(DataSource.TMDB, DataSource.TVDB, DataSource.IMDB, DataSource.OMDB_ORG)

/**
 * Discover: pick the source (TMDB / TVDB / IMDb / omdb.org) and the type (Movies / Series); every list
 * of that source is then shown (trending, popular, new).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(vm: DiscoverViewModel, onOpenLocal: (Long) -> Unit, onOpenRemote: (MediaSummary) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val libraryIndex by vm.libraryIndex.collectAsStateWithLifecycle()
    // Not delegated: the map is read inside each card, so a resolved poster redraws only the cards showing it.
    val posters = vm.posters.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val waitText = state.refreshWaitMinutes?.let { stringResource(R.string.refresh_wait, it.toString()) }
    androidx.compose.runtime.LaunchedEffect(waitText) {
        if (waitText != null) { snackbar.showSnackbar(waitText); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discover_title)) },
                actions = { IconButton(onClick = vm::refresh, enabled = !state.loading) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.discover_refresh)) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Source on one line, type on the next (five chips do not fit on a single line).
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SOURCES.forEach { s ->
                    FilterChip(selected = state.source == s, onClick = { vm.setSource(s) }, label = { Text(s.label) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MediaFilter.entries.forEach { m ->
                    FilterChip(selected = state.media == m, onClick = { vm.setMedia(m) }, label = { Text(stringResource(m.labelRes)) })
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            val inLibraryText = stringResource(R.string.in_library)
            // Rows are built once per data change, not on every recomposition (hundreds of cards).
            val rows = remember(state.sections, state.source, state.media, libraryIndex) {
                state.sections.filter { it.source == state.source }.mapNotNull { it.applyFilter(state.media) }.map { section ->
                    DiscoverRow(
                        key = "${section.source.name}-${section.media.name}-${section.title}",
                        title = section.title,
                        items = section.items.mapIndexed { index, r ->
                            val localId = libraryIndex.localIdOf(r)
                            RowItem(
                                key = "${r.source}-${r.ids.tmdbId ?: r.ids.tvdbId ?: r.ids.imdbId ?: r.ids.omdbOrgId ?: r.title}-${r.isSeries}-$index",
                                title = r.title,
                                year = r.year,
                                posterUrl = r.posterUrl,
                                posterKey = if (r.posterUrl == null) r.ids.imdbId else null,
                                onVisible = if (r.posterUrl == null) ({ vm.ensurePoster(r) }) else null,
                                // No category badge in Discover: the type is already chosen by the toggle.
                                kind = null,
                                status = null,
                                subtitle = if (localId != null) inLibraryText else null,
                                onClick = { if (localId != null) onOpenLocal(localId) else onOpenRemote(r) },
                            )
                        },
                    )
                }
            }
            val problem = state.problems.firstOrNull { it.startsWith(state.source.label) }

            when {
                rows.isEmpty() && !state.loading -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        problem ?: stringResource(R.string.discover_nothing),
                        textAlign = TextAlign.Center,
                        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    rows.forEach { row ->
                        item(key = row.key) {
                            PosterRow(title = row.title, items = row.items, posterFor = { key -> posters.value[key] })
                        }
                    }
                }
            }
        }
    }
}

/** One Discover row, ready to draw. */
private data class DiscoverRow(val key: String, val title: String, val items: List<RowItem>)

/** Movies or Series: hides rows of the other type, filters mixed rows (trending). */
private fun DiscoverSection.applyFilter(filter: MediaFilter): DiscoverSection? = when (filter) {
    MediaFilter.MOVIES -> when (media) {
        SectionMedia.MOVIES -> this
        SectionMedia.SERIES -> null
        SectionMedia.MIXED -> copy(items = items.filter { !it.isSeries }).takeIf { it.items.isNotEmpty() }
    }
    MediaFilter.SERIES -> when (media) {
        SectionMedia.SERIES -> this
        SectionMedia.MOVIES -> null
        SectionMedia.MIXED -> copy(items = items.filter { it.isSeries }).takeIf { it.items.isNotEmpty() }
    }
}
