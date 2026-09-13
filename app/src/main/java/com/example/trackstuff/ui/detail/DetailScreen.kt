package com.example.trackstuff.ui.detail

import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.trackstuff.domain.nextEpisode
import com.example.trackstuff.domain.WatchProviders
import com.example.trackstuff.domain.WatchProvider
import com.example.trackstuff.domain.UserTracking
import com.example.trackstuff.domain.Episode
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.trackstuff.R
import com.example.trackstuff.domain.LibraryItem
import com.example.trackstuff.domain.MediaDetails
import com.example.trackstuff.domain.MediaKind
import com.example.trackstuff.domain.WatchStatus
import com.example.trackstuff.ui.components.KindBadge
import com.example.trackstuff.ui.components.PosterImage
import com.example.trackstuff.ui.components.formatScore
import com.example.trackstuff.ui.components.formatDate
import com.example.trackstuff.ui.components.openUrl
import com.example.trackstuff.ui.components.statusColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(vm: DetailViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmRemove by remember { mutableStateOf(false) }

    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let { m ->
            val arg = m.argRes?.let { context.getString(it) } ?: m.arg
            snackbar.showSnackbar(if (arg != null) context.getString(m.res, arg) else context.getString(m.res))
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.details?.title ?: "", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) } },
                actions = {
                    if (state.inLibrary) {
                        IconButton(onClick = vm::refresh, enabled = !state.refreshing) { Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.detail_refresh)) }
                        IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.detail_remove)) }
                    }
                },
            )
        },
    ) { padding ->
        val d = state.details
        when {
            state.loading && d == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            d == null -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: stringResource(R.string.detail_not_found), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
            }
            else -> DetailContent(d, state.item, state.refreshing, state.providers, state.episodes, vm, Modifier.padding(padding))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.detail_remove_title)) },
            text = { Text(stringResource(R.string.detail_remove_text)) },
            confirmButton = { TextButton(onClick = { confirmRemove = false; vm.remove(); onBack() }) { Text(stringResource(R.string.detail_remove)) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun DetailContent(d: MediaDetails, item: LibraryItem?, refreshing: Boolean, providers: WatchProviders?, episodes: List<Episode>, vm: DetailViewModel, modifier: Modifier) {
    val context = LocalContext.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- Header: background + poster + title
        if (d.backdropUrl != null) {
            Box(Modifier.fillMaxWidth().height(200.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                AsyncImage(model = d.backdropUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        // The text column is as tall as the poster (2:3), its lines spread from the title at the top to the
        // ratings at the bottom.
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            PosterImage(d.posterUrl, d.title, Modifier.width(POSTER_WIDTH))
            Column(Modifier.padding(start = 12.dp).height(POSTER_WIDTH * 3 / 2), verticalArrangement = Arrangement.SpaceBetween) {
                Text(d.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (d.originalTitle != null && d.originalTitle != d.title) {
                    Text(d.originalTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val meta = listOfNotNull(
                    formatDate(d.releaseDate) ?: d.year?.toString(),
                    // Movie runtime only: episode length and the age rating go to the Info section.
                    d.runtimeMinutes?.takeIf { !d.isSeries }?.let { "${it / 60}h${(it % 60).toString().padStart(2, '0')}" },
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.nextAired?.let { next -> formatDate(next)?.let { Text(stringResource(R.string.detail_next_episode, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KindBadge(d.kind)
                    d.status?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically)) }
                }
                // Genres on their own line, under the type and status.
                if (d.genres.isNotEmpty()) Text(d.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Ratings via OMDb: IMDb (audience), Tomatometer and Metascore (critics); the chips open the sites.
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val r = d.ratings
                    MiniRating("IMDb", formatScore(r.imdb), d.imdbUrl, Color(0xFFF5C518).let { if (isDark()) it else Color(0xFFB8860B) })
                    MiniRating("RT", r.rottenTomatoes?.let { "$it %" }, d.rottenTomatoesUrl, Color(0xFFFA320A))
                    MiniRating("MC", r.metacritic?.toString(), d.metacriticUrl, Color(0xFF66CC33).let { if (isDark()) it else Color(0xFF2E7D32) })
                }
            }
        }
        if (refreshing) Text(stringResource(R.string.detail_updating), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp))

        // ---- Add / tracking
        if (item == null) AddSection(vm) else TrackingSection(item, vm, episodes)

        // ---- Episodes of a title not in the library yet (read-only, folded by default)
        if (item == null && d.isSeries && episodes.isNotEmpty()) {
            var open by rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(12.dp))
            ExpandHeader(stringResource(R.string.detail_episodes_title), open) { open = !open }
            if (open) EpisodesSection(episodes, null, onPick = null)
        }

        // ---- Where to watch (TMDB / JustWatch, user's region): one line
        if (providers != null && !providers.isEmpty) {
            Spacer(Modifier.height(8.dp))
            ProvidersSection(providers)
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ---- Description
        SectionTitle(stringResource(R.string.detail_synopsis))
        Text(
            d.overview ?: stringResource(R.string.detail_no_overview),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        // ---- Info: country, seasons and episodes (kept out of the header to leave it airy)
        val info = listOfNotNull(
            d.countries.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.info_country) to it.joinToString(", ") },
            d.numberOfSeasons?.let { stringResource(R.string.info_seasons) to it.toString() },
            d.numberOfEpisodes?.let { stringResource(R.string.info_episodes) to it.toString() },
            d.runtimeMinutes?.takeIf { d.isSeries }?.let { stringResource(R.string.info_episode_length) to stringResource(R.string.detail_min_per_episode, it) },
            d.certification?.let { stringResource(R.string.info_rating) to it },
        )
        if (info.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.detail_info))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                info.forEach { (label, value) -> CreditLine(label, value) }
            }
        }

        // ---- Credits
        val c = d.credits
        if (!c.isEmpty || d.studios.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionTitle(stringResource(R.string.detail_credits))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (c.directors.isNotEmpty()) CreditLine(stringResource(if (d.isSeries) R.string.credit_creator else R.string.credit_director), c.directors.joinToString(", "))
                if (c.writers.isNotEmpty()) CreditLine(stringResource(R.string.credit_writer), c.writers.joinToString(", "))
                if (c.producers.isNotEmpty()) CreditLine(stringResource(R.string.credit_producer), c.producers.joinToString(", "))
                if (d.studios.isNotEmpty()) CreditLine(stringResource(if (d.isSeries) R.string.credit_network else R.string.credit_studio), d.studios.take(3).joinToString(", "))
                if (c.cast.isNotEmpty()) CreditLine(stringResource(R.string.credit_cast), c.cast.joinToString(", ") { m -> m.character?.let { "${m.name} ($it)" } ?: m.name })
            }
        }
        val sources = listOfNotNull(
            d.posterSource?.let { stringResource(R.string.source_poster, it.label) },
            d.overviewSource?.let { stringResource(R.string.source_overview, it.label) },
            if (d.ratings.imdb != null || d.ratings.rottenTomatoes != null || d.ratings.metacritic != null) stringResource(R.string.source_ratings) else null,
        )
        if (sources.isNotEmpty()) {
            Text(stringResource(R.string.detail_sources, sources.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }

        // ---- Links
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            d.imdbUrl?.let { LinkChip("IMDb", it) }
            d.tmdbUrl?.let { LinkChip("TMDB", it) }
            d.ids.tvdbId?.let { LinkChip("TVDB", "https://thetvdb.com/dereferrer/${if (d.isSeries) "series" else "movie"}/$it") }
            d.omdbOrgUrl?.let { LinkChip("omdb.org", it) }
        }
        Spacer(Modifier.height(32.dp))
    }
}


@Composable
private fun isDark() = androidx.compose.foundation.isSystemInDarkTheme()

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
}

@Composable
private fun LinkChip(label: String, url: String) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.clickable { openUrl(context, url) }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun AddSection(vm: DetailViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.add(WatchStatus.PLANNED) }, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.detail_add_planned))
        }
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(stringResource(R.string.detail_add_other)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // Adding straight as "completed" (a movie already seen, a show already finished); watching comes from progress.
                DropdownMenuItem(text = { Text(stringResource(WatchStatus.COMPLETED.labelRes)) }, onClick = { menu = false; vm.add(WatchStatus.COMPLETED) })
            }
        }
    }
}

