package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.domain.repository.InstalledAppsRepository
import javax.inject.Inject

/**
 * Starts the system uninstall flow for one package. The user always confirms
 * in the Android system dialog — removal never happens silently.
 */
class UninstallAppUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
) {
    suspend operator fun invoke(packageName: String): AppResult<Unit> =
        installedAppsRepository.uninstall(packageName)
}
