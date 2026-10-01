package com.novastore.app.feature.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.novastore.app.core.model.AccentPalette
import com.novastore.app.core.model.AppLanguage
import com.novastore.app.core.model.DeviceProfile
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.RepositoryConfig
import com.novastore.app.core.model.RootAccessState
import com.novastore.app.core.model.ThemeMode
import com.novastore.app.core.model.UpdateSchedule
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.SourceBadge
import com.novastore.app.core.ui.theme.AccentPalettes
import com.novastore.app.core.ui.theme.OnEmerald
import com.novastore.app.domain.repository.AccountState
import java.text.DateFormat
import java.util.Date

/**
 * Settings, v4: compact grouped sections with the header OUTSIDE each card.
 * Sections: Appearance · Sources · Updates · Downloads · Account · Advanced · About.
 * Rows stay within ~56–64dp while keeping 44dp+ touch targets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAccount: () -> Unit,
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showAddRepository by remember { mutableStateOf(false) }
    var showSessionProvider by remember { mutableStateOf(false) }
    var fdroidExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.updateSettings { it.copy(notificationsEnabled = granted) }
    }

    fun onNotificationsToggled(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val alreadyGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (alreadyGranted) {
                viewModel.updateSettings { it.copy(notificationsEnabled = true) }
            } else {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            viewModel.updateSettings { it.copy(notificationsEnabled = enabled) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(UiR.string.settings_title)) },
                actions = {
                    IconButton(onClick = { viewModel.refreshRepositories() }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(UiR.string.settings_refresh_all),
                        )
                    }
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.error != null) {
                InfoBanner(
                    text = state.error ?: "",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onDismiss = viewModel::dismissError,
                )
            }
            if (state.notice != null) {
                InfoBanner(
                    text = state.notice ?: "",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                    onDismiss = viewModel::dismissNotice,
                )
            }

            // ----------------------------------------------------------
            // Account (first: the home screen's settings icon lands here)
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_play_account)) {
                AccountStateRow(
                    accountState = state.accountState,
                    onOpenAccount = onOpenAccount,
                )
                DeviceProfileDropdown(
                    profiles = state.deviceProfiles,
                    selectedFile = state.selectedDeviceProfile,
                    onSelect = viewModel::setPlayDeviceProfile,
                )
                SwitchRow(
                    label = stringResource(UiR.string.settings_play_updates),
                    description = stringResource(UiR.string.settings_play_updates_desc),
                    checked = state.playUpdatesEnabled,
                    onChecked = viewModel::setPlayUpdatesEnabled,
                )
            }

            // ----------------------------------------------------------
            // Appearance
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_appearance)) {
                val searchPinned by viewModel.homeSearchPinned.collectAsStateWithLifecycle()
                SwitchRow(
                    label = stringResource(UiR.string.settings_search_pinned),
                    description = stringResource(UiR.string.settings_search_pinned_desc),
                    checked = searchPinned,
                    onChecked = viewModel::setHomeSearchPinned,
                )
                RowDivider()
                LabelRow(stringResource(UiR.string.settings_theme))
                ChoiceChipGrid(
                    items = ThemeMode.entries.map { mode ->
                        mode to stringResource(
                            when (mode) {
                                ThemeMode.SYSTEM -> UiR.string.theme_system
                                ThemeMode.LIGHT -> UiR.string.theme_light
                                ThemeMode.DARK -> UiR.string.theme_dark
                                ThemeMode.AMOLED -> UiR.string.theme_amoled
                            },
                        )
                    },
                    selected = state.themeMode,
                    onSelect = { viewModel.setThemeMode(it) },
                )
                RowDivider()
                LabelRow(stringResource(UiR.string.settings_accent))
                AccentSwatchRow(
                    selected = state.accentPalette,
                    onSelect = { viewModel.setAccentPalette(it) },
                )
                RowDivider()
                LabelRow(stringResource(UiR.string.settings_language))
                ChoiceChipGrid(
                    items = AppLanguage.entries.map { it to it.nativeName },
                    selected = state.appLanguage,
                    onSelect = { viewModel.setAppLanguage(it) },
                )
            }

            // ----------------------------------------------------------
            // Sources — the Nova anonymous access tiers
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_sources)) {
                SwitchRow(
                    icon = Icons.Filled.CloudDownload,
                    label = stringResource(UiR.string.sources_play_anonymous),
                    description = stringResource(UiR.string.sources_play_anonymous_desc),
                    checked = state.anonymousPlayEnabled,
                    onChecked = viewModel::setAnonymousPlayEnabled,
                )
                SwitchRow(
                    icon = Icons.Filled.Language,
                    label = stringResource(UiR.string.sources_web_catalog),
                    description = stringResource(UiR.string.sources_web_catalog_desc),
                    checked = state.playWebCatalogEnabled,
                    onChecked = viewModel::setPlayWebCatalogEnabled,
                )
                SwitchRow(
                    icon = Icons.Filled.CloudDownload,
                    label = stringResource(UiR.string.sources_mirror),
                    description = stringResource(UiR.string.sources_mirror_desc),
                    checked = state.apkPureMirrorEnabled,
                    onChecked = viewModel::setApkPureMirrorEnabled,
                )
                SwitchRow(
                    icon = Icons.Filled.CloudDownload,
                    label = stringResource(UiR.string.sources_mirror_combo),
                    description = stringResource(UiR.string.sources_mirror_combo_desc),
                    checked = state.apkComboMirrorEnabled,
                    onChecked = viewModel::setApkComboMirrorEnabled,
                )
                SwitchRow(
                    icon = Icons.Filled.Code,
                    label = stringResource(UiR.string.sources_github),
                    description = stringResource(UiR.string.sources_github_desc),
                    checked = state.githubCatalogEnabled,
                    onChecked = viewModel::setGithubCatalogEnabled,
                )
                SwitchRow(
                    icon = Icons.Filled.Code,
                    label = stringResource(UiR.string.sources_gitlab),
                    description = stringResource(UiR.string.sources_gitlab_desc),
                    checked = state.gitlabCatalogEnabled,
                    onChecked = viewModel::setGitlabCatalogEnabled,
                )
                RowDivider()
                // F-Droid repositories: opens the repositories manager.
                SourceNavRow(
                    icon = Icons.Filled.Android,
                    label = stringResource(UiR.string.sources_fdroid_repos),
                    description = stringResource(UiR.string.sources_fdroid_repos_desc),
                    expanded = fdroidExpanded,
                    onClick = { fdroidExpanded = !fdroidExpanded },
                )
                if (fdroidExpanded) {
                    RepositoriesManager(
                        state = state,
                        onToggle = { id, enabled -> viewModel.setRepositoryEnabled(id, enabled) },
                        onRefresh = { viewModel.refreshRepository(it) },
                        onRemove = { viewModel.removeRepository(it) },
                        onAdd = { showAddRepository = true },
                        onRefreshAll = { viewModel.refreshRepositories() },
                    )
                }
                RowDivider()
                // Store links (Play, market://, F-Droid) → Nova Store by default.
                SourceNavRow(
                    icon = Icons.Filled.Link,
                    label = stringResource(UiR.string.settings_open_links),
                    description = stringResource(UiR.string.settings_open_links_desc),
                    expanded = false,
                    onClick = {
                        val pkg = Uri.parse("package:" + context.packageName)
                        val byDefault = if (android.os.Build.VERSION.SDK_INT >= 31) {
                            Intent(android.provider.Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
                        } else {
                            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
                        }
                        runCatching { context.startActivity(byDefault) }.onFailure {
                            runCatching {
                                context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg))
                            }
                        }
                    },
                )
                RowDivider()
                // Optional advanced session provider endpoint.
                SourceNavRow(
                    icon = Icons.Filled.Link,
                    label = stringResource(UiR.string.sources_session_provider),
                    description = stringResource(
                        if (state.tokenDispenserUrl.isBlank()) {
                            UiR.string.session_provider_status_off
                        } else {
                            UiR.string.session_provider_status_on
                        },
                    ),
                    expanded = false,
                    onClick = { showSessionProvider = true },
                )
            }

            // ----------------------------------------------------------
            // Updates
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_updates_section)) {
                LabelRow(stringResource(UiR.string.settings_check_updates))
                ChoiceChipGrid(
                    items = UpdateSchedule.entries.map { schedule ->
                        schedule to stringResource(
                            when (schedule) {
                                UpdateSchedule.IMMEDIATELY -> UiR.string.settings_schedule_immediately
                                UpdateSchedule.DAILY -> UiR.string.settings_schedule_daily
                                UpdateSchedule.WEEKLY -> UiR.string.settings_schedule_weekly
                            },
                        )
                    },
                    selected = state.settings.schedule,
                    onSelect = { schedule -> viewModel.updateSettings { it.copy(schedule = schedule) } },
                )
                RowDivider()
                SwitchRow(
                    label = stringResource(UiR.string.settings_auto_updates),
                    description = stringResource(UiR.string.settings_auto_updates_desc),
                    checked = state.settings.automaticUpdates,
                    onChecked = { enabled -> viewModel.updateSettings { it.copy(automaticUpdates = enabled) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ToggleTile(
                        label = stringResource(UiR.string.settings_wifi_only),
                        description = stringResource(UiR.string.settings_wifi_only_desc),
                        checked = state.settings.wifiOnly,
                        onChecked = { enabled -> viewModel.updateSettings { it.copy(wifiOnly = enabled) } },
                        modifier = Modifier.weight(1f),
                    )
                    ToggleTile(
                        label = stringResource(UiR.string.settings_charging_only),
                        description = stringResource(UiR.string.settings_charging_only_desc),
                        checked = state.settings.chargingOnly,
                        onChecked = { enabled -> viewModel.updateSettings { it.copy(chargingOnly = enabled) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ToggleTile(
                        label = stringResource(UiR.string.settings_confirm),
                        description = stringResource(UiR.string.settings_confirm_desc),
                        checked = state.settings.confirmBeforeInstallation,
                        onChecked = { enabled -> viewModel.updateSettings { it.copy(confirmBeforeInstallation = enabled) } },
                        modifier = Modifier.weight(1f),
                    )
                    ToggleTile(
                        label = stringResource(UiR.string.settings_notifications),
                        description = stringResource(UiR.string.settings_notifications_desc),
                        checked = state.settings.notificationsEnabled,
                        onChecked = ::onNotificationsToggled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ----------------------------------------------------------
            // Downloads
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_downloads_section)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ToggleTile(
                        label = stringResource(UiR.string.settings_mobile_data),
                        description = stringResource(UiR.string.settings_mobile_data_desc),
                        checked = state.settings.mobileDataAllowed,
                        onChecked = { enabled -> viewModel.updateSettings { it.copy(mobileDataAllowed = enabled) } },
                        modifier = Modifier.weight(1f),
                    )
                    ToggleTile(
                        label = stringResource(UiR.string.settings_download_charging),
                        description = stringResource(UiR.string.settings_download_charging_desc),
                        checked = state.settings.downloadWhileCharging,
                        onChecked = { enabled -> viewModel.updateSettings { it.copy(downloadWhileCharging = enabled) } },
                        modifier = Modifier.weight(1f),
                    )
                }
                Column {
                    Text(
                        stringResource(UiR.string.settings_simultaneous, state.settings.maxConcurrentDownloads),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    androidx.compose.material3.Slider(
                        value = state.settings.maxConcurrentDownloads.toFloat(),
                        onValueChange = { value ->
                            viewModel.updateSettings { it.copy(maxConcurrentDownloads = value.toInt()) }
                        },
                        valueRange = 1f..4f,
                        steps = 2,
                    )
                }
            }

            // ----------------------------------------------------------
            // Storage & downloads (housekeeping)
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_storage_section)) {
                val autoCleanDays by viewModel.downloadsAutoCleanDays.collectAsStateWithLifecycle()
                AutoCleanDaysRow(
                    days = autoCleanDays,
                    onSelect = { viewModel.setDownloadsAutoCleanDays(it) },
                )
                AllFilesAccessRow()
            }

            // ----------------------------------------------------------
            // Advanced
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_advanced)) {
                InstallationModeDropdown(
                    selected = state.settings.installationMode,
                    onSelect = { mode -> viewModel.updateSettings { it.copy(installationMode = mode) } },
                )
                RootStatusSection(state, viewModel)
            }

            // ----------------------------------------------------------
            // About
            // ----------------------------------------------------------
            SettingsSection(title = stringResource(UiR.string.settings_about)) {
                AboutSection()
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddRepository) {
        AddRepositoryDialog(
            onDismiss = { showAddRepository = false },
            onConfirm = { name, url ->
                viewModel.addCustomRepository(name, url)
                showAddRepository = false
            },
        )
    }
    if (showSessionProvider) {
        SessionProviderDialog(
            initial = state.tokenDispenserUrl,
            onDismiss = { showSessionProvider = false },
            onSave = { url ->
                viewModel.setTokenDispenserUrl(url)
                showSessionProvider = false
            },
        )
    }
}

// ----------------------------------------------------------------------
// Section scaffolding
// ----------------------------------------------------------------------

/** Compact grouped section: header OUTSIDE the card, card with tight rows. */
@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 4.dp),
        )
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun LabelRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/** Compact switch row: ~56–64dp tall, optional leading icon, switch on the right. */
@Composable
private fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChecked, enabled = enabled)
    }
}

