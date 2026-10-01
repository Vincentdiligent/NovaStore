package com.novastore.app.domain.repository

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.AppReview
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the locally cached catalog built from repository indexes.
 */
interface CatalogRepository {
    suspend fun search(query: String): AppResult<List<RemoteApp>>

    /** Instant search over the locally cached repository catalog only (no network). */
    suspend fun searchLocal(query: String): List<RemoteApp>

    /**
     * Public Google Play storefront shelf (no account): the apps home page
     * when [shelf] is null, otherwise a Play category id ("GAME", "TOOLS"…).
     * Empty when the web catalog source is disabled or unreachable.
     */
    suspend fun playStorefront(shelf: String?): List<RemoteApp>

    /**
     * The list entry this package was last shown with (name, icon,
     * developer, rating) — lets the details page render its header
     * instantly instead of showing the package name while it loads.
     */
    fun preview(packageName: String): RemoteApp?

    /**
     * Turns a scanned / shared / opened link into an app or a search. Short
     * and tracking links (goo.gl, bit.ly, onelink, …) are followed through
     * their redirects to the real store page.
     */
    suspend fun resolveStoreLink(raw: String): com.novastore.app.core.model.StoreLink?
    suspend fun getAppDetails(packageName: String): AppResult<RemoteAppDetails?>

    /**
     * Real user reviews of one app, read anonymously from the public Play
     * review feed. Newest first. Empty for apps without reviews or when the
     * feed is unreachable.
     */
    suspend fun getReviews(packageName: String): List<AppReview>

    fun observeRecentlyAdded(limit: Int): Flow<List<RemoteApp>>
    fun observeCategories(): Flow<List<String>>

    /**
     * One page of the catalog, either filtered by an exact category match
     * ([category] != null) or the freshest apps overall ("All", null).
     * Backed by a LIMIT/OFFSET query; [offset] is the number of rows
     * already served to the caller.
     */
    suspend fun listByCategory(category: String?, offset: Int, limit: Int): List<RemoteApp>

    /** Every cached version of the given packages, from all enabled repositories. */
    suspend fun getVersionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>>
}
