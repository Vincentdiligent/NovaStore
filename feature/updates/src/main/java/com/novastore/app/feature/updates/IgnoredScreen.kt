package com.novastore.app.feature.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.ui.components.EmptyState
import com.novastore.app.core.ui.components.InstalledAppIcon
import com.novastore.app.domain.repository.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One ignored app: all its updates, or only one version. */
data class IgnoredEntry(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    /** Empty = every update ignored; otherwise only these versionCodes. */
    val ignoredVersionCodes: List<Long>,
)

@HiltViewModel
class IgnoredViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    installedAppsRepository: InstalledAppsRepository,
) : ViewModel() {

    val entries: StateFlow<List<IgnoredEntry>> = combine(
        settingsDataStore.ignoredUpdates,
        installedAppsRepository.observe(),
    ) { ignored, installed ->
        val byPkg = installed.associateBy { it.packageName }
        // One row per app, however many single versions were ignored.
        ignored.groupBy { it.substringBefore('@') }.map { (pkg, raws) ->
            val app = byPkg[pkg]
            val all = raws.any { !it.contains('@') }
            IgnoredEntry(
                packageName = pkg,
                appName = app?.appName ?: pkg,
                versionName = app?.versionName,
                ignoredVersionCodes = if (all) emptyList() else raws.mapNotNull { it.substringAfter('@', "").toLongOrNull() }.sorted(),
            )
        }.sortedBy { it.appName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun unignore(packageName: String) {
        viewModelScope.launch { settingsDataStore.unignore(packageName) }
    }
}

/** Ignored updates on their own page: icon, name, scope, "Restore". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IgnoredScreen(
    onBack: () -> Unit,
    onOpenAppDetails: (String) -> Unit,
    viewModel: IgnoredViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(UiR.string.updates_ignored_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(UiR.string.cd_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Filled.VisibilityOff,
                    title = stringResource(UiR.string.updates_ignored_none),
                )
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.packageName }) { entry ->
                Surface(
                    onClick = { onOpenAppDetails(entry.packageName) },
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InstalledAppIcon(
                            packageName = entry.packageName,
                            appName = entry.appName,
                            size = 48.dp,
                            shape = RoundedCornerShape(14.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.appName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (entry.ignoredVersionCodes.isNotEmpty()) {
                                    stringResource(UiR.string.updates_ignored_one_version, entry.ignoredVersionCodes.joinToString(", "))
                                } else {
                                    stringResource(UiR.string.updates_ignored_all_versions)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(onClick = { viewModel.unignore(entry.packageName) }) {
                            Text(stringResource(UiR.string.updates_unignore))
                        }
                    }
                }
            }
        }
    }
}
