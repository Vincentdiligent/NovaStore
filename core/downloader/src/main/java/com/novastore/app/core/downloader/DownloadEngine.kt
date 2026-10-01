package com.novastore.app.core.downloader

import android.content.Context
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.database.dao.DownloadDao
import com.novastore.app.core.database.entity.DownloadEntity
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.DownloadState
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.network.monitor.NetworkStatusMonitor
import com.novastore.app.core.downloader.api.DownloadRequest
import com.novastore.app.core.downloader.api.DownloadRequester
import com.novastore.app.core.downloader.api.DownloadSplit
import com.novastore.app.core.downloader.api.DownloadTaskInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Production download engine:
 *  - persistent queue (Room), restored after process restart;
 *  - configurable concurrency;
 *  - pause / resume / cancel / retry with exponential backoff;
 *  - HTTP Range resume when the server supports it;
 *  - progress, speed and ETA via live state;
 *  - streaming to disk — large APKs are never held in RAM;
 *  - Google Play split-APK sets: the base file is downloaded (and resumed)
 *    like any artifact, then each split is fetched sequentially into the
 *    same completed directory as a sibling of the base file.
 */
@Singleton
class DownloadEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val downloadDao: DownloadDao,
    private val settingsDataStore: SettingsDataStore,
    private val networkStatusMonitor: NetworkStatusMonitor,
    private val dispatcherProvider: DispatcherProvider,
) : DownloadRequester {

    private enum class Control { RUNNING, PAUSE, CANCEL }

    data class LiveProgress(
        val taskId: Long,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val speedBytesPerSec: Long?,
        val etaMillis: Long?,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
    private val liveProgress = MutableSharedFlow<Map<Long, LiveProgress>>(replay = 1)
    private val control = ConcurrentHashMap<Long, Control>()
    private val activeJobs = ConcurrentHashMap<Long, Job>()
    private var queueLoop: Job? = null

    /**
     * Split files of an in-flight download, keyed by "packageName:versionCode".
     * Google Play delivery URLs are single-use, so every enqueue refreshes
     * the list — re-enqueueing a resumed task replaces stale URLs.
     */
    private val splitsByTask = ConcurrentHashMap<String, List<DownloadSplit>>()

    init {
        liveProgress.tryEmit(emptyMap())
    }

    // ------------------------------------------------------------------
    // DownloadRequester API
    // ------------------------------------------------------------------

    override suspend fun enqueue(request: DownloadRequest): Long = withContext(dispatcherProvider.io) {
        splitsByTask[taskKey(request.packageName, request.versionCode)] = request.splits

        val existing = downloadDao.getForPackage(request.packageName, request.versionCode)
        if (existing != null && existing.state !in TERMINAL_STATES) {
            when (existing.state) {
                STATE_PAUSED -> downloadDao.updateState(existing.taskId, STATE_QUEUED, now())
                STATE_FAILED -> downloadDao.updateProgress(existing.taskId, STATE_QUEUED, existing.downloadedBytes, now(), null, 0)
            }
            kickQueue()
            return@withContext existing.taskId
        }

        val downloadDir = File(context.cacheDir, DOWNLOAD_DIR).apply { mkdirs() }
        val localPath = File(downloadDir, "${request.packageName}_${request.versionCode}.apk").absolutePath

        val id = downloadDao.upsert(
            DownloadEntity(
                packageName = request.packageName,
                appName = request.appName,
                versionCode = request.versionCode,
                versionName = request.versionName,
                url = request.url,
                fileName = request.fileName,
                localPath = localPath,
                sha256 = request.sha256,
                size = request.size,
                downloadedBytes = 0,
                state = STATE_QUEUED,
                attempts = 0,
                lastError = null,
                source = request.source,
                createdAt = now(),
                updatedAt = now(),
            ),
        )
        kickQueue()
        id
    }

    override suspend fun pause(packageName: String) {
        findTask(packageName)?.let { control[it.taskId] = Control.PAUSE }
    }

    override suspend fun resume(packageName: String) {
        findTask(packageName)?.let {
            downloadDao.updateState(it.taskId, STATE_QUEUED, now())
            control.remove(it.taskId)
            kickQueue()
        }
    }

    override suspend fun cancel(packageName: String) {
        findTask(packageName)?.let {
            control[it.taskId] = Control.CANCEL
            downloadDao.updateState(it.taskId, STATE_CANCELLED, now())
            cleanupPartFile(it)
            cleanupSplitFiles(it)
        }
    }

    override suspend fun retry(packageName: String) {
        findTask(packageName)?.let {
            downloadDao.updateProgress(it.taskId, STATE_QUEUED, 0, now(), null, 0)
            cleanupPartFile(it)
            cleanupSplitFiles(it)
            control.remove(it.taskId)
            kickQueue()
        }
    }

    override fun observeQueue(): Flow<List<DownloadTaskInfo>> =
        combine(downloadDao.observeAll(), liveProgress) { entities, live ->
            entities.map { entity ->
                val progress = live[entity.taskId]
                DownloadTaskInfo(
                    taskId = entity.taskId,
                    packageName = entity.packageName,
                    appName = entity.appName,
                    versionCode = entity.versionCode,
                    versionName = entity.versionName,
                    fileName = entity.fileName,
                    state = entity.state.toDownloadState(),
                    downloadedBytes = progress?.downloadedBytes ?: entity.downloadedBytes,
                    totalBytes = progress?.totalBytes ?: entity.size,
                    speedBytesPerSec = progress?.speedBytesPerSec,
                    etaMillis = progress?.etaMillis,
                    attempts = entity.attempts,
                    lastError = entity.lastError,
                )
            }
        }.distinctUntilChanged()

    override fun observe(packageName: String): Flow<DownloadTaskInfo?> =
        observeQueue().map { list -> list.firstOrNull { it.packageName == packageName } }

    override suspend fun getCompletedFile(packageName: String, versionCode: Long): File? =
        withContext(dispatcherProvider.io) {
            val entity = downloadDao.getCompleted(packageName, versionCode) ?: return@withContext null
            val file = File(entity.localPath)
            if (file.exists() && file.length() > 0) file else null
        }

    override suspend fun getCompletedFiles(packageName: String, versionCode: Long): List<File> =
        withContext(dispatcherProvider.io) {
            val entity = downloadDao.getCompleted(packageName, versionCode) ?: return@withContext emptyList()
            val base = File(entity.localPath)
            if (!base.exists() || base.length() == 0L) return@withContext emptyList()
            // Splits are stored as siblings named "<pkg>_<vc>_<split name>".
            val prefix = "${packageName}_${versionCode}_"
            val splits = base.parentFile
                ?.listFiles { file -> file.isFile && file.name.startsWith(prefix) && !file.name.endsWith(PART_SUFFIX) }
                ?.sortedBy { it.name }
                .orEmpty()
            listOf(base) + splits
        }

    override suspend fun awaitCompletion(packageName: String, versionCode: Long): AppResult<File> {
        val terminal = observeQueue()
            .map { list -> list.firstOrNull { it.packageName == packageName && it.versionCode == versionCode } }
            .firstOrNull { task -> task != null && task.state in TERMINAL_DOWNLOAD_STATES }
            ?: return AppResult.failure(NovaError.Unknown(userMessage = "The download task was not found."))

        return when (terminal.state) {
            DownloadState.COMPLETED -> {
                val entity = downloadDao.getForPackage(packageName, versionCode)
                val file = entity?.let { File(it.localPath) }
                if (file != null && file.exists() && file.length() > 0) {
                    AppResult.success(file)
                } else {
                    AppResult.failure(NovaError.DownloadFailed())
                }
            }
            DownloadState.FAILED -> AppResult.failure(
                NovaError.DownloadFailed(userMessage = terminal.lastError ?: "The download failed."),
            )
            DownloadState.CANCELLED -> AppResult.failure(NovaError.DownloadCancelled)
            else -> AppResult.failure(NovaError.Unknown())
        }
    }

    /** True when the queue has work to do (used by the background worker). */
    suspend fun hasPendingWork(): Boolean {
        val active = activeJobs.isNotEmpty()
        val queued = downloadDao.activeTasks().any { it.state == STATE_QUEUED }
        return active || queued
    }

    override suspend fun clearCompleted(): Int = withContext(dispatcherProvider.io) {
        val rows = downloadDao.completedTasks()
        rows.forEach { removeFiles(it) }
        rows.forEach { downloadDao.deleteById(it.taskId) }
        rows.size
    }

    override suspend fun clearFinished(): Int = withContext(dispatcherProvider.io) {
        val rows = downloadDao.finishedTasks()
        rows.forEach { downloadDao.deleteById(it.taskId) }
        rows.size
    }

    override suspend fun purgeCompletedOlderThan(maxAgeMillis: Long): Int =
        withContext(dispatcherProvider.io) {
            if (maxAgeMillis <= 0) return@withContext 0
            val cutoff = now() - maxAgeMillis
            val rows = downloadDao.completedOlderThan(cutoff)
            rows.forEach { removeFiles(it) }
            rows.forEach { downloadDao.deleteById(it.taskId) }
            rows.size
        }

    /** Deletes the artifact files (base + splits + parts) of one download. */
    private fun removeFiles(entity: DownloadEntity) {
        runCatching {
            File(entity.localPath).delete()
            cleanupPartFile(entity)
            cleanupSplitFiles(entity)
        }
    }

    /** Wakes the processing loop; also called by the WorkManager worker. */
    fun kickQueue() {
        synchronized(this) {
            if (queueLoop?.isActive == true) return
            queueLoop = scope.launch { processQueueLoop() }
        }
    }

    // ------------------------------------------------------------------
    // Queue processing
    // ------------------------------------------------------------------

    private suspend fun processQueueLoop() {
        while (scope.isActive) {
            val settings = settingsDataStore.snapshot()

            if (!networkStatusMonitor.isCurrentlyOnline()) {
                delay(NETWORK_POLL_MS)
                continue
            }
            val unmetered = networkStatusMonitor.isCurrentlyUnmetered()
            // "Wi-Fi only" governs background auto-updates (WorkManager constraints);
            // downloads the user starts are only held back by "Mobile data allowed".
            val meteredBlocked = !settings.mobileDataAllowed && !unmetered
            if (meteredBlocked) {
                delay(NETWORK_POLL_MS)
                continue
            }

            val queued = downloadDao.activeTasks().filter { it.state == STATE_QUEUED }
            val active = activeJobs.size
            if (queued.isEmpty() && active == 0) break // idle; a kick restarts the loop

            val capacity = max(0, settings.maxConcurrentDownloads - active)
            queued.take(capacity).forEach { entity ->
                startTask(entity)
            }
            delay(QUEUE_POLL_MS)
        }
    }

    private fun startTask(entity: DownloadEntity) {
        if (activeJobs.containsKey(entity.taskId)) return
        val job = scope.launch { executeDownload(entity.taskId) }
        activeJobs[entity.taskId] = job
        job.invokeOnCompletion { activeJobs.remove(entity.taskId) }
    }

    private suspend fun executeDownload(taskId: Long) {
        val entity = getEntity(taskId) ?: return
        downloadDao.updateState(taskId, STATE_DOWNLOADING, now())
        control[taskId] = Control.RUNNING

        val splits = splitsByTask[taskKey(entity.packageName, entity.versionCode)].orEmpty()
        val splitsTotal = splits.sumOf { it.size ?: 0L }

        val finalFile = File(entity.localPath)
        val partFile = File(entity.localPath + PART_SUFFIX)

        // A previous attempt may have finalized the base APK and then failed
        // while fetching splits — resume from the completed base instead of
        // re-downloading it.
        val baseAlreadyFinal = finalFile.exists() && finalFile.length() > 0 && !partFile.exists()

        try {
            var bytesSoFar: Long
            var total: Long?

            if (baseAlreadyFinal) {
                bytesSoFar = finalFile.length()
                total = entity.size?.takeIf { it > 0 } ?: bytesSoFar
                downloadDao.updateProgress(taskId, STATE_DOWNLOADING, bytesSoFar, now(), null, entity.attempts)
                publishProgress(taskId, bytesSoFar, withSplits(total, splitsTotal), null, null)
            } else {
                bytesSoFar = if (partFile.exists()) partFile.length() else 0L

                val requestBuilder = Request.Builder().url(entity.url)
                if (bytesSoFar > 0) {
                    requestBuilder.header("Range", "bytes=$bytesSoFar-")
                }

                okHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                    val resumed = response.code == 206 && bytesSoFar > 0
                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}")
                    }
                    if (bytesSoFar > 0 && response.code != 206) {
                        // Server does not support Range: restart from scratch.
                        partFile.delete()
                        bytesSoFar = 0
                    }
                    val body = response.body ?: throw IOException("Empty response body")

                    // HTTP 200 ≠ APK: mirrors behind anti-bot protection answer
                    // with a Cloudflare interstitial / HTML wrapper served with
                    // a SUCCESS code. Saving that as "app.apk" only moves the
                    // failure into the installer; refuse document content
                    // types here, before a single byte reaches the disk.
                    val contentType = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase()
                    if (contentType != null && contentType in TEXT_CONTENT_TYPES) {
                        throw IOException("Server returned a web page ($contentType) instead of the app package")
                    }

                    val contentLength = body.contentLength()
                    val expectedSize = entity.size
                    total = when {
                        contentLength > 0 && resumed -> contentLength + bytesSoFar
                        contentLength > 0 -> contentLength
                        expectedSize != null && expectedSize > 0 -> expectedSize
                        else -> null
                    }

                    // Storage pre-check (base + splits).
                    val needed = (total ?: 0) - bytesSoFar + splitsTotal
                    if (needed > 0 && !hasFreeSpace(needed + STORAGE_HEADROOM)) {
                        markFailed(taskId, NovaError.InsufficientStorage.userMessage)
                        return
                    }

                    val append = resumed && bytesSoFar > 0
                    partFile.parentFile?.mkdirs()
                    FileOutputStream(partFile, append).use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var lastDbUpdate = 0L
                        var speedWindowStart = now()
                        var speedWindowBytes = 0L
                        var speedBps: Long? = null

                        while (true) {
                            when (control[taskId]) {
                                Control.PAUSE -> {
                                    output.flush()
                                    downloadDao.updateProgress(taskId, STATE_PAUSED, bytesSoFar, now(), null, entity.attempts)
                                    publishProgress(taskId, bytesSoFar, total, null, null)
                                    control.remove(taskId)
                                    return
                                }
                                Control.CANCEL -> {
                                    output.flush()
                                    partFile.delete()
                                    downloadDao.updateProgress(taskId, STATE_CANCELLED, 0, now(), null, entity.attempts)
                                    control.remove(taskId)
                                    return
                                }
                                else -> Unit
                            }

                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesSoFar += read
                            speedWindowBytes += read

                            val currentTime = now()
                            val elapsed = currentTime - speedWindowStart
                            if (elapsed >= SPEED_WINDOW_MS) {
                                speedBps = speedWindowBytes * 1000 / elapsed
                                speedWindowStart = currentTime
                                speedWindowBytes = 0
                            }
                            if (currentTime - lastDbUpdate >= DB_UPDATE_MS) {
                                lastDbUpdate = currentTime
                                downloadDao.updateProgress(taskId, STATE_DOWNLOADING, bytesSoFar, currentTime, null, entity.attempts)
                                val totalSnapshot = total
                                val speedSnapshot = speedBps
                                val eta = if (totalSnapshot != null && totalSnapshot > bytesSoFar && speedSnapshot != null && speedSnapshot > 0) {
                                    (totalSnapshot - bytesSoFar) * 1000 / speedSnapshot
                                } else null
                                publishProgress(taskId, bytesSoFar, withSplits(total, splitsTotal), speedBps, eta)
                            }
                        }
                        output.flush()
                    }

                    if (bytesSoFar == 0L) {
                        throw IOException("Downloaded file is empty")
                    }

                    // Magic bytes: APK, XAPK and APKM are all ZIP containers
                    // and must start with the "PK" local-file-header magic.
                    // A renamed HTML page or a truncated redirect body fails
                    // here instead of failing later in the installer.
                    if (!looksLikeZipContainer(partFile)) {
                        partFile.delete()
                        throw IOException("Downloaded file is not a valid package (no ZIP header)")
                    }

                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) {
                        throw IOException("Could not finalize the downloaded file")
                    }
                }
            }

            // Split APKs (if any) are fetched sequentially after the base file.
            val splitBytes = downloadSplits(taskId, entity, splits, baseBytes = bytesSoFar, baseTotal = total)

            val done = bytesSoFar + splitBytes
            downloadDao.updateProgress(taskId, STATE_COMPLETED, done, now(), null, entity.attempts)
            publishProgress(taskId, done, withSplits(total, splitsTotal), null, null)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val attempts = (getEntity(taskId)?.attempts ?: 0) + 1
            if (attempts >= MAX_ATTEMPTS) {
                markFailed(taskId, t.message ?: NovaError.DownloadFailed().userMessage)
            } else {
                // Transient failure: re-queue with exponential backoff.
                downloadDao.updateProgress(taskId, STATE_QUEUED, 0, now(), t.message, attempts)
                delay(backoffMillis(attempts))
                kickQueue()
            }
        } finally {
            control.remove(taskId)
            liveProgress.tryEmit(liveProgress.replayCache.firstOrNull()?.minus(taskId) ?: emptyMap())
        }
    }

    /**
     * Downloads each split sequentially into the base file's directory as
     * "<pkg>_<vc>_<split name>". Already present splits are skipped, so a
     * retry after a partial split download only fetches what is missing.
     * Returns the number of split bytes on disk after the call.
     */
    private suspend fun downloadSplits(
        taskId: Long,
        entity: DownloadEntity,
        splits: List<DownloadSplit>,
        baseBytes: Long,
        baseTotal: Long?,
    ): Long {
        if (splits.isEmpty()) return 0L
        val splitsTotal = splits.sumOf { it.size ?: 0L }
        val total = withSplits(baseTotal, splitsTotal)
        var cumulative = baseBytes
        val parent = File(entity.localPath).parentFile

        for (split in splits) {
            if (control[taskId] == Control.CANCEL) {
                throw IOException("Download cancelled")
            }
            val target = File(parent, splitFileName(entity.packageName, entity.versionCode, split.name))
            if (target.exists() && target.length() > 0) {
                cumulative += target.length()
                continue
            }
            val part = File(target.absolutePath + PART_SUFFIX)
            part.parentFile?.mkdirs()
            part.delete()

            val request = Request.Builder().url(split.url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Split ${split.name}: HTTP ${response.code}")
                val body = response.body ?: throw IOException("Split ${split.name}: empty body")
                FileOutputStream(part).use { output ->
                    body.byteStream().copyTo(output, BUFFER_SIZE)
                    output.flush()
                }
                if (part.length() == 0L) throw IOException("Split ${split.name} is empty")
                if (!looksLikeZipContainer(part)) {
                    part.delete()
                    throw IOException("Split ${split.name} is not a valid APK (no ZIP header)")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IOException("Could not finalize split ${split.name}")
            }
            cumulative += target.length()
            downloadDao.updateProgress(taskId, STATE_DOWNLOADING, cumulative, now(), null, entity.attempts)
            publishProgress(taskId, cumulative, total, null, null)
        }
        return cumulative - baseBytes
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun taskKey(packageName: String, versionCode: Long): String = "$packageName:$versionCode"

    private fun splitFileName(packageName: String, versionCode: Long, splitName: String): String =
        "${packageName}_${versionCode}_${splitName}"

    /** Combines the base size with the split sizes for progress reporting. */
    private fun withSplits(baseTotal: Long?, splitsTotal: Long): Long? =
        if (baseTotal != null && splitsTotal > 0) baseTotal + splitsTotal else baseTotal

    private fun splitsFor(entity: DownloadEntity): List<DownloadSplit> =
        splitsByTask[taskKey(entity.packageName, entity.versionCode)].orEmpty()

    private suspend fun findTask(packageName: String): DownloadEntity? {
        val active = downloadDao.activeTasks().firstOrNull { it.packageName == packageName }
        if (active != null) return active
        return downloadDao.observeAll().first().firstOrNull { it.packageName == packageName }
    }

    private suspend fun getEntity(taskId: Long): DownloadEntity? =
        downloadDao.observeAll().first().firstOrNull { it.taskId == taskId }

    private suspend fun markFailed(taskId: Long, message: String) {
        downloadDao.updateProgress(taskId, STATE_FAILED, 0, now(), message, MAX_ATTEMPTS)
    }

    private fun cleanupPartFile(entity: DownloadEntity) {
        File(entity.localPath + PART_SUFFIX).delete()
    }

    /** Deletes split files and their .part siblings of one download. */
    private fun cleanupSplitFiles(entity: DownloadEntity) {
        val base = File(entity.localPath)
        val prefix = "${entity.packageName}_${entity.versionCode}_"
        base.parentFile
            ?.listFiles { file -> file.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    private fun hasFreeSpace(bytes: Long): Boolean {
        val stats = android.os.StatFs(context.cacheDir.absolutePath)
        return stats.availableBytes >= bytes
    }

    /**
     * Content sniff: APK / XAPK / APKM artifacts are ZIP containers. The
     * signature is the two-byte "PK" prefix; the local-file-header variant
     * (0x0304), the empty-archive (0x0506) and the spanned (0x0708) variants
     * are all accepted, so an "empty" zip or a split container is not a
     * false negative.
     */
    private fun looksLikeZipContainer(file: File): Boolean {
        if (file.length() < 4L) return false
        return runCatching {
            java.io.RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(2)
                raf.readFully(header)
                header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        }.getOrDefault(false)
    }

    private fun publishProgress(
        taskId: Long,
        downloaded: Long,
        total: Long?,
        speedBps: Long?,
        eta: Long?,
    ) {
        val current = liveProgress.replayCache.firstOrNull() ?: emptyMap()
        liveProgress.tryEmit(current + (taskId to LiveProgress(taskId, downloaded, total, speedBps, eta)))
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun backoffMillis(attempt: Int): Long =
        min(MAX_BACKOFF_MS, (BASE_BACKOFF_MS.toDouble() * Math.pow(2.0, (attempt - 1).toDouble())).toLong()) +
            (0..JITTER_MS).random()

    private fun String.toDownloadState(): DownloadState =
        runCatching { DownloadState.valueOf(this) }.getOrDefault(DownloadState.QUEUED)

    companion object {
        const val DOWNLOAD_DIR = "downloads"
        private const val PART_SUFFIX = ".part"
        private const val BUFFER_SIZE = 64 * 1024
        private const val QUEUE_POLL_MS = 500L
        private const val NETWORK_POLL_MS = 3_000L
        private const val DB_UPDATE_MS = 400L
        private const val SPEED_WINDOW_MS = 1_000L
        private const val MAX_ATTEMPTS = 5
        private const val BASE_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val JITTER_MS = 500
        private const val STORAGE_HEADROOM = 32L * 1024 * 1024
        private const val STATE_QUEUED = "QUEUED"
        private const val STATE_DOWNLOADING = "DOWNLOADING"
        private const val STATE_PAUSED = "PAUSED"
        private const val STATE_COMPLETED = "COMPLETED"
        private const val STATE_FAILED = "FAILED"
        private const val STATE_CANCELLED = "CANCELLED"

        /** Content types that can never be an app package artifact. */
        private val TEXT_CONTENT_TYPES = setOf(
            "text/html",
            "text/plain",
            "application/json",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
        )
        private val TERMINAL_STATES = setOf(STATE_COMPLETED, STATE_CANCELLED)
        private val TERMINAL_DOWNLOAD_STATES = setOf(
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        )
    }
}
