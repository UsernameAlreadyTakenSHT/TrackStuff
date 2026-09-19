package io.github.usernamealreadytakensht.trackstuff.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usernamealreadytakensht.trackstuff.R
import io.github.usernamealreadytakensht.trackstuff.data.omdborg.OmdbImportState
import io.github.usernamealreadytakensht.trackstuff.data.remote.Network
import io.github.usernamealreadytakensht.trackstuff.ui.components.openUrl
import java.text.DateFormat
import java.util.Date

/**
 * Settings, in sections: Preferences · Cache · Databases · Ratings · Sync.
 * Everything is saved automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val tokens by vm.tokens.collectAsStateWithLifecycle()
    val importState by vm.omdbImport.collectAsStateWithLifecycle()
    val imdbImportState by vm.imdbImport.collectAsStateWithLifecycle()
    val f = state.form
    var showCredits by remember { mutableStateOf(false) }
    if (showCredits) CreditsDialog(onDismiss = { showCredits = false })

    // Library backup: system file dialogs, the ViewModel does the reading / writing.
    val context = LocalContext.current
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportLibrary(context.contentResolver, it) } }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importLibrary(context.contentResolver, it) } }
    val exportSettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { vm.exportSettings(context.contentResolver, it) } }
    val importSettingsLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importSettings(context.contentResolver, it) } }
    val backupMessage = state.backupMessageRes?.let { res -> state.backupMessageArg?.let { stringResource(res, it) } ?: stringResource(res) }
    androidx.compose.runtime.LaunchedEffect(backupMessage) { if (backupMessage != null) { snackbar.showSnackbar(backupMessage); vm.consumeBackupMessage() } }

    Scaffold(snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) }, topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.settings_prefs), stringResource(R.string.settings_prefs_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.settings_language), f.language, { v -> vm.edit { it.copy(language = v) } }, Modifier.weight(1f), placeholder = io.github.usernamealreadytakensht.trackstuff.data.settings.defaultLanguage())
                    Field(stringResource(R.string.settings_region), f.region, { v -> vm.edit { it.copy(region = v) } }, Modifier.weight(1f), placeholder = io.github.usernamealreadytakensht.trackstuff.data.settings.defaultRegion())
                }
            }

            Section(stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_hint)) {
                Text(stringResource(R.string.backup_library), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("trackstuff-library.json") }) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.backup_import)) }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.backup_settings), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.backup_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportSettingsLauncher.launch("trackstuff-settings.json") }) { Text(stringResource(R.string.backup_export)) }
                    OutlinedButton(onClick = { importSettingsLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text(stringResource(R.string.backup_import)) }
                }
            }

            Section(stringResource(R.string.settings_cache), stringResource(R.string.settings_cache_hint)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Network.CACHE_TIERS_MB.forEach { mb ->
                        FilterChip(selected = f.httpCacheMb == mb, onClick = { vm.edit { it.copy(httpCacheMb = mb) } }, label = { Text(if (mb <= 0) stringResource(R.string.settings_cache_unlimited) else Network.labelFor(mb)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.settings_cache_used, formatBytes(state.cacheUsedBytes)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearCache) { Text(stringResource(R.string.settings_cache_clear)) }
                }
                // Every page of every online Discover row, for offline use.
                val prefetch by vm.prefetch.collectAsStateWithLifecycle()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        prefetch?.let { (done, total) -> stringResource(R.string.discover_caching, done, total) } ?: stringResource(R.string.settings_cache_discover_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    if (prefetch == null) TextButton(onClick = vm::cacheDiscoverPages) { Text(stringResource(R.string.settings_cache_discover)) }
                    else TextButton(onClick = vm::cancelCacheDiscoverPages) { Text(stringResource(R.string.cancel)) }
                }
            }

            Section(stringResource(R.string.settings_databases), stringResource(R.string.settings_databases_hint)) {
                SubTitle("TMDB", "https://www.themoviedb.org/settings/api")
                Field(stringResource(R.string.settings_tmdb_key), f.tmdbApiKey, { v -> vm.edit { it.copy(tmdbApiKey = v) } }, secret = true)

                SubTitle("TVDB", "https://thetvdb.com/dashboard/account/apikey")
                Field(stringResource(R.string.settings_tvdb_key), f.tvdbApiKey, { v -> vm.edit { it.copy(tvdbApiKey = v) } }, secret = true)
                Field(stringResource(R.string.settings_tvdb_pin), f.tvdbPin, { v -> vm.edit { it.copy(tvdbPin = v) } })

                SubTitle("omdb.org", "https://www.omdb.org")
                state.omdbOrgInfo.let { i -> LocalDatasetBlock(stringResource(R.string.settings_omdborg_hint) + (if (i.incomplete) "\n" + stringResource(R.string.settings_import_interrupted) else ""), i.titleCount, i.lastImportAt, i.nextAllowedAt, i.canDownload, importState, onImport = vm::importOmdbOrg, onCancel = vm::cancelOmdbOrgImport) }

                SubTitle("IMDb", "https://datasets.imdbws.com/")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !f.imdbFullDatasets, onClick = { vm.edit { it.copy(imdbFullDatasets = false) } }, label = { Text(stringResource(R.string.settings_imdb_standard)) })
                    FilterChip(selected = f.imdbFullDatasets, onClick = { vm.edit { it.copy(imdbFullDatasets = true) } }, label = { Text(stringResource(R.string.settings_imdb_full)) })
                }
                state.imdbInfo.let { i -> LocalDatasetBlock(stringResource(if (f.imdbFullDatasets) R.string.settings_imdb_hint_full else R.string.settings_imdb_hint) + (if (i.formatOutdated) "\n" + stringResource(R.string.settings_imdb_outdated) else "") + (if (i.incomplete) "\n" + stringResource(R.string.settings_import_interrupted) else ""), i.titleCount, i.lastImportAt, i.nextAllowedAt, i.canDownload, imdbImportState, onImport = vm::importImdb, onCancel = vm::cancelImdbImport) }
            }

            Section(stringResource(R.string.settings_ratings), stringResource(R.string.settings_ratings_hint)) {
                SubTitle("OMDb API", "https://www.omdbapi.com/apikey.aspx")
                Field(stringResource(R.string.settings_api_key), f.omdbApiKey, { v -> vm.edit { it.copy(omdbApiKey = v) } }, secret = true)
            }

            Section(stringResource(R.string.settings_sync), stringResource(R.string.settings_sync_hint)) {
                SubTitle("Trakt", "https://trakt.tv/oauth/applications")
                Field(stringResource(R.string.settings_client_id), f.traktClientId, { v -> vm.edit { it.copy(traktClientId = v) } })
                Field(stringResource(R.string.settings_client_secret), f.traktClientSecret, { v -> vm.edit { it.copy(traktClientSecret = v) } }, secret = true)
                ServiceRow(
                    connected = tokens.traktConnected, service = state.trakt, enabled = f.hasTrakt,
                    onConnect = vm::connectTrakt, onDisconnect = vm::disconnectTrakt, onSync = vm::syncTrakt, onCancel = vm::cancelAuth,
                )
                Text(stringResource(R.string.settings_trakt_redirect), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(4.dp))
                SubTitle("Simkl", "https://simkl.com/settings/developer/")
                Field(stringResource(R.string.settings_client_id), f.simklClientId, { v -> vm.edit { it.copy(simklClientId = v) } })
                Field(stringResource(R.string.settings_client_secret), f.simklClientSecret, { v -> vm.edit { it.copy(simklClientSecret = v) } }, secret = true)
                ServiceRow(
                    connected = tokens.simklConnected, service = state.simkl, enabled = f.hasSimkl,
                    onConnect = vm::connectSimkl, onDisconnect = vm::disconnectSimkl, onSync = vm::syncSimkl, onCancel = vm::cancelAuth,
                )

                // Automatic sync: service trusted on conflicts, and last sync time.
                if (f.hasTrakt && f.hasSimkl) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.settings_sync_primary), style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = f.syncPrimary == "trakt", onClick = { vm.edit { it.copy(syncPrimary = "trakt") } }, label = { Text("Trakt") })
                        FilterChip(selected = f.syncPrimary == "simkl", onClick = { vm.edit { it.copy(syncPrimary = "simkl") } }, label = { Text("Simkl") })
                    }
                }
                Text(
                    if (tokens.lastSyncAt > 0) stringResource(R.string.settings_sync_last, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(tokens.lastSyncAt))) else stringResource(R.string.settings_sync_auto),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                stringResource(if (state.saved) R.string.settings_saved else R.string.settings_autosave),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Credits and licenses: a link, the details in a dialog.
            TextButton(onClick = { showCredits = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text(stringResource(R.string.settings_credits)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---------------------------------------------------------------- Building blocks

/** Settings block: title, one-line explanation, content on a tonal background. */
@Composable
private fun Section(title: String, hint: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

/** Service name with a link to the page where a key can be obtained. */
@Composable
private fun SubTitle(name: String, url: String) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = { openUrl(context, url) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.OpenInNew, contentDescription = stringResource(R.string.settings_open, name), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    secret: Boolean = false,
    placeholder: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = if (placeholder == null) null else {
            { Text(placeholder) }
        },
        singleLine = true,
        visualTransformation = if (secret && value.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
        // Password type: no autocorrect, and the keyboard does not learn keys and secrets.
        keyboardOptions = if (secret) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password) else androidx.compose.foundation.text.KeyboardOptions.Default,
        modifier = modifier,
    )
}

