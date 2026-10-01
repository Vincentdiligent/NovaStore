package com.novastore.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.HomeLayoutStyle
import com.novastore.app.core.model.IconSize
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.domain.repository.NetworkMonitor
import com.novastore.app.domain.usecase.SearchAppsUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Search results layout preference: follows Home when set to "auto". */
internal const val SEARCH_LAYOUT_AUTO = "auto"
internal const val SEARCH_LAYOUT_GRID = "grid"
internal const val SEARCH_LAYOUT_LIST = "list"

/** Client-side sort orders for the result list. */
internal const val SEARCH_SORT_RELEVANCE = "relevance"
internal const val SEARCH_SORT_RATING = "rating"
internal const val SEARCH_SORT_DOWNLOADS = "downloads"
internal const val SEARCH_SORT_NAME = "name"

data class SearchUiState(
    val results: List<RemoteApp> = emptyList(),
    val searching: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    // --- Presentation preferences (persisted via SettingsDataStore) ---
    /** Raw layout preference: "auto" | "grid" | "list" ("auto" follows Home). */
    val layout: String = SEARCH_LAYOUT_AUTO,
    /** Grid column count, shared with the Home browse grid (2..4). */
    val columns: Int = 3,
    /** Sort order applied client-side: relevance | rating | downloads | name. */
    val sort: String = SEARCH_SORT_RELEVANCE,
    /** Icon size for grid cells, shared with Home. */
    val iconSize: IconSize = IconSize.MEDIUM,
    /** Home layout style — resolved when [layout] is "auto". */
    val homeLayoutStyle: HomeLayoutStyle = HomeLayoutStyle.GRID,
    /** Session-only source filter (null = "All"); deliberately NOT persisted. */
    val sourceFilter: String? = null,
    /** False until the saved layout prefs are read (no "3×" flash on open). */
    val prefsLoaded: Boolean = false,
) {
    /** True when the resolved presentation is the icon grid. */
    val grid: Boolean
        get() = when (layout) {
            SEARCH_LAYOUT_GRID -> true
            SEARCH_LAYOUT_LIST -> false
            else -> homeLayoutStyle != HomeLayoutStyle.LIST
        }
}

/** Result of one completed (or cancelled) search run. */
private data class SearchResult(
    /** The query this result belongs to; lags behind the live query while typing. */
    val query: String,
    val apps: List<RemoteApp> = emptyList(),
    val error: String? = null,
    /** True while network sources are still being merged in. */
    val partial: Boolean = false,
)

