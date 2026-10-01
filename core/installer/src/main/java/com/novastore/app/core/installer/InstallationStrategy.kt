package com.novastore.app.core.installer

import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.PackageInstallationPlan

/**
 * Installation backends. A strategy knows nothing about sources or
 * the update engine; it receives a fully verified installation plan.
 */
interface InstallationStrategy {
    suspend fun isAvailable(): Boolean
    suspend fun install(plan: PackageInstallationPlan): InstallResult
}
