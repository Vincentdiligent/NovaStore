package com.novastore.app.feature.details

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.AppReview
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.ui.R as UiR
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.domain.usecase.DownloadUpdateUseCase
import com.novastore.app.domain.usecase.GetAppDetailsUseCase
import com.novastore.app.domain.usecase.GetAppReviewsUseCase
import com.novastore.app.domain.usecase.GetDownloadQueueUseCase
import com.novastore.app.domain.usecase.InstallPackageUseCase
import com.novastore.app.domain.usecase.VerifyArtifactUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DetailsAction { INSTALL, UPDATE, UP_TO_DATE, UNAVAILABLE }

/** Why the action button is disabled — rendered localized in the UI. */
enum class UnavailableReason { NO_VERSIONS, INCOMPATIBLE, SIGNATURE, PAID }

/** Localizable post-install / management notices. */
sealed class DetailsNotice {
    /** Install finished; [version] is the installed version name. */
    data class Installed(val version: String?) : DetailsNotice()

    /** Android shows a system confirmation dialog. */
    data object NeedsConfirmation : DetailsNotice()

    /** Installation failed. */
    data object InstallFailed : DetailsNotice()

    /** The user dismissed the system install dialog — neutral, not red. */
    data object InstallCancelled : DetailsNotice()

    /** Uninstall was launched in the system. */
    data object UninstallStarted : DetailsNotice()
}

