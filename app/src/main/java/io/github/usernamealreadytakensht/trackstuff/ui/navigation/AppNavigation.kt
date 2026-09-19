package io.github.usernamealreadytakensht.trackstuff.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.usernamealreadytakensht.trackstuff.data.remote.describeError
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.usernamealreadytakensht.trackstuff.R
import io.github.usernamealreadytakensht.trackstuff.appContainer
import io.github.usernamealreadytakensht.trackstuff.domain.ExternalIds
import io.github.usernamealreadytakensht.trackstuff.domain.MediaSummary
import io.github.usernamealreadytakensht.trackstuff.ui.detail.DetailScreen
import io.github.usernamealreadytakensht.trackstuff.ui.detail.DetailViewModel
import io.github.usernamealreadytakensht.trackstuff.ui.discover.DiscoverScreen
import io.github.usernamealreadytakensht.trackstuff.ui.discover.DiscoverViewModel
import io.github.usernamealreadytakensht.trackstuff.ui.library.LibraryScreen
import io.github.usernamealreadytakensht.trackstuff.ui.library.LibraryViewModel
import io.github.usernamealreadytakensht.trackstuff.ui.search.SearchScreen
import io.github.usernamealreadytakensht.trackstuff.ui.search.SearchViewModel
import io.github.usernamealreadytakensht.trackstuff.ui.settings.SettingsScreen
import io.github.usernamealreadytakensht.trackstuff.ui.settings.SettingsViewModel
import kotlinx.serialization.Serializable

// ---- Destinations (Navigation 3) ----

@Serializable data object LibraryKey : NavKey
@Serializable data object DiscoverKey : NavKey
@Serializable data object SearchKey : NavKey
@Serializable data object SettingsKey : NavKey

/** Detail page: `localId` for a library title, otherwise the external ids of a search result. */
@Serializable
data class DetailKey(
    val localId: Long? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvdbId: Int? = null,
    val isSeries: Boolean = false,
    val title: String? = null,
    val year: Int? = null,
) : NavKey {
    companion object {
        fun local(id: Long) = DetailKey(localId = id)
        fun remote(s: MediaSummary) = DetailKey(
            tmdbId = s.ids.tmdbId, imdbId = s.ids.imdbId, tvdbId = s.ids.tvdbId,
            isSeries = s.isSeries, title = s.title.ifBlank { null }, year = s.year,
        )
    }
}

private val TOP_LEVEL: List<NavKey> = listOf(LibraryKey, DiscoverKey, SearchKey, SettingsKey)

