package com.example.trackstuff.ui.navigation

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
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
import com.example.trackstuff.R
import com.example.trackstuff.appContainer
import com.example.trackstuff.domain.ExternalIds
import com.example.trackstuff.domain.MediaSummary
import com.example.trackstuff.ui.detail.DetailScreen
import com.example.trackstuff.ui.detail.DetailViewModel
import com.example.trackstuff.ui.discover.DiscoverScreen
import com.example.trackstuff.ui.discover.DiscoverViewModel
import com.example.trackstuff.ui.library.LibraryScreen
import com.example.trackstuff.ui.library.LibraryViewModel
import com.example.trackstuff.ui.search.SearchScreen
import com.example.trackstuff.ui.search.SearchViewModel
import com.example.trackstuff.ui.settings.SettingsScreen
import com.example.trackstuff.ui.settings.SettingsViewModel
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
            isSeries = s.isSeries, title = s.title, year = s.year,
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

    Column(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.weight(1f),
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<LibraryKey> {
                    val vm: LibraryViewModel = viewModel(factory = factory { LibraryViewModel(container.library) })
                    LibraryScreen(vm, onOpen = { backStack.add(DetailKey.local(it)) })
                }
                entry<DiscoverKey> {
                    val vm: DiscoverViewModel = viewModel(factory = factory { DiscoverViewModel(container.metadata, container.library) })
                    DiscoverScreen(
                        vm,
                        onOpenLocal = { backStack.add(DetailKey.local(it)) },
                        onOpenRemote = { backStack.add(DetailKey.remote(it)) },
                    )
                }
                entry<SearchKey> {
                    val vm: SearchViewModel = viewModel(factory = factory { SearchViewModel(container.metadata, container.library) })
                    SearchScreen(
                        vm,
                        onOpenLocal = { backStack.add(DetailKey.local(it)) },
                        onOpenRemote = { backStack.add(DetailKey.remote(it)) },
                    )
                }
                entry<SettingsKey> {
                    val vm: SettingsViewModel = viewModel(factory = factory { SettingsViewModel(container.settings, container.trakt, container.simkl, container.omdbOrg, container.imdb, container.sync, container.backup) })
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
        if (showBar) {
            NavigationBar {
                NavigationBarItem(
                    selected = current == LibraryKey,
                    onClick = { switchTab(backStack, LibraryKey) },
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_library)) },
                )
                NavigationBarItem(
                    selected = current == DiscoverKey,
                    onClick = { switchTab(backStack, DiscoverKey) },
                    icon = { Icon(Icons.Default.Explore, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_discover)) },
                )
                NavigationBarItem(
                    selected = current == SearchKey,
                    onClick = { switchTab(backStack, SearchKey) },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_search)) },
                )
                NavigationBarItem(
                    selected = current == SettingsKey,
                    onClick = { switchTab(backStack, SettingsKey) },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
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

