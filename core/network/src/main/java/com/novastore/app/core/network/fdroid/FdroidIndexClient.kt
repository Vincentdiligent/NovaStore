package com.novastore.app.core.network.fdroid

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.NovaError
import java.io.File
import java.io.IOException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

enum class IndexFormat { V2, V1 }

/** A repository index downloaded to disk, plus where it was actually found. */
/** HTTP cache validators of a downloaded index, sent back to get a cheap 304. */
data class IndexValidators(val lastModified: String?, val etag: String?)

data class FetchedIndex(
    val format: IndexFormat,
    /** The repo URL the index was served from; artifact URLs resolve against it. */
    val baseUrl: String,
    val validators: IndexValidators = IndexValidators(null, null),
) {
    val indexUrl: String get() = baseUrl + "/" + fileNameOf(format)

    companion object {
        fun fileNameOf(format: IndexFormat): String = when (format) {
            IndexFormat.V2 -> "index-v2.json"
            IndexFormat.V1 -> "index-v1.json"
        }

        /** Rebuilds a [FetchedIndex] from a previously resolved index URL. */
        fun fromIndexUrl(indexUrl: String): FetchedIndex? {
            val format = IndexFormat.entries.firstOrNull { indexUrl.endsWith("/" + fileNameOf(it)) } ?: return null
            return FetchedIndex(format, indexUrl.removeSuffix("/" + fileNameOf(format)))
        }
    }
}

/**
 * Downloads and parses F-Droid-compatible repository indexes. The index is
 * streamed to a file and then parsed token by token — the official F-Droid
 * index is ~60 MB and would not fit in memory as a String.
 */
