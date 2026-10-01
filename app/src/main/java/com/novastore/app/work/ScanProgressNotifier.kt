package com.novastore.app.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novastore.app.MainActivity
import com.novastore.app.NovaStoreApp
import com.novastore.app.R
import com.novastore.app.domain.repository.ScanProgress
import com.novastore.app.domain.repository.ScanProgressTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Mirrors [ScanProgressTracker] into an ongoing, silent notification:
 * "Loading repositories 3/12 · IzzyOnDroid", "Checking Google Play 40/120"…
 * It shows for every scan — background worker or pull-to-refresh — and
 * disappears the moment the pipeline is idle again.
 */
@Singleton
class ScanProgressNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracker: ScanProgressTracker,
) {
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            tracker.progress
                .sample(PROGRESS_SAMPLE_MS)
                .distinctUntilChanged()
                .collect { progress -> if (progress == null) cancel() else show(progress) }
        }
        // sample() may swallow the final null — make sure the notification
        // never outlives the scan.
        scope.launch {
            tracker.progress.collect { if (it == null) cancel() }
        }
    }

    private fun show(progress: ScanProgress) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val title = context.getString(
            when (progress.stage) {
                ScanProgress.Stage.REPOSITORIES -> R.string.scan_progress_repositories
                ScanProgress.Stage.INSTALLED_APPS -> R.string.scan_progress_installed
                ScanProgress.Stage.GOOGLE_PLAY -> R.string.scan_progress_play
                ScanProgress.Stage.MIRRORS -> R.string.scan_progress_mirrors
            },
        )
        val text = buildString {
            if (progress.total > 0) append("${progress.done}/${progress.total}")
            if (!progress.label.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(progress.label)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, NovaStoreApp.CHANNEL_SCAN)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(progress.total, progress.done.coerceAtMost(progress.total), progress.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun cancel() {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private companion object {
        const val NOTIFICATION_ID = 4301
        const val REQUEST_CODE = 4301
        const val PROGRESS_SAMPLE_MS = 400L
    }
}
