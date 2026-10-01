package com.novastore.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.installer.RootAccessProvider
import com.novastore.app.core.model.DeviceProfile
import com.novastore.app.core.model.RootAccessState
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.AccentPalette
import com.novastore.app.core.model.AppLanguage
import com.novastore.app.core.model.RepositoryConfig
import com.novastore.app.core.model.RootAccessResult
import com.novastore.app.core.model.ThemeMode
import com.novastore.app.core.model.UpdateSettings
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.domain.repository.AccountState
import com.novastore.app.domain.repository.RepositoriesRepository
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.usecase.ListDeviceProfilesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: UpdateSettings = UpdateSettings(),
    val repositories: List<RepositoryConfig> = emptyList(),
    val appCounts: Map<String, Int> = emptyMap(),
    /** Repository ids currently being loaded. */
    val refreshingIds: Set<String> = emptySet(),
    val notice: String? = null,
    val rootState: RootAccessState = RootAccessState.UNAVAILABLE,
    val busy: Boolean = false,
    val error: String? = null,
    // --- Google Play integration ---
    val deviceProfiles: List<DeviceProfile> = emptyList(),
    val selectedDeviceProfile: String = "",
    val tokenDispenserUrl: String = "",
    val playUpdatesEnabled: Boolean = true,
    /** Anonymous Google Play (native protocol without an account). */
    val anonymousPlayEnabled: Boolean = true,
    /** Current account state for the Google Play section header. */
    val accountState: AccountState = AccountState.NotSignedIn,
    // --- Nova anonymous access tiers (own engine) ---
    /** Nova Web Catalog: anonymous public play.google.com pages. */
    val playWebCatalogEnabled: Boolean = true,
    /** Community mirror (APKPure): anonymous versions + downloads fallback. */
    val apkPureMirrorEnabled: Boolean = true,
    /** Second mirror stage (APKCombo): version history + download attempts. */
    val apkComboMirrorEnabled: Boolean = true,
    /** GitHub releases catalog in search and details. */
    val githubCatalogEnabled: Boolean = true,
    /** GitLab releases catalog in search and details. */
    val gitlabCatalogEnabled: Boolean = true,
    // --- Appearance ---
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accentPalette: AccentPalette = AccentPalette.EMERALD,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val repositoriesRepository: RepositoriesRepository,
    private val rootAccessProvider: RootAccessProvider,
    private val settingsDataStore: SettingsDataStore,
    private val listDeviceProfiles: ListDeviceProfilesUseCase,
    accountRepository: AccountRepository,
) : ViewModel() {

    private val rootState = MutableStateFlow(RootAccessState.UNAVAILABLE)
    private val busy = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val notice = MutableStateFlow<String?>(null)
    private val refreshingIds = MutableStateFlow<Set<String>>(emptySet())
    private val deviceProfiles = MutableStateFlow<List<DeviceProfile>>(emptyList())

    init {
        viewModelScope.launch {
            deviceProfiles.value = runCatching { listDeviceProfiles() }.getOrDefault(emptyList())
        }
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settingsRepository.settings,
            repositoriesRepository.observe(),
            repositoriesRepository.observeAppCounts(),
        ) { a, b, c -> Triple(a, b, c) },
        combine(rootState, busy, refreshingIds) { r, b, ids -> Triple(r, b, ids) },
        combine(error, notice) { a, b -> a to b },
        combine(
            settingsDataStore.playDeviceProfile,
            settingsDataStore.tokenDispenserUrl,
            combine(settingsDataStore.playUpdatesEnabled, settingsDataStore.anonymousPlayEnabled) { a, b -> a to b },
            deviceProfiles,
            combine(
                settingsDataStore.playWebCatalogEnabled,
                settingsDataStore.apkPureMirrorEnabled,
                settingsDataStore.apkComboMirrorEnabled,
                settingsDataStore.githubCatalogEnabled,
                settingsDataStore.gitlabCatalogEnabled,
            ) { webCatalog, mirror, combo, github, gitlab ->
                SourceToggles(webCatalog, mirror, combo, github, gitlab)
            },
        ) { profile, dispenser, playUpdates, profiles, sources ->
            PlaySettings(
                selectedDeviceProfile = profile,
                tokenDispenserUrl = dispenser,
                playUpdatesEnabled = playUpdates.first,
                anonymousPlayEnabled = playUpdates.second,
                deviceProfiles = profiles,
                playWebCatalogEnabled = sources.playWebCatalogEnabled,
                apkPureMirrorEnabled = sources.apkPureMirrorEnabled,
                apkComboMirrorEnabled = sources.apkComboMirrorEnabled,
                githubCatalogEnabled = sources.githubCatalogEnabled,
                gitlabCatalogEnabled = sources.gitlabCatalogEnabled,
            )
        },
        combine(
            combine(
                settingsDataStore.appTheme,
                settingsDataStore.accentPalette,
                settingsDataStore.appLanguage,
            ) { theme, accent, language -> Triple(theme, accent, language) },
            accountRepository.accountState,
        ) { appearance, account -> appearance to account },
    ) { (settings, repositories, counts), (root, isBusy, refreshing), (errorMessage, noticeMessage), play, (appearance, account) ->
        SettingsUiState(
            settings = settings,
            repositories = repositories,
            appCounts = counts,
            refreshingIds = refreshing,
            rootState = root,
            busy = isBusy,
            error = errorMessage,
            notice = noticeMessage,
            deviceProfiles = play.deviceProfiles,
            selectedDeviceProfile = play.selectedDeviceProfile,
            tokenDispenserUrl = play.tokenDispenserUrl,
            playUpdatesEnabled = play.playUpdatesEnabled,
            anonymousPlayEnabled = play.anonymousPlayEnabled,
            playWebCatalogEnabled = play.playWebCatalogEnabled,
            apkPureMirrorEnabled = play.apkPureMirrorEnabled,
            apkComboMirrorEnabled = play.apkComboMirrorEnabled,
            githubCatalogEnabled = play.githubCatalogEnabled,
            gitlabCatalogEnabled = play.gitlabCatalogEnabled,
            themeMode = appearance.first,
            accentPalette = appearance.second,
            appLanguage = appearance.third,
            accountState = account,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private data class PlaySettings(
        val selectedDeviceProfile: String,
        val tokenDispenserUrl: String,
        val playUpdatesEnabled: Boolean,
        val anonymousPlayEnabled: Boolean,
        val deviceProfiles: List<DeviceProfile>,
        val playWebCatalogEnabled: Boolean,
        val apkPureMirrorEnabled: Boolean,
        val apkComboMirrorEnabled: Boolean,
        val githubCatalogEnabled: Boolean,
        val gitlabCatalogEnabled: Boolean,
    )

    /** The five source toggles, bundled to respect the 5-flow combine limit. */
    private data class SourceToggles(
        val playWebCatalogEnabled: Boolean,
        val apkPureMirrorEnabled: Boolean,
        val apkComboMirrorEnabled: Boolean,
        val githubCatalogEnabled: Boolean,
        val gitlabCatalogEnabled: Boolean,
    )

    // ------------------------------------------------------------------
    // Google Play & anonymous sources
    // ------------------------------------------------------------------

    fun setPlayDeviceProfile(fileName: String) {
        viewModelScope.launch {
            settingsDataStore.setPlayDeviceProfile(fileName)
            notice.value = "Device profile saved — Google Play uses it right away."
        }
    }

    fun setTokenDispenserUrl(url: String) {
        viewModelScope.launch {
            settingsDataStore.setTokenDispenserUrl(url)
        }
    }

    fun setPlayUpdatesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPlayUpdatesEnabled(enabled)
        }
    }

    /** Home search bar: pinned or hiding while scrolling. */
    val homeSearchPinned: StateFlow<Boolean> = settingsDataStore.homeSearchPinned
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setHomeSearchPinned(pinned: Boolean) {
        viewModelScope.launch { settingsDataStore.setHomeSearchPinned(pinned) }
    }

    /** Anonymous Google Play: native Play protocol without any account. */
    fun setAnonymousPlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setAnonymousPlayEnabled(enabled)
        }
    }

    /** Nova Web Catalog: anonymous public play.google.com pages. */
    fun setPlayWebCatalogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setPlayWebCatalogEnabled(enabled)
        }
    }

    /** Community mirror (APKPure): anonymous versions + downloads fallback. */
    fun setApkPureMirrorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setApkPureMirrorEnabled(enabled)
        }
    }

    /** Community mirror stage two (APKCombo): version history + downloads. */
    fun setApkComboMirrorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setApkComboMirrorEnabled(enabled)
        }
    }

    /** GitHub releases catalog: searchable apps installable from GitHub Releases. */
    fun setGithubCatalogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setGithubCatalogEnabled(enabled)
        }
    }

    /** GitLab releases catalog: searchable apps installable from GitLab Releases. */
    fun setGitlabCatalogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setGitlabCatalogEnabled(enabled)
        }
    }

    // ------------------------------------------------------------------
    // Storage & downloads housekeeping
    // ------------------------------------------------------------------

    /** Completed-download auto-cleanup age in days (0 = keep forever). */
    val downloadsAutoCleanDays: StateFlow<Int> = settingsDataStore.downloadsAutoCleanDays
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun setDownloadsAutoCleanDays(days: Int) {
        viewModelScope.launch { settingsDataStore.setDownloadsAutoCleanDays(days) }
    }

    // ------------------------------------------------------------------
    // Appearance
    // ------------------------------------------------------------------

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setAppTheme(mode) }
    }

    fun setAccentPalette(palette: AccentPalette) {
        viewModelScope.launch { settingsDataStore.setAccentPalette(palette) }
    }

    /** Persists the language and applies per-app locales immediately. */
    fun setAppLanguage(language: AppLanguage) {
        viewModelScope.launch {
            settingsDataStore.setAppLanguage(language)
        }
        val locales = if (language.tag != null) {
            androidx.core.os.LocaleListCompat.forLanguageTags(language.tag)
        } else {
            androidx.core.os.LocaleListCompat.getEmptyLocaleList()
        }
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales)
    }

    // ------------------------------------------------------------------
    // Existing settings / repositories / root
    // ------------------------------------------------------------------

    fun updateSettings(transform: (UpdateSettings) -> UpdateSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
        }
    }

    fun checkRoot() {
        viewModelScope.launch {
            busy.value = true
            when (val result = rootAccessProvider.requestRootAccess()) {
                is RootAccessResult.Authorized -> {
                    rootState.value = RootAccessState.AUTHORIZED
                    notice.value = "Root access granted."
                }
                is RootAccessResult.Denied -> rootState.value = RootAccessState.DENIED
                is RootAccessResult.Unavailable -> {
                    rootState.value = RootAccessState.UNAVAILABLE
                    notice.value = "No su binary found — this device is not rooted (or root is hidden from Nova Store)."
                }
                is RootAccessResult.Revoked -> rootState.value = RootAccessState.REVOKED
                is RootAccessResult.Error -> {
                    rootState.value = RootAccessState.ERROR
                    error.value = result.message
                }
            }
            busy.value = false
        }
    }

    fun setRepositoryEnabled(repositoryId: String, enabled: Boolean) {
        runForRepository(repositoryId) { repositoriesRepository.setEnabled(repositoryId, enabled) }
    }

    fun refreshRepository(repositoryId: String) {
        runForRepository(repositoryId) { repositoriesRepository.refresh(repositoryId) }
    }

    fun removeRepository(repositoryId: String) {
        viewModelScope.launch { repositoriesRepository.remove(repositoryId) }
    }

    fun addCustomRepository(name: String, url: String) {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            when (val result = repositoriesRepository.add(name, url)) {
                is AppResult.Failure -> error.value = "Could not load the repository: ${result.error.userMessage}"
                is AppResult.Success -> notice.value = "Repository added."
            }
            busy.value = false
        }
    }

    fun refreshRepositories() {
        viewModelScope.launch {
            busy.value = true
            error.value = null
            when (val result = repositoriesRepository.refreshAll(force = true)) {
                is AppResult.Failure -> error.value = result.error.userMessage
                is AppResult.Success -> notice.value = "Repositories updated."
            }
            busy.value = false
        }
    }

    private fun runForRepository(repositoryId: String, block: suspend () -> AppResult<Unit>) {
        viewModelScope.launch {
            refreshingIds.value = refreshingIds.value + repositoryId
            when (val result = block()) {
                is AppResult.Failure -> error.value = result.error.userMessage
                is AppResult.Success -> Unit
            }
            refreshingIds.value = refreshingIds.value - repositoryId
        }
    }

    fun dismissError() {
        error.value = null
    }

    fun dismissNotice() {
        notice.value = null
    }
}
