package com.novastore.app.core.model

/**
 * A discovered update for an installed application.
 *
 * [UpdateConfidence] separates two fundamentally different findings:
 *  - EXACT — a source declared a real versionCode (and usually a signature),
 *    so the update is confirmed and can be auto-installed;
 *  - DISCOVERY — only a freshness signal exists (e.g. the Play listing's
 *    "Updated on" date changed). The user is invited to check, never to
 *    blindly install.
 */
data class UpdateCandidate(
    val installed: InstalledApp,
    val available: AppVersion,
    val source: String,
    val verified: Boolean = false,
    val confidence: UpdateConfidence = UpdateConfidence.EXACT,
    val compatibility: CompatibilityVerdict = CompatibilityVerdict.compatible(),
)

enum class UpdateConfidence {
    EXACT,
    DISCOVERY,

    /**
     * A newer release exists on Google Play, but the app is PAID there — the
     * installed copy came from elsewhere (often a modified build). Nova can
     * not deliver it; the row offers "Open in Play" / "Hide" instead of a
     * button that would only fail.
     */
    PAID,

    /**
     * The installed app is signed with a DIFFERENT key than the update (a
     * modified/re-signed build). Android refuses to update it in place; the
     * row offers replacing it with the original or hiding the update.
     */
    FOREIGN_SIGNATURE,
}

data class CompatibilityVerdict(
    val compatible: Boolean,
    val reason: IncompatibilityReason? = null,
) {
    companion object {
        fun compatible() = CompatibilityVerdict(true, null)
        fun incompatible(reason: IncompatibilityReason) = CompatibilityVerdict(false, reason)
    }
}

enum class IncompatibilityReason {
    ANDROID_VERSION_TOO_LOW,
    ABI_MISMATCH,
    INSUFFICIENT_STORAGE,
    PACKAGE_CONFLICT,
    SPLIT_REQUIREMENTS_NOT_MET,
}
