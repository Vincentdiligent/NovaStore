package com.novastore.app.feature.installed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.domain.usecase.ScanInstalledAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class InstalledSort { NAME_ASC, LAST_UPDATED, SIZE }

data class InstalledUiState(
    val loading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val sort: InstalledSort = InstalledSort.NAME_ASC,
    val updatingPackages: Set<String> = emptySet(),
    val lastError: String? = null,
    /** System/preinstalled packages are hidden unless the user asks for them. */
    val showSystem: Boolean = false,
    /** How many system apps are currently hidden. */
    val hiddenSystemCount: Int = 0,
)

@HiltViewModel
class InstalledViewModel @Inject constructor(
    private val scanInstalledApps: ScanInstalledAppsUseCase,
    installedAppsRepository: InstalledAppsRepository,
    updatesRepository: UpdatesRepository,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(InstalledSort.NAME_ASC)
    private val loading = MutableStateFlow(true)
    private val lastError = MutableStateFlow<String?>(null)
    private val showSystem = MutableStateFlow(false)

    val uiState: StateFlow<InstalledUiState> = combine(
        installedAppsRepository.observe(),
        updatesRepository.observeCandidates(),
        query,
        sort,
        combine(loading, lastError, showSystem) { isLoading, error, system -> Triple(isLoading, error, system) },
    ) { apps, updates, searchQuery, sortOrder, misc ->
        val updating = updates.map { it.installed.packageName }.toSet()
        val userApps = if (misc.third) apps else apps.filterNot { it.isSystemApp }
        val filtered = userApps
            .filter { it.appName.contains(searchQuery, ignoreCase = true) || it.packageName.contains(searchQuery, ignoreCase = true) }
            .sortedWith(
                when (sortOrder) {
                    InstalledSort.NAME_ASC -> compareBy { it.appName.lowercase() }
                    InstalledSort.LAST_UPDATED -> compareByDescending { it.lastUpdateTime }
                    InstalledSort.SIZE -> compareBy { it.packageName }
                },
            )
        InstalledUiState(
            loading = misc.first,
            apps = filtered,
            query = searchQuery,
            sort = sortOrder,
            updatingPackages = updating,
            lastError = misc.second,
            showSystem = misc.third,
            hiddenSystemCount = if (misc.third) 0 else apps.count { it.isSystemApp },
        )
    }.flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, InstalledUiState())

    fun setShowSystem(show: Boolean) {
        showSystem.value = show
    }

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            when (val result = scanInstalledApps()) {
                is com.novastore.app.core.common.AppResult.Failure -> lastError.value = result.error.userMessage
                is com.novastore.app.core.common.AppResult.Success -> lastError.value = null
            }
            loading.value = false
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: InstalledSort) {
        sort.value = value
    }
}
