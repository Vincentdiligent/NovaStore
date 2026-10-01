package com.novastore.app.core.installer

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unpacks APKPure-style XAPK containers.
 *
 * An XAPK is a ZIP holding a `manifest.json` plus the actual APK set of the
 * app (base + config splits for ABI/density/locale). Until v7 such bundles
 * were rejected outright — this extractor turns them into a regular
 * multi-APK `PackageInstallationPlan` that installs in one
 * PackageInstaller session, exactly like a Play split delivery.
 *
 * The split selection mirrors what Play itself does for a device profile:
 * keep the base, the config splits matching this device's ABIs, the closest
 * density, and the useful locales; unknown split ids are kept (safe), and a
 * filter that would drop every ABI split falls back to "install them all".
 */
@Singleton
class XapkExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The unpacked, device-matched contents of one XAPK container. */
    data class Contents(
        val packageName: String?,
        val versionCode: Long?,
        val versionName: String?,
        val baseApk: File,
        val splitApks: List<File>,
        val extractedDir: File,
        /** OBB expansion files found in the container (not installed yet). */
        val obbFiles: List<File>,
    )

    /**
     * True when [file] is an XAPK container. Detection is by CONTENT, not
     * just the file name: an XAPK is a ZIP archive carrying a `manifest.json`
     * entry. Mirrors and CDNs routinely rename artifacts (`download?id=…`,
     * `.zip`, no extension at all), so a name-only check misroutes real
     * bundles into the plain-APK path and the install then fails far away
     * from the actual cause.
     */
    fun isXapk(file: File): Boolean {
        if (file.name.endsWith(".xapk", ignoreCase = true)) return true
        if (!file.exists() || file.length() < 4L) return false
        // Cheap pre-filter: every ZIP container starts with the PK magic —
        // a file that fails this can never be an XAPK.
        val isZip = runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(2)
                raf.readFully(magic)
                magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
        if (!isZip) return false
        // Content probe: the XAPK manifest entry. APKs carry AndroidManifest.xml
        // (binary, not manifest.json) and OBB blobs carry no ZIP entries at all,
        // so this is unambiguous.
        return runCatching {
            ZipFile(file).use { zip -> zip.getEntry("manifest.json") != null }
        }.getOrDefault(false)
    }

    /**
     * Unpacks [container] into a sibling directory `<name>.xapk.d` and
     * returns its device-matched contents. The directory is wiped first,
     * so repeated calls are safe.
     */
    suspend fun extract(container: File): Contents = withContext(kotlinx.coroutines.Dispatchers.IO) {
        require(container.exists() && container.length() > 0) { "XAPK container is missing" }
        val dir = File(container.parentFile, container.name + ".d")
        if (dir.exists()) dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create extraction dir ${dir.path}" }

        ZipFile(container).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val target = File(dir, entry.name)
                check(target.canonicalPath.startsWith(dir.canonicalPath)) {
                    "XAPK entry escapes dir: ${entry.name}"
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }

        val manifest = File(dir, "manifest.json")
            .takeIf { it.exists() }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

        val splits: List<Pair<String, File>> = manifest
            ?.optJSONArray("split_apks")
            ?.let { array -> pairsFrom(array, dir) }
            ?: emptyList()

        val base = splits.firstOrNull { it.first == "base" }?.second
            ?: File(dir, "Android/base.apk").takeIf { it.exists() }
            ?: File(dir, "base.apk").takeIf { it.exists() }
            ?: error("XAPK has no base APK")

        val configSplits = splits.filter { it.first != "base" }
        val obb = dir.walkTopDown()
            .filter { it.isFile && it.extension.equals("obb", ignoreCase = true) }
            .toList()

        Contents(
            packageName = manifest?.optString("package_name")?.takeIf { it.isNotBlank() },
            versionCode = manifest?.optLong("version_code", -1L)?.takeIf { it > 0 },
            versionName = manifest?.optString("version_name")?.takeIf { it.isNotBlank() },
            baseApk = base,
            splitApks = selectDeviceSplits(configSplits),
            extractedDir = dir,
            obbFiles = obb,
        )
    }

    /** (id, file) pairs of the manifest's split_apks array. */
    private fun pairsFrom(array: JSONArray, dir: File): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val id = entry.optString("id").trim()
            if (id.isBlank()) continue
            val path = entry.optString("file").trim()
            val file = when {
                path.isNotBlank() -> File(dir, path)
                else -> File(dir, "Android/$id.apk")
            }
            if (file.exists()) result += id to file
        }
        return result
    }

    /**
     * Device-matching selection for `config.*` splits. Mirrors Play's own
     * bundle resolution: a foreign ABI never ships, densities degrade
     * gracefully, and locale splits keep the device language + English.
     * Unknown split ids are kept — installing an extra signed config APK
     * from the same set is safe, dropping a needed one is not.
     */
    internal fun selectDeviceSplits(splits: List<Pair<String, File>>): List<File> {
        if (splits.isEmpty()) return emptyList()

        val deviceAbis = Build.SUPPORTED_ABIS.toSet()
        val deviceLanguage = Locale.getDefault().language
        val deviceDensity = runCatching {
            context.resources.displayMetrics.densityDpi
        }.getOrDefault(480)

        var sawAbiSplit = false
        var keptAbiSplit = false
        val kept = mutableListOf<File>()
        for ((id, file) in splits) {
            val normalized = id.removePrefix("config.").lowercase()
            val isAbiSplit = KNOWN_ABI_IDS.any { normalized.contains(it) }
            val isDensitySplit = KNOWN_DENSITIES.any { normalized.contains(it) }
            val isLocaleSplit = normalized.length <= 6 && normalized.all { it.isLetter() } &&
                !isAbiSplit && !isDensitySplit && normalized.isNotBlank()

            val keep = when {
                isAbiSplit -> {
                    sawAbiSplit = true
                    val matches = deviceAbis.any { abi -> normalized.contains(abi.replace('-', '_')) }
                    if (matches) keptAbiSplit = true
                    matches
                }
                isDensitySplit -> {
                    val bucket = DENSITY_BUCKETS.entries
                        .filter { normalized.contains(it.key) }
                        .minByOrNull { kotlin.math.abs(it.value - deviceDensity) }
                    bucket == null || bucket.value <= deviceDensity + DENSITY_TOLERANCE
                }
                isLocaleSplit -> normalized.startsWith(deviceLanguage) || normalized.startsWith("en")
                else -> true // unknown naming — safest to install
            }
            if (keep) kept += file
        }

        // The manifest offered ABI splits but none matched this device —
        // its metadata is unreliable here; install the full set rather than
        // a base without its native code.
        if (sawAbiSplit && !keptAbiSplit) {
            return splits.map { it.second }
        }
        return kept
    }

    private companion object {
        /** ABI identifiers as they appear in config split ids. */
        val KNOWN_ABI_IDS = listOf(
            "arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips", "riscv64",
        )
        val KNOWN_DENSITIES = listOf(
            "ldpi", "mdpi", "hdpi", "tvdpi", "xhdpi", "xxhdpi", "xxxhdpi", "nodpi",
        )
        val DENSITY_BUCKETS = mapOf(
            "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
            "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
        )
        const val DENSITY_TOLERANCE = 160
    }
}
