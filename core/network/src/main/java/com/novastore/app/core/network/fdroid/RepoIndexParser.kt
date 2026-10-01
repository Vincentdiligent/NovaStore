package com.novastore.app.core.network.fdroid

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.InputStream
import java.io.InputStreamReader

/** One application parsed from a repository index. */
data class ParsedApp(
    val packageName: String,
    val name: String,
    val summary: String?,
    val description: String?,
    val developer: String?,
    val iconUrl: String?,
    val license: String?,
    val categories: List<String>,
    val website: String?,
    val sourceCode: String?,
    val changelog: String?,
    val added: Long?,
    val lastUpdated: Long?,
)

/** One downloadable version (APK) parsed from a repository index. */
data class ParsedVersion(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val downloadUrl: String,
    val sha256: String?,
    val size: Long?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val added: Long?,
    /** Lowercase hex SHA-256 of the signing certificate, when the index declares it. */
    val signer: String?,
    val nativeCode: List<String>,
)

data class ParsedIndex(
    val repoName: String?,
    val apps: List<ParsedApp>,
    val versions: List<ParsedVersion>,
)

/**
 * Streaming parser for F-Droid repository indexes (index-v2.json and the
 * older index-v1.json). The official F-Droid index is ~60 MB, so it must be
 * read token by token: materializing it as a String or an object tree
 * exhausts the heap on phones. Only the fields Nova Store uses are kept and
 * every localized text is reduced to a single best-matching locale while
 * reading.
 *
 * Artifact and icon URLs are resolved against [baseUrl] (the URL the index
 * was actually fetched from), which also makes mirrors work.
 */
