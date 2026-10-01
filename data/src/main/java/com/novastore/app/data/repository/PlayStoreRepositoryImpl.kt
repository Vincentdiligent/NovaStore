package com.novastore.app.data.repository

import com.novastore.playapi.AnonymousAuth
import com.novastore.playapi.data.models.App
import com.novastore.playapi.data.models.AuthData
import com.novastore.playapi.data.models.Rating
import com.novastore.playapi.exceptions.ApiException
import com.novastore.playapi.exceptions.AuthException
import com.novastore.playapi.helpers.AppDetailsHelper
import com.novastore.playapi.helpers.AuthHelper
import com.novastore.playapi.helpers.AuthValidator
import com.novastore.playapi.helpers.PurchaseHelper
import com.novastore.playapi.helpers.SearchHelper
import com.novastore.playapi.helpers.TopChartsHelper
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.AuthMethod
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PlayDownloadFile
import com.novastore.app.core.model.PlayStoreException
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_APKCOMBO
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.data.playauth.PlayAuthSession
import com.novastore.app.data.playauth.PlayDeviceProperties
import com.novastore.app.data.websource.ApkComboClient
import com.novastore.app.data.websource.ApkPureClient
import com.novastore.app.domain.repository.PlayStoreRepository
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Notified when the Play session turns out to be invalid (tokens revoked). */
fun interface PlaySessionListener {
    suspend fun onSessionInvalidated()
}

/**
 * Google Play repository on top of the vendored GPlayApi helpers.
 *
 * Holds the current [AuthData] (volatile) and rebuilds it from the persisted
 * aasToken when a call fails with [AuthException] (one retry, then the
 * session is dropped and the persisted session cleared).
 *
 * Session order for EVERY Play call (search, details, bulk versions,
 * delivery):
 *  1. the signed-in account (device account / password), when present;
 *  2. an **anonymous Play session** minted lazily from the anonymous token
 *     dispenser — a custom URL from Settings → Sources first, then the
 *     built-in public dispenser. Genuine Play
 *     protocol and original Play files with no account on the device and no
 *     Google services required; kept in memory only and re-minted
 *     automatically when Play rejects it;
 *  3. for downloads only: the community mirror chain (APKPure → APKCombo).
 */
