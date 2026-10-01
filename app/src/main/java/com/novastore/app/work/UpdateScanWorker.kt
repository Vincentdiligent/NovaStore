package com.novastore.app.work

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.novastore.app.MainActivity
import com.novastore.app.NovaStoreApp
import com.novastore.app.R
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.UpdateSchedule
import com.novastore.app.domain.repository.UpdatesRepository
import com.novastore.app.domain.usecase.CheckForUpdatesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Periodic update discovery. Constraints honor the user's settings
 * (Wi-Fi only, charging, schedule). When the scan discovers apps that were
 * not known before, a notification lists them — like every other store.
 */
@HiltWorker
class UpdateScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParameters: WorkerParameters,
    private val checkForUpdates: CheckForUpdatesUseCase,
    private val updateNotifier: UpdateNotifier,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        val report = checkForUpdates()
        return when (report) {
            is com.novastore.app.core.common.AppResult.Failure -> {
                // Retry transient failures with WorkManager backoff.
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
            is com.novastore.app.core.common.AppResult.Success -> {
                // Publish right away — the process may idle soon after.
                updateNotifier.refresh()
                Result.success()
            }
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "nova_update_scan"
        const val UNIQUE_ONE_TIME_NAME = "nova_update_scan_now"
        private const val MAX_ATTEMPTS = 3
    }
}

/**
 * Schedules background work from settings. The user's constraints always
 * translate into WorkManager constraints (prompt #21).
 */
@Singleton
class WorkScheduler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
) {
    /** (Re-)schedules the periodic scan according to current settings. */
    suspend fun schedulePeriodicScan() {
        val settings = settingsDataStore.snapshot()
        val workManager = WorkManager.getInstance(context)

        // The background CHECK always runs on the chosen schedule — it is what
        // produces the "updates available" notification. "Automatic updates"
        // only decides whether updates are also INSTALLED without asking.

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (settings.wifiOnly && !settings.mobileDataAllowed) {
                    NetworkType.UNMETERED
                } else {
                    NetworkType.CONNECTED
                },
            )
            .setRequiresCharging(settings.chargingOnly)
            .build()

        val repeatInterval = when (settings.schedule) {
            UpdateSchedule.IMMEDIATELY -> 1L to TimeUnit.HOURS
            UpdateSchedule.DAILY -> 1L to TimeUnit.DAYS
            UpdateSchedule.WEEKLY -> 7L to TimeUnit.DAYS
        }

        val request = PeriodicWorkRequestBuilder<UpdateScanWorker>(repeatInterval.first, repeatInterval.second)
            .setConstraints(constraints)
            .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            UpdateScanWorker.UNIQUE_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Immediate one-time scan (user pulled "Check for updates"). */
    fun scanNow() {
        val request = OneTimeWorkRequestBuilder<UpdateScanWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UpdateScanWorker.UNIQUE_ONE_TIME_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
