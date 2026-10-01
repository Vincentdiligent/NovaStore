package com.novastore.app.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.novastore.app.MainActivity
import com.novastore.app.NovaStoreApp
import com.novastore.app.R
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateConfidence
import com.novastore.app.domain.repository.UpdatesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The "Updates available" notification, store-style:
 *
 *  - follows the update list itself (any scan: background worker, app
 *    start, Updates screen), not one particular worker run;
 *  - ALERTS only when a version appears that was never announced before
 *    ("pkg@versionCode" remembered), otherwise silently refreshes the count
 *    and the list — no repeated buzzing for the same 26 updates;
 *  - lists the apps (name old → new), "+N more", with "Update all";
 *  - disappears when nothing deliverable is left.
 */
@Singleton
class UpdateNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updatesRepository: UpdatesRepository,
    private val settingsDataStore: SettingsDataStore,
) {
    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Scans write rows one by one — settle before rendering.
            updatesRepository.observeCandidates()
                .debounce(1_500)
                .collect { runCatching { publish(it) } }
        }
    }

    /** Explicit refresh (end of a background scan, before the process may idle). */
    suspend fun refresh() {
        runCatching { publish(updatesRepository.currentCandidates()) }
    }

    private suspend fun publish(all: List<UpdateCandidate>) = lock.withLock {
        val manager = NotificationManagerCompat.from(context)
        val settings = settingsDataStore.snapshot()
        val ignored = settingsDataStore.ignoredUpdatesSnapshot()
        val deliverable = all
            .filter { it.confidence == UpdateConfidence.EXACT }
            .filterNot { c ->
                val pkg = c.installed.packageName
                pkg in ignored || "$pkg@${c.available.versionCode}" in ignored
            }
            .sortedBy { it.installed.appName.lowercase() }

        if (deliverable.isEmpty()) {
            manager.cancel(NOTIFICATION_ID)
            return@withLock
        }
        if (!settings.notificationsEnabled || !manager.areNotificationsEnabled()) return@withLock

        val keys = deliverable.map { "${it.installed.packageName}@${it.available.versionCode}" }.toSet()
        val announced = settingsDataStore.notifiedUpdatesSnapshot()
        val hasNew = keys.any { it !in announced }
        // Remember only what is still pending, so the set never grows forever.
        settingsDataStore.setNotifiedUpdates(announced.intersect(keys) + keys)

        val count = deliverable.size
        val title = context.getString(R.string.notification_updates_title, count)
        val names = deliverable.take(3).joinToString(", ") { it.installed.appName }
        val text = if (count > 3) context.getString(R.string.notification_updates_more, names, count - 3) else names

        val inbox = NotificationCompat.InboxStyle().setBigContentTitle(title)
        deliverable.take(MAX_LINES).forEach { c ->
            inbox.addLine("${c.installed.appName}  ${c.installed.versionName ?: "?"} → ${c.available.versionName ?: "?"}")
        }
        if (count > MAX_LINES) {
            inbox.setSummaryText(context.getString(R.string.notification_updates_summary_more, count - MAX_LINES))
        }

        val notification = NotificationCompat.Builder(context, NovaStoreApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_stat_nova)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(inbox)
            .setNumber(count)
            .setContentIntent(activityIntent(REQUEST_OPEN, MainActivity.ACTION_OPEN_UPDATES))
            .addAction(0, context.getString(R.string.notification_update_all), activityIntent(REQUEST_UPDATE_ALL, MainActivity.ACTION_UPDATE_ALL))
            .setAutoCancel(true)
            // A new version buzzes once; a mere count change stays silent.
            .setOnlyAlertOnce(!hasNew)
            .setSilent(!hasNew)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun activityIntent(requestCode: Int, action: String): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private companion object {
        const val NOTIFICATION_ID = 4201
        const val REQUEST_OPEN = 4201
        const val REQUEST_UPDATE_ALL = 4202
        const val MAX_LINES = 6
    }
}
