package com.novastore.app.data.websource

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppReview
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Nova review feed — real Google Play user reviews, read anonymously.
 *
 * Uses the same `batchexecute` RPC the play.google.com page itself uses when
 * the reviews section opens (`UsvDTd`). No account, no token: the endpoint is
 * public for browsers, and the response embeds author, avatar, star rating,
 * full text and timestamps for every review, plus a pagination token.
 */
@Singleton
class PlayReviewsClient @Inject constructor(
    baseClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    enum class Sort(internal val code: Int) {
        NEWEST(2),
        HELPFUL(1),
        HIGHEST(3),
        LOWEST(4),
    }

    private val http: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", DESKTOP_UA)
                    .header("Referer", "https://play.google.com/")
                    .build(),
            )
        }
        .build()

    private val cache = ConcurrentHashMap<String, Pair<Long, List<AppReview>>>()

    /**
     * Reviews of [packageName], newest first by default. Empty when the feed
     * is unreachable or the app has no reviews.
     */
    suspend fun reviews(
        packageName: String,
        languageTag: String = "en",
        sort: Sort = Sort.NEWEST,
        forceRefresh: Boolean = false,
    ): List<AppReview> = withContext(dispatcherProvider.io) {
        val key = "$packageName:${sort.code}"
        if (!forceRefresh) {
            cache[key]?.let { (time, reviews) ->
                if (System.currentTimeMillis() - time < CACHE_TTL) return@withContext reviews
            }
        }
        val fetched = fetchReviews(packageName, languageTag, sort) ?: return@withContext emptyList()
        cache[key] = System.currentTimeMillis() to fetched
        if (cache.size > CACHE_MAX) {
            val oldest = cache.entries.sortedBy { it.value.first }.take(CACHE_MAX / 4).map { it.key }
            oldest.forEach { cache.remove(it) }
        }
        fetched
    }

    // ------------------------------------------------------------------
    // RPC
    // ------------------------------------------------------------------

    private fun fetchReviews(packageName: String, languageTag: String, sort: Sort): List<AppReview>? {
        val hl = languageTag.substringBefore('-').lowercase(Locale.ROOT).let {
            if (it.length == 2) it else "en"
        }
        // The inner argument is a JSON string itself, exactly like the page sends it.
        // NOTE: the app reference is a FLAT array ["pkg", 7] — the nested
        // [["pkg", 7]] shape is silently rejected by the RPC (null payload).
        val inner = JSONArray()
            .put(JSONObject.NULL)
            .put(JSONObject.NULL)
            .put(
                JSONArray()
                    .put(2)
                    .put(sort.code)
                    .put(JSONArray().put(COUNT).put(null).put(null))
                    .put(null)
                    .put(JSONArray()),
            )
            .put(JSONArray().put(packageName).put(7))
        val fetched = postRpc(packageName, inner, hl, sort) ?: return null
        // Language fallback: a localized feed can be empty while the English
        // one is not — try English before giving up (caches under the same key).
        if (fetched.isEmpty() && hl != "en") {
            return postRpc(packageName, inner, "en", sort)
        }
        return fetched
    }

    private fun postRpc(packageName: String, inner: JSONArray, hl: String, sort: Sort): List<AppReview>? {
        val freq = "f.req=" + java.net.URLEncoder.encode(
            JSONArray()
                .put(JSONArray().put(JSONArray().put("UsvDTd").put(inner.toString()).put(null).put("generic")))
                .toString(),
            "UTF-8",
        )
        val url = "$BASE/_/PlayStoreUi/data/batchexecute?rpcids=qnKhOb" +
            "&f.sid=-697906427155521722&bl=boq_playuiserver_20190903.08_p0" +
            "&hl=$hl&gl=us&authuser&soc-app=121&soc-platform=1&soc-device=1&_reqid=1065213"
        val body = freq.toRequestBody("application/x-www-form-urlencoded;charset=UTF-8".toMediaType())
        val responseText = try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                if (!r.isSuccessful || r.body == null) return null
                r.body!!.string()
            }
        } catch (e: IOException) {
            return null
        }
        return parseReviews(responseText)
    }

    /** Parses the `)]}'`-prefixed batchexecute envelope into review models. */
    internal fun parseReviews(response: String): List<AppReview> {
        val cleaned = response.removePrefix(")]}'").trim()
        if (cleaned.isEmpty()) return emptyList()
        val envelope = runCatching { JSONArray(cleaned) }.getOrNull() ?: return emptyList()
        // envelope[0] = ["wrb.fr", "UsvDTd", "<json-string>", null, ...]
        val first = envelope.opt(0) as? JSONArray ?: return emptyList()
        val payload = first.opt(2) as? String ?: return emptyList()
        val data = runCatching { JSONArray(payload) }.getOrNull() ?: return emptyList()
        val reviewsArray = data.opt(0) as? JSONArray ?: return emptyList()
        val reviews = mutableListOf<AppReview>()
        for (i in 0 until reviewsArray.length()) {
            parseReview(reviewsArray.opt(i))?.let { reviews += it }
        }
        return reviews
    }

    private fun parseReview(node: Any?): AppReview? {
        if (node !is JSONArray || node.length() < 6) return null
        val id = node.opt(0) as? String ?: return null
        val authorBlock = node.opt(1) as? JSONArray
        val author = authorBlock?.opt(0) as? String ?: return null
        val avatar = (authorBlock?.opt(1) as? JSONArray)
            ?.let { imageNode -> (imageNode.opt(3) as? JSONArray)?.opt(2) as? String }
        val rating = (node.opt(2) as? Number)?.toInt() ?: return null
        val text = node.opt(4) as? String ?: ""
        val time = (node.opt(5) as? JSONArray)?.let(::findEpochMillis)
        // [6] = "helpful" votes count, [10] = the app version reviewed.
        val thumbsUp = (node.opt(6) as? Number)?.toInt()
        val reviewAppVersion = node.opt(10) as? String
        // [7] = developer reply: ["Developer name", "text", [epoch, nanos]]
        val (replyText, replyTime) = parseReply(node.opt(7))
        return AppReview(
            id = id,
            author = author,
            avatarUrl = avatar?.let { it.substringBefore('=') + "=s128-c" },
            rating = rating.coerceIn(1, 5),
            text = text.trim(),
            timestampMillis = time,
            thumbsUpCount = thumbsUp?.takeIf { it > 0 },
            reviewAppVersion = reviewAppVersion?.takeIf { it.isNotBlank() },
            replyText = replyText,
            replyTimestampMillis = replyTime,
        )
    }

    /** `null` or `["Dev name", "text", [epochSeconds, nanos]]` from the live feed. */
    private fun parseReply(node: Any?): Pair<String?, Long?> {
        if (node !is JSONArray || node.length() < 2) return null to null
        val text = node.opt(1) as? String ?: return null to null
        val time = findEpochMillis(node.opt(2))
        return text.trim() to time
    }

    private fun findEpochMillis(node: Any?): Long? {
        if (node is JSONArray) {
            for (i in 0 until node.length()) {
                val v = (node.opt(i) as? Number)?.toLong() ?: continue
                if (v in 1_000_000_000L until 10_000_000_000L) return v * 1000L
            }
        }
        return null
    }

    private companion object {
        const val BASE = "https://play.google.com"
        const val COUNT = 24
        const val CACHE_TTL = 10 * 60 * 1000L
        const val CACHE_MAX = 48
        const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
