package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PackageInstallationPlan
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.installer.InstallationStrategyResolver
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.PackageTrustRepository
import com.novastore.app.domain.repository.UpdatesRepository
import java.io.File
import javax.inject.Inject

/**
 * Installs a verified package using the strategy selected by the resolver
 * and the user's installation mode. After installation the actually
 * installed package/version is re-checked via PackageManager before the
 * result is reported as success.
 *
 * XAPK extraction directories are removed once a terminal result is
 * reached, and every CONFIRMED install feeds the local trust cache
 * (certificate + source chain, the foundation of release reputation).
 */
class InstallPackageUseCase @Inject constructor(
    private val resolver: InstallationStrategyResolver,
    private val installedAppsRepository: InstalledAppsRepository,
    private val updatesRepository: UpdatesRepository,
    private val packageTrustRepository: PackageTrustRepository,
) {
    suspend operator fun invoke(plan: PackageInstallationPlan, mode: InstallationMode): AppResult<InstallResult> {
        updatesRepository.transition(plan.packageName, UpdateState.INSTALLING)

        try {
            val strategy = when (val resolved = resolver.resolve(mode)) {
                is AppResult.Failure -> {
                    updatesRepository.transition(plan.packageName, UpdateState.WAITING_FOR_USER)
                    return AppResult.failure(resolved.error)
                }
                is AppResult.Success -> resolved.value
            }

            if (!strategy.isAvailable()) {
                updatesRepository.transition(plan.packageName, UpdateState.WAITING_FOR_USER)
                return AppResult.failure(
                    NovaError.InstallationFailed(detail = "The selected installation backend is not available on this device."),
                )
            }

            val result = strategy.install(plan)

            return when (result) {
                is InstallResult.Success -> {
                    // Never trust exit codes alone: confirm via PackageManager.
                    val installed = installedAppsRepository.refresh(plan.packageName)
                    if (installed == null || installed.versionCode != plan.versionCode) {
                        updatesRepository.transition(plan.packageName, UpdateState.FAILED)
                        AppResult.failure(
                            NovaError.InstallationFailed(
                                detail = "Installation reported success but the installed version does not match.",
                            ),
                        )
                    } else {
                        updatesRepository.transition(plan.packageName, UpdateState.CONFIRMED)
                        // Trust cache: remember which certificate and source
                        // delivered this version.
                        runCatching {
                            packageTrustRepository.recordInstall(
                                packageName = plan.packageName,
                                certSha256 = installed.signingCertDigest,
                                source = plan.source,
                            )
                        }
                        AppResult.success(result)
                    }
                }
                is InstallResult.UserActionRequired -> {
                    updatesRepository.transition(plan.packageName, UpdateState.WAITING_FOR_USER)
                    AppResult.success(result)
                }
                is InstallResult.Cancelled -> {
                    // The user dismissed the system dialog — back to DISCOVERED,
                    // the update simply stays in the list.
                    updatesRepository.transition(plan.packageName, UpdateState.DISCOVERED)
                    AppResult.success(result)
                }
                is InstallResult.Failure -> {
                    updatesRepository.transition(plan.packageName, UpdateState.FAILED)
                    AppResult.failure(result.error)
                }
            }
        } finally {
            // XAPK extraction dirs only exist for this installation.
            plan.cleanupDir?.let { path ->
                runCatching { File(path).deleteRecursively() }
            }
        }
    }
}
