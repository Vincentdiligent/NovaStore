package com.novastore.app.core.model

/**
 * The single "is this a real update?" rule shared by the update scan and
 * the UI. A real update is:
 *  - for Play / repository builds: a strictly higher versionCode AND not the
 *    same version name (a different code for the same name is another device
 *    variant of the same release — the "11.0.3 → 11.0.3" false update);
 *  - for community mirrors (APKPure/APKCombo): a strictly newer version NAME
 *    (their codes are list positions or disagree with Play's).
 * Missing metadata is never treated as "newer".
 */
object UpdateRules {

    fun isRealUpdate(installed: InstalledApp, available: AppVersion): Boolean {
        val availableName = normalizeVersionName(available.versionName)
        val installedName = normalizeVersionName(installed.versionName)
        val isMirror = available.source == SOURCE_APKCOMBO || available.source == SOURCE_APKPURE
        if (isMirror) {
            // Unparsed page → synthetic "v<internalCode>" name: not comparable.
            if (available.versionName == "v${available.versionCode}") return false
            if (availableName == null || installedName == null) return false
            return VersionComparator.compareVersionNames(installedName, availableName) < 0
        }
        if (available.versionCode <= installed.versionCode) return false
        if (availableName != null && installedName != null &&
            VersionComparator.compareVersionNames(installedName, availableName) == 0
        ) {
            return false
        }
        return true
    }

    /** `"v4.0.2 "` and `"4.0.2"` are the same release. */
    fun normalizeVersionName(name: String?): String? =
        name?.trim()?.removePrefix("v")?.removePrefix("V")?.lowercase()?.takeIf { it.isNotEmpty() }
}
