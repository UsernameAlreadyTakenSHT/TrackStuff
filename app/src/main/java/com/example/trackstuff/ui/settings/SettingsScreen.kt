package com.example.trackstuff.ui.settings

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
import com.example.trackstuff.R
import com.example.trackstuff.data.omdborg.OmdbImportState
import com.example.trackstuff.data.remote.Network
import com.example.trackstuff.ui.components.openUrl
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

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Section(stringResource(R.string.settings_prefs), stringResource(R.string.settings_prefs_hint)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(stringResource(R.string.settings_language), f.language, { v -> vm.edit { it.copy(language = v) } }, Modifier.weight(1f), placeholder = com.example.trackstuff.data.settings.defaultLanguage())
                    Field(stringResource(R.string.settings_region), f.region, { v -> vm.edit { it.copy(region = v) } }, Modifier.weight(1f), placeholder = com.example.trackstuff.data.settings.defaultRegion())
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
            }

            Section(stringResource(R.string.settings_databases), stringResource(R.string.settings_databases_hint)) {
                SubTitle("TMDB", "https://www.themoviedb.org/settings/api")
                Field(stringResource(R.string.settings_tmdb_key), f.tmdbApiKey, { v -> vm.edit { it.copy(tmdbApiKey = v) } }, secret = true)

                SubTitle("TVDB", "https://thetvdb.com/dashboard/account/apikey")
                Field(stringResource(R.string.settings_tvdb_key), f.tvdbApiKey, { v -> vm.edit { it.copy(tvdbApiKey = v) } }, secret = true)
                Field(stringResource(R.string.settings_tvdb_pin), f.tvdbPin, { v -> vm.edit { it.copy(tvdbPin = v) } })

                SubTitle("omdb.org", "https://www.omdb.org")
                state.omdbOrgInfo.let { i -> LocalDatasetBlock(stringResource(R.string.settings_omdborg_hint), i.titleCount, i.lastImportAt, i.nextAllowedAt, i.canDownload, importState, onImport = vm::importOmdbOrg, onCancel = vm::cancelOmdbOrgImport) }

                SubTitle("IMDb", "https://datasets.imdbws.com/")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !f.imdbFullDatasets, onClick = { vm.edit { it.copy(imdbFullDatasets = false) } }, label = { Text(stringResource(R.string.settings_imdb_standard)) })
                    FilterChip(selected = f.imdbFullDatasets, onClick = { vm.edit { it.copy(imdbFullDatasets = true) } }, label = { Text(stringResource(R.string.settings_imdb_full)) })
                }
                state.imdbInfo.let { i -> LocalDatasetBlock(stringResource(if (f.imdbFullDatasets) R.string.settings_imdb_hint_full else R.string.settings_imdb_hint), i.titleCount, i.lastImportAt, i.nextAllowedAt, i.canDownload, imdbImportState, onImport = vm::importImdb, onCancel = vm::cancelImdbImport) }
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
                Text(state.step, style = MaterialTheme.typography.bodySmall)
                if (state.progress != null) LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
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

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
    else -> "${bytes / 1024} KB"
}

// ---------------------------------------------------------------- Credits and licenses

/** One credit row: clickable name, license or attribution mention. */
private data class Credit(val name: String, val note: String, val url: String)

/** Data services, with the attribution wording each of them requires. */
private val SERVICES = listOf(
    Credit("TMDB", "This product uses the TMDB API but is not endorsed or certified by TMDB.", "https://www.themoviedb.org/"),
    Credit("TheTVDB", "Metadata provided by TheTVDB. Please consider adding missing information or subscribing.", "https://thetvdb.com/"),
    Credit("OMDb API", "The Open Movie Database — CC BY-NC 4.0.", "https://www.omdbapi.com/"),
    Credit("omdb.org", "Open Media Database — community data under free licenses (see site); images under their own licenses.", "https://www.omdb.org/"),
    Credit("IMDb datasets", "Information courtesy of IMDb (https://www.imdb.com). Used with permission. Non-commercial use.", "https://developer.imdb.com/non-commercial-datasets/"),
    Credit("Rotten Tomatoes · Metacritic", "Scores via OMDb API; links open the official sites.", "https://www.rottentomatoes.com/"),
    Credit("Trakt", "Sync via the Trakt API, under Trakt's API terms.", "https://trakt.tv/"),
    Credit("Simkl", "Sync via the Simkl API, under Simkl's API terms.", "https://simkl.com/"),
)

/** Bundled open-source libraries. */
private val LIBRARIES = listOf(
    Credit("Kotlin & kotlinx (coroutines, serialization)", "Apache License 2.0 — JetBrains", "https://github.com/JetBrains/kotlin"),
    Credit("AndroidX & Jetpack Compose (Material 3, Room, DataStore, Navigation 3, Lifecycle)", "Apache License 2.0 — The Android Open Source Project", "https://developer.android.com/jetpack"),
    Credit("OkHttp", "Apache License 2.0 — Square, Inc.", "https://github.com/square/okhttp"),
    Credit("Retrofit", "Apache License 2.0 — Square, Inc.", "https://github.com/square/retrofit"),
    Credit("Moshi", "Apache License 2.0 — Square, Inc.", "https://github.com/square/moshi"),
    Credit("Coil", "Apache License 2.0 — Coil Contributors", "https://github.com/coil-kt/coil"),
    Credit("Apache Commons Compress", "Apache License 2.0 — The Apache Software Foundation", "https://commons.apache.org/proper/commons-compress/"),
    Credit("Material Components for Android", "Apache License 2.0 — Google LLC", "https://github.com/material-components/material-components-android"),
)

@Composable
private fun CreditRow(c: Credit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable { openUrl(context, c.url) }.padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp))
        }
        Text(c.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Credits & licenses" dialog: data services and open-source libraries. */
@Composable
private fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.settings_credits)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_credits_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_credits_services), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                SERVICES.forEach { CreditRow(it) }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_credits_libraries), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                LIBRARIES.forEach { CreditRow(it) }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.settings_credits_app), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
