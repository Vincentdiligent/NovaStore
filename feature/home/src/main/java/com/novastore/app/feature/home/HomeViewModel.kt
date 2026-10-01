package com.novastore.app.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.HomeLayoutStyle
import com.novastore.app.core.model.IconSize
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.domain.repository.AccountState
import com.novastore.app.domain.repository.CatalogRepository
import com.novastore.app.domain.repository.NetworkMonitor
import com.novastore.app.domain.repository.PlayStoreRepository
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.domain.usecase.CheckForUpdatesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit

/** One themed horizontal shelf on Home ("home" = Play's own front page). */
data class HomeShelf(
    val id: String,
    val apps: List<RemoteApp>,
)

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    /** Horizontal hero carousel: Play top-free when signed in, else fresh catalog picks. */
    val featured: List<RemoteApp> = emptyList(),
    /** Themed Play shelves (random subset + order per session). */
    val shelves: List<HomeShelf> = emptyList(),
    /** Curated, localized category names for the chips row (raw filter keys). */
    val categories: List<String> = emptyList(),
    val selectedCategory: String? = null,
    /** Paged grid (12/page) for the selected category ("All" when null). */
    val categoryApps: List<RemoteApp> = emptyList(),
    val canLoadMore: Boolean = false,
    /** "New on F-Droid" horizontal section. */
    val recentlyAdded: List<RemoteApp> = emptyList(),
    val updatesCount: Int = 0,
    val playAvailable: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
    // --- Layout preferences (persisted) ---
    val gridColumns: Int = 3,
    val iconSize: IconSize = IconSize.MEDIUM,
    val layoutStyle: HomeLayoutStyle = HomeLayoutStyle.SHELVES,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val checkForUpdates: CheckForUpdatesUseCase,
    private val catalogRepository: CatalogRepository,
    private val playStoreRepository: PlayStoreRepository,
    private val accountRepository: AccountRepository,
    private val settingsDataStore: SettingsDataStore,
    updatesRepository: UpdatesRepository,
    private val installedAppsRepository: com.novastore.app.domain.repository.InstalledAppsRepository,
    scanProgressTracker: com.novastore.app.domain.repository.ScanProgressTracker,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val refreshing = MutableStateFlow(false)
    private val featured = MutableStateFlow<List<RemoteApp>>(emptyList())
    private val shelves = MutableStateFlow<List<HomeShelf>>(emptyList())

    // ------------------------------------------------------------------
    // Category rows (default "shelves" layout): loaded lazily, row by row,
    // as they scroll into view — never 20 storefront pages at once.
    // ------------------------------------------------------------------

    private val rowContentState = MutableStateFlow<Map<String, List<RemoteApp>>>(emptyMap())
    val rowContent: StateFlow<Map<String, List<RemoteApp>>> = rowContentState
    private val rowsRequested = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val rowGate = kotlinx.coroutines.sync.Semaphore(2)

    /** One row's content only — a loading row never recomposes the others. */
    fun rowFlow(key: String): kotlinx.coroutines.flow.Flow<List<RemoteApp>?> =
        rowContentState.map { it[key] }.distinctUntilChanged()

    fun rowSnapshot(key: String): List<RemoteApp>? = rowContentState.value[key]

    /** Called by a row when it becomes visible. Idempotent. */
    fun requestRow(key: String) {
        if (!rowsRequested.add(key)) return
        viewModelScope.launch {
            val apps = rowGate.withPermit { loadRow(key) }
            rowContentState.value = rowContentState.value + (key to apps)
        }
    }

    private suspend fun loadRow(key: String): List<RemoteApp> = runCatching {
        when {
            key == ROW_PLAY_HOME -> catalogRepository.playStorefront(null)
            PlayShelves.isShelf(key) -> catalogRepository.playStorefront(PlayShelves.idOf(key))
            else -> catalogRepository.listByCategory(key, 0, ROW_SIZE)
        }
    }.getOrDefault(emptyList()).take(ROW_SIZE)
    private val selectedCategory = MutableStateFlow<String?>(null)
    private val categoryApps = MutableStateFlow<List<RemoteApp>>(emptyList())
    private val canLoadMore = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val offline = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    // Play storefront shelves first ("play:GAME" …), then the curated
    // repository categories — chips exist even with every repository off.
    private val categories = catalogRepository.observeCategories()
        .map { raw -> PlayShelves.chipKeys + com.novastore.app.core.ui.components.NovaCategories.select(raw) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val ignoredUpdates = settingsDataStore.ignoredUpdates
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    /** Visible updates only — apps the user ignored never count. */
    private val updatesCount = kotlinx.coroutines.flow.combine(
        updatesRepository.observeCandidates(),
        ignoredUpdates,
    ) { candidates, ignored ->
        candidates.count { candidate ->
            val pkg = candidate.installed.packageName
            candidate.confidence == com.novastore.app.core.model.UpdateConfidence.EXACT &&
                !ignored.contains(pkg) && !ignored.contains("$pkg@${candidate.available.versionCode}")
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /** Repository refresh / update scan progress, shown on Home itself. */
    val scanProgress: StateFlow<com.novastore.app.domain.repository.ScanProgress?> = scanProgressTracker.progress

    /** Search bar pinned (Settings) — otherwise it hides while scrolling. */
    val searchPinned: StateFlow<Boolean> = settingsDataStore.homeSearchPinned
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Hearted apps, shown as a row on Home. */
    val favorites: StateFlow<List<com.novastore.app.core.datastore.FavoriteApp>> = settingsDataStore.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val playAvailable = accountRepository.accountState

    private val recentlyAdded = catalogRepository.observeRecentlyAdded(limit = 20)
        // Room re-emits the full list on every catalog-table invalidation
        // (the parallel network scan writes rows as it progresses);
        // structurally equal re-emissions are suppressed here so the uiState
        // combine chain does not restart for content that did not change —
        // the home screen stays stable while the scan is running.
        .distinctUntilChanged()


    val uiState: StateFlow<HomeUiState> = kotlinx.coroutines.flow.combine(
        kotlinx.coroutines.flow.combine(loading, refreshing) { a, b -> a to b },
        kotlinx.coroutines.flow.combine(featured, categories, selectedCategory, shelves) { f, c, s, sh ->
            Triple(f, c, s) to sh
        },
        kotlinx.coroutines.flow.combine(categoryApps, canLoadMore, recentlyAdded) { apps, more, recent ->
            Triple(apps, more, recent)
        },
        kotlinx.coroutines.flow.combine(updatesCount, playAvailable, offline, error) { u, p, off, err ->
            HomeUiState(updatesCount = u, playAvailable = p is AccountState.SignedIn, offline = off, error = err)
        },
        kotlinx.coroutines.flow.combine(
            settingsDataStore.homeGridColumns,
            settingsDataStore.homeIconSize,
            settingsDataStore.homeLayoutStyle,
        ) { columns, icon, style -> Triple(columns, icon, style) },
    ) { basics, sections, browse, derived, layout ->
        derived.copy(
            loading = basics.first,
            refreshing = basics.second,
            featured = sections.first.first,
            categories = sections.first.second,
            selectedCategory = sections.first.third,
            shelves = sections.second,
            categoryApps = browse.first,
            canLoadMore = browse.second,
            recentlyAdded = browse.third,
            gridColumns = layout.first,
            iconSize = layout.second,
            layoutStyle = layout.third,
        )
    // Eagerly: the state survives while the user is on a details page, so
    // coming back never flashes an empty/shimmer list (which also reset the
    // scroll position to the top).
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    /** Pull-to-refresh / manual refresh: reload repositories, chart and grid. */
    fun refresh() {
        runRefresh(forceRefresh = true)
    }

    private fun runRefresh(forceRefresh: Boolean) {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            // Content first: the local catalog renders immediately while the
            // network scan (repositories + Play mirror lookups) runs in
            // parallel — the home screen never waits for the scan anymore.
            pageJob?.cancel()
            loadCategoryPage(reset = true)
            loading.value = false
            // Featured row loads in parallel — the network chart never holds
            // back the first paint of the local catalog.
            viewModelScope.launch { loadDiscovery() }
            refreshing.value = false
            viewModelScope.launch {
                when (val result = checkForUpdates.invoke(forceRefresh)) {
                    is AppResult.Failure -> error.value = result.error.userMessage
                    is AppResult.Success<*> -> error.value = null
                }
            }
        }
    }

    fun selectCategory(name: String?) {
        if (selectedCategory.value == name) return
        selectedCategory.value = name
        categoryApps.value = emptyList()
        canLoadMore.value = false
        pageJob?.cancel()
        pageJob = viewModelScope.launch { loadCategoryPage(reset = true) }
    }

    fun loadMore() {
        if (loadingMore.value || !canLoadMore.value) return
        if (pageJob?.isActive == true) return
        pageJob = viewModelScope.launch { loadCategoryPage(reset = false) }
    }

    // --- Layout preferences ---

    fun setGridColumns(columns: Int) {
        viewModelScope.launch { settingsDataStore.setHomeGridColumns(columns) }
    }

    fun setIconSize(size: IconSize) {
        viewModelScope.launch { settingsDataStore.setHomeIconSize(size) }
    }

    fun setLayoutStyle(style: HomeLayoutStyle) {
        viewModelScope.launch { settingsDataStore.setHomeLayoutStyle(style) }
    }

    fun dismissError() {
        error.value = null
    }

    /** Featured: Play top-free chart while signed in, otherwise fresh catalog picks. */
    /**
     * Recommendations + themed shelves from the public Play storefront
     * (no account, cached 30 min, no Play-session rate limits). Every launch
     * draws a DIFFERENT subset/order, and apps already installed are never
     * recommended — so Home never looks the same twice.
     */
    private suspend fun loadDiscovery() = kotlinx.coroutines.coroutineScope {
        val installed = runCatching {
            installedAppsRepository.observe().first().map { it.packageName }.toSet()
        }.getOrDefault(emptySet())
        val random = kotlin.random.Random(System.nanoTime())
        val shelfIds = listOf<String?>(null) + PlayShelves.allFeed.shuffled(random).take(HERO_SOURCES)
        val loaded = shelfIds.map { id ->
            async { id to runCatching { catalogRepository.playStorefront(id) }.getOrDefault(emptyList()) }
        }.awaitAll()

        val nextShelves = loaded.mapNotNull { (id, apps) ->
            val fresh = apps.filterNot { it.packageName in installed }.shuffled(random).take(SHELF_SIZE)
            if (fresh.size < MIN_SHELF_SIZE) null else HomeShelf(id ?: SHELF_HOME, fresh)
        }

        // Hero: well-rated, not installed, across all loaded shelves.
        val pool = loaded.flatMap { it.second }
            .filterNot { it.packageName in installed }
            .distinctBy { it.packageName }
        val rated = pool.filter { (it.rating ?: 0f) >= 4.0f }
        var hero = (rated.ifEmpty { pool }).shuffled(random).take(FEATURED_LIMIT)
        if (hero.isEmpty()) {
            // Offline / web catalog disabled: session chart, then repositories.
            hero = runCatching { playStoreRepository.topFreeApps() }.getOrDefault(emptyList())
                .filterNot { it.packageName in installed }
                .ifEmpty { catalogRepository.observeRecentlyAdded(limit = 30).first() }
                .shuffled(random)
                .take(FEATURED_LIMIT)
        }
        if (featured.value != hero) featured.value = hero
        if (shelves.value != nextShelves) shelves.value = nextShelves
        // The pages just loaded double as ready-made rows.
        val rows = loaded.associate { (id, apps) ->
            (if (id == null) ROW_PLAY_HOME else "play:$id") to apps.take(ROW_SIZE)
        }.filterValues { it.isNotEmpty() }
        rowsRequested.addAll(rows.keys)
        rowContentState.value = rowContentState.value + rows
    }

    /** Scanned text → app / search, following short links' redirects. */
    fun resolveScanned(raw: String, onResult: (com.novastore.app.core.model.StoreLink?) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { catalogRepository.resolveStoreLink(raw) }.getOrNull()
                ?: com.novastore.app.core.model.StoreLinks.parse(raw))
        }
    }

    /** "See all" on a shelf: the browse grid below switches to that shelf. */
    fun openShelf(id: String) {
        selectCategory(if (id == SHELF_HOME) null else "play:$id")
    }

    // ------------------------------------------------------------------
    // Browse feed: Play storefront shelves interleaved with repository pages
    // ------------------------------------------------------------------

    private sealed interface FeedStep {
        data class Shelf(val id: String?) : FeedStep
        data object Local : FeedStep
    }

    private var pageJob: kotlinx.coroutines.Job? = null
    private var feedPlan: List<FeedStep> = emptyList()
    private var feedIndex = 0
    private var localOffset = 0
    private var localExhausted = false
    private val seenPackages = HashSet<String>()

    private fun buildPlan(category: String?): List<FeedStep> = when {
        category == null -> buildList {
            // "All": Play home, games, then Play categories, each followed by
            // a page of the local repositories.
            add(FeedStep.Shelf(null))
            add(FeedStep.Local)
            PlayShelves.allFeed.forEach { add(FeedStep.Shelf(it)); add(FeedStep.Local) }
        }
        PlayShelves.isShelf(category) -> listOf(FeedStep.Shelf(PlayShelves.idOf(category)))
        else -> listOf(FeedStep.Local)
    }

    private fun hasMoreSteps(category: String?): Boolean =
        feedIndex < feedPlan.size || (!PlayShelves.isShelf(category) && !localExhausted)

    private suspend fun loadCategoryPage(reset: Boolean) {
        loadingMore.value = true
        try {
            val category = selectedCategory.value
            if (reset) {
                feedPlan = buildPlan(category)
                feedIndex = 0
                localOffset = 0
                localExhausted = false
                seenPackages.clear()
            }
            val localCategory = category?.takeUnless { PlayShelves.isShelf(it) }
            val added = mutableListOf<RemoteApp>()
            // Step until this page produced something new (or nothing is left).
            while (added.size < MIN_PAGE_ITEMS && hasMoreSteps(category)) {
                val step = if (feedIndex < feedPlan.size) feedPlan[feedIndex++] else FeedStep.Local
                val batch = when (step) {
                    is FeedStep.Shelf -> runCatching { catalogRepository.playStorefront(step.id) }
                        .getOrDefault(emptyList())
                    FeedStep.Local -> {
                        if (localExhausted) {
                            emptyList()
                        } else {
                            val page = catalogRepository.listByCategory(localCategory, localOffset, PAGE_SIZE)
                            localOffset += page.size
                            if (page.size < PAGE_SIZE) localExhausted = true
                            page
                        }
                    }
                }
                batch.filterTo(added) { seenPackages.add(it.packageName) }
            }
            val merged = (if (reset) emptyList() else categoryApps.value) + added
            if (categoryApps.value != merged) categoryApps.value = merged
            canLoadMore.value = hasMoreSteps(category)
        } finally {
            loadingMore.value = false
        }
    }

    // Declared LAST on purpose: init launches work on Main.immediate, which
    // runs synchronously inside the constructor — every property above
    // (feed state, flows) must already be initialized by then.
    init {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { online -> offline.value = !online }
        }
        accountRepository.start()
        runRefresh(forceRefresh = false)
    }

    private companion object {
        const val FEATURED_LIMIT = 8
        const val SHELF_COUNT = 5
        const val SHELF_SIZE = 18
        const val MIN_SHELF_SIZE = 4
        const val SHELF_HOME = "home"
        const val HERO_SOURCES = 2
        const val ROW_SIZE = 24
        const val ROW_PLAY_HOME = "play:home"
        const val PAGE_SIZE = 60
        const val MIN_PAGE_ITEMS = 24
    }
}
