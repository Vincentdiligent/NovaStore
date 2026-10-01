package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.SOURCE_APKPURE
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Nova community mirror on top of APKPure's public web pages.
 *
 * Completes the anonymous access scheme: the Nova Web Catalog
 * ([PlayWebClient]) provides metadata for every app, and this mirror
 * provides version histories and downloadable APK files without any
 * account — the two together make anonymous updates for Google Play apps
 * possible without servers, token dispensers or pooled accounts.
 *
 * Honest limitations: the mirror is a public website; some networks or
 * regions may block it, and apps that only ship as XAPK bundles cannot be
 * installed account-lessly yet. Every failure degrades gracefully.
 */
@Singleton
class ApkPureClient @Inject constructor(
    baseClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    data class DownloadSpec(
        val url: String,
        val fileName: String,
        val sizeBytes: Long?,
        val isBundle: Boolean,
    )

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://apkpure.com/")
                    .build(),
            )
        }
        .build()

    private val requestGate = Semaphore(4)

    /** package -> app path segment ("/slug/package"), resolved once per process. */
    private val appPaths = ConcurrentHashMap<String, String>()
    private val pathTimestamps = ConcurrentHashMap<String, Long>()
    private val versionsCache = ConcurrentHashMap<String, List<AppVersion>>()
    private val versionsTimestamps = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Version history of [packageName], newest first. Empty when the mirror
     * does not know the app or is unreachable.
     */
    suspend fun versions(packageName: String): List<AppVersion> =
        withContext(dispatcherProvider.io) {
            val cached = versionsCache[packageName]
            val fresh = cached != null &&
                System.currentTimeMillis() - (versionsTimestamps[packageName] ?: 0) < VERSIONS_TTL
            if (fresh && cached != null) return@withContext cached

            val path = resolveAppPath(packageName) ?: return@withContext emptyList()
            val html = fetch("$BASE$path/versions") ?: return@withContext emptyList()
            val parsed = parseVersions(packageName, html)
            versionsCache[packageName] = parsed
            versionsTimestamps[packageName] = System.currentTimeMillis()
            parsed
        }

    /** Bulk lookup used by the update scan — parallel but bounded. */
    suspend fun versionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>> =
        coroutineScope {
            packageNames.distinct().map { pkg ->
                async(dispatcherProvider.io) {
                    requestGate.withPermit {
                        pkg to runCatching { versions(pkg) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
                .filter { it.second.isNotEmpty() }
                .toMap()
        }

    /**
     * Resolves the concrete downloadable artifact of one version.
     * Returns null when the mirror cannot serve it (blocked, paid, or
     * only available as an unsupported XAPK bundle).
     */
    suspend fun resolveDownload(packageName: String, versionCode: Long): DownloadSpec? =
        withContext(dispatcherProvider.io) {
            val path = resolveAppPath(packageName) ?: return@withContext null
            val html = fetch("$BASE$path/download/$versionCode?from=versions")
                ?: return@withContext null
            parseDownloadLink(html)
        }

    /** True when the mirror answers at all (used for honest status UI). */
    suspend fun isReachable(): Boolean = withContext(dispatcherProvider.io) {
        fetchHead("$BASE") != null
    }

    // ------------------------------------------------------------------
    // Path resolution (slug) via search-by-package
    // ------------------------------------------------------------------

    private suspend fun resolveAppPath(packageName: String): String? {
        val fresh = appPaths[packageName]?.let {
            System.currentTimeMillis() - (pathTimestamps[packageName] ?: 0) < PATH_TTL
        } ?: false
        if (fresh) return appPaths[packageName]

        val html = fetch("${BASE}search?q=${packageName}") ?: return null
        val path = APP_LINK.find(html)?.groupValues?.get(1) ?: return null
        appPaths[packageName] = path
        pathTimestamps[packageName] = System.currentTimeMillis()
        return path
    }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private fun fetch(url: String): String? =
        try {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                when {
                    !r.isSuccessful -> null // 403 = blocked/region — degrade quietly
                    r.body == null -> null
                    else -> r.body!!.string()
                }
            }
        } catch (e: IOException) {
            null
        }

    private fun fetchHead(url: String): Any? = try {
        http.newCall(Request.Builder().url(url).head().build()).execute().use { r -> r.code }
    } catch (e: IOException) {
        null
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    internal fun parseVersions(packageName: String, html: String): List<AppVersion> {
        val results = mutableListOf<AppVersion>()
        val seen = HashSet<Long>()
        val linkRegex = DOWNLOAD_LINK.toRegex()
        val matches = linkRegex.findAll(html).toList()
        for ((index, match) in matches.withIndex()) {
            val code = match.groupValues[2].toLongOrNull() ?: continue
            if (!seen.add(code)) continue

            val from = (match.range.first - VERSION_CONTEXT).coerceAtLeast(0)
            val to = (match.range.last + VERSION_CONTEXT).coerceAtMost(html.length)
            val context = html.substring(from, to)

            val versionName = versionNameIn(context)
            val size = SIZE_BYTES.find(context)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            val isBundle = context.contains("XAPK", ignoreCase = true)
            val date = DATE_IN_CONTEXT.find(context)?.groupValues?.get(1)

            results += AppVersion(
                packageName = packageName,
                versionCode = code,
                versionName = versionName ?: "v$code",
                source = SOURCE_APKPURE,
                size = size,
                downloadUrl = "", // resolved fresh at download time
                sha256 = null,
                minSdk = null,
                targetSdk = null,
                addedAt = date?.let(::parseDate),
                artifactType = if (isBundle) ArtifactType.APK_SET else ArtifactType.APK,
                signer = null,
                nativeCode = emptyList(),
            )
        }
        return results.take(VERSIONS_LIMIT)
    }

    private fun versionNameIn(context: String): String? =
        NAMED_VERSION.find(context)?.groupValues?.get(1)

    internal fun parseDownloadLink(html: String): DownloadSpec? {
        val direct = PURE_CD_URL.find(html)?.groupValues?.get(1)
            ?: APK_FILE_URL.find(html)?.groupValues?.get(1)
            ?: IFRAME_SRC.find(html)?.groupValues?.get(1)
            ?: return null
        val name = direct.substringAfterLast('/').substringBefore('?')
        val size = SIZE_BYTES.find(html)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        val isBundle = direct.contains(".xapk", true) || direct.contains(".apkm", true) ||
            name.contains(".xapk", true) || name.contains(".apkm", true)
        return DownloadSpec(
            url = direct,
            fileName = when {
                isBundle && !name.endsWith(".xapk", true) && !name.endsWith(".apkm", true) -> "$name.xapk"
                !isBundle && !name.endsWith(".apk", true) -> "$name.apk"
                else -> name
            },
            sizeBytes = size,
            isBundle = isBundle,
        )
    }

    private fun parseDate(text: String): Long? = runCatching {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).parse(text)?.time
    }.getOrNull()

    // ------------------------------------------------------------------

    private companion object {
        const val BASE = "https://apkpure.com"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val VERSIONS_LIMIT = 12
        const val VERSION_CONTEXT = 1200
        const val VERSIONS_TTL = 6 * 60 * 60 * 1000L
        const val PATH_TTL = 24 * 60 * 60 * 1000L

        /** "/slug/package" of the first app card on a search page. */
        val APP_LINK = Regex("""href="(/[a-zA-Z0-9._-]+/([a-z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+))"""")
        val DOWNLOAD_LINK = """href="([^"]*/download/(\d+)[^"]*)""""
        val NAMED_VERSION = Regex(""":?>((?:\d+\.){1,3}\d+[0-9A-Za-z.\-_]{0,24})<""")
        val SIZE_BYTES = Regex("""([0-9][0-9,.]*)\s*(?:MB|Mb|mb)""")
        val DATE_IN_CONTEXT = Regex("""([A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4})""")
        val PURE_CD_URL = Regex("""(https://d\.apkpure\.[a-z]+/[^"'\s<>]+)""")
        val APK_FILE_URL = Regex("""href="(https?://[^"]+\.(?:apk|xapk|apkm)[^"]*)"""")
        val IFRAME_SRC = Regex("""<iframe[^>]+src="([^"]+)"""")
    }
}
