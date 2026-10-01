package com.novastore.app.core.model

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED,
}
