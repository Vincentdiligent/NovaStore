package com.novastore.app.core.model

/**
 * Version comparison rules (prompt #16):
 *  - versionCode is the single source of truth when both sides have it;
 *  - versionName is display-only, used as a best-effort fallback when a
 *    versionCode is missing;
 *  - downgrades are rejected by default;
 *  - missing metadata is never silently treated as "newer".
 *
 * Lives in core:model so both core:updater (the F-Droid scan) and domain
 * (the Play/mirror passes) share ONE comparison semantic — core:updater
 * depends on domain, so the shared code has to sit below both.
 */
object VersionComparator {

    /** True when [available] is strictly newer than [installed]. */
    fun isNewer(installed: InstalledApp, available: AppVersion, allowDowngrade: Boolean = false): ComparisonResult {
        // versionCode is authoritative when present on both sides.
        if (available.versionCode > 0 && installed.versionCode > 0) {
            return when {
                available.versionCode > installed.versionCode -> ComparisonResult.NEWER
                available.versionCode < installed.versionCode ->
                    if (allowDowngrade) ComparisonResult.DOWNGRADE_ALLOWED else ComparisonResult.DOWNGRADE_BLOCKED
                else -> ComparisonResult.SAME
            }
        }

        // Fallback: semantic-ish comparison of versionName (display data).
        val installedName = installed.versionName
        val availableName = available.versionName
        if (installedName != null && availableName != null) {
            val cmp = compareVersionNames(installedName, availableName)
            return when {
                cmp < 0 -> ComparisonResult.NEWER
                cmp > 0 ->
                    if (allowDowngrade) ComparisonResult.DOWNGRADE_ALLOWED else ComparisonResult.DOWNGRADE_BLOCKED
                else -> ComparisonResult.SAME
            }
        }

        // Missing metadata: never report an update blindly.
        return ComparisonResult.METADATA_MISSING
    }

    /**
     * Compares dotted version names: numeric segments numerically, other
     * segments lexicographically; longer equal-prefix wins ("1.2" < "1.2.1").
     */
    fun compareVersionNames(a: String, b: String): Int {
        val partsA = split(a)
        val partsB = split(b)
        val length = maxOf(partsA.size, partsB.size)
        for (i in 0 until length) {
            val segmentA = partsA.getOrNull(i)
            val segmentB = partsB.getOrNull(i)
            val cmp = compareSegments(segmentA, segmentB)
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun split(version: String): List<String> =
        version.split('.', '-', '_', '+').filter { it.isNotBlank() }

    private fun compareSegments(a: String?, b: String?): Int {
        if (a == null && b == null) return 0
        if (a == null) return -1 // shorter prefix is older
        if (b == null) return 1
        val numberA = a.toIntOrNull()
        val numberB = b.toIntOrNull()
        if (numberA != null && numberB != null) {
            return numberA.compareTo(numberB)
        }
        return a.lowercase().compareTo(b.lowercase())
    }

    enum class ComparisonResult {
        NEWER,
        SAME,
        DOWNGRADE_BLOCKED,
        DOWNGRADE_ALLOWED,
        METADATA_MISSING,
    }
}
