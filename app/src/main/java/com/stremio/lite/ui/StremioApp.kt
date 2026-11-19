package com.stremio.lite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.stremio.lite.ui.screens.AccountScreen
import com.stremio.lite.ui.screens.AddonsScreen
import com.stremio.lite.ui.screens.BoardScreen
import com.stremio.lite.ui.screens.DetailScreen
import com.stremio.lite.ui.screens.DiscoverScreen
import com.stremio.lite.ui.screens.DownloadsScreen
import com.stremio.lite.ui.screens.PlayerScreen
import com.stremio.lite.ui.screens.SearchScreen
import com.stremio.lite.ui.theme.Glass
import com.stremio.lite.ui.theme.Palette
import java.net.URLDecoder
import java.net.URLEncoder

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    BOARD("board", "Board", Icons.Filled.Home),
    DISCOVER("discover", "Discover", Icons.Filled.Explore),
    SEARCH("search", "Search", Icons.Filled.Search),
    DOWNLOADS("downloads", "Downloads", Icons.Filled.Download),
    ADDONS("addons", "Addons", Icons.Filled.Extension),
    ACCOUNT("account", "Account", Icons.Filled.Person)
}

@Composable
fun StremioApp(startOnDownloads: Boolean = false) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    // Hoisted here rather than inside each screen, so switching tabs reuses the loaded state
    // instead of rebuilding a ViewModel and refetching every catalog.
    val boardViewModel: BoardViewModel = viewModel()
    val searchViewModel: SearchViewModel = viewModel()
    val discoverViewModel: DiscoverViewModel = viewModel()

    Scaffold(
        containerColor = Palette.Base,
        bottomBar = {
            if (Tab.entries.any { it.route == currentRoute }) {
                NavigationBar(
                    containerColor = Color.Transparent,
                    modifier = Modifier.background(Glass.fill)
                ) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.TextPrimary,
                                selectedTextColor = Palette.TextPrimary,
                                indicatorColor = Palette.AccentSoft,
                                unselectedIconColor = Palette.TextFaint,
                                unselectedTextColor = Palette.TextFaint
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (startOnDownloads) Tab.DOWNLOADS.route else Tab.BOARD.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Tab.BOARD.route) {
                BoardScreen(boardViewModel) { type, id -> navController.navigateToDetail(type, id) }
            }
            composable(Tab.DISCOVER.route) {
                DiscoverScreen(discoverViewModel) { type, id -> navController.navigateToDetail(type, id) }
            }
            composable(Tab.SEARCH.route) {
                SearchScreen(searchViewModel) { type, id -> navController.navigateToDetail(type, id) }
            }
            composable(Tab.DOWNLOADS.route) {
                DownloadsScreen(onPlayFile = { path -> navController.navigateToPlayer(path, local = true) })
            }
            composable(Tab.ADDONS.route) { AddonsScreen() }
            composable(Tab.ACCOUNT.route) { AccountScreen() }

            composable("detail/{type}/{id}") { entry ->
                DetailScreen(
                    type = entry.arguments?.getString("type").orEmpty().decode(),
                    id = entry.arguments?.getString("id").orEmpty().decode(),
                    onBack = { navController.popBackStack() },
                    onPlay = { url, headers -> navController.navigateToPlayer(url, local = false, headers = headers) }
                )
            }
            composable("player/{source}/{local}") { entry ->
                PlayerScreen(
                    source = entry.arguments?.getString("source").orEmpty().decode(),
                    isLocalFile = entry.arguments?.getString("local") == "true",
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private fun androidx.navigation.NavController.navigateToDetail(type: String, id: String) {
    navigate("detail/${type.encode()}/${id.encode()}")
}

private fun androidx.navigation.NavController.navigateToPlayer(
    source: String,
    local: Boolean,
    headers: Map<String, String> = emptyMap()
) {
    PlaybackRequest.headers = headers
    navigate("player/${source.encode()}/$local")
}

/** Transient holder for request headers some addons require for playback. */
object PlaybackRequest {
    var headers: Map<String, String> = emptyMap()
}

private fun String.encode(): String = URLEncoder.encode(this, "UTF-8")
private fun String.decode(): String = runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
