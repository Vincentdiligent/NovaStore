package com.novastore.app.core.model

import java.net.URI
import java.net.URLDecoder

/** What a scanned QR code, a shared text or an opened link points to. */
sealed class StoreLink {
    /** One app, by package name. */
    data class App(val packageName: String) : StoreLink()

    /** A store search ("market://search?q=…", unrecognized text). */
    data class Search(val query: String) : StoreLink()
}

/**
 * Understands every common way apps are linked or shared:
 *  - Google Play: `https://play.google.com/store/apps/details?id=PKG`,
 *    `market://details?id=PKG`, `market://search?q=…`, `…/store/search?q=`;
 *  - F-Droid & mirrors: `f-droid.org/[lang/]packages/PKG`,
 *    `apt.izzysoft.de/fdroid/index/apk/PKG`, `fdroid.app/PKG`;
 *  - APKPure / APKCombo / APKMirror style URLs ending in the package;
 *  - a bare package name ("org.telegram.messenger");
 *  - any text containing one of the above (share sheets add titles).
 * Anything else becomes a search query.
 */
object StoreLinks {

    private val PACKAGE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+$")
    private val URL_IN_TEXT = Regex("""(https?://\S+|market://\S+)""")

    fun parse(raw: String?): StoreLink? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (PACKAGE.matches(text) && text.count { it == '.' } >= 1 && !text.contains("..")) {
            return StoreLink.App(text)
        }
        val url = URL_IN_TEXT.find(text)?.value?.trimEnd('.', ',', ')', ']', '"', '\'') ?: text
        parseUrl(url)?.let { return it }
        return StoreLink.Search(text.take(120))
    }

    /** First URL inside [raw] (share sheets add titles around it). */
    fun extractUrl(raw: String?): String? =
        URL_IN_TEXT.find(raw.orEmpty())?.value?.trimEnd('.', ',', ')', ']', '"', '\'')

    private fun parseUrl(url: String, depth: Int = 0): StoreLink? {
        val uri = runCatching { URI(url.replace(" ", "%20")) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val query = queryParams(uri.rawQuery)
        val host = uri.host?.lowercase().orEmpty()
        val segments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }

        if (scheme == "market") {
            query["id"]?.takeIf { PACKAGE.matches(it) }?.let { return StoreLink.App(it) }
            query["q"]?.let { q ->
                return q.removePrefix("pname:").takeIf { PACKAGE.matches(it) }?.let { StoreLink.App(it) }
                    ?: StoreLink.Search(q)
            }
            return null
        }
        if (scheme != "http" && scheme != "https") return null

        // Any store URL carrying ?id=<package> (Play, Galaxy, Amazon, …).
        query["id"]?.takeIf { PACKAGE.matches(it) }?.let { return StoreLink.App(it) }
        // Wrapped links (Firebase / "play.app.goo.gl/?link=…", trackers):
        // the real store URL rides in a parameter.
        if (depth < 3) {
            for (key in listOf("link", "url", "u", "target", "dest", "destination", "deep_link", "af_web_dp", "fallback")) {
                val inner = query[key]?.takeIf { it.startsWith("http") || it.startsWith("market:") } ?: continue
                val parsed = parseUrl(inner, depth + 1)
                if (parsed is StoreLink.App) return parsed
            }
        }
        if (host.endsWith("play.google.com")) {
            query["q"]?.let { return StoreLink.Search(it) }
            return null
        }
        // F-Droid-style "/packages/<pkg>" and IzzyOnDroid "/apk/<pkg>".
        val marker = segments.indexOfFirst { it == "packages" || it == "apk" || it == "app" }
        if (marker >= 0) {
            segments.getOrNull(marker + 1)?.takeIf { PACKAGE.matches(it) }?.let { return StoreLink.App(it) }
        }
        // Mirrors put the package as a path segment (apkpure.com/name/<pkg>).
        segments.lastOrNull { PACKAGE.matches(it) && it.count { c -> c == '.' } >= 2 }?.let {
            return StoreLink.App(it)
        }
        return null
    }

    private fun queryParams(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').mapNotNull { part ->
            val key = part.substringBefore('=', "").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrNull()
                ?: return@mapNotNull null
            key to value
        }.toMap()
}
