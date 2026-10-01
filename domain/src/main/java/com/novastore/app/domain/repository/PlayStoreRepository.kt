package com.novastore.app.domain.repository

import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.PlayDownloadFile
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails

/**
 * Google Play catalog access on top of the vendored GPlayApi module.
 *
 * All methods are safe to call while not signed in: they return empty/null
 * results instead of failing, except [purchaseDownloadFiles] which throws a
 * [com.novastore.app.core.model.PlayStoreException] describing the failure.
 */
interface PlayStoreRepository {
    /** Play search; never throws — returns an empty list on failure. */
    suspend fun search(query: String): List<RemoteApp>

    /** Play's current top-free apps chart; empty when not signed in or on failure. */
    suspend fun topFreeApps(): List<RemoteApp>

    /** Play details for one package, or null when not found / not signed in. */
    suspend fun getAppDetails(packageName: String): RemoteAppDetails?

    /**
     * Latest known version per package (bulk). Packages unknown to Play or
     * errors are simply missing from the result map.
     */
    suspend fun getVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>>

    /** Latest Play version of one package, or null if not in Play / not signed in. */
    suspend fun resolveLatestVersion(packageName: String): AppVersion?

    /**
     * Resolves the download files (base + splits) for one package/version via
     * the Play purchase/delivery endpoints. The URLs are single-use.
     *
     * When no Play session is signed in, the community mirror resolves the
     * files instead (anonymous mode) — no account and no server required.
     *
     * @throws com.novastore.app.core.model.PlayStoreException when not signed in,
     * the app is paid/unavailable, or Play refuses the delivery.
     */
    suspend fun purchaseDownloadFiles(packageName: String, versionCode: Long): List<PlayDownloadFile>

    /** True while a signed-in (account) Play session is active. */
    suspend fun isLoggedIn(): Boolean

    /**
     * True when the native Play protocol is usable right now: a signed-in
     * account OR the anonymous Play session (minted on demand). This is what
     * search/details/update checks should gate on, not [isLoggedIn].
     */
    suspend fun hasPlayAccess(): Boolean

    /**
     * Latest Play version for this device through any available session
     * (account or anonymous), or null when Play does not carry the app.
     */
    suspend fun resolvePlayLatest(packageName: String): AppVersion?

    /**
     * Listing summaries (rating, downloads, size, developer, price) for many
     * packages in bulk — one request per 50 apps, cached. Missing packages
     * are simply absent.
     */
    suspend fun summaries(packageNames: Collection<String>): Map<String, RemoteApp>

    /**
     * Nova anonymous tier: latest versions from the community mirror, bulk.
     * Empty when the mirror is disabled in settings or unreachable. Works
     * without any account or server.
     */
    suspend fun mirrorVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>>

    /**
     * Nova anonymous tier: concrete download files of one version from the
     * community mirror, or null when the mirror cannot serve it.
     */
    suspend fun resolveMirrorDownload(packageName: String, versionCode: Long): List<PlayDownloadFile>?

    /**
     * Nova anonymous tier, stage two: concrete download files from the
     * APKCombo mirror, matched by version name, or null when it cannot
     * serve the file (its final links are JavaScript-gated).
     */
    suspend fun resolveComboDownload(packageName: String, versionName: String?): List<PlayDownloadFile>?

    /**
     * The unified anonymous delivery chain, tried in order:
     *  1. APKPure resolved by version code (works for APKPure/Play rows);
     *  2. APKPure resolved by version NAME (works when the row came from
     *     APKCombo or when mirror codes disagree with APKPure's);
     *  3. APKCombo resolved by version name.
     *
     * Returns the first mirror that can serve the file, or null when none
     * can. Every anonymous download path funnels through this chain so no
     * single mirror's blind spot becomes the user's error.
     */
    suspend fun resolveMirrorChain(
        packageName: String,
        versionCode: Long,
        versionName: String?,
    ): List<PlayDownloadFile>?

    /**
     * Records a mirror download link resolved out-of-band by the built-in
     * mirror browser (the WebView sniffed the final file URL). The next
     * download of [packageName] uses it instead of re-resolving.
     */
    suspend fun registerMirrorOverride(
        packageName: String,
        url: String,
        fileName: String,
        sizeBytes: Long?,
        versionCode: Long?,
    )

    /** True while the community mirror tier is enabled in settings. */
    suspend fun isMirrorEnabled(): Boolean

    /** True while the APKCombo mirror stage is enabled in settings. */
    suspend fun isComboMirrorEnabled(): Boolean
}
