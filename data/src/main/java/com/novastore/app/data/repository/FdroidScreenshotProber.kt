package com.novastore.app.data.repository

import com.novastore.app.core.common.DispatcherProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class FdroidScreenshotProber @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = ConcurrentHashMap<String, List<String>>()
    private val knownEmpty = ConcurrentHashMap.newKeySet<String>()

    /**
     * Returns screenshot URLs for [packageName] hosted on the repository at
     * [repoBaseUrl] (e.g. "https://f-droid.org/repo"), or an empty list.
     *
     * Two strategies, fastest first:
     *  1. The repository's package web page embeds every screenshot the
     *     developer shipped — in ANY locale — so one GET finds them all.
     *     Only f-droid.org-style sites are known to serve these pages.
     *  2. HEAD-probing the fastlane layout directly under a list of locale
     *     directories: most developers ship only "en-US", so the device
     *     locale alone misses them — this was the root cause of apps with
     *     no photos.
     */
    suspend fun probeScreenshots(repoBaseUrl: String, packageName: String): List<String> {
        val base = repoBaseUrl.trimEnd('/')
        if (base.isBlank()) return emptyList()
        val cacheKey = "$base|$packageName"
        cache[cacheKey]?.let { return it }
        if (cacheKey in knownEmpty) return emptyList()

        return withContext(dispatcherProvider.io) {
            val found = fromPackagePage(base, packageName)
                .ifEmpty { probeFastlaneLayout(base, packageName) }

            if (found.isEmpty()) {
                knownEmpty += cacheKey
            } else {
                cache[cacheKey] = found
            }
            found
        }
    }

    /**
     * Strategy 1: parse `<img src>` tags of the package page. Screenshot
     * URLs live under the repository root and contain a *Screenshots path
     * segment, e.g. "/repo/org.pkg/en-US/phoneScreenshots/00.png".
     */
    private suspend fun fromPackagePage(repoBaseUrl: String, packageName: String): List<String> {
        // The package pages are served by the repository's website, not the
        // repo itself: https://f-droid.org/repo → https://f-droid.org.
        val site = repoBaseUrl.substringBefore("/repo").trimEnd('/')
        if (site.isEmpty() || !site.contains('.')) return emptyList()
        val html = fetch("$site/en/packages/$packageName/") ?: return emptyList()
        return IMG_SRC.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("screenshots", ignoreCase = true) }
            .map { url -> if (url.startsWith("http")) url else "$site${url.removePrefix("/")}" }
            .filter { it.startsWith("http") }
            .distinct()
            .take(MAX_SHOTS)
            .toList()
    }

    /**
     * Strategy 2: HEAD-probe the fastlane directory layout, walking a list
     * of locale candidates from the most to the least likely.
     */
    private suspend fun probeFastlaneLayout(repoBaseUrl: String, packageName: String): List<String> =
        coroutineScope {
            for (locale in LOCALE_CANDIDATES) {
                val found = (0 until MAX_SHOTS).map { index ->
                    async(Dispatchers.IO) {
                        val name = "%02d.png".format(index)
                        val url = "$repoBaseUrl/$packageName/$locale/phoneScreenshots/$name"
                        if (exists(url)) url else null
                    }
                }.awaitAll()
                    .takeWhile { it != null } // stop at the first gap
                    .filterNotNull()
                if (found.isNotEmpty()) return@coroutineScope found
            }
            emptyList()
        }

    private fun exists(url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "NovaStore/3.0")
            .build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && (response.headers["Content-Length"]?.toIntOrNull() ?: 1) > 0
        }
    }.getOrDefault(false)

    private fun fetch(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "NovaStore/3.0")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    private companion object {
        const val MAX_SHOTS = 6
        val IMG_SRC = Regex("""<img[^>]+src="([^"]+)"""")
        val LOCALE_CANDIDATES = listOf("en-US", "en", "de-DE")
    }
}