@Singleton
class PlayStoreRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val apkPureClient: ApkPureClient,
    private val apkComboClient: ApkComboClient,
    private val dispatcherProvider: DispatcherProvider,
    private val deviceProperties: PlayDeviceProperties,
) : PlayStoreRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    /** Out-of-band resolved mirror links (WebView mirror browser), TTL-bounded. */
    private val mirrorOverrides = ConcurrentHashMap<String, Pair<Long, PlayDownloadFile>>()
    private val mirrorOverrideCodes = ConcurrentHashMap<String, Long>()

    /** Lazily minted anonymous Play session (in-memory only, never persisted). */
    @Volatile
    private var anonymousAuth: AuthData? = null

    /** When the last anonymous bootstrap FAILED — retry is TTL-bounded. */
    @Volatile
    private var anonymousAuthFailedAt: Long = 0

    /** Device profile the current anonymous session was minted with. */
    @Volatile
    private var anonymousProfile: String? = null

    /** When the current anonymous session was minted (Play tokens live ~1 h). */
    @Volatile
    private var anonymousAuthMintedAt: Long = 0

    private val anonymousBootstrapMutex = kotlinx.coroutines.sync.Mutex()

    // ------------------------------------------------------------------
    // Rate-limit armour (HTTP 429): traffic shaping + caches + coalescing
    // ------------------------------------------------------------------

    /** Spaces and bounds every native Play request; cools down after a 429. */
    private val gate = com.novastore.app.data.playauth.PlayRequestGate()

    /** Full app documents by package (details screen, latest version, delivery). */
    private val appCache = ConcurrentHashMap<String, Pair<Long, App>>()

    /** Concurrent lookups of the same package share ONE request. */
    private val appInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<App?>>()

    /** Bulk version answers by package (update scans). */
    private val versionCache = ConcurrentHashMap<String, Pair<Long, AppVersion>>()

    /** Bulk listing summaries by package (storefront enrichment). */
    private val summaryCache = ConcurrentHashMap<String, Pair<Long, RemoteApp>>()

    /** Search result pages by query. */
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<RemoteApp>>>()

    @Volatile
    private var topChartCache: Pair<Long, List<RemoteApp>>? = null

    private fun <V> Pair<Long, V>.freshFor(ttl: Long): V? =
        second.takeIf { System.currentTimeMillis() - first < ttl }

    /** One cached, coalesced details request per package. */
    private suspend fun cachedApp(packageName: String): App? {
        appCache[packageName]?.freshFor(APP_CACHE_TTL_MS)?.let { return it }
        val job = appInFlight.computeIfAbsent(packageName) {
            scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    runCatching {
                        withAuth { auth -> AppDetailsHelper.with(auth).getAppByPackageName(packageName) }
                    }.getOrNull()?.takeIf { it.versionCode > 0 }?.also {
                        appCache[packageName] = System.currentTimeMillis() to it
                    }
                } finally {
                    appInFlight.remove(packageName)
                }
            }
        }
        return job.await()
    }

    @Volatile
    private var authData: AuthData? = null

    @Volatile
    private var authMethod: AuthMethod = AuthMethod.GOOGLE_PASSWORD

    /** Set by [AccountRepositoryImpl] to react to session invalidation. */
    @Volatile
    var sessionListener: PlaySessionListener? = null

    // ------------------------------------------------------------------
    // Session management (used by AccountRepositoryImpl)
    // ------------------------------------------------------------------

    /**
     * Restores the persisted session: rebuilds the AuthData from the stored
     * aasToken, validates it, and activates it. Returns null (and clears the
     * persisted session) when no session exists or it is no longer valid.
     */
    suspend fun restoreSession(): AuthData? = withContext(dispatcherProvider.io) {
        val json = settingsDataStore.playAuthSessionSnapshot() ?: return@withContext null
        val session = PlayAuthSession.fromJson(json)
        if (session == null) {
            settingsDataStore.clearPlayAuthSession()
            return@withContext null
        }
        val auth = runCatching {
            AuthHelper.build(session.email, session.aasToken, deviceProperties.resolve(session.deviceProfile))
        }.getOrNull()
        if (auth == null) {
            settingsDataStore.clearPlayAuthSession()
            return@withContext null
        }
        val valid = runCatching { AuthValidator.with(auth).isValid() }.getOrDefault(false)
        if (!valid) {
            settingsDataStore.clearPlayAuthSession()
            return@withContext null
        }
        setAuthData(auth, session.authMethod)
        auth
    }

    /** Activates (or clears) the in-memory session. */
    fun setAuthData(auth: AuthData?, method: AuthMethod) {
        val previous = authData
        authMethod = method
        authData = auth
        if (previous != null && previous !== auth) {
            // The GPlayApi helpers cache one instance per auth — reset them so a
            // new session is not served with stale credentials.
            resetHelperCaches()
        }
        if (auth == null) resetHelperCaches()
    }

    fun currentAuthData(): AuthData? = authData

    fun currentAuthMethod(): AuthMethod = authMethod

    private fun resetHelperCaches() {
        SearchHelper.clear()
        AppDetailsHelper.clear()
        PurchaseHelper.clear()
        AuthValidator.clear()
        TopChartsHelper.clear()
    }

    private fun invalidateSession() {
        authData = null
        scope.launch {
            settingsDataStore.clearPlayAuthSession()
            sessionListener?.let { runCatching { it.onSessionInvalidated() } }
        }
    }

    // ------------------------------------------------------------------
    // PlayStoreRepository
    // ------------------------------------------------------------------

    override suspend fun isLoggedIn(): Boolean = authData != null

    override suspend fun hasPlayAccess(): Boolean =
        authData != null || anonymousSession() != null

    override suspend fun search(query: String): List<RemoteApp> {
        val key = query.trim().lowercase()
        searchCache[key]?.freshFor(SEARCH_CACHE_TTL_MS)?.let { return it }
        return searchUncached(query).also { if (it.isNotEmpty()) searchCache[key] = System.currentTimeMillis() to it }
    }

    private suspend fun searchUncached(query: String): List<RemoteApp> = runCatching {
        withAuth { auth ->
            val helper = SearchHelper.with(auth)
            val first = helper.searchResults(query)
            val apps = LinkedHashMap<String, App>()
            first.appList.forEach { apps.putIfAbsent(it.packageName, it) }
            // Play serves results in small pages — follow the next-page links
            // until the list is useful (bounded, so search stays fast).
            var bundles = first.subBundles.filter { it.nextPageUrl.isNotBlank() }.toMutableSet()
            var pages = 0
            while (apps.size < SEARCH_LIMIT && bundles.isNotEmpty() && pages < SEARCH_EXTRA_PAGES) {
                val next = runCatching { helper.next(bundles) }.getOrNull() ?: break
                next.appList.forEach { apps.putIfAbsent(it.packageName, it) }
                bundles = next.subBundles.filter { it.nextPageUrl.isNotBlank() }.toMutableSet()
                pages++
            }
            apps.values
                .filter { it.packageName.isNotBlank() }
                .take(SEARCH_LIMIT)
                .map { it.toRemoteApp() }
        }
    }.getOrDefault(emptyList())

    override suspend fun topFreeApps(): List<RemoteApp> {
        topChartCache?.freshFor(CHART_CACHE_TTL_MS)?.let { return it }
        return runCatching {
            withAuth { auth ->
                TopChartsHelper.with(auth)
                    .getCluster(TopChartsHelper.Type.APPLICATION, TopChartsHelper.Chart.TOP_SELLING_FREE)
                    .appList
                    .map { it.toRemoteApp() }
            }
        }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) topChartCache = System.currentTimeMillis() to it }
    }

    override suspend fun getAppDetails(packageName: String): RemoteAppDetails? =
        cachedApp(packageName)?.toRemoteDetails()

    override suspend fun summaries(packageNames: Collection<String>): Map<String, RemoteApp> {
        val wanted = packageNames.filter { it.isNotBlank() }.distinct()
        val result = HashMap<String, RemoteApp>()
        val missing = ArrayList<String>()
        for (pkg in wanted) {
            val cached = summaryCache[pkg]?.freshFor(APP_CACHE_TTL_MS * 4)
            if (cached != null) result[pkg] = cached else missing += pkg
        }
        for (chunk in missing.chunked(BULK_CHUNK)) {
            val apps = runCatching {
                withAuth { auth -> AppDetailsHelper.with(auth).getAppByPackageName(chunk) }
            }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            for (app in apps) {
                if (app.packageName.isBlank() || app.versionCode <= 0) continue
                val summary = app.toRemoteApp()
                summaryCache[app.packageName] = now to summary
                result[app.packageName] = summary
            }
        }
        return result
    }

    override suspend fun getVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>> {
        val wanted = packageNames.filter { it.isNotBlank() }.distinct()
        val result = HashMap<String, List<AppVersion>>()
        val missing = ArrayList<String>()
        for (pkg in wanted) {
            val cached = versionCache[pkg]?.freshFor(VERSION_CACHE_TTL_MS)
            if (cached != null) result[pkg] = listOf(cached) else missing += pkg
        }
        // Each chunk is its own gated request: one throttled chunk never
        // throws away the answers of the others.
        for (chunk in missing.chunked(BULK_CHUNK)) {
            val apps = runCatching {
                withAuth { auth -> AppDetailsHelper.with(auth).getAppByPackageName(chunk) }
            }.getOrDefault(emptyList())
            val now = System.currentTimeMillis()
            for (app in apps) {
                if (app.versionCode <= 0) continue
                val version = app.toAppVersion()
                versionCache[app.packageName] = now to version
                result[app.packageName] = listOf(version)
            }
        }
        return result
    }

    override suspend fun resolveLatestVersion(packageName: String): AppVersion? =
        cachedApp(packageName)?.toAppVersion()

    override suspend fun purchaseDownloadFiles(packageName: String, versionCode: Long): List<PlayDownloadFile> =
        withContext(dispatcherProvider.io) {
            // Genuine Play delivery through whatever session is available
            // (account -> anonymous session). Anything Play refuses (paid app,
            // region lock, no session, token trouble) falls through to the
            // community mirrors instead of becoming the user's error.
            val playFailure: Throwable = try {
                val files = withAuth { auth ->
                    PurchaseHelper.with(auth).purchase(packageName, versionCode.toInt(), OFFER_TYPE)
                }
                return@withContext mapPlayFiles(files, packageName)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                t
            }

            val mirror = runCatching { resolveMirrorChain(packageName, versionCode, null) }.getOrNull()
            if (mirror != null) return@withContext mirror

            throw when (playFailure) {
                is ApiException.AppNotPurchased -> PlayStoreException(
                    NovaError.Metadata(
                        userMessage = "This app is paid on Google Play and no free mirror carries it.",
                        packageName = packageName,
                    ),
                )
                is ApiException.AppNotSupported -> PlayStoreException(NovaError.IncompatibleDevice)
                is PlayStoreException -> if (playFailure.error is NovaError.Account) {
                    PlayStoreException(
                        NovaError.Network(
                            userMessage = "Google Play could not be reached anonymously and no mirror " +
                                "carries this app. Check the connection and try again.",
                            cause = playFailure,
                        ),
                    )
                } else {
                    playFailure
                }
                is IOException -> PlayStoreException(
                    NovaError.Network(
                        userMessage = "Google Play delivery failed: ${playFailure.message}",
                        cause = playFailure,
                    ),
                )
                else -> PlayStoreException(
                    NovaError.Metadata(
                        userMessage = "Google Play and the mirrors could not deliver this app right now " +
                            "(${playFailure.message ?: playFailure.javaClass.simpleName}). Try again in a minute.",
                        packageName = packageName,
                    ),
                )
            }
        }

    override suspend fun resolvePlayLatest(packageName: String): AppVersion? =
        cachedApp(packageName)?.toAppVersion()

    /** Normalizes a Play purchase response into Nova download files. */
    private fun mapPlayFiles(
        files: List<com.novastore.playapi.data.models.File>,
        packageName: String,
    ): List<PlayDownloadFile> {
        val base = files.firstOrNull { it.type == com.novastore.playapi.data.models.File.FileType.BASE }
            ?: throw PlayStoreException(
                NovaError.Metadata(
                    userMessage = "Google Play returned no download files (app may be paid or unavailable).",
                    packageName = packageName,
                ),
            )
        if (base.url.isBlank()) {
            throw PlayStoreException(
                NovaError.Metadata(
                    userMessage = "Google Play returned no download URL for the base APK.",
                    packageName = packageName,
                ),
            )
        }

        val result = mutableListOf(
            PlayDownloadFile(name = base.name, url = base.url, sizeBytes = base.size, isSplit = false),
        )
        // Splits ride along; OBB/PATCH files are not handled yet and are dropped.
        files.filter { it.type == com.novastore.playapi.data.models.File.FileType.SPLIT && it.url.isNotBlank() }
            .forEach { split ->
                result += PlayDownloadFile(name = split.name, url = split.url, sizeBytes = split.size, isSplit = true)
            }
        return result
    }

    // ------------------------------------------------------------------
    // Anonymous Play session (token dispenser, user-configured)
    // ------------------------------------------------------------------

    /**
     * Returns the anonymous Play session, minting it on first use from the
     * dispensers (custom URL from Settings first, then the built-in public
     * dispenser while "Anonymous Google Play" is on). The session lives in
     * memory only; it is re-minted before Play's ~1 h token lifetime ends,
     * and a failed bootstrap is retried after [ANONYMOUS_BOOTSTRAP_TTL_MS]
     * (short, so a network blip does not disable Play for long).
     */
    private suspend fun anonymousSession(): AuthData? {
        // A new device profile in Settings takes effect immediately.
        val profileNow = settingsDataStore.playDeviceProfileSnapshot()
        if (anonymousAuth != null && profileNow != anonymousProfile) {
            anonymousAuth = null
            anonymousAuthMintedAt = 0
            anonymousAuthFailedAt = 0
        }
        val now = System.currentTimeMillis()
        anonymousAuth?.let { if (now - anonymousAuthMintedAt < ANONYMOUS_SESSION_MAX_AGE_MS) return it }
        if (now - anonymousAuthFailedAt < ANONYMOUS_BOOTSTRAP_TTL_MS) return anonymousAuth

        return anonymousBootstrapMutex.withLock {
            val again = System.currentTimeMillis()
            anonymousAuth?.let { if (again - anonymousAuthMintedAt < ANONYMOUS_SESSION_MAX_AGE_MS) return@withLock it }
            if (again - anonymousAuthFailedAt < ANONYMOUS_BOOTSTRAP_TTL_MS) return@withLock anonymousAuth

            // A session minted in an earlier launch is reused while it is
            // young: no dispenser round trip on every app start.
            restorePersistedAnonymous()?.let { return@withLock it }

            val urls = buildList {
                settingsDataStore.tokenDispenserUrlSnapshot().trim().takeIf { it.isNotBlank() }?.let(::add)
                if (settingsDataStore.anonymousPlayEnabledSnapshot()) addAll(AnonymousAuth.DEFAULT_DISPENSERS)
            }.distinct()
            if (urls.isEmpty()) return@withLock null

            val profile = settingsDataStore.playDeviceProfileSnapshot()
            var usedFallback = false
            val minted = withContext(dispatcherProvider.io) {
                runCatching { AnonymousAuth.login(urls, deviceProperties.resolve(profile)) }.getOrNull()
                    // An unusual native identity must never cost the user Play:
                    // retry once with the bundled modern profile.
                    ?: runCatching { AnonymousAuth.login(urls, deviceProperties.fallback()) }.getOrNull()
                        ?.also { usedFallback = true }
            }
            if (minted == null) {
                anonymousAuthFailedAt = System.currentTimeMillis()
                // A not-yet-rejected older session stays usable meanwhile.
                anonymousAuth
            } else {
                anonymousAuth = minted
                anonymousProfile = profile
                anonymousAuthMintedAt = System.currentTimeMillis()
                anonymousAuthFailedAt = 0
                persistAnonymous(minted, profile, usedFallback, anonymousAuthMintedAt)
                minted
            }
        }
    }

    /** Drops [failed] (only if it is still the current anonymous session). */
    private suspend fun invalidateAnonymous(failed: AuthData) {
        if (anonymousAuth === failed) {
            anonymousAuth = null
            anonymousAuthMintedAt = 0
            anonymousAuthFailedAt = 0
            runCatching { settingsDataStore.setAnonPlaySession(null) }
        }
    }

    private suspend fun persistAnonymous(auth: AuthData, profile: String, fallback: Boolean, mintedAt: Long) {
        val json = org.json.JSONObject()
            .put("email", auth.email)
            .put("authToken", auth.authToken)
            .put("gsfId", auth.gsfId)
            .put("consistency", auth.deviceCheckInConsistencyToken)
            .put("config", auth.deviceConfigToken)
            .put("cookie", auth.dfeCookie)
            .put("dispenser", auth.tokenDispenserUrl)
            .put("profile", profile)
            .put("fallback", fallback)
            .put("mintedAt", mintedAt)
        runCatching { settingsDataStore.setAnonPlaySession(json.toString()) }
    }

    private suspend fun restorePersistedAnonymous(): AuthData? {
        val raw = runCatching { settingsDataStore.anonPlaySessionSnapshot() }.getOrNull() ?: return null
        return runCatching {
            val json = org.json.JSONObject(raw)
            val mintedAt = json.getLong("mintedAt")
            val profile = json.getString("profile")
            if (System.currentTimeMillis() - mintedAt >= ANONYMOUS_SESSION_MAX_AGE_MS) return null
            if (profile != settingsDataStore.playDeviceProfileSnapshot()) return null
            val properties = if (json.optBoolean("fallback")) deviceProperties.fallback() else deviceProperties.resolve(profile)
            val locale = java.util.Locale.getDefault()
            AuthData(json.getString("email"), "").apply {
                authToken = json.getString("authToken")
                gsfId = json.getString("gsfId")
                deviceCheckInConsistencyToken = json.optString("consistency")
                deviceConfigToken = json.optString("config")
                dfeCookie = json.optString("cookie")
                tokenDispenserUrl = json.optString("dispenser")
                this.locale = locale
                deviceInfoProvider = com.novastore.playapi.data.providers.DeviceInfoProvider(properties, locale.toString())
            }.also {
                anonymousAuth = it
                anonymousProfile = profile
                anonymousAuthMintedAt = mintedAt
                anonymousAuthFailedAt = 0
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Nova anonymous tier (community mirror)
    // ------------------------------------------------------------------

    override suspend fun isMirrorEnabled(): Boolean =
        settingsDataStore.apkPureMirrorEnabledSnapshot()

    override suspend fun mirrorVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>> {
        // Each mirror is switched on/off on its own: disabling APKPure no
        // longer silently disables APKCombo as well.
        val pureOn = isMirrorEnabled()
        val comboOn = isComboMirrorEnabled()
        if (!pureOn && !comboOn) return emptyMap()
        // Source racing: both mirrors are queried concurrently; APKPure wins
        // where it answers, APKCombo fills the gaps. A Cloudflare-gated or
        // slow mirror never stalls the scan.
        return coroutineScope {
            val pureDeferred = async {
                if (pureOn) {
                    runCatching { apkPureClient.versionsFor(packageNames) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val comboDeferred = async {
                if (comboOn) {
                    runCatching { apkComboClient.versionsFor(packageNames) }.getOrDefault(emptyMap())
                } else {
                    emptyMap()
                }
            }
            val primary = pureDeferred.await()
            val missing = packageNames.filter { primary[it].isNullOrEmpty() }
            val secondary = if (missing.isEmpty()) emptyMap() else comboDeferred.await()
            primary + secondary
        }
    }

    override suspend fun resolveMirrorDownload(
        packageName: String,
        versionCode: Long,
    ): List<PlayDownloadFile>? {
        consumeMirrorOverride(packageName)?.let { return listOf(it) }
        if (!isMirrorEnabled()) return null
        val spec = runCatching { apkPureClient.resolveDownload(packageName, versionCode) }.getOrNull()
            ?: return null
        // XAPK bundles are downloaded as-is and unpacked into a multi-APK
        // install session by the verification pipeline (Nova Resolver v8).
        return listOf(
            PlayDownloadFile(
                name = spec.fileName,
                url = spec.url,
                sizeBytes = spec.sizeBytes ?: 0L,
                isSplit = false,
            ),
        )
    }

    override suspend fun isComboMirrorEnabled(): Boolean =
        settingsDataStore.apkComboMirrorEnabledSnapshot()

    override suspend fun registerMirrorOverride(
        packageName: String,
        url: String,
        fileName: String,
        sizeBytes: Long?,
        versionCode: Long?,
    ) = withContext(dispatcherProvider.io) {
        if (url.isBlank()) return@withContext
        mirrorOverrides[packageName] = System.currentTimeMillis() to PlayDownloadFile(
            name = fileName,
            url = url,
            sizeBytes = sizeBytes ?: 0L,
            isSplit = false,
        )
        if (versionCode != null) mirrorOverrideCodes[packageName] = versionCode
    }

    /** Returns the WebView-resolved link when still fresh (does not clear it). */
    private fun consumeMirrorOverride(packageName: String): PlayDownloadFile? {
        val entry = mirrorOverrides[packageName] ?: return null
        if (System.currentTimeMillis() - entry.first > OVERRIDE_TTL) {
            mirrorOverrides.remove(packageName)
            return null
        }
        return entry.second
    }

    override suspend fun resolveComboDownload(
        packageName: String,
        versionName: String?,
    ): List<PlayDownloadFile>? {
        consumeMirrorOverride(packageName)?.let { return listOf(it) }
        if (!isComboMirrorEnabled()) return null
        if (versionName.isNullOrBlank()) return null
        val spec = runCatching { apkComboClient.resolveDownload(packageName, versionName) }.getOrNull()
            ?: return null
        if (spec.isBundle) return null
        if (spec.url.isBlank()) return null
        return listOf(
            PlayDownloadFile(
                name = spec.fileName,
                url = spec.url,
                sizeBytes = spec.sizeBytes ?: 0L,
                isSplit = false,
            ),
        )
    }

    /**
     * The unified anonymous delivery chain — APKPure by code → APKPure by
     * version NAME → APKCombo by name. Stage 2 exists because a row created
     * from APKCombo metadata carries APKCombo's synthetic version code, which
     * APKPure's own download page does not understand; matching the NAME in
     * APKPure's version list recovers a direct APKPure download for exactly
     * the release the user asked for. No single mirror's blind spot can end
     * the chain.
     */
    override suspend fun resolveMirrorChain(
        packageName: String,
        versionCode: Long,
        versionName: String?,
    ): List<PlayDownloadFile>? {
        consumeMirrorOverride(packageName)?.let { return listOf(it) }

        // Stage 1: APKPure by version code (APKPure-sourced and Play rows).
        resolveMirrorDownload(packageName, versionCode)?.let { return it }

        // Stage 2: APKPure by version name (APKCombo-sourced rows, code drift).
        if (isMirrorEnabled() && !versionName.isNullOrBlank()) {
            val matched = runCatching {
                apkPureClient.versions(packageName)
                    .firstOrNull { it.versionName?.trim()?.equals(versionName.trim(), ignoreCase = true) == true }
            }.getOrNull()
            if (matched != null) {
                val spec = runCatching {
                    apkPureClient.resolveDownload(packageName, matched.versionCode)
                }.getOrNull()
                if (spec != null && spec.url.isNotBlank()) {
                    return listOf(
                        PlayDownloadFile(
                            name = spec.fileName,
                            url = spec.url,
                            sizeBytes = spec.sizeBytes ?: 0L,
                            isSplit = false,
                        ),
                    )
                }
            }
        }

        // Stage 3: APKCombo by version name.
        return resolveComboDownload(packageName, versionName)
    }

    /**
     * Mirror resolution with an honest, actionable error when it cannot
     * serve the file (disabled, blocked, or bundle-only variant).
     */
    private suspend fun mirrorDownloadOrThrow(
        packageName: String,
        versionCode: Long,
        versionName: String? = null,
    ): List<PlayDownloadFile> {
        val mirror = resolveMirrorChain(packageName, versionCode, versionName)
        if (mirror != null) return mirror
        if (!isMirrorEnabled()) {
            throw PlayStoreException(
                NovaError.Account(
                    userMessage = "No Google Play session and the community mirror is disabled. " +
                        "Sign in, or enable the mirror in Settings → Sources.",
                ),
            )
        }
        throw PlayStoreException(
            NovaError.Metadata(
                userMessage = "Neither mirror (APKPure, APKCombo) could deliver this release — " +
                    "it may be bundle-only, paid or region-blocked, or the mirrors are " +
                    "temporarily serving protected pages. Signing in with a Google account " +
                    "delivers the original Play files.",
                packageName = packageName,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Runs [block] with the active session. On [AuthException] the session is
     * rebuilt once from the persisted aasToken and the call retried; a second
     * failure drops the session.
     */
    private suspend fun <T> withAuth(block: (AuthData) -> T): T = withContext(dispatcherProvider.io) {
        val account = authData
        if (account != null) {
            return@withContext try {
                try {
                    gate.run { block(account) }
                } catch (limited: com.novastore.playapi.network.RateLimitedException) {
                    // Own account throttled: wait the cool-down, retry once —
                    // never a logout for a 429.
                    gate.onRateLimited(0)
                    gate.run { block(account) }
                }
            } catch (e: AuthException) {
                val profile = settingsDataStore.playDeviceProfileSnapshot()
                val rebuilt = runCatching {
                    AuthHelper.build(account.email, account.aasToken, deviceProperties.resolve(profile))
                }.getOrNull()
                if (rebuilt == null) {
                    // The account session is gone: the anonymous tier takes over.
                    invalidateSession()
                    withAnonymous(block) ?: throw e
                } else {
                    setAuthData(rebuilt, authMethod)
                    try {
                        gate.run { block(rebuilt) }
                    } catch (second: AuthException) {
                        invalidateSession()
                        withAnonymous(block) ?: throw second
                    }
                }
            }
        }
        withAnonymous(block)
            ?: throw PlayStoreException(
                NovaError.Account(
                    userMessage = "No Google Play session: signing in is optional, but the anonymous " +
                        "Play access could not be reached (check the connection or Settings → Sources).",
                ),
            )
    }

    /**
     * Runs [block] with the anonymous session; an expired/rejected session
     * is dropped and re-minted once. Null when no anonymous session exists.
     */
    private suspend fun <T> withAnonymous(block: (AuthData) -> T): T? {
        var session = anonymousSession() ?: return null
        var attempt = 0
        while (true) {
            try {
                return gate.run { block(session) }
            } catch (e: IOException) {
                // Expired token (401) or a throttled pooled account (429):
                // cool down (429), switch to a freshly minted session, retry.
                val limited = e is com.novastore.playapi.network.RateLimitedException
                if (e !is AuthException && !limited) throw e
                if (attempt >= ANONYMOUS_MAX_RETRIES) throw e
                if (limited) gate.onRateLimited(attempt)
                invalidateAnonymous(session)
                session = anonymousSession() ?: throw e
                attempt++
            }
        }
    }

    // ------------------------------------------------------------------
    // Mapping: GPlayApi App -> Nova Store models
    // ------------------------------------------------------------------

    private fun App.toRemoteApp(): RemoteApp = RemoteApp(
        packageName = packageName,
        name = displayName?.takeIf { it.isNotBlank() } ?: packageName,
        summary = shortDescription?.replace(Regex("<[^>]*>"), "")?.trim(),
        developer = developerName,
        iconUrl = iconUrl,
        license = null,
        categories = listOfNotNull(categoryName?.takeIf { it.isNotBlank() }),
        source = SOURCE_PLAY,
        rating = rating?.average?.takeIf { it > 0f },
        downloads = installs.takeIf { it > 0 },
        sizeBytes = size.takeIf { it > 0 },
        updatedMillis = null,
        containsAds = containsAds,
        isFree = isFree,
    )

    private fun App.toAppVersion(): AppVersion = AppVersion(
        packageName = packageName,
        versionCode = versionCode.toLong(),
        versionName = versionName,
        source = SOURCE_PLAY,
        size = size.takeIf { it > 0 },
        // The concrete URL is resolved at download time via the purchase flow.
        downloadUrl = "",
        sha256 = null,
        minSdk = null,
        targetSdk = null,
        addedAt = null,
        artifactType = ArtifactType.APK,
        isPaid = !isFree,
    )

    private fun App.toRemoteDetails(): RemoteAppDetails = RemoteAppDetails(
        app = toRemoteApp(),
        description = description,
        changelog = changes,
        website = developerWebsite,
        sourceCodeUrl = null,
        versions = listOf(toAppVersion()),
        screenshots = screenshotUrls.toList(),
        videoUrl = videoUrl,
        ratingCount = rating?.count()?.takeIf { it > 0 },
        developerEmail = developerEmail,
        price = price?.takeIf { !isFree },
        permissions = permissions.distinct(),
        dependencies = dependencies.sorted(),
        developerAddress = developerAddress?.takeIf { it.isNotBlank() },
        productInfo = offerDetails.filter { it.key.isNotBlank() && it.value.isNotBlank() },
        uploadDate = updated?.takeIf { it.isNotBlank() },
    )

    private fun Rating.count(): Long = oneStar + twoStar + threeStar + fourStar + fiveStar

    private companion object {
        const val SEARCH_LIMIT = 60
        const val SEARCH_EXTRA_PAGES = 2
        const val BULK_CHUNK = 50
        const val OFFER_TYPE = 1
        const val OVERRIDE_TTL = 10 * 60 * 1000L

        /** A failed anonymous-session bootstrap is not retried within this window. */
        const val ANONYMOUS_BOOTSTRAP_TTL_MS = 60 * 1000L

        const val ANONYMOUS_MAX_RETRIES = 2
        const val APP_CACHE_TTL_MS = 15 * 60 * 1000L
        const val VERSION_CACHE_TTL_MS = 15 * 60 * 1000L
        const val SEARCH_CACHE_TTL_MS = 10 * 60 * 1000L
        const val CHART_CACHE_TTL_MS = 30 * 60 * 1000L

        /** Anonymous sessions are re-minted before Play's ~1 h token lifetime ends. */
        const val ANONYMOUS_SESSION_MAX_AGE_MS = 45 * 60 * 1000L
    }
}
