package com.novastore.app.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.domain.usecase.CheckForUpdatesUseCase
import com.novastore.app.domain.usecase.DownloadUpdateUseCase
import com.novastore.app.domain.usecase.GetDownloadQueueUseCase
import com.novastore.app.domain.usecase.InstallPackageUseCase
import com.novastore.app.domain.usecase.UpdateAllUseCase
import com.novastore.app.domain.usecase.VerifyArtifactUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Localizable per-install notices rendered by the screen. */
sealed class UpdateNotice {
    data class Updated(val appName: String, val versionName: String?) : UpdateNotice()
    data class UpdatedLatest(val appName: String) : UpdateNotice()
    data class NeedsConfirmation(val appName: String) : UpdateNotice()
    data class Failed(val appName: String) : UpdateNotice()
    /** The user dismissed the system install dialog — neutral, not red. */
    data class Cancelled(val appName: String) : UpdateNotice()
    /** APKCombo's file link is JavaScript-gated — finish it on the app page. */
    data class MirrorRequired(val appName: String) : UpdateNotice()
    /** One update failed; [reason] is the source's explanation. */
    data class FailedReason(val appName: String, val reason: String) : UpdateNotice()
    /** The installed build is signed with another key (modified app). */
    data class ForeignSignature(val appName: String) : UpdateNotice()
}

data class UpdatesUiState(
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val updateAllInProgress: Boolean = false,
    val updates: List<UpdateCandidate> = emptyList(),
    val aggregateSizeBytes: Long = 0,
    val lastError: String? = null,
    val updateAllSummary: UpdateAllUseCase.Summary? = null,
    /** Packages with an individual update running right now. */
    val updatingPackages: Set<String> = emptySet(),
    /** Live download tasks (progress bars) keyed by package name. */
    val downloads: Map<String, DownloadTaskInfo> = emptyMap(),
    val notice: UpdateNotice? = null,
    /** Packages whose updates are ignored ("pkg" / "pkg@versionCode"). */
    val ignoredPackages: List<String> = emptyList(),
    /** True when at least one entry of the ignore list refers to a single version. */
    val hasVersionScopedIgnores: Boolean = false,
    /** Source chips: source id → number of updates (all sources present). */
    val sourceCounts: List<Pair<String, Int>> = emptyList(),
    /** Selected source chip; null = all. */
    val sourceFilter: String? = null,
    /** Readable names of repository ids. */
    val sourceNames: Map<String, String> = emptyMap(),
    /** Number of updates in the list before the source filter. */
    val totalUpdates: Int = 0,
)

private data class SideState(
    val downloads: Map<String, DownloadTaskInfo>,
    val ignored: Set<String>,
    val filter: String?,
    val names: Map<String, String>,
)

