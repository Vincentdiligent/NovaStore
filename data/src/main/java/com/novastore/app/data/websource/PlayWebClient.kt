package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.RatingHistogram
import com.novastore.app.core.model.RemoteApp
import com.novastore.app.core.model.RemoteAppDetails
import com.novastore.app.core.model.SOURCE_PLAY_WEB
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nova Web Catalog — the anonymous heart of Nova Store (v2).
 *
 * Reads the public play.google.com store pages directly from the app:
 * no Google account, no token dispenser, no third-party server.
 *
 * v2 parses the `AF_initDataCallback` JSON trees the page embeds instead of
 * the rendered HTML: the data layout is protobuf-shaped and independent of
 * the obfuscated CSS class names (which rotate over time) and of the page
 * language (the old `alt="Screenshot image"` anchors are localized and broke
 * screenshots for non-English users). The field positions below were mapped
 * against live pages for en/ru/fr/es and match the structures used by the
 * leading open-source Play scrapers.
 */
@Singleton
class PlayWebClient @Inject constructor(
    baseClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            // Google serves the complete rich page only to full browsers.
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build(),
            )
        }
        .build()

    /** In-memory cache of parsed details (pages are ~1 MB of HTML). */
    private val detailsCache = ConcurrentHashMap<String, RemoteAppDetails>()
    private val cacheTimestamps = ConcurrentHashMap<String, Long>()
    private val searchCache = ConcurrentHashMap<String, Pair<Long, List<RemoteApp>>>()

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    suspend fun search(query: String, languageTag: String): List<RemoteApp> =
        withContext(dispatcherProvider.io) {
            val cacheKey = "$languageTag:$query"
            val cached = searchCache[cacheKey]
            if (cached != null &&
                System.currentTimeMillis() - cached.first < SEARCH_TTL
            ) {
                return@withContext cached.second
            }
            val html = fetch("${BASE}store/search?q=${urlEncode(query)}&c=apps&hl=${hl(languageTag)}&gl=US")
                ?: return@withContext emptyList()
            val results = parseSearch(html)
            searchCache[cacheKey] = System.currentTimeMillis() to results
            trimSearchCache()
            results
        }

    /**
     * Public Play storefront shelves: the apps home page (category == null),
     * the games page ("GAME") or one category page ("PRODUCTIVITY", …).
     * 40–110 apps per page, no account, no rate-limited Play session —
     * this is what fills Home → "All apps" with Play content.
     */
    /** Concurrent requests for the same shelf share one download + parse. */
    private val collectionInFlight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<List<RemoteApp>>>()

    /**
     * A storefront page is 2–3 MB of HTML; parsing several at once floods the
     * heap and the resulting GC pauses froze the UI. One parse at a time.
     */
    private val parseGate = kotlinx.coroutines.sync.Semaphore(1)

    private val clientScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcherProvider.io)

    suspend fun collection(category: String?, languageTag: String): List<RemoteApp> {
        val key = "${category.orEmpty()}|$languageTag"
        val job = collectionInFlight.computeIfAbsent(key) {
            clientScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                try {
                    parseGate.withPermit { collectionUncached(category, languageTag) }
                } finally {
                    collectionInFlight.remove(key)
                }
            }
        }
        return job.await()
    }

    private suspend fun collectionUncached(category: String?, languageTag: String): List<RemoteApp> =
        withContext(dispatcherProvider.io) {
            val path = when {
                category.isNullOrBlank() -> "store/apps"
                category == "GAME" -> "store/games"
                else -> "store/apps/category/${urlEncode(category)}"
            }
            val cacheKey = "collection:$languageTag:$path"
            searchCache[cacheKey]?.let { (at, apps) ->
                if (System.currentTimeMillis() - at < COLLECTION_TTL) return@withContext apps
            }
            val html = fetch("${BASE}$path?hl=${hl(languageTag)}&gl=US") ?: return@withContext emptyList()
            val results = parseApps(html, COLLECTION_LIMIT)
            if (results.isNotEmpty()) {
                searchCache[cacheKey] = System.currentTimeMillis() to results
                trimSearchCache()
            }
            results
        }

    // ------------------------------------------------------------------
    // Details
    // ------------------------------------------------------------------

    suspend fun details(packageName: String, languageTag: String): RemoteAppDetails? =
        withContext(dispatcherProvider.io) {
            val cached = detailsCache[packageName]
            val fresh = cached != null &&
                System.currentTimeMillis() - (cacheTimestamps[packageName] ?: 0) < DETAILS_TTL
            if (fresh && cached != null) return@withContext cached

            val html = fetch("${BASE}store/apps/details?id=${urlEncode(packageName)}&hl=${hl(languageTag)}&gl=US")
                ?: return@withContext null
            val parsed = parseDetails(packageName, html) ?: return@withContext null
            detailsCache[packageName] = parsed
            cacheTimestamps[packageName] = System.currentTimeMillis()
            if (detailsCache.size > CACHE_MAX) {
                cacheTimestamps.entries.sortedBy { it.value }.take(CACHE_MAX / 4).forEach {
                    detailsCache.remove(it.key)
                    cacheTimestamps.remove(it.key)
                }
            }
            parsed
        }

    // ------------------------------------------------------------------
    // Fetching
    // ------------------------------------------------------------------

    private fun fetch(url: String): String? {
        val request = Request.Builder().url(url).get().build()
        return try {
            http.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful -> null
                    response.body == null -> null
                    else -> response.body!!.string()
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    // ------------------------------------------------------------------
    // AF_initDataCallback extraction
    // ------------------------------------------------------------------

    /**
     * Extracts the JSON arrays the page embeds as
     * `AF_initDataCallback({key: 'ds:N', ..., data: [...], sideChannel: ...})`.
     * Returns the data arrays keyed by their ds id.
     */
    internal fun extractDataBlocks(html: String): Map<String, JSONArray> {
        val blocks = HashMap<String, JSONArray>()
        val anchor = DATA_KEY.toRegex()
        val matches = anchor.findAll(html).toList()
        for (match in matches) {
            val key = match.groupValues[1]
            if (blocks.containsKey(key)) continue
            val dataAt = DATA_MARK.find(html, match.range.last) ?: continue
            val start = dataAt.range.last + 1
            if (start >= html.length || html[start] != '[') continue
            val end = matchBracket(html, start) ?: continue
            val json = runCatching { html.substring(start, end + 1) }.getOrNull() ?: continue
            runCatching { JSONArray(json) }.getOrNull()?.let { blocks[key] = it }
        }
        return blocks
    }

    /** Index of the closing bracket of the JSON array starting at [start] (string-aware). */
    private fun matchBracket(html: String, start: Int): Int? {
        var depth = 0
        var i = start
        var inString = false
        var escaped = false
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return null
    }

    // ------------------------------------------------------------------
    // Parsing — search results
    // ------------------------------------------------------------------

    /**
     * App entries are lists with the package string at [0][0], the display
     * title at [3] and ~30 sibling fields. The walker accepts both list and
     * dict containers so the search clusters (list) and the promoted first
     * result (dict) are both discovered.
     */
    internal fun parseSearch(html: String): List<RemoteApp> = parseApps(html, SEARCH_LIMIT)

    internal fun parseApps(html: String, limit: Int): List<RemoteApp> {
        val results = LinkedHashMap<String, RemoteApp>()
        // Tier 1: the promoted / exact-match card is only in the server-rendered
        // HTML (not in any AF_initDataCallback block) — without this pass the
        // ORIGINAL app is missing for queries like "Telegram".
        for (card in parseSearchHtmlCards(html)) {
            results[card.packageName] = card
        }
        // Tier 2: the structured AF data clusters (related apps, charts).
        val blocks = extractDataBlocks(html)
        for (block in blocks.values) {
            scanForAppEntries(block, maxDepth = 16) { entry ->
                val pkg = (entry.opt(0) as? JSONArray)?.opt(0) as? String ?: return@scanForAppEntries
                if (!looksLikePackage(pkg)) return@scanForAppEntries
                val existing = results[pkg]
                val candidate = searchEntryToApp(pkg, entry) ?: return@scanForAppEntries
                if (existing == null || (existing.rating == null && candidate.rating != null)) {
                    results[pkg] = candidate
                }
            }
        }
        return results.values.take(limit)
    }

    /**
     * Extracts the server-rendered app cards: anchors of the shape
     * `<a href="/store/apps/details?id=PKG" aria-label="TITLE">` followed
     * within a few KB by the icon image, the summary line and the star block.
     * Class names around them rotate over time, so only stable attributes
     * (href, aria-label, googleusercontent image URLs, the numeric rating
     * span) are matched.
     */
    internal fun parseSearchHtmlCards(html: String): List<RemoteApp> {
        val results = LinkedHashMap<String, RemoteApp>()
        for (match in SEARCH_CARD_ANCHOR.findAll(html)) {
            val pkg = match.groupValues[1]
            if (!looksLikePackage(pkg)) continue
            val title = match.groupValues[2].htmlUnescape()
            if (title.isBlank()) continue
            if (results.containsKey(pkg)) continue

            val windowStart = (match.range.last + 1).coerceAtMost(html.length - 1)
            val windowEnd = (windowStart + CARD_WINDOW).coerceAtMost(html.length)
            val window = html.substring(windowStart, windowEnd)

            val icon = CARD_ICON.find(window)?.groupValues?.get(1)?.let(::upscaleIcon)
            val summary = CARD_SUMMARY.find(window)?.groupValues?.get(1)?.htmlUnescape()
            val developer = CARD_DEVELOPER.find(window)?.groupValues?.get(1)?.htmlUnescape()
            // `3,8` (ru) and `3.8` (en) alike:
            val rating = CARD_RATING.find(window)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull()?.takeIf { it > 0f && it <= 5f }
            val downloads = CARD_DOWNLOADS.find(window)?.groupValues?.get(1)?.let(::parseDownloadCount)

            results[pkg] = RemoteApp(
                packageName = pkg,
                name = title,
                summary = summary,
                developer = developer,
                iconUrl = icon,
                license = null,
                categories = emptyList(),
                source = SOURCE_PLAY_WEB,
                rating = rating,
                downloads = downloads,
                sizeBytes = null,
                updatedMillis = null,
                containsAds = false,
                isFree = true,
            )
        }
        return results.values.toList()
    }

    /** Walks the JSON tree, invoking [visitor] for every list that looks like an app entry. */
    private fun scanForAppEntries(node: Any?, maxDepth: Int, visitor: (JSONArray) -> Unit) {
        scan(node, 0, maxDepth, visitor)
    }

    private fun scan(node: Any?, depth: Int, maxDepth: Int, visitor: (JSONArray) -> Unit) {
        if (depth > maxDepth) return
        when (node) {
            is JSONArray -> {
                if (isAppEntry(node)) visitor(node)
                for (i in 0 until node.length()) {
                    val child = node.opt(i) ?: continue
                    if (child is JSONArray || child is JSONObject) scan(child, depth + 1, maxDepth, visitor)
                }
            }
            is JSONObject -> {
                for (key in node.keys()) {
                    val child = node.opt(key) ?: continue
                    if (child is JSONArray || child is JSONObject) scan(child, depth + 1, maxDepth, visitor)
                }
            }
        }
    }

    /** Structural signature of a search app entry: [pkg, …icon…, …, title, rating, category, …]. */
    private fun isAppEntry(entry: JSONArray): Boolean {
        if (entry.length() < 12) return false
        val first = entry.opt(0)
        if (first !is JSONArray || first.length() == 0) return false
        val pkg = first.opt(0) as? String ?: return false
        if (!looksLikePackage(pkg)) return false
        val title = entry.opt(3)
        if (title !is String || title.isBlank()) return false
        // rating block is a 2-element list ["4.5", 4.51]
        val rating = entry.opt(4)
        if (rating !is JSONArray || rating.length() < 2) return false
        return true
    }

    private fun searchEntryToApp(pkg: String, entry: JSONArray): RemoteApp? {
        val title = entry.opt(3) as? String ?: return null
        val rating = (entry.opt(4) as? JSONArray)?.opt(0) as? String
        val developer = entry.opt(14) as? String
        val summary = (entry.opt(13) as? JSONArray)?.opt(1) as? String
        val downloads = entry.opt(15) as? String
        val category = entry.opt(5) as? String
        val icon = imageUrlIn(entry.opt(1))
            ?: imageUrlIn(entry.opt(22))
        // screenshots are shipped with search cards on modern pages
        val priceBlock = entry.opt(8) as? JSONArray
        val isFree = priceBlock == null || priceBlock.optJSONObject(1) == null
        return RemoteApp(
            packageName = pkg,
            name = title.htmlUnescape(),
            summary = summary?.htmlUnescape(),
            developer = developer?.htmlUnescape(),
            iconUrl = icon?.let(::upscaleIcon),
            license = null,
            categories = listOfNotNull(category?.takeIf { it.isNotBlank() }),
            source = SOURCE_PLAY_WEB,
            rating = rating?.toFloatOrNull()?.takeIf { it > 0f },
            downloads = downloads?.let(::parseDownloadCount),
            sizeBytes = null,
            updatedMillis = null,
            containsAds = false,
            isFree = isFree,
        )
    }

    /** Finds the URL inside an image descriptor `[null, type, [w, h], [null, null, url]]`. */
    private fun imageUrlIn(node: Any?): String? {
        if (node !is JSONArray) return null
        val urlNode = node.opt(3) as? JSONArray ?: return null
        return urlNode.opt(2) as? String
    }

    /**
     * Image URL inside a wrapped descriptor `[[null, type, [w,h], [null,null,url]]]`
     * or a bare one — details-page fields appear in both shapes.
     */
    private fun firstImageIn(node: Any?): String? {
        if (node !is JSONArray) return null
        imageUrlIn(node)?.let { return it }
        val inner = node.opt(0) ?: return null
        if (inner is JSONArray) return imageUrlIn(inner)
        return null
    }

    /** Epoch millis: scans a date block for the first epoch-seconds number (≥1e9). */
    private fun epochAt(L: JSONArray, vararg basePath: Int): Long? {
        val node = L.valueAt(*basePath) ?: return null
        return findEpochSeconds(node)?.times(1000L)
    }

    private fun findEpochSeconds(node: Any?): Long? {
        if (node is Number) {
            val v = node.toLong()
            return if (v in 1_000_000_000L until 10_000_000_000L) v else null
        }
        if (node !is JSONArray) return null
        for (i in 0 until node.length()) {
            findEpochSeconds(node.opt(i))?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Parsing — details page
    // ------------------------------------------------------------------

    internal fun parseDetails(packageName: String, html: String): RemoteAppDetails? {
        val blocks = extractDataBlocks(html)
        // The listing block: a long list where known indices hold the fields.
        var listing: JSONArray? = null
        for (block in blocks.values) {
            val candidate = findListingArray(block)
            if (candidate != null) {
                listing = candidate
                break
            }
        }
        val L = listing ?: return parseDetailsFallback(packageName, html)

        val name = L.stringAt(0, 0) ?: return parseDetailsFallback(packageName, html)
        val summary = L.stringAt(73, 0, 1)
        val descriptionHtml = L.stringAt(72, 0, 1)
        val developer = L.stringAt(37, 0) ?: L.stringAt(68, 0)
        val category = L.stringAt(79, 0, 0, 0)
        val contentRating = L.stringAt(9, 0)

        val icon = firstImageIn(L.opt(95)) ?: metaContent(html, "og:image")?.let(::upscaleIcon)
        val screenshots = parseScreenshots(L.opt(78))
        val banner = firstImageIn(L.opt(96))

        val ratingBlock = L.opt(51) as? JSONArray
        val ratingInner = ratingBlock?.opt(0) as? JSONArray
        val ratingValue = (ratingInner?.opt(1) as? Number)?.toFloat()
            ?: (ratingInner?.opt(0) as? String)?.toFloatOrNull()
        val ratingCount = ((ratingBlock?.opt(2) as? JSONArray)?.opt(1) as? Number)?.toLong()
        val histogram = parseHistogram(ratingBlock?.opt(1))

        val downloads = ((L.opt(13) as? JSONArray)?.opt(1) as? Number)?.toLong()
        val downloadsText = L.stringAt(13, 0)
        val updatedMillis = epochAt(L, 145) ?: updatedFromHtml(html)
        val releasedMillis = epochAt(L, 10)

        val website = L.valueAt(69, 0, 5, 2) as? String
        val email = L.valueAt(69, 1, 0) as? String
        val privacy = L.valueAt(99, 0, 5, 2) as? String
        val videoUrl = findVideoUrl(L)

        val iap = L.stringAt(19, 0)
        val priceMeta = PRICE_META.find(html)?.groupValues?.get(1)
        val isFree = priceMeta?.let { it == "0" } ?: (priceSignal(L) == null)

        val app = RemoteApp(
            packageName = packageName,
            name = name.htmlUnescape(),
            summary = summary?.htmlUnescape(),
            developer = developer?.htmlUnescape(),
            iconUrl = icon?.let { upscaleIcon(it) },
            license = null,
            categories = listOfNotNull(
                category?.takeIf { it.isNotBlank() },
                contentRating?.takeIf { it.isNotBlank() },
            ),
            source = SOURCE_PLAY_WEB,
            rating = ratingValue?.takeIf { it > 0f },
            downloads = downloads ?: downloadsText?.let(::parseDownloadCount),
            sizeBytes = null,
            updatedMillis = updatedMillis,
            containsAds = false,
            isFree = isFree,
        )
        return RemoteAppDetails(
            app = app,
            description = (descriptionHtml ?: summary)?.let(::renderHtmlText),
            changelog = null, // Play loads "What's new" through a separate RPC
            website = website ?: privacy,
            sourceCodeUrl = null,
            versions = emptyList(), // versions come from the mirror or a signed-in session
            screenshots = if (screenshots.isNotEmpty()) screenshots else listOfNotNull(banner),
            videoUrl = videoUrl,
            ratingCount = ratingCount,
            developerEmail = email,
            price = priceSignal(L) ?: iap,
            ratingHistogram = histogram,
            updatedMillis = updatedMillis,
            releasedMillis = releasedMillis,
        )
    }

    /**
     * Locates the 100+ field listing array: the nested list that contains
     * the package name at index 77 and a title at index 0.
     */
    private fun findListingArray(root: JSONArray, depth: Int = 0): JSONArray? {
        if (depth > 6) return null
        for (i in 0 until root.length()) {
            val node = root.opt(i) ?: continue
            if (node !is JSONArray) continue
            if (node.length() >= 100 && isListingArray(node)) return node
            val found = findListingArray(node, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun isListingArray(node: JSONArray): Boolean {
        val pkgNode = node.opt(77) as? JSONArray ?: return false
        if ((pkgNode.opt(0) as? String).isNullOrBlank()) return false
        val title = node.stringAt(0, 0) ?: return false
        return title.isNotBlank()
    }

    private fun parseScreenshots(node: Any?): List<String> {
        if (node !is JSONArray || node.length() == 0) return emptyList()
        // wrapper may be direct or one level deep
        var wrapper = node.opt(0) as? JSONArray
        if (wrapper != null && imageUrlIn(wrapper) != null) wrapper = node
        if (wrapper == null) return emptyList()
        val urls = LinkedHashSet<String>()
        for (i in 0 until wrapper.length()) {
            val shot = wrapper.opt(i) as? JSONArray ?: continue
            val dims = shot.opt(2) as? JSONArray
            val width = (dims?.opt(0) as? Number)?.toInt() ?: 0
            val height = (dims?.opt(1) as? Number)?.toInt() ?: 0
            val url = imageUrlIn(shot) ?: continue
            // keep screenshots only — icons/banners are square-ish or banner shaped
            if (width > 0 && height > 0 && width != height) {
                urls += if (width > height) "$url=w1052-h592-rw" else "$url=w592-h1052-rw"
            }
        }
        return urls.toList().take(SCREENSHOT_LIMIT)
    }

    private fun parseHistogram(node: Any?): RatingHistogram? {
        val countsWrapper = node as? JSONArray ?: return null
        val counts = (countsWrapper.opt(1) as? JSONArray) ?: return null
        if (counts.length() < 5) return null
        val desc = (0 until 5).mapNotNull { i ->
            ((counts.opt(i) as? JSONArray)?.opt(1) as? Number)?.toLong()
        }
        if (desc.size < 5) return null
        return RatingHistogram.fromCountsDesc(desc)
    }

    private fun findVideoUrl(L: JSONArray): String? {
        val serialized = L.toString()
        val m = VIDEO_URL.find(serialized) ?: return null
        return m.groupValues[1]
    }

    /** Old-class-based parsing kept as a safety net when the JSON tree moves. */
    private fun parseDetailsFallback(packageName: String, html: String): RemoteAppDetails? {
        val name = metaContent(html, "og:title")?.removeSuffix(TITLE_SUFFIX)
            ?: return null
        val summary = metaContent(html, "og:description")?.htmlUnescape()
        val icon = metaContent(html, "og:image")?.let(::upscaleIcon)
        val screenshots = SCREENSHOT_ANY.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.contains("play-lh", ignoreCase = true) }
            .distinct()
            .toList()
        val app = RemoteApp(
            packageName = packageName,
            name = name.htmlUnescape(),
            summary = summary,
            developer = null,
            iconUrl = icon,
            license = null,
            categories = emptyList(),
            source = SOURCE_PLAY_WEB,
            rating = RATING_JSON.find(html)?.groupValues?.get(1)?.toFloatOrNull(),
            downloads = null,
            sizeBytes = null,
            updatedMillis = updatedFromHtml(html),
            containsAds = false,
            isFree = true,
        )
        return RemoteAppDetails(
            app = app,
            description = summary,
            changelog = null,
            website = null,
            sourceCodeUrl = null,
            versions = emptyList(),
            screenshots = screenshots,
            videoUrl = null,
            ratingCount = RATING_COUNT_JSON.find(html)?.groupValues?.get(1)?.toLongOrNull(),
            developerEmail = null,
            price = null,
        )
    }

    // ------------------------------------------------------------------
    // JSON helpers
    // ------------------------------------------------------------------

    /**
     * HTML fallback for the listing's "Updated on" date. Google removed the
     * version number from the public pages, but the localized update date is
     * always rendered — Nova's DISCOVERY tier feeds on exactly this signal.
     * Pages are always fetched with hl=en, so the English date format is the
     * only one that needs to parse.
     */
    internal fun updatedFromHtml(html: String): Long? {
        val raw = UPDATED_ON_HTML.find(html)?.groupValues?.get(1) ?: return null
        return runCatching {
            UPDATED_ON_FORMAT.get().parse(raw.trim())?.time
        }.getOrNull()
    }

    private fun JSONArray.stringAt(vararg path: Int): String? {
        val value = valueAt(*path)
        return value as? String
    }

    private fun JSONArray.valueAt(vararg path: Int): Any? {
        var node: Any? = this
        for (index in path) {
            node = (node as? JSONArray)?.opt(index) ?: return null
        }
        return node
    }

    private fun looksLikePackage(value: String): Boolean =
        PACKAGE_REGEX.matches(value) && value.length > 5

    // ------------------------------------------------------------------
    // Text helpers
    // ------------------------------------------------------------------

    private fun hl(languageTag: String): String {
        val base = languageTag.substringBefore('-').lowercase(Locale.ROOT)
        return if (base.length == 2) base else "en"
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private fun metaContent(html: String, property: String): String? {
        val direct = """<meta[^>]*property="$property"[^>]*content="([^"]*)"""".toRegex()
            .find(html)?.groupValues?.get(1)
        if (direct != null) return direct
        return """<meta[^>]*content="([^"]*)"[^>]*property="$property"""".toRegex()
            .find(html)?.groupValues?.get(1)
    }

    private fun upscaleIcon(url: String): String {
        val base = url.substringBefore('=')
        return "$base=s256-rw"
    }

    /** Enlarges a screenshot URL for the full-screen viewer. */
    fun upscaleScreenshot(url: String): String {
        if (url.contains("=w1052") || url.contains("=w592")) return url
        val base = url.substringBefore('=')
        return "$base=w1052-h592-rw"
    }

    /**
     * Plain text for titles/summaries: entities decoded AND markup removed.
     * Play embeds promo markup in short descriptions
     * (`Welcome to <font color="#59288a">Upstox</font>`) — it must never
     * reach the UI as raw tags.
     */
    private fun String.htmlUnescape(): String = this
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&#160;", " ")
        .replace("&nbsp;", " ")
        .replace(NUMERIC_ENTITY) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: "" }
        .replace(HTML_TAG, "")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Turns Play's description HTML into displayable text: keeps paragraph
     * and line breaks, list bullets and bold markers as plain text.
     */
    private fun renderHtmlText(html: String): String {
        return html
            .htmlUnescape()
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
            .replace(Regex("<[^>]+>"), "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun parseDownloadCount(text: String): Long? {
        val clean = text.trim().removeSuffix("+").replace("\u00A0", "").replace(",", ".")
        val lower = clean.lowercase(Locale.ROOT)
        val multiplier = when {
            lower.endsWith("млрд") || lower.endsWith("млрд.") || lower.endsWith("md") ||
                lower.endsWith("bn") || lower.endsWith("b") -> 1_000_000_000L
            lower.endsWith("млн") || lower.endsWith("млн.") || lower.endsWith("mm") ||
                lower.endsWith("m") -> 1_000_000L
            lower.endsWith("тыс") || lower.endsWith("тыс.") || lower.endsWith("k") -> 1_000L
            else -> 1L
        }
        val number = buildString {
            for (c in clean) {
                if (c.isDigit() || c == '.') append(c)
            }
        }.toDoubleOrNull() ?: return null
        if (number <= 0.0) return null
        return (number * multiplier).toLong()
    }

    private fun priceSignal(L: JSONArray): String? {
        val iap = L.stringAt(19, 0)
        if (!iap.isNullOrBlank()) return iap
        val price = L.valueAt(8, 1, 0) as? String
        return price?.takeIf { it.isNotBlank() && it != "0" }
    }

    private fun trimSearchCache() {
        if (searchCache.size <= SEARCH_CACHE_MAX) return
        val oldest = searchCache.entries.sortedBy { it.value.first }
            .take(searchCache.size / 4)
            .map { it.key }
        oldest.forEach { searchCache.remove(it) }
    }

    private companion object {
        const val BASE = "https://play.google.com/"
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        const val SEARCH_LIMIT = 60
        val HTML_TAG = Regex("<[^>]*>")
        val NUMERIC_ENTITY = Regex("""&#(\d{1,6});""")
        val WHITESPACE = Regex("""\s+""")
        const val COLLECTION_LIMIT = 150
        const val COLLECTION_TTL = 30 * 60 * 1000L
        const val SCREENSHOT_LIMIT = 12
        const val CACHE_MAX = 64
        const val DETAILS_TTL = 10 * 60 * 1000L
        const val SEARCH_TTL = 2 * 60 * 1000L
        const val SEARCH_CACHE_MAX = 24
        const val TITLE_SUFFIX = " - Apps on Google Play"

        val UPDATED_ON_HTML = Regex("""Updated on</div><div[^>]*>([^<]{4,40})</div>""")
        val UPDATED_ON_FORMAT = object : ThreadLocal<java.text.SimpleDateFormat>() {
            override fun initialValue() = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US)
        }

        val PACKAGE_REGEX = Regex("""[a-z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+""")

        val DATA_KEY = """AF_initDataCallback\(\{key: '(ds:\d+)'"""
        val DATA_MARK = Regex("""data:""")

        val RATING_JSON = """"ratingValue":"([0-9.]+)"""".toRegex()
        val RATING_COUNT_JSON = """"ratingCount":"([0-9]+)"""".toRegex()
        val PRICE_META = """itemprop="price" content="([0-9.]+)"""".toRegex()
        val SCREENSHOT_ANY = """src="(https://play-lh\.googleusercontent\.com/[A-Za-z0-9_=-]+)"""".toRegex()
        val VIDEO_URL = Regex("""(https://www\.youtube\.com/embed/[A-Za-z0-9_-]{6,})""")

        // --- server-rendered promoted app card (stable attributes only) ---
        /** `<a href="/store/apps/details?id=PKG" aria-label="TITLE">` */
        val SEARCH_CARD_ANCHOR = Regex(
            """<a\s+href="/store/apps/details\?id=([a-zA-Z0-9._]+)"\s+aria-label="([^"]+)"""",
        )
        /** First icon image of the card; the =s64/-rw suffix is stripped by [upscaleIcon]. */
        val CARD_ICON = Regex("""src="(https://play-lh\.googleusercontent\.com/[A-Za-z0-9_+=-]+)"""")
        /** Summary line `<div class="omXQ6c">…</div>` (class rotates; best-effort). */
        val CARD_SUMMARY = Regex("""<div class="omXQ6c">([^<]{10,300})</div>""")
        /** Developer line `<div class="LbQbAe">…</div>` (class rotates; best-effort). */
        val CARD_DEVELOPER = Regex("""<div class="LbQbAe">([^<]{2,80})</div>""")
        /** Star block numeric value, `3,8` / `3.8` alike. */
        val CARD_RATING = Regex("""<span aria-hidden="true">([0-9]+[.,][0-9]+)</span>""")
        /** Text-only `ClM7O` cell = the download count ("1 млрд+" / "1B+"). */
        val CARD_DOWNLOADS = Regex("""<div class="ClM7O">([^<]{1,24})</div>""")
        /** How far behind the anchor the card fields live. */
        const val CARD_WINDOW = 3200
    }
}
