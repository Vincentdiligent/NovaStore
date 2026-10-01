package com.novastore.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.downloader.api.DownloadRequester
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.work.WorkScheduler
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class NovaStoreApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var accountRepository: AccountRepository

    @Inject
    lateinit var downloadRequester: DownloadRequester

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var scanProgressNotifier: com.novastore.app.work.ScanProgressNotifier

    @Inject
    lateinit var packageChangeReceiver: com.novastore.app.receiver.PackageChangeReceiver

    @Inject
    lateinit var updateNotifier: com.novastore.app.work.UpdateNotifier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Process-wide Coil ImageLoader — every AsyncImage resolves it through
     * the Application's [ImageLoaderFactory] implementation. Tuning: memory
     * cache at 20% of max memory, a 256 MB disk cache under
     * cacheDir/nova_image_cache, Cache-Control headers ignored (icon CDNs
     * and mirrors send hostile no-cache headers that would starve the disk
     * cache) and a short 120 ms global crossfade. Previously no shared
     * loader config existed, so home-grid cells decoded without any cache
     * sizing — a major source of home-screen jank.
     */
    @OptIn(ExperimentalCoilApi::class)
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this@NovaStoreApp)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(cacheDir, "nova_image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .crossfade(IMAGE_CROSSFADE_MILLIS)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Visible progress for repository loading / update scans.
        scanProgressNotifier.start(appScope)
        // "Updates available" follows the update list from every scan source.
        updateNotifier.start(appScope)
        // Installed/Updates lists follow installs and uninstalls instantly.
        runCatching { packageChangeReceiver.register(this, appScope) }
        // Schedule the periodic update scan according to user settings.
        appScope.launch { workScheduler.schedulePeriodicScan() }
        // Restore the persisted Google Play session (if any) in the background.
        appScope.launch { accountRepository.start() }
        // Housekeeping: auto-clean completed downloads past their age.
        appScope.launch {
            runCatching {
                val days = settingsDataStore.downloadsAutoCleanDaysSnapshot()
                if (days > 0) {
                    downloadRequester.purgeCompletedOlderThan(TimeUnit.DAYS.toMillis(days.toLong()))
                }
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_UPDATES,
                    getString(R.string.channel_updates),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
                NotificationChannel(
                    CHANNEL_DOWNLOADS,
                    getString(R.string.channel_downloads),
                    NotificationManager.IMPORTANCE_LOW,
                ),
                NotificationChannel(
                    CHANNEL_INSTALLATION,
                    getString(R.string.channel_installation),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
                // Repository / update-scan progress: visible in the shade and
                // status bar (LOW channels are hidden in "silent" sections on
                // many ROMs), but the notification itself never makes a sound.
                NotificationChannel(
                    CHANNEL_SCAN,
                    getString(R.string.channel_scan),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                },
                NotificationChannel(
                    CHANNEL_ERRORS,
                    getString(R.string.channel_errors),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            ),
        )
    }

    companion object {
        private const val IMAGE_DISK_CACHE_BYTES = 256L * 1024 * 1024
        private const val IMAGE_CROSSFADE_MILLIS = 120
        const val CHANNEL_UPDATES = "updates"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_INSTALLATION = "installation"
        const val CHANNEL_ERRORS = "errors"
        const val CHANNEL_SCAN = "scan_progress"
    }
}