@HiltViewModel
class UpdatesViewModel @Inject constructor(
    private val checkForUpdates: CheckForUpdatesUseCase,
    private val updateAll: UpdateAllUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val verifyArtifact: VerifyArtifactUseCase,
    private val installPackage: InstallPackageUseCase,
    private val updatesRepository: UpdatesRepository,
    private val settingsRepository: SettingsRepository,
    private val settingsDataStore: SettingsDataStore,
    private val installedAppsRepository: com.novastore.app.domain.repository.InstalledAppsRepository,
    private val catalogRepository: com.novastore.app.domain.repository.CatalogRepository,
    repositoriesRepository: com.novastore.app.domain.repository.RepositoriesRepository,
    downloadQueue: GetDownloadQueueUseCase,
) : ViewModel() {

    private val sourceFilter = MutableStateFlow<String?>(null)

    private val sourceNames = repositoriesRepository.observe()
        .map { repos -> repos.associate { it.repositoryId to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Alternative update sources per package (loaded when the row menu opens). */
    private val alternativesState = MutableStateFlow<Map<String, List<com.novastore.app.core.model.AppVersion>>>(emptyMap())
    val alternatives: StateFlow<Map<String, List<com.novastore.app.core.model.AppVersion>>> = alternativesState

    fun setSourceFilter(source: String?) {
        sourceFilter.value = source
    }

    /**
     * Other repositories that offer a NEWER, installable build of the same
     * app (same signing key as the installed one — Android refuses others).
     */
    fun loadAlternatives(candidate: UpdateCandidate) {
        val pkg = candidate.installed.packageName
        viewModelScope.launch {
            val versions = runCatching { catalogRepository.getVersionsFor(listOf(pkg))[pkg].orEmpty() }
                .getOrDefault(emptyList())
            val signer = candidate.installed.signingCertDigest
            val options = versions
                .filter { it.source != candidate.source }
                .filter { signer == null || it.signer == null || it.signer.equals(signer, ignoreCase = true) }
                .filter { com.novastore.app.core.model.UpdateRules.isRealUpdate(candidate.installed, it) }
                .groupBy { it.source }
                .mapNotNull { (_, list) -> list.maxByOrNull { it.versionCode } }
                .sortedByDescending { it.versionCode }
            alternativesState.value = alternativesState.value + (pkg to options)
        }
    }

    /** Update this app from [version]'s source from now on. */
    fun chooseSource(candidate: UpdateCandidate, version: com.novastore.app.core.model.AppVersion) {
        viewModelScope.launch {
            settingsDataStore.setPreferredSource(candidate.installed.packageName, version.source)
            updatesRepository.saveCandidate(
                candidate.copy(available = version, source = version.source),
                com.novastore.app.core.model.UpdateState.DISCOVERED,
            )
        }
    }

    private val scanning = MutableStateFlow(false)
    private val updateAllInProgress = MutableStateFlow(false)
    private val lastError = MutableStateFlow<String?>(null)
    private val summary = MutableStateFlow<UpdateAllUseCase.Summary?>(null)
    private val updatingPackages = MutableStateFlow<Set<String>>(emptySet())
    private val notice = MutableStateFlow<UpdateNotice?>(null)

    private val downloads = downloadQueue()
        .map { tasks -> tasks.associateBy { it.packageName } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val ignored = settingsDataStore.ignoredUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val uiState: StateFlow<UpdatesUiState> = combine(
        updatesRepository.observeCandidates(),
        scanning,
        combine(updateAllInProgress, updatingPackages) { a, b -> a to b },
        combine(lastError, summary, notice) { e, s, n -> Triple(e, s, n) },
        combine(downloads, ignored, sourceFilter, sourceNames) { downloadMap, ignoredSet, filter, names ->
            SideState(downloadMap, ignoredSet, filter, names)
        },
    ) { candidates, isScanning, progress, messages, side ->
        val downloadMap = side.downloads
        val ignoredSet = side.ignored
        val notIgnored = candidates.filterNot { candidate ->
            val pkg = candidate.installed.packageName
            ignoredSet.contains(pkg) ||
                ignoredSet.contains("$pkg@${candidate.available.versionCode}")
        }
        val counts = notIgnored.groupingBy { it.source }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key to it.value }
        val filter = side.filter?.takeIf { f -> counts.any { it.first == f } }
        val visible = if (filter == null) notIgnored else notIgnored.filter { it.source == filter }
        val ignoredPackages = ignoredSet.map { it.substringBefore('@') }.distinct().sorted()
        UpdatesUiState(
            loading = false,
            scanning = isScanning,
            updateAllInProgress = progress.first,
            updates = visible,
            // Only deliverable (EXACT) updates count for "Update all".
            aggregateSizeBytes = visible
                .filter { it.confidence == com.novastore.app.core.model.UpdateConfidence.EXACT }
                .sumOf { it.available.size ?: 0 },
            lastError = messages.first,
            updateAllSummary = messages.second,
            updatingPackages = progress.second,
            downloads = downloadMap,
            notice = messages.third,
            ignoredPackages = ignoredPackages,
            hasVersionScopedIgnores = ignoredSet.any { it.contains('@') },
            sourceCounts = counts,
            sourceFilter = filter,
            sourceNames = side.names,
            totalUpdates = notIgnored.size,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UpdatesUiState())

    fun scan() {
        viewModelScope.launch {
            scanning.value = true
            when (val result = checkForUpdates(forceRefresh = true)) {
                is AppResult.Failure -> lastError.value = result.error.userMessage
                is AppResult.Success -> lastError.value = null
            }
            scanning.value = false
        }
    }

    fun updateAll() {
        viewModelScope.launch {
            updateAllInProgress.value = true
            summary.value = null
            when (val result = updateAll.invoke()) {
                is AppResult.Failure -> lastError.value = result.error.userMessage
                is AppResult.Success -> summary.value = result.value
            }
            updateAllInProgress.value = false
            // The candidate list is re-derived right away so finished apps
            // drop out of the list without a manual refresh.
            rescanQuietly()
        }
    }

    /** Download → verify → install a single update without touching the others. */
    fun updateApp(packageName: String) {
        if (packageName in updatingPackages.value) return
        viewModelScope.launch {
            updatingPackages.value = updatingPackages.value + packageName
            try {
                val stored = updatesRepository.currentCandidates()
                    .firstOrNull { it.installed.packageName == packageName }
                    ?: return@launch
                // DISCOVERY candidates carry no deliverable version — they
                // exist to send the user to the details screen.
                if (stored.confidence != com.novastore.app.core.model.UpdateConfidence.EXACT) {
                    // DISCOVERY / PAID / FOREIGN_SIGNATURE rows have their own
                    // actions — never a download attempt that can only fail.
                    return@launch
                }
                // Mirror rows are served by Google Play when it has the build.
                val candidate = downloadUpdate.prepare(stored)
                val file = when (val download = downloadUpdate(candidate)) {
                    is AppResult.Failure -> {
                        // Per-app problem → per-app notice, not the global
                        // "Something went wrong" screen error.
                        notice.value = UpdateNotice.FailedReason(candidate.installed.appName, download.error.userMessage)
                        return@launch
                    }
                    is AppResult.Success -> download.value
                }
                val verified = when (val verification = verifyArtifact(candidate, file)) {
                    is AppResult.Failure -> {
                        if (verification.error is com.novastore.app.core.model.NovaError.SignatureMismatch) {
                            markForeignSignature(candidate)
                            notice.value = UpdateNotice.ForeignSignature(candidate.installed.appName)
                        } else {
                            notice.value = UpdateNotice.FailedReason(
                                candidate.installed.appName,
                                verification.error.userMessage,
                            )
                        }
                        return@launch
                    }
                    is AppResult.Success -> verification.value
                }
                val settings = settingsRepository.settings.first()
                val mode = if (settings.confirmBeforeInstallation) InstallationMode.STANDARD else settings.installationMode
                when (val install = installPackage(verified.plan, mode)) {
                    is AppResult.Failure -> notice.value = UpdateNotice.FailedReason(
                        candidate.installed.appName,
                        install.error.userMessage,
                    )
                    is AppResult.Success -> when (install.value) {
                        is InstallResult.Success -> {
                            // Drop the candidate immediately: the app vanishes
                            // from the list the moment it is updated.
                            updatesRepository.remove(packageName)
                            updatesRepository.clearFinished()
                            notice.value = UpdateNotice.Updated(
                                candidate.installed.appName,
                                candidate.available.versionName,
                            )
                            rescanQuietly()
                        }
                        is InstallResult.UserActionRequired -> notice.value =
                            UpdateNotice.NeedsConfirmation(candidate.installed.appName)
                        is InstallResult.Cancelled -> notice.value =
                            UpdateNotice.Cancelled(candidate.installed.appName)
                        is InstallResult.Failure -> notice.value =
                            UpdateNotice.Failed(candidate.installed.appName)
                    }
                }
            } finally {
                updatingPackages.value = updatingPackages.value - packageName
            }
        }
    }

    // ------------------------------------------------------------------
    // Ignored updates
    // ------------------------------------------------------------------

    /**
     * Replaces a modified build with the original: opens the system
     * uninstaller; the caller then shows the app page, where the original
     * installs cleanly once the modified copy is gone.
     */
    fun uninstallModified(packageName: String) {
        viewModelScope.launch {
            when (val result = installedAppsRepository.uninstall(packageName)) {
                is AppResult.Failure -> lastError.value = result.error.userMessage
                is AppResult.Success -> Unit
            }
        }
    }

    /** Remembers that this update can not be installed over the modified build. */
    private suspend fun markForeignSignature(candidate: UpdateCandidate) {
        updatesRepository.saveCandidate(
            candidate.copy(confidence = com.novastore.app.core.model.UpdateConfidence.FOREIGN_SIGNATURE),
            com.novastore.app.core.model.UpdateState.DISCOVERED,
        )
    }

    /** Hides only this exact versionCode of the app. */
    fun ignoreVersion(packageName: String, versionCode: Long) {
        viewModelScope.launch {
            settingsDataStore.ignoreVersion(packageName, versionCode)
            updatesRepository.remove(packageName)
        }
    }

    /** Hides every future update of the app until undone. */
    fun ignoreAllVersions(packageName: String) {
        viewModelScope.launch {
            settingsDataStore.ignoreAllVersions(packageName)
            updatesRepository.remove(packageName)
        }
    }

    /** Restores update notifications for the app. */
    fun unignore(packageName: String) {
        viewModelScope.launch {
            settingsDataStore.unignore(packageName)
            rescanQuietly()
        }
    }

    /** Re-runs the update scan without toggling the visible spinner. */
    private fun rescanQuietly() {
        viewModelScope.launch {
            runCatching { checkForUpdates(forceRefresh = false) }
        }
    }

    fun dismissSummary() {
        summary.value = null
    }

    fun dismissError() {
        lastError.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }
}