/** Imported local database (omdb.org, IMDb datasets): state, monthly limit, progress. */
@Composable
private fun LocalDatasetBlock(hint: String, titleCount: Int, lastImportAt: Long?, nextAllowedAt: Long?, canDownload: Boolean, state: OmdbImportState, onImport: () -> Unit, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state) {
            is OmdbImportState.Running -> {
                // Current step with its own percentage and ETA; the bar and the line below are the whole import.
                val stepDetail = listOfNotNull(state.progress?.let { "${(it * 100).toInt()} %" }, state.etaSeconds?.let { formatEta(it) }).joinToString(" · ")
                Text(if (stepDetail.isEmpty()) state.step else "${state.step} — $stepDetail", style = MaterialTheme.typography.bodySmall)
                if (state.overall != null) LinearProgressIndicator(progress = { state.overall }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                val overall = listOfNotNull(
                    state.overall?.let { "${(it * 100).toInt()} %" },
                    state.overallEtaSeconds?.let { stringResource(R.string.import_eta, formatEta(it)) },
                ).joinToString(" · ")
                if (overall.isNotEmpty()) Text(stringResource(R.string.import_overall, overall), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(if (titleCount > 0) pluralStringResource(R.plurals.settings_omdborg_titles, titleCount, "%,d".format(java.util.Locale.getDefault(), titleCount)) else stringResource(R.string.settings_omdborg_not_imported), style = MaterialTheme.typography.bodyMedium)
                        lastImportAt?.let { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Button(onClick = onImport, enabled = canDownload) { Text(stringResource(if (titleCount > 0) R.string.settings_omdborg_update else R.string.settings_omdborg_download)) }
                }
                if (!canDownload && nextAllowedAt != null) {
                    Text(
                        stringResource(R.string.settings_omdborg_limit, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(nextAllowedAt))),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state is OmdbImportState.Error) Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state is OmdbImportState.Done) Text(if (state.aliases > 0) stringResource(R.string.settings_omdborg_done, state.titles, state.aliases) else stringResource(R.string.settings_imdb_done, state.titles), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ServiceRow(
    connected: Boolean,
    service: ServiceState,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSync: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val code = service.code
        when {
            service.connecting && code != null -> {
                Text(stringResource(R.string.service_code_intro, code.verificationUrl), style = MaterialTheme.typography.bodyMedium)
                Text(code.userCode, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.CenterHorizontally))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { openUrl(context, code.verificationUrl) }) { Text(stringResource(R.string.service_open_site)) }
                    OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            service.connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.service_requesting))
            }
            connected -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.service_connected), color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                TextButton(onClick = onDisconnect) { Text(stringResource(R.string.service_disconnect)) }
                Button(onClick = onSync, enabled = !service.syncing) {
                    if (service.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.service_sync))
                }
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.service_not_connected), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Button(onClick = onConnect, enabled = enabled) { Text(stringResource(R.string.service_connect)) }
            }
        }
        (service.lastResultRes?.let { stringResource(it) } ?: service.lastResult)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** "45 s", "3 min", "1 h 05" — coarse on purpose, the estimate is rough. */
