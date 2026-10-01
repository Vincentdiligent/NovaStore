package com.novastore.app.core.model

/**
 * One downloadable file of a Google Play purchase/delivery response.
 * A Play download usually consists of a BASE apk plus optional SPLIT apks.
 */
data class PlayDownloadFile(
    val name: String,
    val url: String,
    val sizeBytes: Long,
    val isSplit: Boolean,
)
