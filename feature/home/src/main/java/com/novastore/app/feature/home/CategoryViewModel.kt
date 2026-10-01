package com.novastore.app.feature.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.HomeLayoutStyle
import com.novastore.app.core.model.IconSize
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoryUiState(
    val key: String = "",
    val loading: Boolean = true,
    val apps: List<RemoteApp> = emptyList(),
    val canLoadMore: Boolean = false,
    val columns: Int = 3,
    val iconSize: IconSize = IconSize.MEDIUM,
    val list: Boolean = false,
)

/** "See all" page: every app of one category on one vertically scrolling page. */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val catalogRepository: CatalogRepository,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val key: String = android.net.Uri.decode(savedStateHandle.get<String>("key").orEmpty())

    private val apps = MutableStateFlow<List<RemoteApp>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val canLoadMore = MutableStateFlow(false)
    private var localOffset = 0

    val uiState: StateFlow<CategoryUiState> = combine(
        apps,
        loading,
        canLoadMore,
        combine(
            settingsDataStore.homeGridColumns,
            settingsDataStore.homeIconSize,
            settingsDataStore.homeLayoutStyle,
            settingsDataStore.categoryListMode,
        ) { c, i, s, pick -> Triple(c, i, pick ?: (s == HomeLayoutStyle.LIST)) },
    ) { list, isLoading, more, prefs ->
        CategoryUiState(
            key = key,
            loading = isLoading,
            apps = list,
            canLoadMore = more,
            columns = prefs.first,
            iconSize = prefs.second,
            list = prefs.third,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CategoryUiState(key = key))

    private val isLocal: Boolean get() = !PlayShelves.isShelf(key)

    /** Grid ⇄ list, remembered for every "See all" page. */
    fun setListMode(list: Boolean) {
        viewModelScope.launch { settingsDataStore.setCategoryListMode(list) }
    }

    fun loadMore() {
        if (!canLoadMore.value || loading.value) return
        loading.value = true
        viewModelScope.launch {
            val page = runCatching { catalogRepository.listByCategory(key, localOffset, PAGE) }.getOrDefault(emptyList())
            localOffset += page.size
            val seen = apps.value.map { it.packageName }.toHashSet()
            apps.value = apps.value + page.filter { seen.add(it.packageName) }
            canLoadMore.value = page.size >= PAGE
            loading.value = false
        }
    }

    // Declared last: runs after every property above is initialized.
    init {
        viewModelScope.launch {
            val first = runCatching {
                when {
                    key == "play:home" -> catalogRepository.playStorefront(null)
                    PlayShelves.isShelf(key) -> catalogRepository.playStorefront(PlayShelves.idOf(key))
                    else -> catalogRepository.listByCategory(key, 0, PAGE)
                }
            }.getOrDefault(emptyList())
            localOffset = first.size
            apps.value = first.distinctBy { it.packageName }
            canLoadMore.value = isLocal && first.size >= PAGE
            loading.value = false
        }
    }

    private companion object {
        const val PAGE = 90
    }
}