class RepoIndexParser(
    private val baseUrl: String,
    preferredLocales: List<String>,
) {
    private val locales = preferredLocales.map { it.replace('_', '-') }

    fun parseV2(input: InputStream): ParsedIndex {
        val apps = ArrayList<ParsedApp>()
        val versions = ArrayList<ParsedVersion>()
        var repoName: String? = null
        JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "repo" -> repoName = readV2RepoName(reader)
                    "packages" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val packageName = reader.nextName()
                            readV2Package(reader, packageName, apps, versions)
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return ParsedIndex(repoName, apps, versions)
    }

    fun parseV1(input: InputStream): ParsedIndex {
        val apps = ArrayList<ParsedApp>()
        val versions = ArrayList<ParsedVersion>()
        var repoName: String? = null
        JsonReader(InputStreamReader(input.buffered(), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "repo" -> repoName = readV1RepoName(reader)
                    "apps" -> {
                        reader.beginArray()
                        while (reader.hasNext()) readV1App(reader)?.let { apps += it }
                        reader.endArray()
                    }
                    "packages" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val packageName = reader.nextName()
                            reader.beginArray()
                            while (reader.hasNext()) readV1Version(reader, packageName)?.let { versions += it }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return ParsedIndex(repoName, apps, versions)
    }

    // ------------------------------------------------------------------
    // index-v2
    // ------------------------------------------------------------------

    private fun readV2RepoName(reader: JsonReader): String? {
        var name: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = readLocalizedText(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return name
    }

    private fun readV2Package(
        reader: JsonReader,
        packageName: String,
        apps: MutableList<ParsedApp>,
        versions: MutableList<ParsedVersion>,
    ) {
        var app: ParsedApp? = null
        val packageVersions = ArrayList<ParsedVersion>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "metadata" -> app = readV2Metadata(reader, packageName)
                "versions" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        reader.nextName() // keyed by APK sha256, not by versionCode
                        readV2Version(reader, packageName)?.let { packageVersions += it }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (packageVersions.isEmpty()) return
        apps += app ?: ParsedApp(
            packageName = packageName, name = packageName, summary = null, description = null,
            developer = null, iconUrl = null, license = null, categories = emptyList(), website = null,
            sourceCode = null, changelog = null, added = null, lastUpdated = null,
        )
        versions += packageVersions
    }

    private fun readV2Metadata(reader: JsonReader, packageName: String): ParsedApp {
        var name: String? = null
        var summary: String? = null
        var description: String? = null
        var author: String? = null
        var icon: String? = null
        var license: String? = null
        var categories: List<String> = emptyList()
        var website: String? = null
        var sourceCode: String? = null
        var changelog: String? = null
        var added: Long? = null
        var lastUpdated: Long? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = readLocalizedText(reader)
                "summary" -> summary = readLocalizedText(reader)
                "description" -> description = readLocalizedText(reader)
                "authorName" -> author = readStringOrNull(reader)
                "icon" -> icon = readLocalizedFileName(reader)
                "license" -> license = readStringOrNull(reader)
                "categories" -> categories = readStringArray(reader)
                "webSite" -> website = readStringOrNull(reader)
                "sourceCode" -> sourceCode = readStringOrNull(reader)
                "changelog" -> changelog = readStringOrNull(reader)
                "added" -> added = readLongOrNull(reader)
                "lastUpdated" -> lastUpdated = readLongOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return ParsedApp(
            packageName = packageName,
            name = name?.trim()?.ifBlank { null } ?: packageName,
            summary = summary?.trim(),
            description = description?.trim(),
            developer = author,
            iconUrl = icon?.let(::resolve),
            license = license,
            categories = categories,
            website = website,
            sourceCode = sourceCode,
            changelog = changelog,
            added = added,
            lastUpdated = lastUpdated,
        )
    }

    private fun readV2Version(reader: JsonReader, packageName: String): ParsedVersion? {
        var fileName: String? = null
        var sha256: String? = null
        var size: Long? = null
        var added: Long? = null
        var versionCode: Long? = null
        var versionName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var signer: String? = null
        var nativeCode: List<String> = emptyList()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "added" -> added = readLongOrNull(reader)
                "file" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "name" -> fileName = readStringOrNull(reader)
                            "sha256" -> sha256 = readStringOrNull(reader)
                            "size" -> size = readLongOrNull(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                "manifest" -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "versionCode" -> versionCode = readLongOrNull(reader)
                            "versionName" -> versionName = readStringOrNull(reader)
                            "usesSdk" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "minSdkVersion" -> minSdk = readLongOrNull(reader)?.toInt()
                                        "targetSdkVersion" -> targetSdk = readLongOrNull(reader)?.toInt()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            "signer" -> {
                                reader.beginObject()
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "sha256" -> signer = readStringArray(reader).firstOrNull()
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                            }
                            "nativecode" -> nativeCode = readStringArray(reader)
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val code = versionCode ?: return null
        val file = fileName?.ifBlank { null } ?: return null
        return ParsedVersion(
            packageName = packageName,
            versionCode = code,
            versionName = versionName,
            downloadUrl = resolve(file),
            sha256 = sha256?.lowercase(),
            size = size,
            minSdk = minSdk,
            targetSdk = targetSdk,
            added = added,
            signer = signer?.lowercase(),
            nativeCode = nativeCode,
        )
    }

    // ------------------------------------------------------------------
    // index-v1
    // ------------------------------------------------------------------

    private fun readV1RepoName(reader: JsonReader): String? {
        var name: String? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = readStringOrNull(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return name
    }

    private fun readV1App(reader: JsonReader): ParsedApp? {
        var packageName: String? = null
        var name: String? = null
        var summary: String? = null
        var description: String? = null
        var author: String? = null
        var icon: String? = null
        var license: String? = null
        var categories: List<String> = emptyList()
        var website: String? = null
        var sourceCode: String? = null
        var changelog: String? = null
        var added: Long? = null
        var lastUpdated: Long? = null
        var localized: LocalizedV1? = null
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "packageName" -> packageName = readStringOrNull(reader)
                "name" -> name = readStringOrNull(reader)
                "summary" -> summary = readStringOrNull(reader)
                "description" -> description = readStringOrNull(reader)
                "authorName" -> author = readStringOrNull(reader)
                "icon" -> icon = readStringOrNull(reader)
                "license" -> license = readStringOrNull(reader)
                "categories" -> categories = readStringArray(reader)
                "webSite" -> website = readStringOrNull(reader)
                "sourceCode" -> sourceCode = readStringOrNull(reader)
                "changelog" -> changelog = readStringOrNull(reader)
                "added" -> added = readLongOrNull(reader)
                "lastUpdated" -> lastUpdated = readLongOrNull(reader)
                "localized" -> localized = readV1Localized(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val pkg = packageName ?: return null
        val iconUrl = when {
            localized?.icon != null && localized.iconLocale != null ->
                resolve("/$pkg/${localized.iconLocale}/${localized.icon}")
            icon != null -> resolve("/icons-640/$icon")
            else -> null
        }
        return ParsedApp(
            packageName = pkg,
            name = (localized?.name ?: name)?.trim()?.ifBlank { null } ?: pkg,
            summary = (localized?.summary ?: summary)?.trim(),
            description = (localized?.description ?: description)?.trim(),
            developer = author,
            iconUrl = iconUrl,
            license = license,
            categories = categories,
            website = website,
            sourceCode = sourceCode,
            changelog = changelog,
            added = added,
            lastUpdated = lastUpdated,
        )
    }

    private class LocalizedV1(
        val name: String?,
        val summary: String?,
        val description: String?,
        val icon: String?,
        val iconLocale: String?,
    )

    private fun readV1Localized(reader: JsonReader): LocalizedV1 {
        var best: Map<String, String>? = null
        var bestLocale: String? = null
        var bestRank = Int.MAX_VALUE
        reader.beginObject()
        while (reader.hasNext()) {
            val locale = reader.nextName()
            val fields = HashMap<String, String>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                if (key in V1_LOCALIZED_KEYS && reader.peek() == JsonToken.STRING) {
                    fields[key] = reader.nextString()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            val rank = rankOf(locale)
            if (best == null || rank < bestRank) {
                best = fields
                bestLocale = locale
                bestRank = rank
            }
        }
        reader.endObject()
        return LocalizedV1(
            name = best?.get("name"),
            summary = best?.get("summary"),
            description = best?.get("description"),
            icon = best?.get("icon"),
            iconLocale = bestLocale,
        )
    }

    private fun readV1Version(reader: JsonReader, packageName: String): ParsedVersion? {
        var versionCode: Long? = null
        var versionName: String? = null
        var apkName: String? = null
        var hash: String? = null
        var hashType: String? = null
        var size: Long? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var added: Long? = null
        var signer: String? = null
        var nativeCode: List<String> = emptyList()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "versionCode" -> versionCode = readLongOrNull(reader)
                "versionName" -> versionName = readStringOrNull(reader)
                "apkName" -> apkName = readStringOrNull(reader)
                "hash" -> hash = readStringOrNull(reader)
                "hashType" -> hashType = readStringOrNull(reader)
                "size" -> size = readLongOrNull(reader)
                "minSdkVersion" -> minSdk = readLongOrNull(reader)?.toInt()
                "targetSdkVersion" -> targetSdk = readLongOrNull(reader)?.toInt()
                "added" -> added = readLongOrNull(reader)
                "signer" -> signer = readStringOrNull(reader)
                "nativecode" -> nativeCode = readStringArray(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val code = versionCode ?: return null
        val file = apkName?.ifBlank { null } ?: return null
        return ParsedVersion(
            packageName = packageName,
            versionCode = code,
            versionName = versionName,
            downloadUrl = resolve("/$file"),
            sha256 = hash?.takeIf { hashType.equals("sha256", ignoreCase = true) || hashType == null }?.lowercase(),
            size = size,
            minSdk = minSdk,
            targetSdk = targetSdk,
            added = added,
            signer = signer?.lowercase(),
            nativeCode = nativeCode,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Reads a {locale: text} object and keeps only the best-matching locale. */
    private fun readLocalizedText(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.STRING) return reader.nextString()
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        reader.beginObject()
        while (reader.hasNext()) {
            val locale = reader.nextName()
            if (reader.peek() != JsonToken.STRING) {
                reader.skipValue()
                continue
            }
            val rank = rankOf(locale)
            if (best == null || rank < bestRank) {
                best = reader.nextString()
                bestRank = rank
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return best
    }

    /** Reads a {locale: {name, sha256, size}} object and returns the best file name. */
    private fun readLocalizedFileName(reader: JsonReader): String? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) {
            reader.skipValue()
            return null
        }
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        reader.beginObject()
        while (reader.hasNext()) {
            val locale = reader.nextName()
            var name: String? = null
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "name") name = readStringOrNull(reader) else reader.skipValue()
                }
                reader.endObject()
            } else {
                reader.skipValue()
            }
            val rank = rankOf(locale)
            if (name != null && (best == null || rank < bestRank)) {
                best = name
                bestRank = rank
            }
        }
        reader.endObject()
        return best
    }

    /** Lower is better: exact preferred locale, then same language, then English, then anything. */
    private fun rankOf(locale: String): Int {
        val normalized = locale.replace('_', '-')
        locales.forEachIndexed { index, preferred ->
            if (normalized.equals(preferred, ignoreCase = true)) return index * 2
            if (normalized.substringBefore('-').equals(preferred.substringBefore('-'), ignoreCase = true)) {
                return index * 2 + 1
            }
        }
        return when {
            normalized.equals("en-US", ignoreCase = true) -> 1_000
            normalized.startsWith("en", ignoreCase = true) -> 1_001
            else -> 2_000
        }
    }

    private fun readStringOrNull(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readLongOrNull(reader: JsonReader): Long? = when (reader.peek()) {
        JsonToken.NUMBER -> reader.nextString().toBigDecimalOrNull()?.toLong()
        JsonToken.STRING -> reader.nextString().trim().toLongOrNull()
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readStringArray(reader: JsonReader): List<String> {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return emptyList()
        }
        val out = ArrayList<String>()
        reader.beginArray()
        while (reader.hasNext()) {
            readStringOrNull(reader)?.let { out += it }
        }
        reader.endArray()
        return out
    }

    private fun resolve(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        path.startsWith("/") -> baseUrl.trimEnd('/') + path
        else -> baseUrl.trimEnd('/') + "/" + path
    }

    private companion object {
        val V1_LOCALIZED_KEYS = setOf("name", "summary", "description", "icon")
    }
}
