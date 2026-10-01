package com.novastore.app.feature.updates

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.core.model.DownloadState
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.InstalledAppIcon
import com.novastore.app.core.model.UpdateConfidence
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.ErrorState
import com.novastore.app.core.ui.components.LoadingState
import com.novastore.app.core.ui.components.formatFileSize
import com.novastore.app.core.ui.components.novaAccentBrush
import com.novastore.app.core.ui.theme.OnEmerald

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    onBack: (() -> Unit)? = null,
    onOpenAppDetails: (String) -> Unit,
    onOpenInstalled: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenIgnored: () -> Unit = {},
    startUpdateAll: Boolean = false,
    onUpdateAllStarted: () -> Unit = {},
    viewModel: UpdatesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val alternatives by viewModel.alternatives.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(startUpdateAll) {
        if (startUpdateAll) {
            onUpdateAllStarted()
            viewModel.updateAll()
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    var replaceTarget by remember { mutableStateOf<UpdateCandidate?>(null) }

    replaceTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { replaceTarget = null },
            title = { Text(stringResource(UiR.string.updates_modified_title)) },
            text = { Text(stringResource(UiR.string.updates_modified_body, target.installed.appName)) },
            confirmButton = {
                TextButton(onClick = {
                    replaceTarget = null
                    viewModel.uninstallModified(target.installed.packageName)
                    onOpenAppDetails(target.installed.packageName)
                }) { Text(stringResource(UiR.string.updates_modified_replace), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    replaceTarget = null
                    viewModel.ignoreVersion(target.installed.packageName, target.available.versionCode)
                }) { Text(stringResource(UiR.string.updates_modified_hide)) }
            },
        )
    }

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
                            text = stringResource(UiR.string.updates_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        if (state.updates.isNotEmpty()) {
                            Text(
                                text = stringResource(
                                    UiR.string.updates_pending,
                                    state.updates.size,
                                    formatFileSize(state.aggregateSizeBytes),
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDownloads) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = stringResource(UiR.string.cd_downloads),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.scan() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(UiR.string.cd_check_updates),
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
            when {
                state.loading -> LoadingState(Modifier.fillMaxWidth(), stringResource(UiR.string.updates_loading))
                state.scanning && state.updates.isEmpty() ->
                    LoadingState(Modifier.fillMaxWidth(), stringResource(UiR.string.updates_scanning))
                state.updates.isEmpty() && state.lastError == null && state.ignoredPackages.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Filled.SystemUpdate,
                        title = stringResource(UiR.string.updates_empty_title),
                        description = stringResource(UiR.string.updates_empty_desc),
                        actionLabel = stringResource(UiR.string.updates_empty_action),
                        onAction = { viewModel.scan() },
                    )
                }
                else -> Column(modifier = Modifier.fillMaxSize()) {
                if (state.scanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.lastError != null) {
                        item(key = "error") {
                            ErrorState(
                                description = state.lastError ?: "",
                                retryLabel = stringResource(UiR.string.action_retry),
                                onRetry = { viewModel.scan() },
                            )
                        }
                    }
                    if (state.notice != null) {
                        item(key = "notice") {
                            NoticeBanner(
                                text = noticeText(state.notice ?: UpdateNotice.UpdatedLatest("")),
                                onDismiss = viewModel::dismissNotice,
                            )
                        }
                    }
                    // Source chips: All · Google Play · F-Droid · IzzyOnDroid …
                    if (state.sourceCounts.size > 1 || state.sourceFilter != null) {
                        item(key = "source-chips") {
                            androidx.compose.foundation.lazy.LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item {
                                    androidx.compose.material3.FilterChip(
                                        selected = state.sourceFilter == null,
                                        onClick = { viewModel.setSourceFilter(null) },
                                        label = { Text(stringResource(UiR.string.updates_source_all, state.totalUpdates)) },
                                    )
                                }
                                items(state.sourceCounts, key = { it.first }) { (source, count) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = state.sourceFilter == source,
                                        onClick = { viewModel.setSourceFilter(source) },
                                        label = { Text("${sourceLabel(source, state.sourceNames)} · $count") },
                                    )
                                }
                            }
                        }
                    }
                    val deliverable = state.updates.count { it.confidence == UpdateConfidence.EXACT }
                    if (deliverable > 0) {
                        item(key = "update-all") {
                            UpdateAllCard(
                                count = deliverable,
                                totalSize = state.aggregateSizeBytes,
                                busy = state.updateAllInProgress,
                                onUpdateAll = { viewModel.updateAll() },
                            )
                        }
                    }
                    items(state.updates, key = { it.installed.packageName }) { candidate ->
                        val openDetails = { onOpenAppDetails(candidate.installed.packageName) }
                        UpdateRow(
                            sourceName = sourceLabel(candidate.source, state.sourceNames),
                            alternatives = alternatives[candidate.installed.packageName],
                            onMenuOpened = { viewModel.loadAlternatives(candidate) },
                            onChooseSource = { viewModel.chooseSource(candidate, it) },
                            sourceNames = state.sourceNames,
                            candidate = candidate,
                            task = state.downloads[candidate.installed.packageName],
                            updating = candidate.installed.packageName in state.updatingPackages,
                            onUpdate = {
                                when (candidate.confidence) {
                                    UpdateConfidence.EXACT -> viewModel.updateApp(candidate.installed.packageName)
                                    UpdateConfidence.PAID -> openInPlay(context, candidate.installed.packageName)
                                    UpdateConfidence.FOREIGN_SIGNATURE -> replaceTarget = candidate
                                    UpdateConfidence.DISCOVERY -> openDetails()
                                }
                            },
                            onIgnoreVersion = {
                                viewModel.ignoreVersion(
                                    candidate.installed.packageName,
                                    candidate.available.versionCode,
                                )
                            },
                            onIgnoreAll = { viewModel.ignoreAllVersions(candidate.installed.packageName) },
                            onClick = openDetails,
                        )
                    }

                    if (state.ignoredPackages.isNotEmpty() || state.hasVersionScopedIgnores) {
                        item(key = "ignored-entry") {
                            Surface(
                                onClick = onOpenIgnored,
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(Icons.Filled.VisibilityOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        text = stringResource(UiR.string.updates_ignored_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = state.ignoredPackages.size.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Icon(
                                        androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }

                    item(key = "footer") {
                        TextButton(
                            onClick = onOpenInstalled,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        ) {
                            Text(
                                stringResource(UiR.string.updates_installed_apps),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                }
            }
        }
    }

    if (state.updateAllSummary != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSummary() },
            title = { Text(stringResource(UiR.string.updates_summary_title)) },
            text = {
                val summary = state.updateAllSummary
                Text(
                    buildString {
                        appendLine(stringResource(UiR.string.updates_summary_processed, summary?.total ?: 0))
                        appendLine(stringResource(UiR.string.updates_summary_installed, summary?.installed ?: 0))
                        appendLine(stringResource(UiR.string.updates_summary_awaiting, summary?.awaitingConfirmation ?: 0))
                        appendLine(stringResource(UiR.string.updates_summary_failed, summary?.failed ?: 0))
                        (summary?.results ?: emptyList())
                            .filter { it.outcome == com.novastore.app.domain.usecase.UpdateAllUseCase.Outcome.FAILED }
                            .take(3)
                            .forEach { item ->
                                appendLine("• ${item.appName}: ${item.error?.userMessage ?: ""}")
                            }
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissSummary() }) { Text(stringResource(UiR.string.action_ok)) }
            },
        )
    }
}

/** Translates an [UpdateNotice] into a localized message. */
@Composable
private fun noticeText(notice: UpdateNotice): String = when (notice) {
    is UpdateNotice.Updated -> notice.versionName
        ?.let { stringResource(UiR.string.updates_notice_updated, notice.appName, it) }
        ?: stringResource(UiR.string.updates_notice_updated_latest, notice.appName)
    is UpdateNotice.UpdatedLatest -> stringResource(UiR.string.updates_notice_updated_latest, notice.appName)
    is UpdateNotice.NeedsConfirmation -> stringResource(UiR.string.updates_notice_confirmation, notice.appName)
    is UpdateNotice.Failed -> stringResource(UiR.string.updates_notice_failed, notice.appName)
    is UpdateNotice.Cancelled -> stringResource(UiR.string.updates_notice_cancelled, notice.appName)
    is UpdateNotice.MirrorRequired -> stringResource(UiR.string.updates_notice_mirror, notice.appName)
    is UpdateNotice.FailedReason -> "${notice.appName}: ${notice.reason}"
    is UpdateNotice.ForeignSignature -> stringResource(UiR.string.updates_notice_modified, notice.appName)
}

/** Opens the app in the Play Store app, or its web page when there is none. */
private fun openInPlay(context: android.content.Context, packageName: String) {
    val market = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("market://details?id=$packageName"),
    ).setPackage("com.android.vending").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    val web = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName"),
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(market) }.onFailure { runCatching { context.startActivity(web) } }
}

