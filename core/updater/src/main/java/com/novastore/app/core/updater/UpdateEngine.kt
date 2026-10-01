package com.novastore.app.core.updater

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateSettings
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.model.VersionComparator
import com.novastore.app.domain.repository.CatalogRepository
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.RepositoriesRepository
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdateCheckService
import com.novastore.app.domain.repository.UpdateScanReport
import com.novastore.app.domain.repository.UpdatesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * The update engine: compares every installed app with every version the
 * enabled repositories offer.
 *
 * A version is only offered when Android would actually accept it as an
 * update: same signing key as the installed app (when both are known), a
 * compatible CPU architecture and Android version, and a higher versionCode.
 */
@Singleton
class UpdateEngine @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val repositoriesRepository: RepositoriesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val updatesRepository: UpdatesRepository,
    private val settingsRepository: SettingsRepository,
    private val compatibilityChecker: CompatibilityChecker,
    private val sourceResolutionPolicy: SourceResolutionPolicy,
    private val dispatcherProvider: DispatcherProvider,
    private val settingsDataStore: com.novastore.app.core.datastore.SettingsDataStore,
) : UpdateCheckService {

    private val inProgress = MutableStateFlow(false)
    val scanning: Flow<Boolean> = inProgress.asStateFlow()

    override suspend fun checkForUpdates(): AppResult<UpdateScanReport> {
        if (!inProgress.compareAndSet(expect = false, update = true)) {
            return AppResult.failure(
                NovaError.Unknown(userMessage = "An update scan is already running."),
            )
        }
        return try {
            withContext(dispatcherProvider.io) { doScan() }
        } finally {
            inProgress.value = false
        }
    }

    private suspend fun doScan(): AppResult<UpdateScanReport> {
        val settings = settingsRepository.settings.first()

        val installed = when (val scan = installedAppsRepository.scan()) {
            is AppResult.Failure -> return AppResult.failure(scan.error)
            is AppResult.Success -> scan.value
        }.filterNot { it.isSystemApp } // updates are offered for user apps only

        val versionsByPackage = catalogRepository.getVersionsFor(installed.map { it.packageName })
        val priorities = repositoriesRepository.priorities()
        val preferred = runCatching { settingsDataStore.preferredSources.first() }.getOrDefault(emptyMap())
        val priorityOf: (String) -> Int = { source -> priorities[source] ?: Int.MAX_VALUE }

        val candidates = installed.mapNotNull { app ->
            val versions = versionsByPackage[app.packageName] ?: return@mapNotNull null
            resolveOne(app, versions, settings, priorityOf, preferred[app.packageName])
        }

        for (candidate in candidates) {
            updatesRepository.saveCandidate(candidate, UpdateState.DISCOVERED)
        }
        // Stale-row cleanup happens ONCE at the end of the whole scan
        // (CheckForUpdatesUseCase): deleting every non-repository row here
        // made the Updates list flash "all up to date" until the Play pass
        // re-added its rows.

        return AppResult.success(
            UpdateScanReport(
                installedScanned = installed.size,
                candidatesFound = candidates.size,
                candidates = candidates,
                errors = emptyList(),
            ),
        )
    }

    private fun resolveOne(
        app: InstalledApp,
        versions: List<AppVersion>,
        settings: UpdateSettings,
        priorityOf: (String) -> Int,
        preferredSource: String? = null,
    ): UpdateCandidate? {
        val bySource = LinkedHashMap<String, UpdateCandidate>()
        for ((source, sourceVersions) in versions.groupBy { it.source }) {
            val best = sourceVersions
                .asSequence()
                .filter { signerMatches(app, it) }
                .filter { compatibilityChecker.checkAbi(it.nativeCode).compatible }
                .filter { compatibilityChecker.check(it, requiredSpaceBytes = 0).compatible } // minSdk
                .filter {
                    VersionComparator.isNewer(app, it, settings.allowDowngrade) ==
                        VersionComparator.ComparisonResult.NEWER
                }
                // Same version name = same release (no "1.2.3 → 1.2.3" rows).
                .filter { version ->
                    val offered = com.novastore.app.core.model.UpdateRules.normalizeVersionName(version.versionName)
                    val current = com.novastore.app.core.model.UpdateRules.normalizeVersionName(app.versionName)
                    offered == null || current == null ||
                        VersionComparator.compareVersionNames(current, offered) != 0
                }
                .maxByOrNull { it.versionCode }
                ?: continue

            bySource[source] = UpdateCandidate(
                installed = app,
                available = best,
                source = source,
                compatibility = compatibilityChecker.check(best, requiredSpaceBytes = best.size ?: 0),
            )
        }
        // The user picked a source for this app: it wins while it offers an update.
        preferredSource?.let { pick -> bySource[pick]?.let { return it } }
        return sourceResolutionPolicy.select(bySource, priorityOf)
    }

    /**
     * Android refuses an update signed with a different key, so such
     * versions are never offered. Unknown signers are allowed; the
     * downloaded APK is verified before installation anyway.
     */
    private fun signerMatches(app: InstalledApp, version: AppVersion): Boolean {
        val installed = app.signingCertDigest ?: return true
        val offered = version.signer ?: return true
        return installed.equals(offered, ignoreCase = true)
    }
}
