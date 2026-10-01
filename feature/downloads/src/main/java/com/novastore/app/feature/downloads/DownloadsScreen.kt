package com.novastore.app.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.novastore.app.core.model.DownloadState
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.InstalledAppIcon
import com.novastore.app.core.ui.components.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit = {},
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val autoCleanDays by viewModel.autoCleanDays.collectAsStateWithLifecycle()
    val hasAnything = state.active.isNotEmpty() || state.queued.isNotEmpty() ||
        state.completed.isNotEmpty() || state.failed.isNotEmpty()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(UiR.string.downloads_title)) },
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
        if (!hasAnything) {
            EmptyState(
                modifier = Modifier.padding(padding),
                icon = Icons.Filled.Download,
                title = stringResource(UiR.string.downloads_empty_title),
                description = stringResource(UiR.string.downloads_empty_desc),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Housekeeping selector first; pointless without anything finished.
                if (state.completed.isNotEmpty() || state.failed.isNotEmpty()) {
                    item(key = "autoclean") {
                        AutoCleanRow(
                            days = autoCleanDays,
                            onSelect = { viewModel.setAutoCleanDays(it) },
                        )
                    }
                }
                if (state.active.isNotEmpty()) {
                    item(key = "active-header") { SectionHeader(stringResource(UiR.string.downloads_section_active)) }
                    items(state.active, key = { it.taskId }) { task ->
                        DownloadRow(task = task, active = true, viewModel = viewModel)
                    }
                }
                if (state.queued.isNotEmpty()) {
                    item(key = "queued-header") { SectionHeader(stringResource(UiR.string.downloads_section_queued)) }
                    items(state.queued, key = { it.taskId }) { task ->
                        DownloadRow(task = task, active = false, viewModel = viewModel)
                    }
                }
                if (state.completed.isNotEmpty()) {
                    item(key = "completed-header") {
                        SectionHeader(
                            title = stringResource(UiR.string.downloads_section_completed),
                            trailing = {
                                TextButton(
                                    onClick = { viewModel.clearCompleted() },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Text(stringResource(UiR.string.downloads_clear_completed))
                                }
                            },
                        )
                    }
                    items(state.completed, key = { it.taskId }) { task ->
                        DownloadRow(task = task, active = false, viewModel = viewModel)
                    }
                }
                if (state.failed.isNotEmpty()) {
                    item(key = "failed-header") {
                        SectionHeader(
                            title = stringResource(UiR.string.downloads_section_failed),
                            trailing = {
                                TextButton(
                                    onClick = { viewModel.clearFailed() },
                                    modifier = Modifier.height(36.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                ) {
                                    Text(stringResource(UiR.string.downloads_clear_failed))
                                }
                            },
                        )
                    }
                    items(state.failed, key = { it.taskId }) { task ->
                        DownloadRow(task = task, active = false, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** Compact auto-clean selector row: label + dropdown chip (Off / 1 / 3 / 7 / 14 days). */
@Composable
private fun AutoCleanRow(days: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(UiR.string.downloads_autoclean),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                Surface(
                    onClick = { expanded = true },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(36.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = autoCleanLabel(days),
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    listOf(0, 1, 3, 7, 14).forEach { option ->
                        DropdownMenuItem(
                            text = { Text(autoCleanLabel(option)) },
                            leadingIcon = if (option == days) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun autoCleanLabel(days: Int): String =
    if (days <= 0) {
        stringResource(UiR.string.downloads_autoclean_off)
    } else {
        stringResource(UiR.string.downloads_autoclean_days_fmt, days)
    }

@Composable
private fun DownloadRow(task: DownloadTaskInfo, active: Boolean, viewModel: DownloadsViewModel) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                InstalledAppIcon(
                    packageName = task.packageName,
                    appName = task.appName,
                    size = 48.dp,
                    shape = RoundedCornerShape(14.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        text = buildString {
                            append(task.versionName ?: "")
                            append(" · ")
                            append(formatFileSize(task.totalBytes))
                            if (task.state == DownloadState.PAUSED) {
                                append(" · ")
                                append(stringResource(UiR.string.updates_paused))
                            }
                            if (task.state == DownloadState.FAILED) {
                                append(" · ")
                                append(stringResource(UiR.string.downloads_failed_short))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when (task.state) {
                    DownloadState.DOWNLOADING, DownloadState.VERIFYING ->
                        IconButton(onClick = { viewModel.pause(task.packageName) }) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = stringResource(UiR.string.downloads_pause),
                            )
                        }
                    DownloadState.PAUSED, DownloadState.QUEUED ->
                        IconButton(onClick = { viewModel.resume(task.packageName) }) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(UiR.string.downloads_resume),
                            )
                        }
                    DownloadState.FAILED ->
                        IconButton(onClick = { viewModel.retry(task.packageName) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(UiR.string.action_retry),
                            )
                        }
                    DownloadState.CANCELLED ->
                        IconButton(onClick = { viewModel.retry(task.packageName) }) {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = stringResource(UiR.string.action_retry),
                            )
                        }
                    DownloadState.COMPLETED -> Unit
                }

                if (task.state != DownloadState.COMPLETED) {
                    IconButton(onClick = { viewModel.cancel(task.packageName) }) {
                        Icon(
                            Icons.Filled.Cancel,
                            contentDescription = stringResource(UiR.string.downloads_cancel),
                        )
                    }
                }
            }

            if (task.state == DownloadState.DOWNLOADING || task.state == DownloadState.PAUSED || task.state == DownloadState.VERIFYING) {
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = buildString {
                            append("${task.progressPercent}%")
                            append(" · ${formatFileSize(task.downloadedBytes)}")
                            val speed = task.speedBytesPerSec
                            if (speed != null) {
                                append(" · ${formatFileSize(speed)}/s")
                            }
                            val eta = task.etaMillis
                            if (eta != null && eta > 0) {
                                append(" · ETA ${eta / 1000}s")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (task.lastError != null) {
                Text(
                    text = task.lastError ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
