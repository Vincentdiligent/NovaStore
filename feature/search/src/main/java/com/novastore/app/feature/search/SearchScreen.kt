package com.novastore.app.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.model.SOURCE_FDROID
import com.novastore.app.core.model.SOURCE_GITHUB
import com.novastore.app.core.model.SOURCE_GITLAB
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.AppCardRow
import com.novastore.app.core.ui.components.AppGridCell
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.ErrorState

/** Sources offered in the filter chip row ("All" is represented by null). */
private val SEARCH_SOURCE_FILTERS: List<String?> = listOf(
    null,
    SOURCE_PLAY,
    SOURCE_FDROID,
    SOURCE_GITHUB,
    SOURCE_GITLAB,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onOpenAppDetails: (String) -> Unit,
    onBack: () -> Unit = {},
    initialQuery: String? = null,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    // One tap is enough: arriving from the search pill, the field already
    // has the cursor and the keyboard is up. Coming back from an app page
    // (saved state) does not pop the keyboard again.
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var focusedOnce by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!focusedOnce && initialQuery.isNullOrBlank()) {
            focusedOnce = true
            kotlinx.coroutines.delay(150)
            runCatching { searchFocus.requestFocus() }
            keyboard?.show()
        }
    }
    LaunchedEffect(initialQuery) {
        if (!initialQuery.isNullOrBlank()) viewModel.setQuery(initialQuery)
    }
    // QR scanner: a store/F-Droid link (short links resolved through their
    // redirects) or a package name opens the app page; other text → search.
    val startScan = com.novastore.app.core.ui.components.rememberQrScanner { raw ->
        viewModel.resolveScanned(raw) { link ->
            when (link) {
                is com.novastore.app.core.model.StoreLink.App -> onOpenAppDetails(link.packageName)
                is com.novastore.app.core.model.StoreLink.Search -> viewModel.setQuery(link.query)
                null -> Unit
            }
        }
    }
    // THE FIX: the TextField binds to the instant queryState, never to the
    // debounced uiState — typed text can no longer be swallowed mid-search.
    val query by viewModel.queryState.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Hoisted scroll state: survives every recomposition of this screen.
    // The position resets ONLY when the query itself changes — changing the
    // sort, the source filter, the layout or the column count never jumps.
    val gridState = rememberLazyGridState()
    val listState = rememberLazyListState()
    // Only a NEW query scrolls back to the top — returning from an app page
    // re-enters composition with the same query and keeps the position.
    var scrolledForQuery by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(query) }
    LaunchedEffect(query) {
        if (query != scrolledForQuery) {
            scrolledForQuery = query
            gridState.scrollToItem(0)
            listState.scrollToItem(0)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                        placeholder = { Text(stringResource(UiR.string.search_hint)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(UiR.string.action_cancel),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                IconButton(onClick = startScan) {
                                    Icon(
                                        Icons.Filled.QrCodeScanner,
                                        contentDescription = stringResource(UiR.string.search_scan),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(UiR.string.cd_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (state.searching) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }

            if (state.offline) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        text = stringResource(UiR.string.home_offline_banner),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            // --- Compact options row: layout · grid columns · sort order ---
            // Shown once the saved prefs are known: the defaults used to
            // flash a "3×" columns pill that vanished a moment later.
            if (state.prefsLoaded) SearchOptionsRow(
                layout = state.layout,
                grid = state.grid,
                columns = state.columns,
                sort = state.sort,
                onCycleLayout = {
                    viewModel.setLayout(
                        when (state.layout) {
                            SEARCH_LAYOUT_AUTO -> SEARCH_LAYOUT_GRID
                            SEARCH_LAYOUT_GRID -> SEARCH_LAYOUT_LIST
                            else -> SEARCH_LAYOUT_AUTO
                        },
                    )
                },
                onCycleColumns = {
                    viewModel.setColumns(if (state.columns >= 4) 2 else state.columns + 1)
                },
                onSort = viewModel::setSort,
            )

            // --- Session-only source filter chips (only with a query) ---
            if (query.isNotBlank()) {
                SourceFilterRow(
                    selected = state.sourceFilter,
                    onSelect = viewModel::setSourceFilter,
                )
            }

            when {
                query.isBlank() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Search,
                        title = stringResource(UiR.string.search_hint),
                        description = stringResource(UiR.string.search_empty_hint),
                    )
                }
                state.error != null -> ErrorState(
                    modifier = Modifier.fillMaxWidth(),
                    description = state.error ?: "",
                    retryLabel = stringResource(UiR.string.action_retry),
                    onRetry = { viewModel.setQuery(query) },
                )
                state.results.isEmpty() && !state.searching -> EmptyState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(UiR.string.search_no_results, query),
                    description = stringResource(UiR.string.home_catalog_empty_desc),
                )
                else -> {
                    Text(
                        text = stringResource(UiR.string.search_results_count, state.results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                    if (state.grid) {
                        // 4-column grids tighten the horizontal gutter (12dp →
                        // 8dp) so cells keep more room for the clamped icon;
                        // content padding and the vertical gutter are unchanged.
                        val gridSpacing = if (state.columns >= 4) 8.dp else 12.dp
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(state.columns),
                            state = gridState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(gridSpacing),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            gridItems(
                                state.results,
                                key = { "${it.source}:${it.packageName}" },
                                contentType = { "app" },
                            ) { app ->
                                AppGridCell(
                                    app = app,
                                    onClick = { onOpenAppDetails(app.packageName) },
                                    iconSize = state.iconSize.dp.dp,
                                    columns = state.columns,
                                    spacing = gridSpacing,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                state.results,
                                key = { "${it.source}:${it.packageName}" },
                                contentType = { "app" },
                            ) { app ->
                                AppCardRow(
                                    app = app,
                                    onClick = { onOpenAppDetails(app.packageName) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Compact presentation controls under the top bar: layout toggle (48dp
 * IconButton), grid column pill (grid mode only) and the sort dropdown.
 */
@Composable
private fun SearchOptionsRow(
    layout: String,
    grid: Boolean,
    columns: Int,
    sort: String,
    onCycleLayout: () -> Unit,
    onCycleColumns: () -> Unit,
    onSort: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LayoutToggle(layout = layout, grid = grid, onCycle = onCycleLayout)
        if (grid) {
            ColumnsPill(columns = columns, onCycle = onCycleColumns)
        }
        Spacer(Modifier.weight(1f))
        SortSelector(sort = sort, onSelect = onSort)
    }
}

/**
 * Layout toggle: shows the icon of the EFFECTIVE mode; a tiny badge (the
 * first letter of the localized "Auto" label) marks the auto preference.
 * Click cycles auto → grid → list → auto.
 */
@Composable
private fun LayoutToggle(layout: String, grid: Boolean, onCycle: () -> Unit) {
    val autoBadge = stringResource(UiR.string.search_layout_auto).take(1)
    Box {
        IconButton(onClick = onCycle) {
            Icon(
                imageVector = if (grid) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                contentDescription = stringResource(UiR.string.cd_search_layout),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (layout == SEARCH_LAYOUT_AUTO) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = CircleShape,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.background),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = autoBadge,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Grid column stepper pill: "2× / 3× / 4×", cycles 2 → 3 → 4 → 2. */
@Composable
private fun ColumnsPill(columns: Int, onCycle: () -> Unit) {
    val columnsLabel = stringResource(UiR.string.search_columns_fmt, columns)
    Surface(
        onClick = onCycle,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .height(36.dp)
            .semantics { contentDescription = columnsLabel },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text(
                text = "$columns×",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

/** Sort order dropdown with a compact chip anchor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSelector(sort: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = remember {
        listOf(
            SEARCH_SORT_RELEVANCE to UiR.string.search_sort_relevance,
            SEARCH_SORT_RATING to UiR.string.search_sort_rating,
            SEARCH_SORT_DOWNLOADS to UiR.string.search_sort_downloads,
            SEARCH_SORT_NAME to UiR.string.search_sort_name,
        )
    }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .height(36.dp)
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(UiR.string.cd_search_sort),
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = sortLabel(sort),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    leadingIcon = {
                        if (value == sort) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(sort: String): String = when (sort) {
    SEARCH_SORT_RATING -> stringResource(UiR.string.search_sort_rating)
    SEARCH_SORT_DOWNLOADS -> stringResource(UiR.string.search_sort_downloads)
    SEARCH_SORT_NAME -> stringResource(UiR.string.search_sort_name)
    else -> stringResource(UiR.string.search_sort_relevance)
}

/** Scrollable session-only source filter chips ("All" + per-source). */
@Composable
private fun SourceFilterRow(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(
            SEARCH_SOURCE_FILTERS,
            key = { it ?: "all" },
            contentType = { "source-chip" },
        ) { source ->
            SourceFilterChip(
                label = sourceFilterLabel(source),
                selected = selected == source,
                onClick = { onSelect(source) },
            )
        }
    }
}

/**
 * 36dp filter chip. Brand names (Play / F-Droid / GitHub / GitLab) are
 * proper nouns kept as-is, exactly like [com.novastore.app.core.ui.components.SourceBadge].
 */
@Composable
private fun SourceFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.height(36.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun sourceFilterLabel(source: String?): String = when (source) {
    null -> stringResource(UiR.string.search_filter_all)
    SOURCE_PLAY -> "Play"
    SOURCE_FDROID -> "F-Droid"
    SOURCE_GITHUB -> "GitHub"
    SOURCE_GITLAB -> "GitLab"
    else -> source
}
