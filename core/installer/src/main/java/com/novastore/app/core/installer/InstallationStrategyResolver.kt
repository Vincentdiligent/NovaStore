package com.novastore.app.core.installer

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.RootAccessState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses the installation strategy for the user's configured mode.
 *
 * AUTOMATIC: root when actually available, otherwise standard.
 * ROOT (explicit): never silently falls back — unavailability is an error
 * with a clear reason (prompt #35).
 */
@Singleton
class InstallationStrategyResolver @Inject constructor(
    private val standard: StandardInstallationStrategy,
    private val root: RootInstallationStrategy,
    private val managed: ManagedDeviceInstallationStrategy,
    private val rootAccessProvider: RootAccessProvider,
) {
    suspend fun resolve(mode: InstallationMode): AppResult<InstallationStrategy> = when (mode) {
        InstallationMode.STANDARD -> AppResult.success(standard)

        InstallationMode.ROOT -> {
            if (root.isAvailable()) {
                AppResult.success(root)
            } else {
                // Explicit ROOT mode never silently falls back to standard.
                AppResult.failure(NovaError.RootUnavailable)
            }
        }

        InstallationMode.MANAGED -> {
            if (managed.isAvailable()) {
                AppResult.success(managed)
            } else {
                AppResult.failure(
                    NovaError.InstallationFailed(
                        detail = "Managed installation requires Device Owner mode, which is not active on this device.",
                    ),
                )
            }
        }

        InstallationMode.AUTOMATIC -> {
            if (rootAccessProvider.currentState() == RootAccessState.AUTHORIZED || root.isAvailable()) {
                AppResult.success(root)
            } else {
                AppResult.success(standard)
            }
        }
    }
}