@Composable
private fun TrackingSection(item: LibraryItem, vm: DetailViewModel, episodes: List<Episode>) {
    val t = item.tracking
    val d = item.details

    // No section title: the status chips speak for themselves (compact block).
    // Status: planned / watching / completed. Watching is shown for series only and follows the progress
    // (first episode marked), it cannot be picked by hand.
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WatchStatus.entries.filter { d.isSeries || it != WatchStatus.WATCHING }.forEach { s ->
            FilterChip(
                selected = t.status == s,
                // Watching is informational: tapping it does nothing (setStatus keeps the derived value).
                onClick = { vm.setStatus(s) },
                label = { Text(stringResource(s.labelRes)) },
                leadingIcon = { Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(statusColor(s))) },
            )
        }
    }

    // Progress (series / anime): position, "+1", and the episode list folded behind the row. The S / E
    // counters only remain when no episode list could be fetched.
    var open by rememberSaveable { mutableStateOf(false) }
    if (d.isSeries) {
        Row(
            Modifier.fillMaxWidth().then(if (episodes.isNotEmpty()) Modifier.clickable { open = !open } else Modifier).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.detail_progress), style = MaterialTheme.typography.labelLarge)
            if (episodes.isNotEmpty()) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (episodes.isNotEmpty()) {
                val max = d.seasonEpisodes.getOrNull((if (t.currentSeason == 0) 1 else t.currentSeason) - 1)
                Text("S${t.currentSeason}E${t.currentEpisode}" + (max?.let { "/$it" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            } else {
                Counter("S", t.currentSeason, onChange = { vm.setProgress(it, if (it != t.currentSeason) 0 else t.currentEpisode) })
                Counter("E", t.currentEpisode, onChange = { vm.setProgress(if (t.currentSeason == 0 && it > 0) 1 else t.currentSeason, it) }, max = d.seasonEpisodes.getOrNull((if (t.currentSeason == 0) 1 else t.currentSeason) - 1))
            }
            FilledTonalButton(onClick = vm::nextEpisode, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("+1")
            }
        }
    }

    // Next episode to watch, with its title and air date when the episode list is known.
    if (d.isSeries && t.status != WatchStatus.COMPLETED) {
        val next = nextEpisode(t, d.seasonEpisodes)
        if (next != null) {
            val ep = episodes.firstOrNull { it.season == next.season && it.number == next.episode }
            val text = listOfNotNull(next.label, ep?.title, ep?.airDate?.let { formatDate(it) }).joinToString(" · ")
            Text(stringResource(R.string.library_next, text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    if (d.isSeries && open && episodes.isNotEmpty()) EpisodesSection(episodes, t, onPick = { s, e -> vm.setPosition(s, e) })

    Spacer(Modifier.height(8.dp))

    val sync = listOfNotNull(t.lastSyncedTrakt?.let { "Trakt" }, t.lastSyncedSimkl?.let { "Simkl" })
    if (sync.isNotEmpty()) {
        Text(stringResource(R.string.detail_synced, sync.joinToString(", ")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun Counter(prefix: String, value: Int, onChange: (Int) -> Unit, max: Int? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(value - 1) }, enabled = value > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Remove, contentDescription = "-") }
        // "E3/13" when the number of episodes in the season is known.
        Text("$prefix$value" + (max?.let { "/$it" } ?: ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = { onChange(value + 1) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, contentDescription = "+") }
    }
}

/** Credits line such as "Director: Someone, Someone". */
@Composable
private fun CreditLine(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}


/** Streaming, free, rental and purchase services in the user's region; the row opens the JustWatch page. */
@Composable
private fun ProvidersSection(p: WatchProviders) {
    val context = LocalContext.current
    // "Where to watch (FR)  Stream [N] [P]  Rent [A] [G]" on one scrolling line; everything opens JustWatch.
    Row(
        Modifier.fillMaxWidth().clickable(enabled = p.link != null) { p.link?.let { openUrl(context, it) } }.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Default.Tv, contentDescription = stringResource(R.string.detail_watch, p.region), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(p.region, style = MaterialTheme.typography.labelLarge)
        ProviderGroup(stringResource(R.string.watch_stream), p.stream)
        ProviderGroup(stringResource(R.string.watch_free), p.free)
        ProviderGroup(stringResource(R.string.watch_rent), p.rent)
        ProviderGroup(stringResource(R.string.watch_buy), p.buy)
    }
}

@Composable
private fun ProviderGroup(label: String, providers: List<WatchProvider>) {
    if (providers.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 2.dp))
        providers.forEach { pr ->
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                if (pr.logoUrl != null) AsyncImage(model = pr.logoUrl, contentDescription = pr.name, modifier = Modifier.fillMaxSize())
                else Text(pr.name.take(2), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Season chips and the episodes of the selected season. Episodes up to the current position are ticked;
 * tapping one (library titles) marks the series watched up to it.
 */
@Composable
private fun EpisodesSection(episodes: List<Episode>, tracking: UserTracking?, onPick: ((Int, Int) -> Unit)?) {
    val seasons = remember(episodes) { episodes.map { it.season }.distinct().sorted() }
    val current = tracking?.currentSeason?.takeIf { it > 0 } ?: seasons.first()
    var selected by rememberSaveable(current) { mutableStateOf(current) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        seasons.forEach { s -> FilterChip(selected = selected == s, onClick = { selected = s }, label = { Text("S$s") }) }
    }
    val today = remember { java.time.LocalDate.now().toString() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        episodes.filter { it.season == selected }.forEach { ep ->
            val watched = tracking != null && (ep.season < tracking.currentSeason || (ep.season == tracking.currentSeason && ep.number <= tracking.currentEpisode))
            val future = ep.airDate != null && ep.airDate > today
            Row(
                Modifier.fillMaxWidth().then(if (onPick != null) Modifier.clickable { onPick(ep.season, ep.number) } else Modifier).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (watched) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
                Text("E${ep.number}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 10.dp).width(40.dp))
                Text(
                    ep.title ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (future) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ep.airDate?.let { formatDate(it) }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp)) }
            }
        }
    }
}

/** Section title that folds / unfolds the content below it. */
@Composable
private fun ExpandHeader(text: String, open: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small rating chip for the header column: value in colour, source underneath. */
@Composable
private fun MiniRating(label: String, value: String?, url: String?, color: Color) {
    val context = LocalContext.current
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.15f), modifier = if (url != null) Modifier.clickable { openUrl(context, url) } else Modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Poster width on the page; the header column takes the matching 2:3 height. */
private val POSTER_WIDTH = 132.dp