@Composable
private fun UpdateAllCard(
    count: Int,
    totalSize: Long,
    busy: Boolean,
    onUpdateAll: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(novaAccentBrush())
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = OnEmerald,
            modifier = Modifier.size(30.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (count == 1) {
                    stringResource(UiR.string.home_updates_banner_one)
                } else {
                    stringResource(UiR.string.updates_count_many, count)
                },
                style = MaterialTheme.typography.titleMedium,
                color = OnEmerald,
            )
            Text(
                text = stringResource(UiR.string.updates_total_size, formatFileSize(totalSize)),
                style = MaterialTheme.typography.bodySmall,
                color = OnEmerald.copy(alpha = 0.85f),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = OnEmerald,
                strokeWidth = 3.dp,
            )
        } else {
            Surface(
                color = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                shape = RoundedCornerShape(14.dp),
                onClick = onUpdateAll,
            ) {
                Text(
                    text = stringResource(UiR.string.updates_update_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun UpdateRow(
    sourceName: String,
    alternatives: List<com.novastore.app.core.model.AppVersion>?,
    onMenuOpened: () -> Unit,
    onChooseSource: (com.novastore.app.core.model.AppVersion) -> Unit,
    sourceNames: Map<String, String>,
    candidate: UpdateCandidate,
    task: DownloadTaskInfo?,
    updating: Boolean,
    onUpdate: () -> Unit,
    onIgnoreVersion: () -> Unit,
    onIgnoreAll: () -> Unit,
    onClick: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isDiscovery = candidate.confidence == UpdateConfidence.DISCOVERY
    val isPaid = candidate.confidence == UpdateConfidence.PAID
    val isModified = candidate.confidence == UpdateConfidence.FOREIGN_SIGNATURE
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ONE compact, vertically-centered row: icon | name/version/size
            // column (the ONLY flexible element — buttons can never wrap to a
            // second line on narrow screens) | Update pill | 3-dot menu.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                InstalledAppIcon(
                    packageName = candidate.installed.packageName,
                    appName = candidate.installed.appName,
                    size = 52.dp,
                    shape = RoundedCornerShape(14.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = candidate.installed.appName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isDiscovery) {
                            val date = candidate.available.addedAt?.let {
                                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it))
                            }
                            if (date != null) {
                                stringResource(UiR.string.updates_discovery_line, date)
                            } else {
                                stringResource(UiR.string.updates_discovery_no_date)
                            }
                        } else {
                            "${candidate.installed.versionName ?: "?"} → ${candidate.available.versionName ?: "?"}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (isDiscovery) {
                            stringResource(UiR.string.updates_discovery_state)
                        } else if (isPaid) {
                            stringResource(UiR.string.updates_state_paid)
                        } else if (isModified) {
                            stringResource(UiR.string.updates_state_modified)
                        } else {
                            buildString {
                                // Source first: where this update comes from.
                                append(sourceName)
                                append(" · ")
                                append(formatFileSize(candidate.available.size))
                                append(" · ")
                                append(
                                    when (task?.state) {
                                        DownloadState.DOWNLOADING -> stringResource(UiR.string.updates_downloading)
                                        DownloadState.VERIFYING -> stringResource(UiR.string.updates_verifying)
                                        DownloadState.PAUSED -> stringResource(UiR.string.updates_paused)
                                        else -> stringResource(UiR.string.updates_ready)
                                    },
                                )
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                UpdateButton(
                    task = task,
                    updating = updating,
                    onUpdate = onUpdate,
                    isDiscovery = isDiscovery,
                    labelRes = when {
                        isPaid -> UiR.string.updates_action_open_play
                        isModified -> UiR.string.updates_action_replace
                        isDiscovery -> UiR.string.updates_check
                        else -> UiR.string.updates_update
                    },
                    secondary = isDiscovery || isPaid || isModified,
                )
                Box {
                    IconButton(
                        onClick = {
                            menuExpanded = true
                            onMenuOpened()
                        },
                        modifier = Modifier.size(42.dp),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(UiR.string.cd_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        // Other repositories with an installable newer build.
                        if (!alternatives.isNullOrEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(UiR.string.updates_source_choose),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                enabled = false,
                                onClick = {},
                            )
                            alternatives.forEach { version ->
                                DropdownMenuItem(
                                    text = { Text("${sourceLabel(version.source, sourceNames)} · ${version.versionName ?: version.versionCode}") },
                                    onClick = {
                                        menuExpanded = false
                                        onChooseSource(version)
                                    },
                                )
                            }
                            androidx.compose.material3.HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.updates_ignore_version)) },
                            onClick = {
                                menuExpanded = false
                                onIgnoreVersion()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(UiR.string.updates_ignore_all)) },
                            onClick = {
                                menuExpanded = false
                                onIgnoreAll()
                            },
                        )
                    }
                }
            }

            val downloading = task?.state == DownloadState.DOWNLOADING || task?.state == DownloadState.VERIFYING
            if (downloading && task != null) {
                LinearProgressIndicator(
                    progress = { task.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            } else if (updating) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
    }
}

/** Small bright "Update" pill; % / spinner while working. */
@Composable
private fun UpdateButton(
    task: DownloadTaskInfo?,
    updating: Boolean,
    onUpdate: () -> Unit,
    isDiscovery: Boolean = false,
    labelRes: Int = UiR.string.updates_update,
    secondary: Boolean = isDiscovery,
) {
    val downloading = task?.state == DownloadState.DOWNLOADING || task?.state == DownloadState.VERIFYING
    when {
        downloading -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(
                text = "${task?.progressPercent ?: 0}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        updating -> CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        else -> Surface(
            color = if (secondary) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            shape = RoundedCornerShape(14.dp),
            onClick = onUpdate,
        ) {
            Text(
                text = stringResource(labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (secondary) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}



/** Readable name of an update source id. */
@Composable
private fun sourceLabel(source: String, names: Map<String, String>): String = when (source.lowercase()) {
    "play" -> "Google Play"
    "apkpure" -> "APKPure"
    "apkcombo" -> "APKCombo"
    "github" -> "GitHub"
    "gitlab" -> "GitLab"
    else -> names[source] ?: source.replaceFirstChar { it.uppercase() }
}
