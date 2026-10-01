package com.novastore.app.core.downloader.api

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.DownloadState
import java.io.File
import kotlinx.coroutines.flow.Flow

/** An additional file of a split-APK set (Google Play deliveries). */
data class DownloadSplit(
    val name: String,
    val url: String,
    val size: Long?,
)

/** A request to download one package artifact set. */
data class DownloadRequest(
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String?,
    val url: String,
    val fileName: String,
    val sha256: String?,
    val size: Long?,
    val source: String,
    /** Split APKs downloaded after the base file (same completed directory). */
    val splits: List<DownloadSplit> = emptyList(),
)

/** Live view of one download task. */
data class DownloadTaskInfo(
    val taskId: Long,
    val packageName: String,
    val appName: String,
    val versionCode: Long,
    val versionName: String?,
    val fileName: String,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSec: Long?,
    val etaMillis: Long?,
    val attempts: Int,
    val lastError: String?,
) {
    val progressPercent: Int
        get() = if (totalBytes != null && totalBytes > 0) {
            ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else 0
}

/**
 * Entry point into the download engine used by use cases and the UI.
 * Implemented by the core downloader module.
 */
interface DownloadRequester {
    /** Enqueues a download and returns its task id. */
    suspend fun enqueue(request: DownloadRequest): Long

    suspend fun pause(packageName: String)
    suspend fun resume(packageName: String)
    suspend fun cancel(packageName: String)
    suspend fun retry(packageName: String)

    fun observeQueue(): Flow<List<DownloadTaskInfo>>
    fun observe(packageName: String): Flow<DownloadTaskInfo?>

    /** Returns the completed, verified file for the given package, if present. */
    suspend fun getCompletedFile(packageName: String, versionCode: Long): File?

    /**
     * The completed artifact set for the given package: base file first,
     * then any split APKs staged next to it (ordered by file name).
     * Empty when the base file is not (yet) completed.
     */
    suspend fun getCompletedFiles(packageName: String, versionCode: Long): List<File>

    /**
     * Suspends until the download for the given package reaches a terminal
     * state (COMPLETED, FAILED, CANCELLED). Returns the file on completion.
     */
    suspend fun awaitCompletion(packageName: String, versionCode: Long): AppResult<File>

    /** Removes every COMPLETED download (rows + files). Returns how many. */
    suspend fun clearCompleted(): Int

    /** Removes FAILED and CANCELLED entries. Returns how many. */
    suspend fun clearFinished(): Int

    /**
     * Auto-cleanup: removes COMPLETED downloads whose files were finished
     * more than [maxAgeMillis] ago. Returns how many.
     */
    suspend fun purgeCompletedOlderThan(maxAgeMillis: Long): Int
}
