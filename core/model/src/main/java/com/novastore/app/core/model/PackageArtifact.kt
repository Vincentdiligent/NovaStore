package com.novastore.app.core.model

/**
 * One downloadable file of a package version. A version may consist of
 * several artifacts (split APKs / .apks sets).
 */
data class PackageArtifact(
    val fileName: String,
    val url: String,
    val sha256: String?,
    val size: Long?,
    val artifactType: ArtifactType = ArtifactType.APK,
    /** Absolute path of the verified local file, set after verification. */
    val localPath: String? = null,
)

/**
 * The full, validated set of files that must be installed together
 * for one package update.
 */
data class PackageInstallationPlan(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val source: String,
    val artifacts: List<PackageArtifact>,
    /**
     * Directory holding files that only exist for this installation
     * (e.g. an unpacked XAPK container). The install pipeline removes it
     * after the terminal result; null for plain downloads.
     */
    val cleanupDir: String? = null,
)
