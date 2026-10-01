package com.novastore.app.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.AppCardRow
import com.novastore.app.core.ui.components.AppGridCell
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.NovaCategories

/** "See all" page of one category: every app, vertical scrolling, user's grid prefs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onBack: () -> Unit,
    onOpenAppDetails: (String) -> Unit,
    viewModel: CategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    // Grid ⇄ list toggle for this page (list rows show name, developer,
    // stars, size and the source store badge).
    val showList = state.list
    val title = when {
        state.key == "play:home" -> stringResource(UiR.string.home_shelf_home)
        PlayShelves.isShelf(state.key) -> PlayShelves.label(state.key)
        else -> NovaCategories.label(state.key)
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            total > 0 && last >= total - 12
        }
    }
    LaunchedEffect(nearEnd, state.canLoadMore) { if (nearEnd && state.canLoadMore) viewModel.loadMore() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setListMode(!showList) }) {
                        Icon(
                            imageVector = if (showList) Icons.Outlined.GridView else Icons.AutoMirrored.Filled.ViewList,
                            contentDescription = stringResource(UiR.string.home_layout_title),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                state.loading && state.apps.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.apps.isEmpty() -> Box(Modifier.align(Alignment.Center)) {
                    EmptyState(
                        icon = Icons.Filled.Apps,
                        title = stringResource(UiR.string.home_catalog_empty_title),
                        description = stringResource(UiR.string.home_catalog_empty_desc),
                    )
                }
                else -> {
                    val columns = if (showList) 1 else state.columns
                    val spacing = if (columns >= 4) 8.dp else 12.dp
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(state.apps, key = { it.packageName }, contentType = { "app" }) { app ->
                            if (showList) {
                                AppCardRow(app = app, onClick = { onOpenAppDetails(app.packageName) })
                            } else {
                                AppGridCell(
                                    app = app,
                                    onClick = { onOpenAppDetails(app.packageName) },
                                    iconSize = state.iconSize.dp.dp,
                                    columns = columns,
                                    spacing = spacing,
                                )
                            }
                        }
                        if (state.loading) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