/** Presentation prefs bundled so the uiState combine stays within 5 flows. */
private data class SearchPrefs(
    val layout: String,
    val sort: String,
    val homeLayoutStyle: HomeLayoutStyle,
    val columns: Int,
    val iconSize: IconSize,
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val searchApps: SearchAppsUseCase,
    private val catalogRepository: com.novastore.app.domain.repository.CatalogRepository,
    private val settingsDataStore: SettingsDataStore,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val offline = MutableStateFlow(false)
    private val sourceFilter = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online -> offline.value = !online }
        }
    }

    /**
     * Mirrors the raw query with zero latency. The TextField binds to THIS
     * flow and never to [uiState] — the debounced/suspending pipeline in
     * [uiState] used to stall recomposition, which made typed characters
     * disappear while a search was in flight.
     */
    val queryState: StateFlow<String> = query.asStateFlow()

    /** All presentation prefs in one cold flow (nested combine: max 5 each). */
    private val prefs = combine(
        settingsDataStore.searchListStyle,
        settingsDataStore.searchSort,
        combine(
            settingsDataStore.homeLayoutStyle,
            settingsDataStore.homeGridColumns,
            settingsDataStore.homeIconSize,
        ) { style, columns, icon -> Triple(style, columns, icon) },
    ) { style, sort, home ->
        SearchPrefs(
            layout = style,
            sort = sort,
            homeLayoutStyle = home.first,
            columns = home.second,
            iconSize = home.third,
        )
    }

    /**
     * Debounced + cancelable search: superseded queries never overwrite
     * fresh ones (mapLatest cancels the previous run). "Searching" is
     * derived by comparing the live query with the last searched one, so
     * the indicator is always consistent with what is on screen. The
     * presentation prefs and the session source filter are combined in the
     * SAME pipeline (nested combines, max 5 flows each), so sorting and
     * filtering never retrigger the network search itself.
     */
    val uiState: StateFlow<SearchUiState> = combine(
        query
            .debounce(300)
            .distinctUntilChanged()
            .flatMapLatest { debounced ->
                flow {
                    if (debounced.isBlank()) {
                        emit(SearchResult(""))
                        return@flow
                    }
                    // 1) Local repositories: instant.
                    val local = searchApps.local(debounced)
                    emit(SearchResult(debounced, local, partial = true))
                    // 2) Everything (Play, web catalog, GitHub, GitLab) merged.
                    when (val result = searchApps(debounced.trim())) {
                        is AppResult.Success -> emit(SearchResult(debounced, result.value.ifEmpty { local }))
                        is AppResult.Failure -> emit(
                            SearchResult(debounced, local, error = result.error.userMessage.takeIf { local.isEmpty() }),
                        )
                    }
                }
            },
        query,
        offline,
        prefs,
        sourceFilter,
    ) { searched, current, isOffline, prefs, filter ->
        SearchUiState(
            // While typing, the previous results stay on screen (no blank
            // flash); they are replaced as soon as the new query answers.
            results = if (current.isBlank()) emptyList() else searched.apps.presented(prefs.sort, filter),
            searching = searched.query != current || searched.partial,
            offline = isOffline,
            error = if (searched.query == current) searched.error else null,
            layout = prefs.layout,
            columns = prefs.columns,
            sort = prefs.sort,
            iconSize = prefs.iconSize,
            homeLayoutStyle = prefs.homeLayoutStyle,
            sourceFilter = filter,
            prefsLoaded = true,
        )
    }
        // Sorting/filtering hundreds of results never runs on the UI thread.
        .flowOn(Dispatchers.Default)
        // Eagerly: while the user looks at an app and comes back, the search
        // pipeline stays alive — no restart, no re-query, results intact.
        .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    /** Scanned text → app / search, following short links' redirects. */
    fun resolveScanned(raw: String, onResult: (com.novastore.app.core.model.StoreLink?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { catalogRepository.resolveStoreLink(raw) }.getOrNull()
                ?: com.novastore.app.core.model.StoreLinks.parse(raw))
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun clearQuery() {
        query.value = ""
    }

    // ------------------------------------------------------------------
    // Presentation preferences (persisted immediately)
    // ------------------------------------------------------------------

    /** Cycles auto -> grid -> list -> auto (call site computes the next). */
    fun setLayout(value: String) {
        viewModelScope.launch { settingsDataStore.setSearchListStyle(value) }
    }

    /** Column count is shared with the Home browse grid (2..4). */
    fun setColumns(columns: Int) {
        viewModelScope.launch { settingsDataStore.setHomeGridColumns(columns) }
    }

    fun setSort(sort: String) {
        viewModelScope.launch { settingsDataStore.setSearchSort(sort) }
    }

    /** Session-only source filter; null selects "All". Never persisted. */
    fun setSourceFilter(source: String?) {
        sourceFilter.value = source
    }
}

/** Applies the session source filter and the client-side sort order. */
private fun List<RemoteApp>.presented(sort: String, filter: String?): List<RemoteApp> {
    val filtered = if (filter.isNullOrBlank()) this else filter { it.source == filter }
    return when (sort) {
        // Descending with nulls last: null coerces to the lowest possible value.
        SEARCH_SORT_RATING -> filtered.sortedByDescending { it.rating ?: -1f }
        SEARCH_SORT_DOWNLOADS -> filtered.sortedByDescending { it.downloads ?: -1L }
        SEARCH_SORT_NAME -> filtered.sortedBy { it.name.lowercase() }
        else -> filtered
    }
}
