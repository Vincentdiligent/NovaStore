package com.novastore.app.domain.repository

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.DeviceProfile
import com.novastore.app.core.model.InstalledApp
import kotlinx.coroutines.flow.Flow

/**
 * Scans locally installed applications via PackageManager.
 * Scanning never runs on the main thread and results are cached.
 */
interface InstalledAppsRepository {
    suspend fun scan(): AppResult<List<InstalledApp>>
    fun observe(): Flow<List<InstalledApp>>
    suspend fun get(packageName: String): InstalledApp?

    /** Re-reads one package from PackageManager (e.g. right after an install). */
    suspend fun refresh(packageName: String): InstalledApp?

    /**
     * Opens the system uninstall confirmation dialog for the package.
     * No permission is required; the user confirms in the system dialog.
     */
    suspend fun uninstall(packageName: String): AppResult<Unit>

    /** Launches the app's main activity, if it has one. */
    suspend fun openApp(packageName: String): AppResult<Unit>

    /** Opens the system "App info" settings page for the package. */
    suspend fun openAppSettings(packageName: String): AppResult<Unit>
}

/** Bundled Google Play device identities (used for spoofing at login). */
interface DeviceProfileRepository {
    suspend fun listDeviceProfiles(): List<DeviceProfile>
}
