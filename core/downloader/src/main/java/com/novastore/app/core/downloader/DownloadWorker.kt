package com.novastore.app.core.downloader

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.novastore.app.core.common.DispatcherProvider
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

/**
 * Keeps the download queue processing while the app is in the background
 * via a long-running (foreground) WorkManager worker.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val downloadEngine: DownloadEngine,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        downloadEngine.kickQueue()
        // Stay alive while the engine has work; constraints are enforced by
        // the engine itself based on user settings.
        var idleTicks = 0
        while (!isStopped) {
            if (downloadEngine.hasPendingWork()) {
                idleTicks = 0
            } else {
                idleTicks++
                if (idleTicks >= IDLE_TICKS_BEFORE_FINISH) break
            }
            delay(POLL_MS)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = createForegroundInfo()

    private fun createForegroundInfo(): ForegroundInfo {
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Nova Store")
            .setContentText("Downloading updates…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "nova_download_queue"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val POLL_MS = 2_000L
        private const val IDLE_TICKS_BEFORE_FINISH = 3
    }
}