@Composable
fun AppNavigation() {
    val container = LocalContext.current.appContainer
    val backStack = rememberNavBackStack(LibraryKey)
    val current = backStack.lastOrNull()
    val showBar = current in TOP_LEVEL
    val context = LocalContext.current

    // A page URL shared to the app: resolved to a title, then opened (existing library entry preferred).
    val sharedLink by container.sharedLink.collectAsStateWithLifecycle()
    LaunchedEffect(sharedLink) {
        val url = sharedLink ?: return@LaunchedEffect
        // Consumed only at the end: clearing it first would restart (cancel) this effect.
        try {
            val summary = try { container.metadata.resolveLink(url) } catch (e: Exception) {
                Toast.makeText(context, context.getString(R.string.link_failed, describeError(e)), Toast.LENGTH_LONG).show(); return@LaunchedEffect
            }
            if (summary == null) { Toast.makeText(context, R.string.link_unknown, Toast.LENGTH_LONG).show(); return@LaunchedEffect }
            val existing = container.library.findExisting(summary.ids, summary.isSeries)
            backStack.add(if (existing != null) DetailKey.local(existing.localId) else DetailKey.remote(summary))
        } finally {
            if (container.sharedLink.value == url) container.sharedLink.value = null
        }
    }

    // Wide layout (landscape phone, tablet): the tabs move to a rail on the left.
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
    if (showBar && wide) Tabs(current, wide = true) { switchTab(backStack, it) }
    Column(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            // The tab bar below already sits above the system navigation bar: screens must not pad for it too
            // (that padding showed as a blank strip above the tabs).
            modifier = Modifier.weight(1f).then(if (showBar && !wide) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LibraryKey> {
                    val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(container.library, container.settings, container.sync) })
                    LibraryScreen(vm, onOpen = { backStack.add(DetailKey.local(it)) }, onOpenSettings = { switchTab(backStack, SettingsKey) })
                }
                entry<DiscoverKey> {
                    val vm: DiscoverViewModel = viewModel(factory = factory { DiscoverViewModel(container.metadata, container.library) })
                    DiscoverScreen(
                        vm,
                        onOpenLocal = { backStack.add(DetailKey.local(it)) },
                        onOpenRemote = { backStack.add(DetailKey.remote(it)) },
                        onOpenSettings = { switchTab(backStack, SettingsKey) },
                    )
                }
                entry<SearchKey> {
                    val vm: SearchViewModel = viewModel(factory = factory { SearchViewModel(container.metadata, container.library) })
                    SearchScreen(
                        vm,
                        onOpenLocal = { backStack.add(DetailKey.local(it)) },
                        onOpenRemote = { backStack.add(DetailKey.remote(it)) },
                        onOpenSettings = { switchTab(backStack, SettingsKey) },
                    )
                }
                entry<SettingsKey> {
                    val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settings, container.trakt, container.simkl, container.omdbOrg, container.imdb, container.sync, container.backup, container.metadata) })
                    SettingsScreen(vm)
                }
                entry<DetailKey> { key ->
                    val vm: DetailViewModel = viewModel(
                        key = "detail-${key.hashCode()}",
                        factory = factory {
                            DetailViewModel(
                                container.library, container.metadata,
                                key.localId, ExternalIds(tmdbId = key.tmdbId, imdbId = key.imdbId, tvdbId = key.tvdbId),
                                key.isSeries, key.title, key.year,
                            )
                        },
                    )
                    DetailScreen(vm, onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) })
                }
            },
        )
        if (showBar && !wide) Tabs(current, wide = false) { switchTab(backStack, it) }
    }
    }
}

/** Tab destinations, in order. */
private data class Tab(val key: NavKey, val icon: androidx.compose.ui.graphics.vector.ImageVector, @androidx.annotation.StringRes val label: Int)
private val TABS = listOf(
    Tab(LibraryKey, Icons.Default.VideoLibrary, R.string.tab_library),
    Tab(DiscoverKey, Icons.Default.Explore, R.string.tab_discover),
    Tab(SearchKey, Icons.Default.Search, R.string.tab_search),
    Tab(SettingsKey, Icons.Default.Settings, R.string.tab_settings),
)

/** Bottom bar on phones in portrait; a rail on the left on wide screens (landscape, tablets), where a bar would eat the height. */
@Composable
private fun Tabs(current: NavKey?, wide: Boolean, onSelect: (NavKey) -> Unit) {
    if (wide) {
        androidx.compose.material3.NavigationRail {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TABS.forEach { t ->
                androidx.compose.material3.NavigationRailItem(
                    selected = current == t.key,
                    onClick = { onSelect(t.key) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(stringResource(t.label)) },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
    } else {
        NavigationBar {
            TABS.forEach { t ->
                NavigationBarItem(
                    selected = current == t.key,
                    onClick = { onSelect(t.key) },
                    icon = { Icon(t.icon, contentDescription = null) },
                    label = { Text(stringResource(t.label)) },
                )
            }
        }
    }
}

/** Tabs replace the stack: the library stays the root for the Back button. */
private fun switchTab(backStack: MutableList<NavKey>, key: NavKey) {
    if (backStack.lastOrNull() == key) return
    backStack.clear()
    if (key != LibraryKey) backStack.add(LibraryKey)
    backStack.add(key)
}

@Suppress("UNCHECKED_CAST")
private fun <VM : ViewModel> factory(create: () -> VM) = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