data class AppDetailsUiState(
    val loading: Boolean = true,
    val details: RemoteAppDetails? = null,
    val installed: InstalledApp? = null,
    /** The version Nova Store would install: compatible device, matching signature. */
    val bestVersion: AppVersion? = null,
    val action: DetailsAction = DetailsAction.UNAVAILABLE,
    /** Why nothing can be installed, when [action] is UNAVAILABLE. */
    val unavailableReason: UnavailableReason? = null,
    val busy: Boolean = false,
    val error: String? = null,
    /** Localized error banner, a core/ui string resource (mirror-browser cases). */
    val errorRes: Int? = null,
    val notice: DetailsNotice? = null,
    /** Latest user reviews from the anonymous Play feed; empty when none. */
    val reviews: List<AppReview> = emptyList(),
    /** True while the review feed is being fetched. */
    val reviewsLoading: Boolean = false,
    /** What the list showed for this app — instant header while loading. */
    val preview: com.novastore.app.core.model.RemoteApp? = null,
) {
    val isInstalled: Boolean get() = installed != null
    val updateAvailable: Boolean get() = action == DetailsAction.UPDATE
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AppDetailsViewModel @Inject constructor(
    private val getAppDetails: GetAppDetailsUseCase,
    private val getAppReviews: GetAppReviewsUseCase,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val verifyArtifact: VerifyArtifactUseCase,
    private val installPackage: InstallPackageUseCase,
    private val installedAppsRepository: InstalledAppsRepository,
    private val updatesRepository: UpdatesRepository,
    private val settingsRepository: SettingsRepository,
    private val settingsDataStore: com.novastore.app.core.datastore.SettingsDataStore,
    private val catalogRepository: com.novastore.app.domain.repository.CatalogRepository,
    downloadQueue: GetDownloadQueueUseCase,
) : ViewModel() {

    private val state = MutableStateFlow(AppDetailsUiState())
    val uiState: StateFlow<AppDetailsUiState> = state.asStateFlow()

    private var packageName: String? = null

    /**
     * Live download task for the app on screen — REAL progress (percent,
     * bytes, speed, ETA) for the busy bar. The screen swaps the indeterminate
     * "busy" indicator for a determinate one whenever this task reports
     * DOWNLOADING, so an install shows an actual moving scale instead of a
     * fast cycling bar.
     */
    private val packageFlow = MutableStateFlow<String?>(null)
    val downloadTask: StateFlow<DownloadTaskInfo?> = packageFlow
        .flatMapLatest { pkg ->
            if (pkg == null) {
                flowOf(null)
            } else {
                downloadQueue().map { tasks -> tasks.firstOrNull { it.packageName == pkg } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Heart state of the app on screen. */
    val isFavorite: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(
        settingsDataStore.favorites,
        packageFlow,
    ) { favorites, pkg -> pkg != null && favorites.any { it.packageName == pkg } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleFavorite() {
        val pkg = packageName ?: return
        val ui = state.value
        val entry = com.novastore.app.core.datastore.FavoriteApp(
            packageName = pkg,
            name = ui.details?.app?.name ?: ui.installed?.appName ?: pkg,
            iconUrl = ui.details?.app?.iconUrl,
        )
        viewModelScope.launch { settingsDataStore.setFavorite(entry, !isFavorite.value) }
    }

    fun load(pkg: String) {
        if (packageName == pkg && state.value.details != null) {
            packageFlow.value = pkg
            return
        }
        packageName = pkg
        packageFlow.value = pkg
        viewModelScope.launch {
            state.update {
                it.copy(
                    loading = true,
                    error = null,
                    errorRes = null,
                    reviews = emptyList(),
                    reviewsLoading = false,
                    preview = catalogRepository.preview(pkg),
                    details = null,
                )
            }
            val installed = installedAppsRepository.refresh(pkg)
            when (val result = getAppDetails(pkg)) {
                is AppResult.Success -> state.update { resolve(it.copy(details = result.value, installed = installed)) }
                is AppResult.Failure -> state.update { it.copy(installed = installed, error = result.error.userMessage, errorRes = null) }
            }
            state.update { it.copy(loading = false) }
            // Synthetic GitHub/GitLab release apps have no Play review feed.
            if (!pkg.startsWith("github.") && !pkg.startsWith("gitlab.")) {
                loadReviews(pkg)
            }
        }
    }

    /** Fetches the anonymous Play review feed; failures degrade to no reviews. */
    private fun loadReviews(pkg: String) {
        viewModelScope.launch {
            state.update { it.copy(reviewsLoading = true) }
            val reviews = try {
                getAppReviews(pkg)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                emptyList()
            }
            // A different package was opened while the feed was in flight.
            if (packageName == pkg) {
                state.update { it.copy(reviews = reviews, reviewsLoading = false) }
            }
        }
    }

    /** Download → verify → install the best available version. */
    fun installOrUpdate() {
        val current = state.value
        val version = current.bestVersion ?: return
        val appName = current.details?.app?.name ?: version.packageName
        viewModelScope.launch {
            // The mirror-takeover notice stays visible through the busy phase;
            // it is replaced by the final install result below.
            state.update { ui ->
                ui.copy(
                    busy = true,
                    error = null,
                    errorRes = null,
                    notice = null,
                )
            }

            val installed = current.installed ?: InstalledApp(
                packageName = version.packageName,
                appName = appName,
                versionName = null,
                versionCode = 0,
                firstInstallTime = 0,
                lastUpdateTime = 0,
                installerSource = null,
                signingCertDigest = null,
                isSystemApp = false,
            )
            val requested = UpdateCandidate(installed = installed, available = version, source = version.source)
            // Mirror/web versions are delivered by Google Play when it has the build.
            val candidate = downloadUpdate.prepare(requested)
            updatesRepository.saveCandidate(candidate, UpdateState.DISCOVERED)

            val file = when (val download = downloadUpdate(candidate)) {
                is AppResult.Failure -> {
                    return@launch fail(download.error.userMessage)
                }
                is AppResult.Success -> download.value
            }
            val verified = when (val verification = verifyArtifact(candidate, file)) {
                is AppResult.Failure -> return@launch fail(verification.error.userMessage)
                is AppResult.Success -> verification.value
            }

            val settings = settingsRepository.settings.first()
            val mode = if (settings.confirmBeforeInstallation) InstallationMode.STANDARD else settings.installationMode
            when (val install = installPackage(verified.plan, mode)) {
                is AppResult.Failure -> return@launch fail(install.error.userMessage)
                is AppResult.Success -> {
                    val notice = when (install.value) {
                        is InstallResult.Success -> {
                            updatesRepository.remove(version.packageName)
                            updatesRepository.clearFinished()
                            DetailsNotice.Installed(version.versionName)
                        }
                        is InstallResult.UserActionRequired -> DetailsNotice.NeedsConfirmation
                        is InstallResult.Cancelled -> DetailsNotice.InstallCancelled
                        is InstallResult.Failure -> DetailsNotice.InstallFailed
                    }
                    val refreshed = installedAppsRepository.refresh(version.packageName)
                    state.update { resolve(it.copy(installed = refreshed, busy = false, notice = notice)) }
                }
            }
        }
    }

    private fun fail(message: String) {
        state.update { it.copy(busy = false, error = message, errorRes = null, notice = null) }
    }

    private fun failLocalized(res: Int) {
        state.update { it.copy(busy = false, error = null, errorRes = res, notice = null) }
    }

    /** Picks the version to install and the action to offer. */
    private fun resolve(ui: AppDetailsUiState): AppDetailsUiState {
        val details = ui.details ?: return ui.copy(bestVersion = null, action = DetailsAction.UNAVAILABLE)
        val installed = ui.installed
        val deviceAbis = Build.SUPPORTED_ABIS.toSet()

        val runnable = details.versions.filter { version ->
            (version.minSdk == null || version.minSdk!! <= Build.VERSION.SDK_INT) &&
                (version.nativeCode.isEmpty() || version.nativeCode.any { it in deviceAbis })
        }
        val installable = runnable.filter { version ->
            val installedSigner = installed?.signingCertDigest
            installedSigner == null || version.signer == null || version.signer.equals(installedSigner, ignoreCase = true)
        }
        // Paid Play builds can not be delivered without a purchase — they
        // never become the install target while anything free exists.
        val deliverable = installable.filterNot { it.isPaid }
        val best = pickBest(deliverable, installed)
        val paidOnly = best == null && installable.any { it.isPaid }

        // Same version NAME = same release, whatever the codes say (device
        // variants, mirror codes) — never an "11.0.3 → 11.0.3" Update button.
        val sameVersion = best != null && installed != null &&
            normalize(best.versionName) != null &&
            normalize(best.versionName) == normalize(installed.versionName)
        val sameMirrorVersion = sameVersion
        val effectiveBest = if (sameMirrorVersion) null else best

        val (action, reason) = when {
            paidOnly -> DetailsAction.UNAVAILABLE to UnavailableReason.PAID
            best == null && details.versions.isEmpty() ->
                DetailsAction.UNAVAILABLE to UnavailableReason.NO_VERSIONS
            best == null && runnable.isEmpty() ->
                DetailsAction.UNAVAILABLE to UnavailableReason.INCOMPATIBLE
            best == null ->
                DetailsAction.UNAVAILABLE to UnavailableReason.SIGNATURE
            sameMirrorVersion -> DetailsAction.UP_TO_DATE to null
            installed == null -> DetailsAction.INSTALL to null
            effectiveBest != null && isNewerThanInstalled(effectiveBest, installed) ->
                DetailsAction.UPDATE to null
            else -> DetailsAction.UP_TO_DATE to null
        }
        return ui.copy(bestVersion = effectiveBest ?: best, action = action, unavailableReason = reason)
    }

    /**
     * Version choice by signing key: Android only accepts an update signed
     * like the installed app. Repository builds with a matching signer win;
     * otherwise Google Play's own build (the developer's key); then any other
     * source. For fresh installs: repository → Play → mirrors/releases.
     */
    private fun pickBest(installable: List<AppVersion>, installed: InstalledApp?): AppVersion? {
        val signer = installed?.signingCertDigest
        val verified = installable.filter { it.signer != null && (signer == null || it.signer.equals(signer, ignoreCase = true)) }
        val play = installable.filter { it.source == com.novastore.app.core.model.SOURCE_PLAY }
        val rest = installable.filter { it.signer == null && it.source != com.novastore.app.core.model.SOURCE_PLAY }
        val groups = if (installed != null && signer != null) {
            listOf(installable.filter { it.signer != null && it.signer.equals(signer, ignoreCase = true) }, play, rest)
        } else {
            listOf(verified, play, rest)
        }
        return groups.firstOrNull { it.isNotEmpty() }?.maxByOrNull { it.versionCode }
    }

    /** Mirror codes are not Android versionCodes: their NAME must be newer. */
    private fun isNewerThanInstalled(version: AppVersion, installed: InstalledApp): Boolean {
        if (isMirrorSource(version.source)) {
            val available = normalize(version.versionName) ?: return false
            val current = normalize(installed.versionName) ?: return false
            return com.novastore.app.core.model.VersionComparator.compareVersionNames(current, available) < 0
        }
        return version.versionCode > installed.versionCode
    }

    private fun isMirrorSource(source: String): Boolean =
        source == com.novastore.app.core.model.SOURCE_APKPURE ||
            source == com.novastore.app.core.model.SOURCE_APKCOMBO

    private fun normalize(name: String?): String? =
        name?.trim()?.removePrefix("v")?.removePrefix("V")?.lowercase()?.takeIf { it.isNotEmpty() }

    fun dismissNotice() {
        state.update { it.copy(notice = null) }
    }

    fun dismissError() {
        state.update { it.copy(error = null, errorRes = null) }
    }

    // ------------------------------------------------------------------
    // Local app management (installed apps that may not be in the catalog)
    // ------------------------------------------------------------------

    /** Opens the Android uninstall confirmation for this package. */
    fun uninstall() {
        val pkg = packageName ?: return
        viewModelScope.launch {
            when (val result = installedAppsRepository.uninstall(pkg)) {
                is AppResult.Failure -> state.update { it.copy(error = result.error.userMessage) }
                is AppResult.Success -> state.update { it.copy(notice = DetailsNotice.UninstallStarted) }
            }
        }
    }

    /** Launches the app's main activity. */
    fun openApp() {
        val pkg = packageName ?: return
        viewModelScope.launch {
            when (val result = installedAppsRepository.openApp(pkg)) {
                is AppResult.Failure -> state.update { it.copy(error = result.error.userMessage) }
                is AppResult.Success -> Unit
            }
        }
    }

    /** Opens the system App info settings page. */
    fun openAppSettings() {
        val pkg = packageName ?: return
        viewModelScope.launch {
            when (val result = installedAppsRepository.openAppSettings(pkg)) {
                is AppResult.Failure -> state.update { it.copy(error = result.error.userMessage) }
                is AppResult.Success -> Unit
            }
        }
    }

    /** Re-reads the installed state (e.g. after returning from the system dialog). */
    fun refreshInstalledState() {
        val pkg = packageName ?: return
        viewModelScope.launch {
            val installed = installedAppsRepository.refresh(pkg)
            state.update { it.copy(installed = installed).let(::resolve) }
        }
    }
}