class FdroidIndexClient(
    private val okHttpClient: OkHttpClient,
    private val dispatcherProvider: DispatcherProvider,
) {

    /**
     * Streams the repository index into [targetFile]. Tries index-v2.json and
     * falls back to index-v1.json, and also tries the conventional `/repo`
     * sub-path when the given URL points at the repository's web root.
     *
     * When [knownIndexUrl] and [validators] from a previous download are given,
     * a conditional request is made first; the result is then `null` if the
     * index has not changed.
     */
    suspend fun fetchIndex(
        repoUrl: String,
        targetFile: File,
        knownIndexUrl: String? = null,
        validators: IndexValidators? = null,
    ): AppResult<FetchedIndex?> =
        withContext(dispatcherProvider.io) {
            val known = knownIndexUrl?.let(FetchedIndex::fromIndexUrl)
            if (known != null) {
                when (val result = download(known.indexUrl, targetFile, validators)) {
                    is DownloadOutcome.Ok -> return@withContext AppResult.success(known.copy(validators = result.validators))
                    DownloadOutcome.NotModified -> return@withContext AppResult.success(null)
                    DownloadOutcome.NotFound -> Unit
                    is DownloadOutcome.Failed -> return@withContext AppResult.failure(result.error)
                }
            }
            val base = normalizeRepoUrl(repoUrl)
            for (candidate in candidateBaseUrls(base)) {
                for (format in IndexFormat.entries) {
                    val url = candidate + "/" + FetchedIndex.fileNameOf(format)
                    if (url == known?.indexUrl) continue
                    when (val result = download(url, targetFile, null)) {
                        is DownloadOutcome.Ok ->
                            return@withContext AppResult.success(FetchedIndex(format, candidate, result.validators))
                        DownloadOutcome.NotFound, DownloadOutcome.NotModified -> Unit
                        is DownloadOutcome.Failed -> return@withContext AppResult.failure(result.error)
                    }
                }
            }
            AppResult.failure(
                NovaError.Repository(
                    userMessage = "No F-Droid repository index found at $base (HTTP 404). " +
                        "Use the repository address, usually ending in /repo.",
                ),
            )
        }

    /** Parses an index previously downloaded by [fetchIndex]. */
    suspend fun parseIndex(
        file: File,
        fetched: FetchedIndex,
        preferredLocales: List<String>,
    ): AppResult<ParsedIndex> = withContext(dispatcherProvider.io) {
        try {
            val parser = RepoIndexParser(fetched.baseUrl, preferredLocales)
            val parsed = file.inputStream().use { input ->
                when (fetched.format) {
                    IndexFormat.V2 -> parser.parseV2(input)
                    IndexFormat.V1 -> parser.parseV1(input)
                }
            }
            AppResult.success(parsed)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppResult.failure(
                NovaError.Metadata(
                    userMessage = "The repository index could not be parsed (${t::class.simpleName}: ${t.message}).",
                    packageName = null,
                ),
            )
        }
    }

    private sealed interface DownloadOutcome {
        data class Ok(val validators: IndexValidators) : DownloadOutcome
        data object NotModified : DownloadOutcome
        data object NotFound : DownloadOutcome
        data class Failed(val error: NovaError) : DownloadOutcome
    }

    private fun download(url: String, targetFile: File, validators: IndexValidators?): DownloadOutcome {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
        validators?.etag?.let { builder.header("If-None-Match", it) }
        validators?.lastModified?.let { builder.header("If-Modified-Since", it) }
        val request = builder.build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.code == 304) return DownloadOutcome.NotModified
                if (response.code == 404 || response.code == 410) return DownloadOutcome.NotFound
                if (!response.isSuccessful) {
                    return DownloadOutcome.Failed(
                        NovaError.Repository(userMessage = "$url returned HTTP ${response.code}."),
                    )
                }
                val body = response.body ?: return DownloadOutcome.Failed(
                    NovaError.Repository(userMessage = "$url returned an empty response."),
                )
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
                }
                // Some web servers answer missing files with an HTML page and HTTP 200.
                if (!looksLikeJson(targetFile)) {
                    DownloadOutcome.NotFound
                } else {
                    DownloadOutcome.Ok(IndexValidators(response.header("Last-Modified"), response.header("ETag")))
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            DownloadOutcome.Failed(
                NovaError.Network(
                    userMessage = "Could not reach ${request.url.host} (${describeNetworkFailure(t)}).",
                    cause = t,
                ),
            )
        }
    }

    private fun looksLikeJson(file: File): Boolean {
        if (file.length() == 0L) return false
        file.inputStream().use { input ->
            while (true) {
                val b = input.read()
                if (b == -1) return false
                val c = b.toChar()
                if (c.isWhitespace() || b == 0xEF || b == 0xBB || b == 0xBF) continue
                return c == '{'
            }
        }
    }

    /** Turns a raw network exception into a short, actionable diagnostic. */
    private fun describeNetworkFailure(t: Throwable): String = when (t) {
        is java.net.UnknownHostException -> "DNS lookup failed — check the connection or Private DNS"
        is java.net.SocketTimeoutException -> "connection timed out"
        is javax.net.ssl.SSLHandshakeException -> "TLS handshake failed: ${t.message}"
        is java.net.ConnectException -> "connection refused"
        is IOException -> t::class.simpleName + (t.message?.let { ": $it" } ?: "")
        else -> t::class.simpleName + (t.message?.let { ": $it" } ?: "")
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * Accepts what users actually paste: fdroidrepos:// links, URLs with
         * ?fingerprint=…, trailing slashes or a direct link to the index file.
         */
        fun normalizeRepoUrl(input: String): String {
            var url = input.trim()
            url = when {
                url.startsWith("fdroidrepos://", ignoreCase = true) -> "https://" + url.substring("fdroidrepos://".length)
                url.startsWith("fdroidrepo://", ignoreCase = true) -> "http://" + url.substring("fdroidrepo://".length)
                !url.contains("://") -> "https://$url"
                else -> url
            }
            url = url.substringBefore('#').substringBefore('?').trimEnd('/')
            for (suffix in INDEX_FILE_SUFFIXES) {
                if (url.endsWith(suffix, ignoreCase = true)) {
                    url = url.dropLast(suffix.length).trimEnd('/')
                }
            }
            return url
        }

        private val INDEX_FILE_SUFFIXES = listOf(
            "/index-v2.json", "/index-v1.json", "/index-v1.jar", "/entry.json", "/entry.jar", "/index.xml", "/index.jar",
        )

        private fun candidateBaseUrls(base: String): List<String> =
            if (base.endsWith("/repo", ignoreCase = true)) {
                listOf(base)
            } else {
                listOf(base, "$base/repo", "$base/fdroid/repo")
            }
    }
}