/** Half-width toggle tile used for side-by-side compact settings. */
@Composable
private fun ToggleTile(
    label: String,
    description: String?,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = { onChecked(!checked) },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Text(
                        description,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

/** Compact choice chips laid out two per row. */
@Composable
private fun <T> ChoiceChipGrid(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowItems.forEach { (value, label) ->
                    ChoiceChip(
                        label = label,
                        selected = value == selected,
                        onClick = { onSelect(value) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Single compact selectable chip (≥44dp touch target). */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
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
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One row of six gradient accent swatches (44dp touch targets). */
@Composable
private fun AccentSwatchRow(
    selected: AccentPalette,
    onSelect: (AccentPalette) -> Unit,
) {
    // 15 palettes: wrapping rows instead of one squeezed line.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AccentPalette.entries.forEach { palette ->
            AccentSwatch(
                palette = palette,
                selected = palette == selected,
                onClick = { onSelect(palette) },
            )
        }
    }
}

@Composable
private fun AccentSwatch(
    palette: AccentPalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = AccentPalettes.of(palette)
    Box(
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    Brush.horizontalGradient(listOf(accent.start, accent.end)),
                    CircleShape,
                )
                .then(
                    if (selected) {
                        Modifier.border(
                            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                            CircleShape,
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = OnEmerald,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Compact navigation row for a source sub-feature (chevron on the right). */
@Composable
private fun SourceNavRow(
    icon: ImageVector,
    label: String,
    description: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 90f else 0f),
            )
        }
    }
}

// ----------------------------------------------------------------------
// Storage & downloads housekeeping
// ----------------------------------------------------------------------

/** Value row for the completed-download auto-cleanup age (Off / 1 / 3 / 7 / 14 days). */
@Composable
private fun AutoCleanDaysRow(days: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(UiR.string.settings_autoclean),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(UiR.string.settings_autoclean_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Visual-only value chip; the whole row opens the dropdown.
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.height(36.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = autoCleanDaysLabel(days),
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
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(0, 1, 3, 7, 14).forEach { option ->
                DropdownMenuItem(
                    text = { Text(autoCleanDaysLabel(option)) },
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

@Composable
private fun autoCleanDaysLabel(days: Int): String =
    if (days <= 0) {
        stringResource(UiR.string.settings_autoclean_off)
    } else {
        stringResource(UiR.string.settings_autoclean_days_fmt, days)
    }

/** True when the system "All files access" toggle is on for Nova Store (API 30+). */
private fun allFilesAccessGranted(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

/**
 * Informational row for the MANAGE_EXTERNAL_STORAGE permission: the system
 * owns the state — tapping opens the system toggle, returning refreshes it.
 */
@Composable
private fun AllFilesAccessRow() {
    val context = LocalContext.current
    var allFilesGranted by remember { mutableStateOf(allFilesAccessGranted()) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        allFilesGranted = allFilesAccessGranted()
    }
    Surface(
        onClick = {
            val packageUri = Uri.parse("package:${context.packageName}")
            // NOTE: Settings.ACTION_MANAGE_APP_PERMISSION is NOT part of the
            // public SDK (system API) — the public per-app all-files screen is
            // ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION (API 30+).
            runCatching {
                launcher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri))
            }.onFailure {
                runCatching {
                    launcher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, packageUri))
                }
            }
        },
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(UiR.string.settings_all_files),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(UiR.string.settings_all_files_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(
                    if (allFilesGranted) {
                        UiR.string.settings_all_files_granted
                    } else {
                        UiR.string.settings_all_files_denied
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (allFilesGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

// ----------------------------------------------------------------------
// Account
// ----------------------------------------------------------------------

@Composable
private fun AccountStateRow(
    accountState: AccountState,
    onOpenAccount: () -> Unit,
) {
    Surface(
        onClick = onOpenAccount,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Filled.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (accountState) {
                        is AccountState.SignedIn -> accountState.email
                        AccountState.Anonymous -> stringResource(UiR.string.settings_fdroid_only)
                        AccountState.NotSignedIn -> stringResource(UiR.string.settings_not_signed_in)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (accountState) {
                        is AccountState.SignedIn -> stringResource(UiR.string.settings_account_manage)
                        AccountState.Anonymous -> stringResource(UiR.string.settings_account_anonymous_sub)
                        AccountState.NotSignedIn -> stringResource(UiR.string.settings_account_not_signed_sub)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceProfileDropdown(
    profiles: List<DeviceProfile>,
    selectedFile: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = profiles.firstOrNull { it.fileName == selectedFile }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected?.displayName ?: selectedFile.removeSuffix(".properties"),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(UiR.string.settings_device_profile)) },
            leadingIcon = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
            supportingText = { Text(stringResource(UiR.string.settings_device_profile_hint)) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.displayName) },
                    leadingIcon = {
                        if (profile.fileName == selectedFile) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        onSelect(profile.fileName)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallationModeDropdown(
    selected: InstallationMode,
    onSelect: (InstallationMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = stringResource(installationModeLabel(selected))
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(UiR.string.settings_installation)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            InstallationMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(installationModeLabel(mode))) },
                    leadingIcon = {
                        if (mode == selected) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun installationModeLabel(mode: InstallationMode): Int = when (mode) {
    InstallationMode.AUTOMATIC -> UiR.string.settings_mode_automatic
    InstallationMode.STANDARD -> UiR.string.settings_mode_standard
    InstallationMode.ROOT -> UiR.string.settings_mode_root
    InstallationMode.MANAGED -> UiR.string.settings_mode_managed
}

// ----------------------------------------------------------------------
// Repositories manager (inside the Sources section)
// ----------------------------------------------------------------------

@Composable
private fun RepositoriesManager(
    state: SettingsUiState,
    onToggle: (String, Boolean) -> Unit,
    onRefresh: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: () -> Unit,
    onRefreshAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val enabledCount = state.repositories.count { it.enabled }
        val totalApps = state.repositories.filter { it.enabled }.sumOf { state.appCounts[it.repositoryId] ?: 0 }
        Text(
            stringResource(UiR.string.settings_repos_enabled, enabledCount, state.repositories.size, totalApps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        state.repositories.forEach { repository ->
            RepositoryRow(
                repository = repository,
                appCount = state.appCounts[repository.repositoryId] ?: 0,
                loading = repository.repositoryId in state.refreshingIds,
                onToggle = { enabled -> onToggle(repository.repositoryId, enabled) },
                onRefresh = { onRefresh(repository.repositoryId) },
                onRemove = { onRemove(repository.repositoryId) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onAdd, enabled = !state.busy) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(UiR.string.settings_add_repository))
            }
            TextButton(onClick = onRefreshAll, enabled = !state.busy) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(UiR.string.settings_refresh_all))
            }
        }
    }
}

@Composable
private fun RepositoryRow(
    repository: RepositoryConfig,
    appCount: Int,
    loading: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        repository.name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = repository.baseUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                } else if (repository.enabled) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(UiR.string.cd_refresh))
                    }
                }
                Switch(checked = repository.enabled, onCheckedChange = onToggle, enabled = !loading)
                if (!repository.isBuiltIn) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(UiR.string.action_cancel))
                    }
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val status = when {
                    loading -> stringResource(UiR.string.settings_repo_loading)
                    !repository.enabled -> stringResource(UiR.string.settings_repo_disabled)
                    repository.lastRefreshError != null -> repository.lastRefreshError!!
                    repository.lastRefreshAt == null -> stringResource(UiR.string.settings_repo_not_loaded)
                    else -> stringResource(UiR.string.settings_repo_apps, appCount)
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (repository.enabled && repository.lastRefreshError != null && !loading) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                repository.lastRefreshAt?.let {
                    Text(
                        text = "· " + stringResource(
                            UiR.string.settings_repo_synced,
                            DateFormat.getDateInstance().format(Date(it)),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (repository.enabled) {
                    SourceBadge(source = repository.repositoryId, compact = true)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Root / banners / dialogs
// ----------------------------------------------------------------------

@Composable
private fun RootStatusSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(UiR.string.settings_root_installation), style = MaterialTheme.typography.bodyMedium)
        val statusText = when (state.rootState) {
            RootAccessState.UNAVAILABLE -> stringResource(UiR.string.settings_root_unavailable)
            RootAccessState.AVAILABLE -> stringResource(UiR.string.settings_root_available)
            RootAccessState.AUTHORIZATION_REQUIRED -> stringResource(UiR.string.settings_root_pending)
            RootAccessState.AUTHORIZED -> stringResource(UiR.string.settings_root_authorized)
            RootAccessState.DENIED -> stringResource(UiR.string.settings_root_denied)
            RootAccessState.REVOKED -> stringResource(UiR.string.settings_root_revoked)
            RootAccessState.ERROR -> stringResource(UiR.string.settings_root_error)
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.rootState != RootAccessState.AUTHORIZED) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.checkRoot() }, enabled = !state.busy) {
                    Text(
                        stringResource(UiR.string.settings_root_check),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                }
            }
        }
    }
}

@Composable
private fun InfoBanner(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    onDismiss: () -> Unit,
) {
    Surface(
        color = container,
        shape = RoundedCornerShape(14.dp),
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** Editor for the optional session provider (token dispenser) endpoint. */
@Composable
private fun SessionProviderDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.sources_session_provider)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(UiR.string.session_provider_label)) },
                    placeholder = { Text(stringResource(UiR.string.session_provider_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(UiR.string.session_provider_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(stringResource(UiR.string.action_save)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { draft = "" }) { Text(stringResource(UiR.string.action_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, url: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(UiR.string.settings_add_repository)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(UiR.string.settings_repo_name)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(UiR.string.settings_repo_url)) },
                    placeholder = { Text("https://example.com/fdroid/repo") },
                    supportingText = { Text(stringResource(UiR.string.settings_repo_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Filled.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        stringResource(UiR.string.settings_repo_trust),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, url) },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(UiR.string.settings_add_repository)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) }
        },
    )
}


/**
 * "About": the REAL installed version (read from the package, never a
 * hard-coded string that goes stale), what Nova Store uses, this device,
 * privacy and the license.
 */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    if (showLicense) LicenseDialog(onDismiss = { showLicense = false })
    val (versionName, versionCode) = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            (info.versionName ?: "?") to code
        }.getOrDefault("?" to 0L)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "Nova Store",
                style = MaterialTheme.typography.headlineSmall.copy(
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color(0xFF6965F1), androidx.compose.ui.graphics.Color(0xFFA556F7)),
                    ),
                ),
            )
            Text(
                stringResource(UiR.string.settings_about_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AboutRow(
        icon = Icons.Filled.Info,
        title = stringResource(UiR.string.settings_about_version_label),
        value = stringResource(UiR.string.settings_about_version_value, versionName, versionCode),
    )
    AboutRow(
        icon = Icons.Filled.CloudDownload,
        title = stringResource(UiR.string.settings_about_sources),
        value = stringResource(UiR.string.settings_about_sources_value),
    )
    AboutRow(
        icon = Icons.Filled.Android,
        title = stringResource(UiR.string.settings_about_device),
        value = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} " +
            "(API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}",
    )
    AboutRow(
        icon = Icons.Filled.Lock,
        title = stringResource(UiR.string.settings_about_privacy),
        value = stringResource(UiR.string.settings_privacy_note),
    )
    AboutRow(
        icon = Icons.Filled.Code,
        title = stringResource(UiR.string.settings_about_license),
        value = stringResource(UiR.string.settings_gplayapi_credit),
        // The full text ships inside the app: works offline and in regions
        // where gnu.org answers "Forbidden".
        onClick = { showLicense = true },
    )
}

@Composable
private fun AboutRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}


/** Full GPL-3.0 text bundled with the app, scrollable, full screen. */
@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.resources.openRawResource(com.novastore.app.core.ui.R.raw.gpl3).bufferedReader().use { it.readText() }
        }.getOrDefault("GNU General Public License v3.0")
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back))
                    }
                    Text("GNU GPL v3.0", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                )
            }
        }
    }
}
