package com.novastore.app.core.model

/**
 * A locally installed application, produced by the PackageManager scanner.
 * Only fields that Android genuinely exposes are present.
 */
data class InstalledApp(
    val packageName: String,
    val appName: String,
    val versionName: String?,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val installerSource: String?,
    /** Hex encoded SHA-256 of the current signing certificate, null when not resolvable. */
    val signingCertDigest: String?,
    val isSystemApp: Boolean,
)
