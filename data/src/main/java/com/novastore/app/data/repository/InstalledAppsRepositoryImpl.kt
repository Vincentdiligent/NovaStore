package com.novastore.app.data.repository

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.novastore.app.core.common.AppResult
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.database.dao.InstalledAppDao
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.security.SignatureVerifier
import com.novastore.app.data.mapper.toEntity
import com.novastore.app.data.mapper.toModel
import com.novastore.app.domain.repository.InstalledAppsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * PackageManager-based scanner. Runs on the IO dispatcher and caches the
 * result in Room. The signing certificate digest is captured so updates
 * are only offered from APKs signed with the same key.
 */
@Singleton
class InstalledAppsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedAppDao: InstalledAppDao,
    private val signatureVerifier: SignatureVerifier,
    private val dispatcherProvider: DispatcherProvider,
) : InstalledAppsRepository {

    override suspend fun scan(): AppResult<List<InstalledApp>> = withContext(dispatcherProvider.io) {
        try {
            val packageManager = context.packageManager
            @Suppress("DEPRECATION")
            val packages: List<PackageInfo> = packageManager.getInstalledPackages(SignatureVerifier.SIGNING_FLAGS)
            val scanned = packages.mapNotNull { info -> toInstalledApp(info, packageManager) }
            val now = System.currentTimeMillis()
            installedAppDao.replaceAll(scanned.map { it.toEntity(icon = null, scannedAt = now) })
            AppResult.success(scanned.sortedBy { it.appName.lowercase() })
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppResult.failure(NovaError.Unknown(cause = t))
        }
    }

    override fun observe(): Flow<List<InstalledApp>> =
        installedAppDao.observeAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun get(packageName: String): InstalledApp? =
        installedAppDao.get(packageName)?.toModel()

    override suspend fun refresh(packageName: String): InstalledApp? = withContext(dispatcherProvider.io) {
        val packageManager = context.packageManager
        val info = try {
            packageManager.getPackageInfo(packageName, SignatureVerifier.SIGNING_FLAGS)
        } catch (_: PackageManager.NameNotFoundException) {
            installedAppDao.delete(packageName)
            return@withContext null
        }
        val app = toInstalledApp(info, packageManager) ?: return@withContext null
        installedAppDao.upsertAll(listOf(app.toEntity(icon = null, scannedAt = System.currentTimeMillis())))
        app
    }

    override suspend fun uninstall(packageName: String): AppResult<Unit> = withContext(dispatcherProvider.io) {
        // ACTION_DELETE is the non-deprecated entry point of the system
        // uninstaller (needs REQUEST_DELETE_PACKAGES on Android 9+); some
        // vendor ROMs only register ACTION_UNINSTALL_PACKAGE, so it stays as
        // a fallback. Last resort: the App info page with its Uninstall button.
        val uri = Uri.parse("package:$packageName")
        val primary = launchIntent(
            Intent(Intent.ACTION_DELETE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            missing = "No uninstaller is available on this device.",
        )
        if (primary is AppResult.Success) return@withContext primary
        @Suppress("DEPRECATION")
        val legacy = launchIntent(
            Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            missing = "No uninstaller is available on this device.",
        )
        if (legacy is AppResult.Success) return@withContext legacy
        launchIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            missing = "No uninstaller is available on this device.",
        )
    }

    override suspend fun openApp(packageName: String): AppResult<Unit> = withContext(dispatcherProvider.io) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext AppResult.failure(
                NovaError.Metadata(
                    userMessage = "This app cannot be launched.",
                    packageName = packageName,
                ),
            )
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent(launchIntent, missing = "The app could not be launched.")
    }

    override suspend fun openAppSettings(packageName: String): AppResult<Unit> = withContext(dispatcherProvider.io) {
        launchIntent(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            missing = "The app settings page could not be opened.",
        )
    }

    private fun launchIntent(intent: Intent, missing: String): AppResult<Unit> = try {
        context.startActivity(intent)
        AppResult.success(Unit)
    } catch (e: ActivityNotFoundException) {
        AppResult.failure(NovaError.Unknown(userMessage = missing, cause = e))
    } catch (e: SecurityException) {
        AppResult.failure(NovaError.Unknown(userMessage = e.message ?: missing, cause = e))
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        AppResult.failure(NovaError.Unknown(cause = t))
    }

    private fun toInstalledApp(info: PackageInfo, packageManager: PackageManager): InstalledApp? {
        return try {
            val applicationInfo = info.applicationInfo ?: return null
            val label = packageManager.getApplicationLabel(applicationInfo).toString()
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            val installerSource = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    packageManager.getInstallSourceInfo(info.packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getInstallerPackageName(info.packageName)
                }
            } catch (_: Exception) {
                null
            }
            InstalledApp(
                packageName = info.packageName,
                appName = label,
                versionName = info.versionName,
                versionCode = versionCode,
                firstInstallTime = info.firstInstallTime,
                lastUpdateTime = info.lastUpdateTime,
                installerSource = installerSource,
                signingCertDigest = signatureVerifier.digestOf(info),
                isSystemApp = (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        } catch (_: Exception) {
            null
        }
    }
}
