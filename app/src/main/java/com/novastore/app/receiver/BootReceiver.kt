package com.novastore.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.novastore.app.core.database.dao.DownloadDao
import com.novastore.app.core.downloader.DownloadEngine
import com.novastore.app.core.downloader.DownloadWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * Restores the pending download queue after a device reboot (prompt #69).
 * Tasks that already completed successfully are never re-downloaded: the
 * engine picks up only QUEUED/DOWNLOADING/PAUSED tasks from persistence.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var downloadDao: DownloadDao

    @Inject
    lateinit var downloadEngine: DownloadEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val pendingTasks = downloadDao.activeTasks()
                if (pendingTasks.isNotEmpty()) {
                    // Re-queue interrupted downloads and restart processing.
                    downloadEngine.kickQueue()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        DownloadWorker.UNIQUE_WORK_NAME,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<DownloadWorker>()
                            .setConstraints(
                                Constraints.Builder()
                                    .setRequiredNetworkType(NetworkType.CONNECTED)
                                    .build(),
                            )
                            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
                            .build(),
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
