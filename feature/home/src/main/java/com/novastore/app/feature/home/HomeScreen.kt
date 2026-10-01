package com.novastore.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.novastore.app.core.ui.components.NovaRatingCompact
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.model.HomeLayoutStyle
import com.novastore.app.core.model.IconSize
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.AppCardRow
import com.novastore.app.core.ui.components.AppCardLarge
import com.novastore.app.core.ui.components.AppGridCell
import com.novastore.app.core.ui.components.AppIcon
import com.novastore.app.core.ui.components.CategoryChip
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.NovaCategories
import com.novastore.app.core.ui.components.NovaRatingRow
import com.novastore.app.core.ui.components.ShimmerBox
import com.novastore.app.core.ui.components.SourceBadge
import com.novastore.app.core.ui.components.novaAccentBrush
import com.novastore.app.core.ui.components.SectionTitle
import com.novastore.app.core.ui.theme.OnEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenUpdates: () -> Unit,
    onOpenAppDetails: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenInstalled: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenCategory: (String) -> Unit = {},
    onOpenSearchQuery: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val startScan = com.novastore.app.core.ui.components.rememberQrScanner { raw ->
        viewModel.resolveScanned(raw) { link ->
            when (link) {
                is com.novastore.app.core.model.StoreLink.App -> onOpenAppDetails(link.packageName)
                is com.novastore.app.core.model.StoreLink.Search -> onOpenSearchQuery(link.query)
                null -> Unit
            }
        }
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val searchPinned by viewModel.searchPinned.collectAsStateWithLifecycle()

    // --- Search bar that slides under the header while scrolling ---
    // Offset in px: 0 = fully shown, -searchBarPx = hidden under the header.
    // Driven by nested scroll (the list keeps all of its scroll), drawn via
    // graphicsLayer — no relayout of the feed on any frame.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val searchBarPx = with(density) { SEARCH_BAR_HEIGHT.toPx() }
    val searchOffset = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    LaunchedEffect(searchPinned) { if (searchPinned) searchOffset.floatValue = 0f }
    val collapseConnection = remember(searchPinned, searchBarPx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ): androidx.compose.ui.geometry.Offset {
                if (!searchPinned) {
                    searchOffset.floatValue = (searchOffset.floatValue + available.y).coerceIn(-searchBarPx, 0f)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPostFling(
                consumed: androidx.compose.ui.unit.Velocity,
                available: androidx.compose.ui.unit.Velocity,
            ): androidx.compose.ui.unit.Velocity {
                // Never stop half-way: settle fully shown or fully hidden.
                if (!searchPinned) {
                    val current = searchOffset.floatValue
                    val target = if (current < -searchBarPx / 2) -searchBarPx else 0f
                    if (current != target) {
                        androidx.compose.animation.core.animate(current, target) { value, _ -> searchOffset.floatValue = value }
                    }
                }
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // --- Scroll-gated browse content (see git history for the rationale) ---
    val browseContentPair = remember {
        // Seeded with the CURRENT content: re-entering Home from a details
        // page must not start from an empty list (that clamped the restored
        // scroll position back to the top).
        mutableStateOf(state.categoryApps to state.canLoadMore)
    }
    val incomingPair = state.categoryApps to state.canLoadMore
    LaunchedEffect(incomingPair) {
        val current = browseContentPair.value.first
        val isAppend = current.isNotEmpty() && incomingPair.first.size > current.size &&
            incomingPair.first.subList(0, current.size) == current
        // Appends (load-more) apply immediately; only a REPLACEMENT of
        // visible content waits for the fling to finish.
        if (current.isNotEmpty() && !isAppend) {
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .first { scrolling -> !scrolling }
        }
        if (browseContentPair.value != incomingPair) {
            browseContentPair.value = incomingPair
        }
    }
    val browseApps: List<RemoteApp> = browseContentPair.value.first
    val browseCanLoadMore: Boolean = browseContentPair.value.second
    val browseRows = remember(browseApps, state.gridColumns) { browseApps.chunked(state.gridColumns) }

    val nearListEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 4 && last >= total - 8
        }
    }
    LaunchedEffect(nearListEnd, browseCanLoadMore, state.layoutStyle) {
        if (state.layoutStyle != HomeLayoutStyle.SHELVES && nearListEnd && browseCanLoadMore) viewModel.loadMore()
    }

    // Index of the "All apps" header, recorded while the list is built, so
    // a shelf's "See all" can jump straight to the grid.
    val browseIndex = remember { intArrayOf(0) }
    val scrollToBrowse: () -> Unit = {
        scope.launch { listState.animateScrollToItem(browseIndex[0]) }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        HomeTopBar(
            state = state,
            onOpenSearch = onOpenSearch,
            onScan = startScan,
            onOpenSettings = onOpenSettings,
            onStyle = viewModel::setLayoutStyle,
            onColumns = viewModel::setGridColumns,
            onIconSize = viewModel::setIconSize,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The bar slides out at this edge — i.e. under the header.
                .clipToBounds()
                .nestedScroll(collapseConnection),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = SEARCH_BAR_HEIGHT + 4.dp,
                // No bottom bar anymore: keep the last row above the system
                // navigation bar (gesture pill / 3-button bar).
                bottom = 28.dp + androidx.compose.foundation.layout.WindowInsets.navigationBars
                    .asPaddingValues().calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(if (state.layoutStyle == HomeLayoutStyle.SHELVES) 6.dp else 18.dp),
        ) {
            var count = 0

            // --- Quick access tiles (replace the old "updates" banner) ---
            item(key = "tiles", contentType = { "tiles" }) {
                QuickTilesRow(
                    updatesCount = state.updatesCount,
                    favoritesCount = favorites.size,
                    onUpdates = onOpenUpdates,
                    onInstalled = onOpenInstalled,
                    onDownloads = onOpenDownloads,
                    onFavorites = {
                        if (favorites.isNotEmpty()) scope.launch { listState.animateScrollToItem(favoritesIndexOf(state)) }
                    },
                )
            }
            count++

            // Why the first start takes a while: every repository index is
            // being downloaded — shown right here, not only in a notification.
            scanProgress?.let { progress ->
                item(key = "scan-progress", contentType = { "banner" }) { ScanProgressBanner(progress) }
                count++
            }
            if (state.offline) {
                item(key = "offline", contentType = { "banner" }) { OfflineBanner() }
                count++
            }
            if (state.error != null) {
                item(key = "error", contentType = { "banner" }) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        onClick = viewModel::dismissError,
                    ) {
                        Text(
                            text = state.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                count++
            }

            // --- Hero: "Recommended for you" pager ---
            if (state.loading && state.featured.isEmpty()) {
                item(key = "hero-shimmer", contentType = { "shimmer" }) {
                    ShimmerBox(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(196.dp),
                        cornerRadius = 30.dp,
                    )
                }
                count++
            } else if (state.featured.isNotEmpty()) {
                item(key = "hero-title", contentType = { "section-title" }) {
                    HomeSectionHeader(
                        title = stringResource(UiR.string.home_recommended),
                        subtitle = stringResource(UiR.string.home_recommended_sub),
                    )
                }
                item(key = "hero", contentType = { "hero" }) {
                    HeroPager(apps = state.featured, onOpen = onOpenAppDetails)
                }
                count += 2
            }

            // --- Favorites ---
            if (favorites.isNotEmpty()) {
                item(key = "favorites-title", contentType = { "section-title" }) {
                    HomeSectionHeader(title = stringResource(UiR.string.home_favorites))
                }
                item(key = "favorites-row", contentType = { "carousel" }) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(favorites, key = { "fav:${it.packageName}" }, contentType = { "fav" }) { fav ->
                            ShelfCard(
                                name = fav.name,
                                packageName = fav.packageName,
                                iconUrl = fav.iconUrl,
                                rating = null,
                                iconSize = 64.dp,
                                onClick = { onOpenAppDetails(fav.packageName) },
                            )
                        }
                    }
                }
                count += 2
            }

            if (state.layoutStyle == HomeLayoutStyle.SHELVES) {
                // --- Default layout: one horizontal row per category ---
                val shelfIcon = when (state.iconSize) {
                    IconSize.SMALL -> 64.dp
                    IconSize.MEDIUM -> 80.dp
                    IconSize.LARGE -> 96.dp
                }
                // Play front page, "New in F-Droid", then Play and repository
                // categories interleaved — repository apps are never buried
                // under 19 Play rows.
                val playKeys = state.categories.filter { PlayShelves.isShelf(it) }
                val localKeys = state.categories.filterNot { PlayShelves.isShelf(it) }
                val rowKeys = buildList {
                    add(ROW_PLAY_HOME)
                    if (state.recentlyAdded.isNotEmpty()) add(ROW_FDROID_NEW)
                    val longest = maxOf(playKeys.size, localKeys.size)
                    for (i in 0 until longest) {
                        playKeys.getOrNull(i)?.let(::add)
                        localKeys.getOrNull(i)?.let(::add)
                    }
                }
                // Fixed row height: loading → loaded never shifts the list.
                val rowHeight = shelfIcon + 44.dp
                rowKeys.forEachIndexed { index, key ->
                    item(key = "row-$key", contentType = { "category-row" }) {
                        // Each row observes ONLY its own content: a row that
                        // finishes loading recomposes itself, not Home.
                        val flow = remember(key) { viewModel.rowFlow(key) }
                        val loadedRow by flow.collectAsStateWithLifecycle(initialValue = viewModel.rowSnapshot(key))
                        val apps = if (key == ROW_FDROID_NEW) state.recentlyAdded else loadedRow
                        LaunchedEffect(key) {
                            // This row plus the next two: data is ready
                            // before they scroll into view.
                            listOfNotNull(key, rowKeys.getOrNull(index + 1), rowKeys.getOrNull(index + 2))
                                .filter { it != ROW_FDROID_NEW }
                                .forEach(viewModel::requestRow)
                        }
                        val loaded = apps
                        if (loaded == null || loaded.isNotEmpty()) {
                            Column {
                                HomeSectionHeader(
                                    title = rowLabel(key),
                                    onSeeAll = if (key == ROW_FDROID_NEW) null else ({ onOpenCategory(key) }),
                                )
                                Spacer(Modifier.height(2.dp))
                                if (loaded == null) {
                                    ShelfPlaceholderRow(shelfIcon, rowHeight)
                                } else {
                                    LazyRow(
                                        modifier = Modifier.heightIn(min = rowHeight),
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        items(loaded, key = { it.packageName }, contentType = { "shelf-app" }) { app ->
                                            ShelfCard(
                                                name = app.name,
                                                packageName = app.packageName,
                                                iconUrl = app.iconUrl,
                                                rating = app.rating,
                                                iconSize = shelfIcon,
                                                onClick = { onOpenAppDetails(app.packageName) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
            // --- Grid / list feed (user-selected via the grid icon) ---
            browseIndex[0] = count
            item(key = "browse-title", contentType = { "section-title" }) {
                HomeSectionHeader(
                    title = state.selectedCategory
                        ?.let { if (PlayShelves.isShelf(it)) PlayShelves.label(it) else NovaCategories.label(it) }
                        ?: stringResource(UiR.string.home_browse_all),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            item(key = "categories", contentType = { "chips" }) {
                CategoryChipsRow(
                    categories = state.categories,
                    selected = state.selectedCategory,
                    onSelect = viewModel::selectCategory,
                )
            }

            val cellSpacing = if (state.gridColumns >= 4) 8.dp else 12.dp
            if (state.loading && browseApps.isEmpty()) {
                items(3, key = { "browse-shimmer-$it" }, contentType = { "shimmer" }) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(cellSpacing),
                    ) {
                        repeat(state.gridColumns) {
                            ShimmerBox(modifier = Modifier.weight(1f).height(150.dp), cornerRadius = 20.dp)
                        }
                    }
                }
            } else if (browseApps.isEmpty()) {
                item(key = "browse-empty", contentType = { "empty" }) {
                    EmptyState(
                        icon = Icons.Filled.Apps,
                        title = stringResource(UiR.string.home_catalog_empty_title),
                        description = stringResource(UiR.string.home_catalog_empty_desc),
                        actionLabel = stringResource(UiR.string.home_catalog_empty_action),
                        onAction = { viewModel.refresh() },
                    )
                }
            } else if (state.layoutStyle == HomeLayoutStyle.LIST) {
                items(browseApps, key = { "b:${it.source}:${it.packageName}" }, contentType = { "app" }) { app ->
                    AppCardRow(
                        app = app,
                        onClick = { onOpenAppDetails(app.packageName) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                itemsIndexed(
                    browseRows,
                    key = { index, _ -> "browse-$index" },
                    contentType = { _, _ -> "browse-row" },
                ) { _, row ->
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(cellSpacing),
                    ) {
                        row.forEach { app ->
                            AppGridCell(
                                app = app,
                                onClick = { onOpenAppDetails(app.packageName) },
                                iconSize = state.iconSize.dp.dp,
                                columns = state.gridColumns,
                                spacing = cellSpacing,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(state.gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (browseCanLoadMore && browseApps.isNotEmpty()) {
                item(key = "browse-more", contentType = { "load-more" }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ShimmerBox(modifier = Modifier.size(width = 140.dp, height = 40.dp), cornerRadius = 20.dp)
                    }
                }
            }
            }
        }
        // Search bar overlay: opaque, so the feed passes UNDER it while it
        // is visible, and it slides up under the header when hidden.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationY = searchOffset.floatValue }
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        ) {
            HomeSearchPill(onOpenSearch = onOpenSearch, onScan = startScan)
        }
        }
    }
}

/** Search pill (52dp) + its bottom spacing. */
private val SEARCH_BAR_HEIGHT = 62.dp

/** Index of the favorites row in the list (tiles + optional banners + hero). */
private fun favoritesIndexOf(state: HomeUiState): Int {
    var index = 1
    if (state.offline) index++
    if (state.error != null) index++
    if (state.featured.isNotEmpty()) index += 2 else if (state.loading) index++
    return index
}

// ----------------------------------------------------------------------
// Brand
// ----------------------------------------------------------------------

private val BrandIndigo = Color(0xFF6965F1)
private val BrandViolet = Color(0xFFA556F7)

/** Brand gradient of the "Nova Store" wordmark: indigo → violet. */
private val NovaTitleBrush = Brush.linearGradient(colors = listOf(BrandIndigo, BrandViolet))

/** "Everything is up to date" — calm green instead of the call-to-action gradient. */
private val UpToDateBrush = Brush.linearGradient(colors = listOf(Color(0xFF12B886), Color(0xFF0CA678), Color(0xFF15AABF)))

/** Hero card gradients, cycled per page. */
private val HeroGradients = listOf(
    listOf(Color(0xFF6965F1), Color(0xFFA556F7)),
    listOf(Color(0xFF3B5BDB), Color(0xFF6965F1)),
    listOf(Color(0xFFA556F7), Color(0xFFE64980)),
    listOf(Color(0xFF0CA678), Color(0xFF3B5BDB)),
    listOf(Color(0xFFF76707), Color(0xFFE64980)),
    listOf(Color(0xFF1C7ED6), Color(0xFF15AABF)),
)

// ----------------------------------------------------------------------
// Top bar
// ----------------------------------------------------------------------

@Composable
private fun HomeTopBar(
    state: HomeUiState,
    onOpenSearch: () -> Unit,
    onScan: () -> Unit,
    onOpenSettings: () -> Unit,
    onStyle: (HomeLayoutStyle) -> Unit,
    onColumns: (Int) -> Unit,
    onIconSize: (IconSize) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Nova Store",
                style = MaterialTheme.typography.headlineMedium.copy(
                    brush = NovaTitleBrush,
                    fontWeight = FontWeight.ExtraBold,
                ),
                modifier = Modifier.weight(1f),
            )
            LayoutMenuButton(state = state, onStyle = onStyle, onColumns = onColumns, onIconSize = onIconSize)
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = stringResource(UiR.string.tab_settings),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The search pill — the store's front door. */
@Composable
private fun HomeSearchPill(onOpenSearch: () -> Unit, onScan: () -> Unit) {
        Surface(
            onClick = onOpenSearch,
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Row(
                modifier = Modifier.padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(UiR.string.cd_search),
                    tint = BrandIndigo,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(UiR.string.home_search_pill),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onScan) {
                    Icon(
                        Icons.Filled.QrCodeScanner,
                        contentDescription = stringResource(UiR.string.search_scan),
                        tint = BrandIndigo,
                    )
                }
            }
        }
}

/** Grid icon: layout (rows / grid / list), columns and icon size. */
@Composable
private fun LayoutMenuButton(
    state: HomeUiState,
    onStyle: (HomeLayoutStyle) -> Unit,
    onColumns: (Int) -> Unit,
    onIconSize: (IconSize) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.GridView,
                contentDescription = stringResource(UiR.string.home_layout_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuHeader(stringResource(UiR.string.home_layout_style))
            MenuCheck(stringResource(UiR.string.home_layout_shelves), state.layoutStyle == HomeLayoutStyle.SHELVES) {
                onStyle(HomeLayoutStyle.SHELVES); expanded = false
            }
            MenuCheck(stringResource(UiR.string.home_layout_grid), state.layoutStyle == HomeLayoutStyle.GRID) {
                onStyle(HomeLayoutStyle.GRID); expanded = false
            }
            MenuCheck(stringResource(UiR.string.home_layout_list), state.layoutStyle == HomeLayoutStyle.LIST) {
                onStyle(HomeLayoutStyle.LIST); expanded = false
            }
            MenuHeader(stringResource(UiR.string.home_layout_columns))
            listOf(2, 3, 4).forEach { columns ->
                MenuCheck(stringResource(UiR.string.home_layout_columns_value, columns), columns == state.gridColumns) {
                    onColumns(columns)
                }
            }
            MenuHeader(stringResource(UiR.string.home_layout_icon_size))
            IconSize.entries.forEach { size ->
                MenuCheck(
                    stringResource(
                        when (size) {
                            IconSize.SMALL -> UiR.string.icon_size_small
                            IconSize.MEDIUM -> UiR.string.icon_size_medium
                            IconSize.LARGE -> UiR.string.icon_size_large
                        },
                    ),
                    size == state.iconSize,
                ) { onIconSize(size) }
            }
        }
    }
}

@Composable
private fun MenuHeader(text: String) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.labelMedium, color = BrandIndigo) },
        enabled = false,
        onClick = {},
    )
}

@Composable
private fun MenuCheck(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        trailingIcon = { if (checked) Text("✓", color = BrandIndigo, fontWeight = FontWeight.Bold) },
        onClick = onClick,
    )
}

@Composable
private fun rowLabel(key: String): String = when {
    key == ROW_PLAY_HOME -> stringResource(UiR.string.home_shelf_home)
    key == ROW_FDROID_NEW -> stringResource(UiR.string.home_new_fdroid)
    PlayShelves.isShelf(key) -> PlayShelves.label(key)
    // Repository categories carry their origin: "Finance · F-Droid" is
    // never confused with Play's "Finance" row.
    else -> NovaCategories.label(key) + " · F-Droid"
}

private const val ROW_PLAY_HOME = "play:home"
private const val ROW_FDROID_NEW = "fdroid:new"

@Composable
private fun ShelfPlaceholderRow(iconSize: androidx.compose.ui.unit.Dp, height: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.height(height).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(4) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(iconSize + 10.dp)) {
                Box(
                    Modifier
                        .size(iconSize)
                        .clip(RoundedCornerShape(iconSize / 3.4f))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .size(width = iconSize * 0.8f, height = 10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            }
        }
    }
}

@Composable
private fun ShelfShimmerRow(iconSize: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(4) {
            ShimmerBox(modifier = Modifier.size(width = iconSize + 20.dp, height = iconSize + 48.dp), cornerRadius = 20.dp)
        }
    }
}

// ----------------------------------------------------------------------
// Quick tiles
// ----------------------------------------------------------------------

@Composable
private fun QuickTilesRow(
    updatesCount: Int,
    favoritesCount: Int,
    onUpdates: () -> Unit,
    onInstalled: () -> Unit,
    onDownloads: () -> Unit,
    onFavorites: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickTile(
            icon = if (updatesCount > 0) Icons.Filled.SystemUpdateAlt else Icons.Filled.CheckCircle,
            label = stringResource(if (updatesCount > 0) UiR.string.home_tile_updates else UiR.string.home_tile_up_to_date),
            badge = updatesCount.takeIf { it > 0 }?.toString(),
            highlighted = true,
            gradient = if (updatesCount > 0) NovaTitleBrush else UpToDateBrush,
            onClick = onUpdates,
            modifier = Modifier.weight(1f),
        )
        QuickTile(
            icon = Icons.Filled.Apps,
            label = stringResource(UiR.string.home_tile_installed),
            onClick = onInstalled,
            modifier = Modifier.weight(1f),
        )
        QuickTile(
            icon = Icons.Filled.Download,
            label = stringResource(UiR.string.home_tile_downloads),
            onClick = onDownloads,
            modifier = Modifier.weight(1f),
        )
        QuickTile(
            icon = Icons.Filled.Favorite,
            label = stringResource(UiR.string.home_favorites),
            badge = favoritesCount.takeIf { it > 0 }?.toString(),
            onClick = onFavorites,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = false,
    gradient: Brush = NovaTitleBrush,
) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .height(88.dp)
            .clip(shape)
            .then(
                if (highlighted) {
                    Modifier.background(gradient)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(
                        if (highlighted) Color.White.copy(alpha = 0.22f) else BrandIndigo.copy(alpha = 0.14f),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlighted) Color.White else BrandIndigo,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (badge != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 8.dp, y = (-4).dp)
                        .clip(CircleShape)
                        .background(if (highlighted) Color.White else BrandViolet)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (highlighted) BrandIndigo else Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (highlighted) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

// ----------------------------------------------------------------------
// Section header
// ----------------------------------------------------------------------

@Composable
private fun HomeSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onSeeAll: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSeeAll != null) {
            Surface(
                onClick = onSeeAll,
                shape = RoundedCornerShape(50),
                color = BrandIndigo.copy(alpha = 0.12f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(UiR.string.action_see_all),
                        style = MaterialTheme.typography.labelLarge,
                        color = BrandIndigo,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = BrandIndigo,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Hero pager
// ----------------------------------------------------------------------

@Composable
private fun HeroPager(apps: List<RemoteApp>, onOpen: (String) -> Unit) {
    val pagerState = rememberPagerState(pageCount = { apps.size })
    // Gentle auto-advance; pauses while the user is swiping.
    LaunchedEffect(pagerState, apps) {
        while (apps.size > 1) {
            delay(5_500)
            if (!pagerState.isScrollInProgress) {
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % apps.size)
            }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            key = { apps[it].packageName },
        ) { page ->
            HeroCard(app = apps[page], colors = HeroGradients[page % HeroGradients.size], onClick = { onOpen(apps[page].packageName) })
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(apps.size) { index ->
                val selected = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .height(6.dp)
                        .width(if (selected) 18.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) BrandIndigo else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
    }
}

@Composable
private fun HeroCard(app: RemoteApp, colors: List<Color>, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(196.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Brush.linearGradient(colors))
            .drawBehind {
                // Soft decorative orbs — depth without images.
                drawCircle(Color.White.copy(alpha = 0.10f), radius = size.height * 0.75f, center = Offset(size.width * 0.95f, -size.height * 0.1f))
                drawCircle(Color.White.copy(alpha = 0.07f), radius = size.height * 0.45f, center = Offset(size.width * 0.78f, size.height * 1.05f))
            }
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                        .padding(3.dp),
                ) {
                    AppIcon(
                        packageName = app.packageName,
                        appName = app.name,
                        iconUrl = app.iconUrl,
                        size = 74.dp,
                        shape = RoundedCornerShape(21.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = app.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    (app.developer ?: app.categories.firstOrNull())?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.summary ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.9f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                app.rating?.let { rating ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("%.1f".format(rating), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = stringResource(UiR.string.home_hero_open),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.first(),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Shelf card
// ----------------------------------------------------------------------

@Composable
private fun ShelfCard(
    name: String,
    packageName: String,
    iconUrl: String?,
    rating: Float?,
    onClick: () -> Unit,
    iconSize: androidx.compose.ui.unit.Dp = 84.dp,
) {
    // Tight lanes: the label may be ~10dp wider than the icon, no more.
    Column(
        modifier = Modifier
            .width(iconSize + 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(
            packageName = packageName,
            appName = name,
            iconUrl = iconUrl,
            size = iconSize,
            shape = RoundedCornerShape(iconSize / 3.4f),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (rating != null) {
            Spacer(Modifier.height(2.dp))
            NovaRatingCompact(value = rating)
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(UiR.string.home_offline_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun CategoryChipsRow(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "chip-all", contentType = { "chip" }) {
            CategoryChip(
                name = stringResource(UiR.string.home_category_all),
                selected = selected == null,
                onClick = { onSelect(null) },
            )
        }
        items(categories, key = { it }, contentType = { "category-chip" }) { category ->
            CategoryChip(
                name = if (PlayShelves.isShelf(category)) PlayShelves.label(category) else NovaCategories.label(category),
                selected = selected == category,
                onClick = { onSelect(category) },
            )
        }
    }
}

/** Compact vertical card for the "New on F-Droid" carousel. */
@Composable
private fun RecentAppCard(app: RemoteApp, iconSize: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    val width = (iconSize * 2.1f).coerceIn(148.dp, 208.dp)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.width(width),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Icon clamp: measured inside the 12dp-padded column, so maxWidth
            // is the card's icon-available width (card width − 24dp) — the
            // icon can never clip the card in the carousel.
            val effectiveIconSize = minOf(iconSize, width - 24.dp)
            AppIcon(
                packageName = app.packageName,
                appName = app.name,
                iconUrl = app.iconUrl,
                size = effectiveIconSize,
                shape = RoundedCornerShape(effectiveIconSize / 3.2f),
            )
            Text(
                text = app.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                minLines = 2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                NovaRatingRow(value = app.rating, starSize = 11.dp, centered = true)
            }
            SourceBadge(source = app.source, compact = true)
        }
    }
}

@Composable
private fun FeaturedShimmerRow() {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(2, key = { "shimmer-hero-$it" }, contentType = { "shimmer" }) {
            ShimmerBox(
                modifier = Modifier.size(width = 272.dp, height = 96.dp),
                cornerRadius = 24.dp,
            )
        }
    }
}


@Composable
private fun ScanProgressBanner(progress: com.novastore.app.domain.repository.ScanProgress) {
    val title = stringResource(
        when (progress.stage) {
            com.novastore.app.domain.repository.ScanProgress.Stage.REPOSITORIES -> UiR.string.scan_stage_repositories
            com.novastore.app.domain.repository.ScanProgress.Stage.INSTALLED_APPS -> UiR.string.scan_stage_installed
            com.novastore.app.domain.repository.ScanProgress.Stage.GOOGLE_PLAY -> UiR.string.scan_stage_play
            com.novastore.app.domain.repository.ScanProgress.Stage.MIRRORS -> UiR.string.scan_stage_mirrors
        },
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (progress.total > 0) {
                    Text(
                        "${progress.done}/${progress.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = BrandIndigo,
                    )
                }
            }
            progress.label?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            if (progress.total > 0) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandIndigo,
                )
            } else {
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = BrandIndigo)
            }
        }
    }
}
