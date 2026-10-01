package com.novastore.app.core.model

/**
 * An application as described by a remote source (F-Droid index or Google Play).
 */
data class RemoteApp(
    val packageName: String,
    val name: String,
    val summary: String?,
    val developer: String?,
    val iconUrl: String?,
    val license: String?,
    val categories: List<String>,
    val source: String,
    // --- Google Play enrichment (all optional so F-Droid call sites compile unchanged) ---
    val rating: Float? = null,
    val downloads: Long? = null,
    val sizeBytes: Long? = null,
    val updatedMillis: Long? = null,
    val containsAds: Boolean = false,
    val isFree: Boolean = true,
    /** Second icon source (another catalog's icon) used when [iconUrl] fails to load. */
    val altIconUrl: String? = null,
)

data class RemoteAppDetails(
    val app: RemoteApp,
    val description: String?,
    val changelog: String?,
    val website: String?,
    val sourceCodeUrl: String?,
    val versions: List<AppVersion>,
    // --- Google Play enrichment (all optional so F-Droid call sites compile unchanged) ---
    val screenshots: List<String> = emptyList(),
    val videoUrl: String? = null,
    val ratingCount: Long? = null,
    val developerEmail: String? = null,
    val price: String? = null,
    /** Star distribution 5→1, from the public Play listing. */
    val ratingHistogram: RatingHistogram? = null,
    /** When the listing was last updated, epoch millis. */
    val updatedMillis: Long? = null,
    /** When the app was first released, epoch millis. */
    val releasedMillis: Long? = null,
    /** Android permissions the app requests (from Google Play). */
    val permissions: List<String> = emptyList(),
    /** Packages the app depends on (Play Services, shared libraries…). */
    val dependencies: List<String> = emptyList(),
    val developerAddress: String? = null,
    /** Play "product details" rows, e.g. "In-app purchases" → "$0.99 – $49.99 per item". */
    val productInfo: Map<String, String> = emptyMap(),
    /** Play's upload date as displayed by Play ("Sep 12, 2026"). */
    val uploadDate: String? = null,
)
