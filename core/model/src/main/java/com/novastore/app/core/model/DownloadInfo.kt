package com.novastore.app.core.model

/**
 * Everything needed to fetch one artifact of a version.
 */
data class DownloadInfo(
    val url: String,
    val fileName: String,
    val size: Long?,
    val sha256: String?,
    val artifactType: ArtifactType = ArtifactType.APK,
)
