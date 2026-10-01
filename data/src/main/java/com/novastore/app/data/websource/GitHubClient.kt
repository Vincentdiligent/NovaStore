package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_GITHUB
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nova GitHub catalog — apps published as APK assets on GitHub Releases.
 *
 * Search maps a query to repositories, and each repository's releases become
 * installable versions, straight from GitHub's CDN. No account needed; the
 * public REST API is used with a polite User-Agent.
 *
 * Honest limits: 60 requests/hour per IP unauthenticated (search 10/min);
 * results are cached to stay far below.
 */
@Singleton
class GitHubClient @Inject constructor(
    baseClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", NOVA_UA)
                    .header("Accept", "application/vnd.github+json")
                    .build(),
            )
        }
        .build()

    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<RemoteApp>>>()
    private val repoCache = ConcurrentHashMap<String, Pair<Long, RepoMeta?>>()
    private val releasesCache = ConcurrentHashMap<String, Pair<Long, List<AppVersion>>>()

    data class RepoMeta(
        val fullName: String,
        val description: String?,
        val ownerLogin: String,
        val ownerAvatar: String?,
        val stars: Long,
        val homepage: String?,
        val updatedAt: Long?,
    )

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    suspend fun search(query: String): List<RemoteApp> = withContext(dispatcherProvider.io) {
        val cached = searchCache[query]
        if (cached != null && System.currentTimeMillis() - cached.first < SEARCH_TTL) {
            return@withContext cached.second
        }
        val results = runCatching { fetchSearch(query) }.getOrDefault(emptyList())
        searchCache[query] = System.currentTimeMillis() to results
        if (searchCache.size > SEARCH_CACHE_MAX) {
            searchCache.entries.sortedBy { it.value.first }.take(4).forEach { searchCache.remove(it.key) }
        }
        results
    }

    private fun fetchSearch(query: String): List<RemoteApp> {
        val repos = getJsonArray(
            "$API/search/repositories?q=${urlEncode("$query android in:name,description,topics")}" +
                "&sort=stars&order=desc&per_page=12",
        ) ?: return emptyList()
        val items = repos.optJSONObject(0)?.optJSONArray("items") ?: return emptyList()
        val apps = mutableListOf<RemoteApp>()
        for (i in 0 until items.length()) {
            val repo = items.optJSONObject(i) ?: continue
            if (repo.optBoolean("archived")) continue
            val stars = repo.optLong("stargazers_count", 0)
            if (stars < MIN_STARS) continue
            apps += repoToRemoteApp(repo)
        }
        return apps.take(SEARCH_LIMIT)
    }

    private fun repoToRemoteApp(repo: JSONObject): RemoteApp {
        val fullName = repo.optString("full_name", "")
        return RemoteApp(
            packageName = syntheticPackage(fullName),
            name = repo.optString("name", fullName),
            summary = repo.optString("description").takeIf { it.isNotBlank() },
            developer = repo.optJSONObject("owner")?.optString("login"),
            iconUrl = repo.optJSONObject("owner")?.optString("avatar_url"),
            license = repo.optJSONObject("license")?.optString("spdx_id")?.takeIf { it != "NOASSERTION" },
            categories = listOf("GitHub"),
            source = SOURCE_GITHUB,
            // downloads column shows stars for GitHub entries — the community signal
            downloads = repo.optLong("stargazers_count", 0),
            updatedMillis = parseIsoDate(repo.optString("pushed_at")),
        )
    }

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    suspend fun details(fullName: String): RemoteAppDetails? = withContext(dispatcherProvider.io) {
        val cached = repoCache[fullName]
        if (cached != null && System.currentTimeMillis() - cached.first < DETAILS_TTL) {
            return@withContext cached.second?.let { buildDetails(it) }
        }
        val meta = fetchRepoMeta(fullName) ?: return@withContext null
        repoCache[fullName] = System.currentTimeMillis() to meta
        buildDetails(meta)
    }

    private fun fetchRepoMeta(fullName: String): RepoMeta? {
        val repo = getJsonObject("$API/repos/${urlEncode(fullName)}") ?: return null
        return RepoMeta(
            fullName = repo.optString("full_name", fullName),
            description = repo.optString("description").takeIf { it.isNotBlank() },
            ownerLogin = repo.optJSONObject("owner")?.optString("login") ?: fullName.substringBefore('/'),
            ownerAvatar = repo.optJSONObject("owner")?.optString("avatar_url"),
            stars = repo.optLong("stargazers_count", 0),
            homepage = repo.optString("homepage").takeIf { it.isNotBlank() },
            updatedAt = parseIsoDate(repo.optString("pushed_at")),
        )
    }

    private fun buildDetails(meta: RepoMeta): RemoteAppDetails {
        val app = RemoteApp(
            packageName = syntheticPackage(meta.fullName),
            name = meta.fullName.substringAfter('/'),
            summary = meta.description,
            developer = meta.ownerLogin,
            iconUrl = meta.ownerAvatar,
            license = null,
            categories = listOf("GitHub"),
            source = SOURCE_GITHUB,
            downloads = meta.stars,
            updatedMillis = meta.updatedAt,
        )
        return RemoteAppDetails(
            app = app,
            description = meta.description,
            changelog = null,
            website = meta.homepage,
            sourceCodeUrl = "$WEB/${meta.fullName}",
            versions = emptyList(), // filled by releases()
            ratingHistogram = null,
        )
    }

    // ------------------------------------------------------------------
    // Releases → versions
    // ------------------------------------------------------------------

    suspend fun releases(fullName: String): List<AppVersion> = withContext(dispatcherProvider.io) {
        val cached = releasesCache[fullName]
        if (cached != null && System.currentTimeMillis() - cached.first < RELEASES_TTL) {
            return@withContext cached.second
        }
        val versions = runCatching { fetchReleases(fullName) }.getOrDefault(emptyList())
        releasesCache[fullName] = System.currentTimeMillis() to versions
        if (releasesCache.size > RELEASES_CACHE_MAX) {
            releasesCache.entries.sortedBy { it.value.first }.take(12).forEach { releasesCache.remove(it.key) }
        }
        versions
    }

    private fun fetchReleases(fullName: String): List<AppVersion> {
        val releases = getJsonArray("$API/repos/${urlEncode(fullName)}/releases?per_page=30") ?: return emptyList()
        val versions = mutableListOf<AppVersion>()
        val pkg = syntheticPackage(fullName)
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val assets = release.optJSONArray("assets") ?: continue
            for (a in 0 until assets.length()) {
                val asset = assets.optJSONObject(a) ?: continue
                val name = asset.optString("name", "")
                val lower = name.lowercase()
                if (!lower.endsWith(".apk") && !lower.endsWith(".apkm")) continue
                versions += AppVersion(
                    packageName = pkg,
                    versionCode = asset.optLong("id", 0),
                    versionName = release.optString("tag_name").removePrefix("v").ifBlank { null },
                    source = SOURCE_GITHUB,
                    size = asset.optLong("size", 0).takeIf { it > 0 },
                    downloadUrl = asset.optString("browser_download_url"),
                    sha256 = asset.optString("digest").removePrefix("sha256:").takeIf { it.isNotBlank() },
                    minSdk = null,
                    targetSdk = null,
                    addedAt = parseIsoDate(asset.optString("updated_at") ?: release.optString("published_at")),
                    artifactType = if (lower.endsWith(".apkm")) ArtifactType.APK_SET else ArtifactType.APK,
                    signer = null,
                    nativeCode = emptyList(),
                )
            }
        }
        // newest assets carry the highest ids
        return versions.sortedByDescending { it.versionCode }.take(VERSIONS_LIMIT)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** `owner/repo` → stable catalog key `github.owner.repo` (dots normalized). */
    fun syntheticPackage(fullName: String): String =
        "github." + fullName.lowercase().replace('/', '.')

    fun isSyntheticPackage(packageName: String): Boolean = packageName.startsWith("github.")

    fun fullNameOf(packageName: String): String? {
        if (!isSyntheticPackage(packageName)) return null
        return packageName.removePrefix("github.").replace('.', '/', ignoreCase = false)
            .takeIf { it.contains('/') }
    }

    private fun getJsonArray(url: String): JSONArray? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            when {
                !r.isSuccessful || r.body == null -> null
                else -> runCatching { JSONArray(r.body!!.string()) }.getOrNull()
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun getJsonObject(url: String): JSONObject? = try {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
            when {
                !r.isSuccessful || r.body == null -> null
                else -> runCatching { JSONObject(r.body!!.string()) }.getOrNull()
            }
        }
    } catch (e: IOException) {
        null
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun parseIsoDate(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        return runCatching {
            java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private companion object {
        const val API = "https://api.github.com"
        const val WEB = "https://github.com"
        const val NOVA_UA = "NovaStore/5.0 (Android app catalog client)"
        const val MIN_STARS = 50
        const val SEARCH_LIMIT = 10
        const val VERSIONS_LIMIT = 12
        const val SEARCH_TTL = 10 * 60 * 1000L
        const val DETAILS_TTL = 10 * 60 * 1000L
        const val RELEASES_TTL = 10 * 60 * 1000L
        const val SEARCH_CACHE_MAX = 16
        const val RELEASES_CACHE_MAX = 64
    }
}
