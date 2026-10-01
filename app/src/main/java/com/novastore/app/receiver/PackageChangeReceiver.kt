package com.novastore.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.UpdatesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Keeps the installed-apps cache and the update list in sync with the
 * system the moment a package is installed, updated or removed — so an app
 * disappears from "Installed"/"Updates" right after uninstalling it, and an
 * update installed elsewhere drops its stale row immediately.
 *
 * Registered at runtime (implicit package broadcasts are not delivered to
 * manifest receivers since Android 8).
 */
@Singleton
class PackageChangeReceiver @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
    private val updatesRepository: UpdatesRepository,
) {
    fun register(context: Context, scope: CoroutineScope) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                val pending = goAsync()
                scope.launch {
                    try {
                        when (intent.action) {
                            Intent.ACTION_PACKAGE_REMOVED, Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                                if (!replacing) {
                                    installedAppsRepository.refresh(pkg) // deletes the cached row
                                    updatesRepository.remove(pkg)
                                }
                            }
                            else -> {
                                val refreshed = installedAppsRepository.refresh(pkg)
                                // A row whose offered version is now installed is done.
                                val row = updatesRepository.currentCandidates()
                                    .firstOrNull { it.installed.packageName == pkg }
                                if (row != null && refreshed != null &&
                                    row.available.versionCode <= refreshed.versionCode
                                ) {
                                    updatesRepository.remove(pkg)
                                }
                            }
                        }
                    } catch (_: Throwable) {
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }
}
