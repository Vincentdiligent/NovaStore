package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.SOURCE_APKCOMBO
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Second stage of the Nova mirror chain: APKCombo.
 *
 * Same catalog family as APKPure behind a different edge network — it serves
 * plain HTML pages (search by package, full version history with dates) even
 * to non-browser clients, which makes it a reliable companion when one mirror
 * family is blocked on the current network.
 *
 * Honest limitation: final file links are produced by in-page JavaScript, so
 * automated resolution degrades to the WebView fallback (`MirrorBrowser`)
 * which always works on the user's device.
 */
@Singleton
class ApkComboClient @Inject constructor(
    baseClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://apkcombo.com/")
                    .build(),
            )
        }
        .build()

    private val appPaths = ConcurrentHashMap<String, String>()
    private val pathTimestamps = ConcurrentHashMap<String, Long>()
    private val versionsCache = ConcurrentHashMap<String, List<AppVersion>>()
    private val versionsTimestamps = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Version history (name + date + size when present), newest first. */
    suspend fun versions(packageName: String): List<AppVersion> =
        withContext(dispatcherProvider.io) {
            val cached = versionsCache[packageName]
            val fresh = cached != null &&
                System.currentTimeMillis() - (versionsTimestamps[packageName] ?: 0) < VERSIONS_TTL
            if (fresh && cached != null) return@withContext cached

            val path = resolveAppPath(packageName) ?: return@withContext emptyList()
            val html = fetch("$BASE$path/old-versions/") ?: return@withContext emptyList()
            val parsed = parseOldVersions(packageName, html)
            versionsCache[packageName] = parsed
            versionsTimestamps[packageName] = System.currentTimeMillis()
            parsed
        }

    /** Bulk version lookup — bounded parallelism, quiet failures. */
    suspend fun versionsFor(packageNames: Collection<String>): Map<String, List<AppVersion>> =
        kotlinx.coroutines.coroutineScope {
            val gate = Semaphore(4)
            packageNames.distinct().map { pkg ->
                async(dispatcherProvider.io) {
                    gate.withPermit {
                        pkg to runCatching { versions(pkg) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll()
                .filter { it.second.isNotEmpty() }
                .toMap()
        }

    /**
     * Tries to resolve a direct artifact URL for one version. APKCombo hides
     * the final link behind JavaScript, so this returns null most of the time
     * and the WebView fallback takes over.
     */
    suspend fun resolveDownload(packageName: String, versionName: String): DownloadSpec? =
        withContext(dispatcherProvider.io) {
            val path = resolveAppPath(packageName) ?: return@withContext null
            val slugVersion = versionName.filter { it.isLetterOrDigit() || it == '.' }.lowercase()
            val html = fetch("$BASE$path/download/phone-$slugVersion-apk") ?: return@withContext null
            parseDirectLink(html)
        }

    /** Landing page for the WebView fallback: search results for the package. */
    fun searchLandingUrl(packageName: String): String = "$BASE/search/${encode(packageName)}"

    data class DownloadSpec(
        val url: String,
        val fileName: String,
        val sizeBytes: Long?,
        val isBundle: Boolean,
    )

    // ------------------------------------------------------------------
    // Path resolution
    // ------------------------------------------------------------------

    private suspend fun resolveAppPath(packageName: String): String? {
        val fresh = appPaths[packageName]?.let {
            System.currentTimeMillis() - (pathTimestamps[packageName] ?: 0) < PATH_TTL
        } ?: false
        if (fresh) return appPaths[packageName]

        val html = fetch("$BASE/search/${encode(packageName)}") ?: return null
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
                    !r.isSuccessful -> null
                    r.body == null -> null
                    else -> r.body!!.string()
                }
            }
        } catch (e: IOException) {
            null
        }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    internal fun parseOldVersions(packageName: String, html: String): List<AppVersion> {
        val results = mutableListOf<AppVersion>()
        val matches = VER_ITEM.findAll(html).toList()
        var rank = 0
        val seen = HashSet<String>()
        for (match in matches) {
            val href = match.groupValues[1]
            val body = match.groupValues[2]
            val versionName = VER_SLUG.find(href)?.groupValues?.get(1)?.removePrefix("v") ?: continue
            if (!seen.add(versionName)) continue
            val size = SIZE_BYTES.find(body)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            val date = DATE_IN_BODY.find(body)?.groupValues?.get(1)?.let(::parseDate)
            results += AppVersion(
                packageName = packageName,
                // APKCombo does not expose numeric version codes in HTML; a
                // descending synthetic code keeps ordering stable (newest first).
                versionCode = SYNTHETIC_BASE - rank,
                versionName = versionName,
                source = SOURCE_APKCOMBO,
                size = size?.times(MB),
                downloadUrl = "",
                sha256 = null,
                minSdk = null,
                targetSdk = null,
                addedAt = date,
                artifactType = ArtifactType.APK,
                signer = null,
                nativeCode = emptyList(),
            )
            rank++
        }
        return results.take(VERSIONS_LIMIT)
    }

    internal fun parseDirectLink(html: String): DownloadSpec? {
        val direct = APK_FILE_URL.find(html)?.groupValues?.get(1)
            ?: CDN_URL.find(html)?.groupValues?.get(1)
            ?: return null
        if (direct.contains("apkcombo-installer") || direct.contains("apkflash.com")) return null
        val name = direct.substringAfterLast('/').substringBefore('?')
        val isBundle = direct.contains(".xapk", true) || direct.contains(".apkm", true) ||
            name.contains(".xapk", true)
        if (!isBundle && !name.endsWith(".apk", true) && !direct.endsWith(".apk")) return null
        val size = SIZE_BYTES.find(html)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
        return DownloadSpec(
            url = direct,
            fileName = when {
                isBundle && !name.endsWith(".xapk", true) -> "$name.xapk"
                !isBundle && !name.endsWith(".apk", true) -> "$name.apk"
                else -> name
            },
            sizeBytes = size?.times(MB),
            isBundle = isBundle,
        )
    }

    private fun parseDate(text: String): Long? = runCatching {
        java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).parse(text)?.time
    }.getOrNull()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE = "https://apkcombo.com"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val VERSIONS_LIMIT = 12
        const val VERSIONS_TTL = 6 * 60 * 60 * 1000L
        const val PATH_TTL = 24 * 60 * 60 * 1000L
        const val SYNTHETIC_BASE = 900_000_000L
        const val MB = 1024L * 1024L

        /** first `/<slug>/<package>/` link on a package search page */
        val APP_LINK = Regex("""href="(/[a-zA-Z0-9._-]+/([a-z][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)+)/?)"?""")

        val VER_ITEM = Regex("""<a class="ver-item" href="(/[^"]+)" rel="nofollow">(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val VER_SLUG = Regex("""/download/(?:phone-)?v?([0-9][0-9A-Za-z.\-]*)-?(?:apk|xapk)?/?$""")
        val SIZE_BYTES = Regex("""([0-9][0-9,.]*)\s*(?:MB|Mb|mb)""")
        val DATE_IN_BODY = Regex("""([A-Z][a-z]{2} [0-9]{1,2}, [0-9]{4})""")
        val APK_FILE_URL = Regex("""href="(https?://[^"]+\.(?:apk|xapk|apkm)[^"]*)"""")
        val CDN_URL = Regex("""(https://(?:files|cdn|dl)[a-z0-9.-]*(?:apkcombo|apkpure)[a-z.]*/[^"'\s<>]{5,140})""")
    }
}
