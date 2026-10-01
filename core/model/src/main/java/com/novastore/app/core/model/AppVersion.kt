package com.novastore.app.core.model

/**
 * A remotely installable application, one row per version.
 */
data class AppVersion(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val source: String,
    val size: Long?,
    val downloadUrl: String,
    val sha256: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val addedAt: Long?,
    val artifactType: ArtifactType = ArtifactType.APK,
    /** Lowercase hex SHA-256 of the signing certificate declared by the repository. */
    val signer: String? = null,
    /** ABIs of bundled native code; empty means architecture independent. */
    val nativeCode: List<String> = emptyList(),
    /** Paid on Google Play (not deliverable without a purchase). */
    val isPaid: Boolean = false,
)

/** How the downloadable artifact is structured. */
enum class ArtifactType {
    APK,
    SPLIT_APK,
    APK_SET,
    MULTI_APK,
}
