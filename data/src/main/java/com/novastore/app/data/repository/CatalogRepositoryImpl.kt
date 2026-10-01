package com.novastore.app.data.repository

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.database.dao.CatalogDao
import com.novastore.app.core.database.dao.RepositoryDao
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.AppLanguage
import com.novastore.app.core.model.AppReview
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.data.mapper.remoteDetails
import com.novastore.app.data.mapper.toModel
import com.novastore.app.data.websource.ApkComboClient
import com.novastore.app.data.websource.ApkPureClient
import com.novastore.app.data.websource.GitHubClient
import com.novastore.app.data.websource.GitLabClient
import com.novastore.app.data.websource.PlayReviewsClient
import com.novastore.app.data.websource.PlayWebClient
import com.novastore.app.domain.repository.CatalogRepository
import com.novastore.app.domain.repository.PlayStoreRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Read access to the app catalog: F-Droid repositories, the Nova Web Catalog
 * (anonymous public Play pages), the GitHub and GitLab release catalogs, and
 * the community mirror chain for anonymous downloads.
 *
 * Access tiers, fully automatic:
 *  - **Signed-in session** — the native Play protocol (search, details,
 *    versions, delivery);
 *  - **Nova Web Catalog** — anonymous play.google.com pages (search, details,
 *    screenshots, ratings, full descriptions, user reviews);
 *  - **GitHub / GitLab** — release-tracked open-source apps;
 *  - **Community mirrors** — APKPure then APKCombo: anonymous version
 *    histories merged into details so every app shows an up-to-date
 *    "available version" list even when nobody is signed in.
 */
