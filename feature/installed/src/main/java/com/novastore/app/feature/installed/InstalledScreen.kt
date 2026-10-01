package com.novastore.app.feature.installed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.ErrorState
import com.novastore.app.core.ui.components.InstalledAppIcon
import com.novastore.app.core.ui.components.LoadingState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledScreen(
    onBack: (() -> Unit)? = null,
    onOpenAppDetails: (String) -> Unit,
    viewModel: InstalledViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var sortMenuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(UiR.string.cd_back),
                            )
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = stringResource(UiR.string.installed_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = stringResource(UiR.string.settings_repo_apps, state.apps.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(UiR.string.installed_sort),
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            InstalledSort.entries.forEach { sort ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                when (sort) {
                                                    InstalledSort.NAME_ASC -> UiR.string.installed_sort_name
                                                    InstalledSort.LAST_UPDATED -> UiR.string.installed_sort_recent
                                                    InstalledSort.SIZE -> UiR.string.installed_sort_name
                                                },
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        if (state.sort == sort) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        sortMenuOpen = false
                                    },
                                )
                            }
                            androidx.compose.material3.HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(UiR.string.installed_show_system)) },
                                leadingIcon = {
                                    if (state.showSystem) {
                                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    viewModel.setShowSystem(!state.showSystem)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(UiR.string.cd_refresh),
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
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(UiR.string.installed_search_hint)) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            when {
                state.loading -> LoadingState(Modifier.fillMaxWidth(), stringResource(UiR.string.updates_scanning))
                state.lastError != null -> ErrorState(
                    modifier = Modifier.fillMaxWidth(),
                    description = state.lastError ?: "",
                    retryLabel = stringResource(UiR.string.action_retry),
                    onRetry = { viewModel.refresh() },
                )
                state.apps.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.Apps,
                        title = stringResource(UiR.string.installed_empty_title),
                        description = stringResource(UiR.string.installed_empty_desc),
                    )
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.apps, key = { it.packageName }) { app ->
                        InstalledAppRow(
                            app = app,
                            hasUpdate = app.packageName in state.updatingPackages,
                            onClick = { onOpenAppDetails(app.packageName) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstalledAppRow(app: InstalledApp, hasUpdate: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InstalledAppIcon(
                packageName = app.packageName,
                appName = app.appName,
                size = 52.dp,
                shape = RoundedCornerShape(14.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = app.appName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${app.versionName ?: "?"} · ${installerLabel(app)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        UiR.string.settings_repo_synced,
                        DateFormat.getDateInstance().format(Date(app.lastUpdateTime)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hasUpdate) {
                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(
                        Icons.Filled.SystemUpdateAlt,
                        contentDescription = stringResource(UiR.string.updates_title),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/** Human label for where an app was installed from. */
@Composable
private fun installerLabel(app: InstalledApp): String = when (app.installerSource?.lowercase()) {
    "com.android.vending" -> stringResource(UiR.string.installed_chip_play)
    "org.fdroid.fdroid" -> stringResource(UiR.string.installed_chip_fdroid)
    null -> if (app.isSystemApp) "system" else stringResource(UiR.string.installed_chip_other)
    else -> (app.installerSource?.substringAfterLast('.') ?: "installer")
}