private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds.coerceAtLeast(1)} s"
    seconds < 3600 -> "${seconds / 60} min"
    else -> "${seconds / 3600} h ${"%02d".format((seconds % 3600) / 60)}"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

// ---------------------------------------------------------------- Credits and licenses

/** One data service: what it provides to the app, the attribution wording it requires, its site. */
private data class ServiceCredit(val name: String, val role: String, val note: String, val url: String)

/** One open-source library (all Apache License 2.0). */
private data class LibraryCredit(val name: String, val author: String, val url: String)

/** Data services, with the attribution wording each of them requires. */
private val SERVICES = listOf(
    ServiceCredit("TMDB", "Posters · metadata · charts", "This product uses the TMDB API but is not endorsed or certified by TMDB.", "https://www.themoviedb.org/"),
    ServiceCredit("TheTVDB", "Posters · metadata · charts", "Metadata provided by TheTVDB. Please consider adding missing information or subscribing.", "https://thetvdb.com/"),
    ServiceCredit("OMDb API", "Ratings · fallback posters", "The Open Movie Database — CC BY-NC 4.0.", "https://www.omdbapi.com/"),
    ServiceCredit("IMDb datasets", "Offline ratings · Top 250 · credits", "Information courtesy of IMDb (https://www.imdb.com). Used with permission. Non-commercial use.", "https://developer.imdb.com/non-commercial-datasets/"),
    ServiceCredit("omdb.org", "Offline posters · synopses · search", "Open Media Database — community data under free licenses; images under their own licenses.", "https://www.omdb.org/"),
    ServiceCredit("Rotten Tomatoes · Metacritic", "Scores", "Scores via OMDb API; the chips open the official sites.", "https://www.rottentomatoes.com/"),
    ServiceCredit("Trakt", "Sync", "Sync through the Trakt API, under Trakt's API terms.", "https://trakt.tv/"),
    ServiceCredit("Simkl", "Sync", "Sync through the Simkl API, under Simkl's API terms.", "https://simkl.com/"),
)