@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val catalogDao: CatalogDao,
    private val repositoryDao: RepositoryDao,
    private val playStoreRepository: PlayStoreRepository,
    private val playWebClient: PlayWebClient,
    private val apkPureClient: ApkPureClient,
    private val apkComboClient: ApkComboClient,
    private val gitHubClient: GitHubClient,
    private val gitLabClient: GitLabClient,
    private val playReviewsClient: PlayReviewsClient,
    private val settingsDataStore: SettingsDataStore,
    private val screenshotProber: FdroidScreenshotProber,
    private val storefrontCache: com.novastore.app.data.websource.StorefrontDiskCache,
    private val httpClient: okhttp3.OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) : CatalogRepository {

    override suspend fun search(query: String): AppResult<List<RemoteApp>> =
        AppResult.success(remember(mergedSearch(query)))

    /** Last-shown list entry per package (bounded). */
    private val previews = object : android.util.LruCache<String, RemoteApp>(PREVIEW_CACHE) {}

    private fun remember(apps: List<RemoteApp>): List<RemoteApp> {
        apps.forEach { app -> if (app.name.isNotBlank()) previews.put(app.packageName, app) }
        return apps
    }

    override fun preview(packageName: String): RemoteApp? = previews.get(packageName)

    override suspend fun resolveStoreLink(raw: String): com.novastore.app.core.model.StoreLink? =
        withContext(dispatcherProvider.io) {
            val direct = com.novastore.app.core.model.StoreLinks.parse(raw)
            if (direct is com.novastore.app.core.model.StoreLink.App) return@withContext direct
            var current = com.novastore.app.core.model.StoreLinks.extractUrl(raw) ?: return@withContext direct
            if (!current.startsWith("http")) return@withContext direct
            // Follow redirects by hand: every hop is checked, and a landing
            // page mentioning a store link is scanned as well.
            val client = httpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            repeat(MAX_REDIRECTS) {
                val request = okhttp3.Request.Builder().url(current)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36")
                    .build()
                val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext direct
                response.use { res ->
                    val location = res.header("Location")
                    if (location != null) {
                        val next = runCatching { java.net.URI(current).resolve(location).toString() }.getOrDefault(location)
                        val parsed = com.novastore.app.core.model.StoreLinks.parse(next)
                        if (parsed is com.novastore.app.core.model.StoreLink.App) return@withContext parsed
                        if (!next.startsWith("http")) return@withContext direct
                        current = next
                    } else {
                        // Landing page: look for a store link inside it.
                        val body = runCatching { res.peekBody(LANDING_PEEK_BYTES).string() }.getOrDefault("")
                        val found = STORE_LINK_IN_PAGE.find(body)?.value?.replace("&amp;", "&")
                        val parsed = found?.let { com.novastore.app.core.model.StoreLinks.parse(it) }
                        return@withContext if (parsed is com.novastore.app.core.model.StoreLink.App) parsed else direct
                    }
                }
            }
            direct
        }

    override suspend fun playStorefront(shelf: String?): List<RemoteApp> = remember(playStorefrontRaw(shelf))

    private suspend fun playStorefrontRaw(shelf: String?): List<RemoteApp> {
        if (!settingsDataStore.playWebCatalogEnabledSnapshot()) return emptyList()
        val language = languageTag()
        val key = "${shelf ?: "home"}-$language"
        val cached = storefrontCache.read(key)
        if (cached != null && cached.second) return cached.first
        val fresh = enrichWithPlay(runCatching { playWebClient.collection(shelf, language) }.getOrDefault(emptyList()))
        if (fresh.isNotEmpty()) {
            storefrontCache.write(key, fresh)
            return fresh
        }
        // Offline or a blocked page: yesterday's shelf beats an empty one.
        return cached?.first ?: emptyList()
    }

    /**
     * The public storefront pages carry names and icons only. One bulk Play
     * request per 50 apps adds the real rating, downloads, size and price —
     * then it is cached with the shelf, so lists and rows are never "empty"
     * cards. Bounded in time: a slow/throttled Play never delays the shelf.
     */
    private suspend fun enrichWithPlay(apps: List<RemoteApp>): List<RemoteApp> {
        if (apps.isEmpty()) return apps
        val summaries = kotlinx.coroutines.withTimeoutOrNull(ENRICH_TIMEOUT_MS) {
            runCatching { playStoreRepository.summaries(apps.map { it.packageName }) }.getOrNull()
        } ?: return apps
        return apps.map { app ->
            val play = summaries[app.packageName] ?: return@map app
            app.copy(
                rating = app.rating ?: play.rating,
                downloads = app.downloads ?: play.downloads,
                sizeBytes = app.sizeBytes ?: play.sizeBytes,
                developer = app.developer ?: play.developer,
                summary = app.summary ?: play.summary,
                isFree = play.isFree,
                containsAds = play.containsAds,
                altIconUrl = play.iconUrl?.takeIf { it != app.iconUrl },
            )
        }
    }

    override suspend fun searchLocal(query: String): List<RemoteApp> = withContext(dispatcherProvider.io) {
        remember(
            catalogDao.search(query)
                .distinctBy { it.packageName }
                .map { it.toModel() }
                .take(SEARCH_LIMIT),
        )
    }

    private suspend fun mergedSearch(query: String): List<RemoteApp> = withContext(dispatcherProvider.io) {
        // Every source runs CONCURRENTLY — a slow or timing-out source (Play
        // Web, GitLab) no longer serially delays the whole result; the merge
        // order below stays deterministic.
        coroutineScope {
            val fdroidDeferred = async {
                catalogDao.search(query)
                    .distinctBy { it.packageName } // results are ordered so the preferred repository comes first
                    .map { it.toModel() }
            }
            // Native Play protocol (account OR anonymous session) and the
            // public web catalog run side by side: each finds apps the other
            // misses, the union is what makes "every app is findable" true.
            val playNativeDeferred = async {
                runCatching { playStoreRepository.search(query) }.getOrDefault(emptyList())
            }
            val playWebDeferred = async {
                if (settingsDataStore.playWebCatalogEnabledSnapshot()) {
                    runCatching { playWebClient.search(query, languageTag()) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
            // A package-name query resolves that exact app directly.
            val exactDeferred = async {
                if (PACKAGE_NAME.matches(query.trim())) {
                    runCatching { playStoreRepository.getAppDetails(query.trim())?.app }.getOrNull()
                } else {
                    null
                }
            }
            val githubDeferred = async {
                if (settingsDataStore.githubCatalogEnabledSnapshot()) {
                    runCatching { gitHubClient.search(query) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }
            val gitlabDeferred = async {
                if (settingsDataStore.gitlabCatalogEnabledSnapshot()) {
                    runCatching { gitLabClient.search(query) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            val fdroid = fdroidDeferred.await()
            val play = (listOfNotNull(exactDeferred.await()) + playNativeDeferred.await() + playWebDeferred.await())
                .distinctBy { it.packageName }
            val github = githubDeferred.await()
            val gitlab = gitlabDeferred.await()

            val looksLikePackage = query.contains('.')

            val exact = mutableListOf<RemoteApp>()
            val playResults = mutableListOf<RemoteApp>()
            val fdroidResults = mutableListOf<RemoteApp>()
            val devResults = mutableListOf<RemoteApp>()

            for (app in play) (if (app.packageName.equals(query, ignoreCase = true)) exact else playResults).add(app)
            for (app in fdroid) (if (app.packageName.equals(query, ignoreCase = true)) exact else fdroidResults).add(app)
            for (app in github + gitlab) devResults.add(app)

            // Package-like queries prioritize Play (it is the authoritative source
            // for package names); free text queries keep the local catalog first
            // with developer-platform entries at the end.
            val ordered = if (looksLikePackage) {
                exact + playResults + fdroidResults + devResults
            } else {
                exact + fdroidResults + playResults + devResults
            }

            ordered
                .distinctBy { it.source to it.packageName }
                .take(SEARCH_LIMIT)
        }
    }

    override suspend fun getAppDetails(packageName: String): AppResult<RemoteAppDetails?> =
        AppResult.success(mergedDetails(packageName))

    private suspend fun mergedDetails(packageName: String): RemoteAppDetails? =
        withContext(dispatcherProvider.io) {
            when {
                gitHubClient.isSyntheticPackage(packageName) ->
                    return@withContext githubDetails(packageName)
                gitLabClient.isSyntheticPackage(packageName) ->
                    return@withContext gitlabDetails(packageName)
            }

            val fdroid = catalogDao.getApp(packageName)?.let { app ->
                val details = remoteDetails(app, catalogDao.getVersions(packageName))
                // Fastlane screenshots shipped inside the repository itself.
                val repoBase = runCatching { repositoryDao.get(app.source)?.baseUrl }.getOrNull()
                if (repoBase != null && details.screenshots.isEmpty()) {
                    val shots = runCatching {
                        screenshotProber.probeScreenshots(repoBase, packageName)
                    }.getOrDefault(emptyList())
                    if (shots.isNotEmpty()) details.copy(screenshots = shots) else details
                } else {
                    details
                }
            }
            // Native Play details (account or anonymous session) carry the
            // exact version for THIS device; the web catalog fills in when
            // Play's protocol is unreachable, and enriches the listing.
            val native = runCatching { playStoreRepository.getAppDetails(packageName) }.getOrNull()
            val web = if (settingsDataStore.playWebCatalogEnabledSnapshot() &&
                (native == null || native.screenshots.isEmpty() || native.description.isNullOrBlank())
            ) {
                runCatching { playWebClient.details(packageName, languageTag()) }.getOrNull()
            } else {
                null
            }
            val play = when {
                native == null -> web
                web == null -> native
                else -> native.copy(
                    app = native.app.copy(
                        iconUrl = native.app.iconUrl ?: web.app.iconUrl,
                        altIconUrl = web.app.iconUrl?.takeIf { it != native.app.iconUrl },
                    ),
                    developerEmail = native.developerEmail ?: web.developerEmail,
                    description = native.description?.takeIf { it.isNotBlank() } ?: web.description,
                    screenshots = native.screenshots.ifEmpty { web.screenshots },
                    ratingHistogram = native.ratingHistogram ?: web.ratingHistogram,
                    updatedMillis = native.updatedMillis ?: web.updatedMillis,
                    releasedMillis = native.releasedMillis ?: web.releasedMillis,
                )
            }
            val merged = mergeDetails(fdroid, play)
            mergeMirrorVersions(merged, packageName)
        }

    private suspend fun githubDetails(packageName: String): RemoteAppDetails? {
        val fullName = gitHubClient.fullNameOf(packageName) ?: return null
        val details = runCatching { gitHubClient.details(fullName) }.getOrNull() ?: return null
        val releases = runCatching { gitHubClient.releases(fullName) }.getOrDefault(emptyList())
        return details.copy(versions = releases)
    }

    private suspend fun gitlabDetails(packageName: String): RemoteAppDetails? {
        val projectId = gitLabClient.projectIdOf(packageName) ?: return null
        val details = runCatching { gitLabClient.details(projectId) }.getOrNull() ?: return null
        val releases = runCatching { gitLabClient.releases(projectId, packageName) }.getOrDefault(emptyList())
        return details.copy(versions = releases)
    }

    override suspend fun getReviews(packageName: String): List<AppReview> =
        withContext(dispatcherProvider.io) {
            if (gitHubClient.isSyntheticPackage(packageName) || gitLabClient.isSyntheticPackage(packageName)) {
                return@withContext emptyList()
            }
            val language = settingsDataStore.appLanguage.first()
            val tag = language.tag ?: Locale.getDefault().toLanguageTag()
            runCatching { playReviewsClient.reviews(packageName, tag) }.getOrDefault(emptyList())
        }

    /**
     * Anonymous tier: when nobody signed in, the community mirror chain
     * (APKPure → APKCombo) fills the "available version" list so every app
     * can be installed/updated without an account.
     */
    private suspend fun mergeMirrorVersions(
        details: RemoteAppDetails?,
        packageName: String,
    ): RemoteAppDetails? {
        if (details == null) return null
        if (details.versions.isNotEmpty()) return details
        if (settingsDataStore.apkPureMirrorEnabledSnapshot()) {
            val mirrorVersions = runCatching { apkPureClient.versions(packageName) }.getOrDefault(emptyList())
            if (mirrorVersions.isNotEmpty()) return details.copy(versions = mirrorVersions)
        }
        if (settingsDataStore.apkComboMirrorEnabledSnapshot()) {
            val comboVersions = runCatching { apkComboClient.versions(packageName) }.getOrDefault(emptyList())
            if (comboVersions.isNotEmpty()) return details.copy(versions = comboVersions)
        }
        return details
    }

    /**
     * Merge rules:
     *  - only one source present → that one wins;
     *  - both present → F-Droid metadata/description/versions plus Play
     *    enrichment (screenshots, video, rating, downloads, price).
     */
    private fun mergeDetails(fdroid: RemoteAppDetails?, play: RemoteAppDetails?): RemoteAppDetails? {
        if (fdroid == null) return play
        if (play == null) return fdroid

        val app = fdroid.app.copy(
            rating = fdroid.app.rating ?: play.app.rating,
            downloads = fdroid.app.downloads ?: play.app.downloads,
            sizeBytes = fdroid.app.sizeBytes ?: play.app.sizeBytes,
            updatedMillis = play.app.updatedMillis ?: fdroid.app.updatedMillis,
            containsAds = play.app.containsAds,
            isFree = play.app.isFree,
            // Play icons are always served; repository icon paths sometimes
            // 404 (moved files) — Play first, the repository icon as fallback.
            iconUrl = play.app.iconUrl ?: fdroid.app.iconUrl,
            altIconUrl = fdroid.app.iconUrl?.takeIf { it != play.app.iconUrl },
            summary = fdroid.app.summary ?: play.app.summary,
        )
        return fdroid.copy(
            app = app,
            // Play's own build rides along: an app installed from Play (or
            // signed with the Play key) is updated from Play, an app installed
            // from F-Droid keeps its F-Droid builds — the details screen picks
            // by signing key.
            versions = fdroid.versions + play.versions.filter { it.source == com.novastore.app.core.model.SOURCE_PLAY },
            // Play descriptions are the full listing text; F-Droid summaries stay authoritative.
            description = play.description ?: fdroid.description,
            changelog = fdroid.changelog ?: play.changelog,
            screenshots = if (play.screenshots.isNotEmpty()) play.screenshots else fdroid.screenshots,
            videoUrl = play.videoUrl,
            ratingCount = play.ratingCount ?: fdroid.ratingCount,
            developerEmail = play.developerEmail ?: fdroid.developerEmail,
            price = play.price,
            ratingHistogram = play.ratingHistogram,
            updatedMillis = play.updatedMillis,
            releasedMillis = play.releasedMillis,
            permissions = play.permissions,
            dependencies = play.dependencies,
            developerAddress = play.developerAddress,
            productInfo = play.productInfo,
            uploadDate = play.uploadDate,
        )
    }

    override fun observeRecentlyAdded(limit: Int): Flow<List<RemoteApp>> =
        catalogDao.observeRecent(limit).map { entities -> entities.map { it.toModel() } }

    override fun observeCategories(): Flow<List<String>> =
        catalogDao.observeCategoryStrings().map { rows ->
            rows.flatMap { it.split(CATEGORY_SEPARATOR) }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
        }

    override suspend fun listByCategory(category: String?, offset: Int, limit: Int): List<RemoteApp> =
        withContext(dispatcherProvider.io) {
            val entities = if (category.isNullOrBlank()) {
                catalogDao.listRecent(offset, limit)
            } else {
                catalogDao.listByCategory(category, offset, limit)
            }
            remember(entities.map { it.toModel() })
        }

    override suspend fun getVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>> =
        packageNames.distinct()
            .chunked(QUERY_CHUNK) // stay below SQLite's bound-parameter limit
            .flatMap { chunk -> catalogDao.getVersionsFor(chunk) }
            .map { it.toModel() }
            .groupBy { it.packageName }

    /** Resolves the catalog content language: the app language or the device default. */
    private suspend fun languageTag(): String {
        val language = settingsDataStore.appLanguage.first()
        return language.tag ?: Locale.getDefault().toLanguageTag()
    }

    private companion object {
        const val CATEGORY_SEPARATOR = "|"
        const val SEARCH_LIMIT = 120
        const val QUERY_CHUNK = 500
        const val PREVIEW_CACHE = 2000
        const val MAX_REDIRECTS = 6
        const val LANDING_PEEK_BYTES = 512L * 1024
        val STORE_LINK_IN_PAGE = Regex("""(https://play\.google\.com/store/apps/details\?id=[A-Za-z0-9._]+|market://details\?id=[A-Za-z0-9._]+)""")
        const val ENRICH_TIMEOUT_MS = 8_000L
        val PACKAGE_NAME = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    }
}
