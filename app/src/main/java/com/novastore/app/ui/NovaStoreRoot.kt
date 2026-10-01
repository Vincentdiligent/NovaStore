package com.novastore.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.AccentPalette
import com.novastore.app.core.model.ThemeMode
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.theme.NovaTheme
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.feature.account.AccountScreen
import com.novastore.app.feature.details.AppDetailsScreen
import com.novastore.app.feature.downloads.DownloadsScreen
import com.novastore.app.feature.home.HomeScreen
import com.novastore.app.feature.installed.InstalledScreen
import com.novastore.app.feature.search.SearchScreen
import com.novastore.app.feature.settings.SettingsScreen
import com.novastore.app.feature.updates.UpdatesScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Bottom navigation destinations (Search & Downloads live as plain routes). */
enum class TopDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Home("home", UiR.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
    Updates("updates", UiR.string.tab_updates, Icons.Filled.SystemUpdate, Icons.Outlined.SystemUpdate),
    Installed("installed", UiR.string.tab_installed, Icons.Filled.Apps, Icons.Outlined.Apps),
    Settings("settings", UiR.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

/** Root-level model: badge count for the Updates tab + theme preferences. */
@HiltViewModel
class NovaStoreRootViewModel @Inject constructor(
    updatesRepository: UpdatesRepository,
    settingsDataStore: SettingsDataStore,
) : ViewModel() {
    /** Visible updates only — ignored apps never light up the badge. */
    val updatesCount = kotlinx.coroutines.flow.combine(
        updatesRepository.observeCandidates(),
        settingsDataStore.ignoredUpdates,
    ) { candidates, ignored ->
        candidates.count { candidate ->
            val pkg = candidate.installed.packageName
            candidate.confidence == com.novastore.app.core.model.UpdateConfidence.EXACT &&
                !ignored.contains(pkg) && !ignored.contains("$pkg@${candidate.available.versionCode}")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val themeMode = settingsDataStore.appTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val accentPalette = settingsDataStore.accentPalette
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AccentPalette.EMERALD)
}

private const val ROUTE_SEARCH = "search"
private const val ROUTE_DOWNLOADS = "downloads"
private const val ROUTE_ACCOUNT = "account"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_DETAILS = "details/{packageName}"

@Composable
fun NovaStoreRoot(
    launchAction: String? = null,
    onLaunchActionConsumed: () -> Unit = {},
    rootViewModel: NovaStoreRootViewModel = hiltViewModel(),
) {
    val themeMode by rootViewModel.themeMode.collectAsStateWithLifecycle()
    val accentPalette by rootViewModel.accentPalette.collectAsStateWithLifecycle()

    NovaTheme(mode = themeMode, accent = accentPalette) {
        // First-launch storage access request (asked once per install).
        StoragePermissionGate()
        val navController = rememberNavController()
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = backStackEntry?.destination
        val updatesCount by rootViewModel.updatesCount.collectAsStateWithLifecycle()

        // Arriving from the "updates available" notification (cold or warm).
        var updateAllRequest by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(launchAction) {
            if (launchAction != null) {
                when {
                    launchAction.startsWith(com.novastore.app.MainActivity.PREFIX_DETAILS) ->
                        navController.navigate("details/" + launchAction.removePrefix(com.novastore.app.MainActivity.PREFIX_DETAILS))
                    launchAction.startsWith(com.novastore.app.MainActivity.PREFIX_SEARCH) ->
                        navController.navigate(
                            "$ROUTE_SEARCH?q=" + android.net.Uri.encode(launchAction.removePrefix(com.novastore.app.MainActivity.PREFIX_SEARCH)),
                        )
                    else -> {
                        if (launchAction == com.novastore.app.MainActivity.ACTION_UPDATE_ALL) updateAllRequest = true
                        navController.navigate(TopDestination.Updates.route) { launchSingleTop = true }
                    }
                }
                onLaunchActionConsumed()
            }
        }

        // The bottom bar belongs to the three main browse surfaces only —
        // details, search, account and settings are full-screen experiences.
        // No bottom navigation: Home's tiles (Updates, Installed, Downloads,
        // Favorites) and its settings icon reach every screen — the whole
        // height belongs to content.
        val showBottomBar = false

        Scaffold(
            // Each destination owns its own Scaffold/TopAppBar and consumes the
            // status bar inset itself. Reserving nothing here (instead of the
            // Scaffold default of systemBars) avoids the top inset being applied
            // twice, which would push the destination's TopAppBar down and
            // leave an uncolored gap above the bottom navigation bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (showBottomBar) {
                    Column {
                        InstallPermissionBanner()
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            TopDestination.entries.forEach { destination ->
                                val selected = currentDestination?.hierarchy
                                    ?.any { it.route == destination.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        navController.navigate(destination.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                    icon = {
                                        if (destination == TopDestination.Updates && updatesCount > 0) {
                                            BadgedBox(
                                                badge = {
                                                    Badge(
                                                        containerColor = MaterialTheme.colorScheme.primary,
                                                        contentColor = MaterialTheme.colorScheme.onPrimary,
                                                    ) {
                                                        Text(updatesCount.coerceAtMost(99).toString())
                                                    }
                                                },
                                            ) {
                                                Icon(
                                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                                    contentDescription = stringResource(destination.labelRes),
                                                )
                                            }
                                        } else {
                                            Icon(
                                                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                                contentDescription = stringResource(destination.labelRes),
                                            )
                                        }
                                    },
                                    label = { NovaTabLabel(destination.labelRes) },
                                )
                            }
                        }
                    }
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)) {
            if (currentDestination?.route == TopDestination.Home.route) InstallPermissionBanner()
            NavHost(
                navController = navController,
                startDestination = TopDestination.Home.route,
                modifier = Modifier.weight(1f),
            ) {
                composable(TopDestination.Home.route) {
                    HomeScreen(
                        onOpenUpdates = { navController.navigate(TopDestination.Updates.route) },
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                        onOpenSearch = { navController.navigate(ROUTE_SEARCH) },
                        onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onOpenAccount = { navController.navigate(ROUTE_ACCOUNT) },
                        onOpenInstalled = {
                            navController.navigate(TopDestination.Installed.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenDownloads = { navController.navigate(ROUTE_DOWNLOADS) },
                        onOpenCategory = { key -> navController.navigate("category/" + android.net.Uri.encode(key)) },
                        onOpenSearchQuery = { q -> navController.navigate("$ROUTE_SEARCH?q=" + android.net.Uri.encode(q)) },
                    )
                }
                composable("ignored") {
                    com.novastore.app.feature.updates.IgnoredScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                    )
                }
                composable("category/{key}") {
                    com.novastore.app.feature.home.CategoryScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                    )
                }
                composable(TopDestination.Updates.route) {
                    UpdatesScreen(
                        onBack = { navController.popBackStack() },
                        onOpenIgnored = { navController.navigate("ignored") },
                        startUpdateAll = updateAllRequest,
                        onUpdateAllStarted = { updateAllRequest = false },
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                        onOpenInstalled = { navController.navigate(TopDestination.Installed.route) },
                        onOpenDownloads = { navController.navigate(ROUTE_DOWNLOADS) },
                    )
                }
                composable(TopDestination.Installed.route) {
                    InstalledScreen(
                        onBack = { navController.popBackStack() },
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                    )
                }
                composable(
                    "$ROUTE_SEARCH?q={q}",
                    arguments = listOf(
                        androidx.navigation.navArgument("q") {
                            type = androidx.navigation.NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    SearchScreen(
                        initialQuery = entry.arguments?.getString("q"),
                        onOpenAppDetails = { packageName -> navController.navigate("details/$packageName") },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_DOWNLOADS) {
                    DownloadsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_SETTINGS) {
                    SettingsScreen(
                        onOpenAccount = { navController.navigate(ROUTE_ACCOUNT) },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(ROUTE_ACCOUNT) {
                    AccountScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("details/{packageName}") { entry ->
                    val packageName = entry.arguments?.getString("packageName") ?: return@composable
                    AppDetailsScreen(
                        packageName = packageName,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            }
        }
    }
}

/**
 * Bottom-navigation label that NEVER wraps: long localized tab names such as
 * «Установленные» would otherwise break into two lines and push the icon up.
 * 10sp labelMedium keeps even 13-char labels on one line at 4 tabs (360dp).
 */
@Composable
private fun NovaTabLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.sp),
    )
}
