package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.domain.repository.InstalledAppsRepository
import javax.inject.Inject

/** Scans locally installed applications. */
class ScanInstalledAppsUseCase @Inject constructor(
    private val installedAppsRepository: InstalledAppsRepository,
) {
    suspend operator fun invoke(): AppResult<List<InstalledApp>> = installedAppsRepository.scan()
}