private val LIBRARIES = listOf(
    LibraryCredit("Kotlin & kotlinx", "JetBrains", "https://github.com/JetBrains/kotlin"),
    LibraryCredit("AndroidX & Jetpack Compose", "Android Open Source Project", "https://developer.android.com/jetpack"),
    LibraryCredit("Material 3", "Google", "https://m3.material.io/"),
    LibraryCredit("Room · DataStore · Navigation 3", "Android Open Source Project", "https://developer.android.com/jetpack"),
    LibraryCredit("OkHttp", "Square", "https://github.com/square/okhttp"),
    LibraryCredit("Retrofit", "Square", "https://github.com/square/retrofit"),
    LibraryCredit("Moshi", "Square", "https://github.com/square/moshi"),
    LibraryCredit("Coil", "Coil Contributors", "https://github.com/coil-kt/coil"),
    LibraryCredit("Apache Commons Compress", "Apache Software Foundation", "https://commons.apache.org/proper/commons-compress/"),
)

/** A service as a small tonal card: name + role tag, attribution line, link. */
@Composable
private fun ServiceCard(c: ServiceCredit) {
    val context = LocalContext.current
    androidx.compose.material3.Surface(
        onClick = { openUrl(context, c.url) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            }
            androidx.compose.material3.Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(c.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A library as a clickable chip; the license is common to all of them and stated once above. */
@Composable
private fun LibraryChip(l: LibraryCredit) {
    val context = LocalContext.current
    androidx.compose.material3.AssistChip(
        onClick = { openUrl(context, l.url) },
        label = { Text(l.name) },
        trailingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp)) },
    )
}

/** "Credits & licenses" dialog: data services as cards, open-source libraries as chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.settings_credits)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.settings_credits_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.settings_credits_services), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                SERVICES.forEach { ServiceCard(it) }
                Text(stringResource(R.string.settings_credits_libraries), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(R.string.settings_credits_libraries_license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LIBRARIES.forEach { LibraryChip(it) }
                }
                androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(stringResource(R.string.settings_credits_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
