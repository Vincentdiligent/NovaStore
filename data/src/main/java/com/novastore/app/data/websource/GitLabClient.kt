package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_GITLAB
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
 * Nova GitLab catalog — apps published as APK assets on GitLab Releases
 * (gitlab.com and self-hosted instances exposing the v4 API).
 *
 * GitLab's public API needs no authentication and returns project metadata,
 * avatars and release asset links directly.
 */
@Singleton
class GitLabClient @Inject constructor(
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
                    .build(),
            )
        }
        .build()

    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<RemoteApp>>>()
    private val projectCache = ConcurrentHashMap<Long, Pair<Long, RemoteAppDetails?>>()
    private val releasesCache = ConcurrentHashMap<Long, Pair<Long, List<AppVersion>>>()

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
        val projects = getJsonArray(
            "$API/projects?search=${urlEncode(query)}&simple=true&order_by=last_activity_at" +
                "&sort=desc&per_page=15",
        ) ?: return emptyList()
        val apps = mutableListOf<RemoteApp>()
        for (i in 0 until projects.length()) {
            val project = projects.optJSONObject(i) ?: continue
            val stars = project.optLong("star_count", 0)
            if (stars < MIN_STARS) continue
            apps += projectToRemoteApp(project)
        }
        return apps.take(SEARCH_LIMIT)
    }

    private fun projectToRemoteApp(project: JSONObject): RemoteApp {
        val id = project.optLong("id", 0)
        return RemoteApp(
            packageName = syntheticPackage(id, project.optString("path", "p$id")),
            name = project.optString("name"),
            summary = project.optString("description").takeIf { it.isNotBlank() },
            developer = project.optString("namespace", ""),
            iconUrl = project.optJSONObject("owner")?.optString("avatar_url")
                ?: project.optString("avatar_url").takeIf { it.isNotBlank() },
            license = null,
            categories = listOf("GitLab"),
            source = SOURCE_GITLAB,
            downloads = starsOrZero(project),
            updatedMillis = parseIsoDate(project.optString("last_activity_at")),
        )
    }

    private fun starsOrZero(project: JSONObject): Long =
        project.optLong("star_count", 0)

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    suspend fun details(projectId: Long): RemoteAppDetails? = withContext(dispatcherProvider.io) {
        val cached = projectCache[projectId]
        if (cached != null && System.currentTimeMillis() - cached.first < DETAILS_TTL) {
            return@withContext cached.second
        }
        val project = getJsonObject("$API/projects/$projectId")
        val details = project?.let { buildDetails(it) }
        projectCache[projectId] = System.currentTimeMillis() to details
        if (projectCache.size > DETAILS_CACHE_MAX) {
            projectCache.entries.sortedBy { it.value.first }.take(12).forEach { projectCache.remove(it.key) }
        }
        details
    }

    private fun buildDetails(project: JSONObject): RemoteAppDetails {
        val id = project.optLong("id", 0)
        val app = RemoteApp(
            packageName = syntheticPackage(id, project.optString("path", "p$id")),
            name = project.optString("name"),
            summary = project.optString("description").takeIf { it.isNotBlank() },
            developer = project.optString("namespace", ""),
            iconUrl = project.optJSONObject("owner")?.optString("avatar_url")
                ?: project.optString("avatar_url").takeIf { it.isNotBlank() },
            license = null,
            categories = listOf("GitLab"),
            source = SOURCE_GITLAB,
            downloads = starsOrZero(project),
            updatedMillis = parseIsoDate(project.optString("last_activity_at")),
        )
        val webUrl = project.optString("web_url")
        return RemoteAppDetails(
            app = app,
            description = project.optString("description").takeIf { it.isNotBlank() },
            changelog = null,
            website = project.optString("web_url").takeIf { it.isNotBlank() },
            sourceCodeUrl = webUrl.takeIf { it.isNotBlank() },
            versions = emptyList(), // filled by releases()
            ratingHistogram = null,
        )
    }

    // ------------------------------------------------------------------
    // Releases → versions
    // ------------------------------------------------------------------

    suspend fun releases(projectId: Long, packageName: String): List<AppVersion> = withContext(dispatcherProvider.io) {
        val cached = releasesCache[projectId]
        if (cached != null && System.currentTimeMillis() - cached.first < RELEASES_TTL) {
            return@withContext cached.second
        }
        val versions = runCatching { fetchReleases(projectId, packageName) }.getOrDefault(emptyList())
        releasesCache[projectId] = System.currentTimeMillis() to versions
        if (releasesCache.size > RELEASES_CACHE_MAX) {
            releasesCache.entries.sortedBy { it.value.first }.take(12).forEach { releasesCache.remove(it.key) }
        }
        versions
    }

    private fun fetchReleases(projectId: Long, packageName: String): List<AppVersion> {
        val releases = getJsonArray("$API/projects/$projectId/releases?per_page=30") ?: return emptyList()
        val versions = mutableListOf<AppVersion>()
        for (i in 0 until releases.length()) {
            val release = releases.optJSONObject(i) ?: continue
            val tagName = release.optString("tag_name")
            val links = release.optJSONObject("assets")?.optJSONArray("links") ?: continue
            val committedAt = parseIsoDate(release.optString("released_at") ?: release.optString("created_at"))
            for (l in 0 until links.length()) {
                val link = links.optJSONObject(l) ?: continue
                val name = link.optString("name", "")
                val url = (link.optString("direct_asset_url").takeIf { it.isNotBlank() }
                    ?: link.optString("url"))
                val lower = name.lowercase()
                if (!lower.endsWith(".apk") && !lower.endsWith(".apkm")) continue
                if (url.isBlank()) continue
                versions += AppVersion(
                    packageName = packageName,
                    versionCode = syntheticVersionCode(tagName, versions.size),
                    versionName = tagName.removePrefix("v").ifBlank { null },
                    source = SOURCE_GITLAB,
                    size = link.optLong("size", 0).takeIf { it > 0 },
                    downloadUrl = url,
                    sha256 = null,
                    minSdk = null,
                    targetSdk = null,
                    addedAt = committedAt,
                    artifactType = if (lower.endsWith(".apkm")) ArtifactType.APK_SET else ArtifactType.APK,
                    signer = null,
                    nativeCode = emptyList(),
                )
            }
        }
        return versions.take(VERSIONS_LIMIT)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** project id + path → stable catalog key `gitlab.<id>.<path>`. */
    fun syntheticPackage(projectId: Long, path: String): String =
        "gitlab.$projectId." + path.lowercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun isSyntheticPackage(packageName: String): Boolean = packageName.startsWith("gitlab.")

    fun projectIdOf(packageName: String): Long? {
        if (!isSyntheticPackage(packageName)) return null
        return packageName.removePrefix("gitlab.").substringBefore('.').toLongOrNull()
    }

    private fun syntheticVersionCode(tagName: String, index: Int): Long {
        // deterministic pseudo-code from the tag digits, decreasing with index
        val digits = tagName.filter { it.isDigit() }.take(5).toLongOrNull() ?: 1000L
        return digits * 1000L + (1000 - index)
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
        const val API = "https://gitlab.com/api/v4"
        const val NOVA_UA = "NovaStore/5.0 (Android app catalog client)"
        const val MIN_STARS = 30
        const val SEARCH_LIMIT = 10
        const val VERSIONS_LIMIT = 12
        const val SEARCH_TTL = 10 * 60 * 1000L
        const val DETAILS_TTL = 10 * 60 * 1000L
        const val RELEASES_TTL = 10 * 60 * 1000L
        const val SEARCH_CACHE_MAX = 16
        const val DETAILS_CACHE_MAX = 64
        const val RELEASES_CACHE_MAX = 64
    }
}
