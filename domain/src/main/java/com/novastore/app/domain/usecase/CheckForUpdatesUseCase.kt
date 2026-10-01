package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.model.AppVersion
import com.novastore.app.core.model.InstalledApp
import com.novastore.app.core.model.SOURCE_APKCOMBO
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.core.model.SyntheticVersionCodes
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateConfidence
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.model.VersionComparator
import com.novastore.app.domain.repository.CatalogRepository
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.PlayStoreRepository
import com.novastore.app.domain.repository.RepositoriesRepository
import com.novastore.app.domain.repository.ScanProgress
import com.novastore.app.domain.repository.ScanProgressTracker
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdateCheckService
import com.novastore.app.domain.repository.UpdateScanReport
import com.novastore.app.domain.repository.UpdatesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * Brings the catalog up to date and then scans installed apps for updates:
 * first the repositories (via [UpdateCheckService]), then a Google Play pass
 * for every app the repositories do not manage.
 *
 * Only REAL updates are ever produced: a strictly higher versionCode from a
 * source that knows the exact build for this device, and never a release
 * whose version name equals the installed one ("11.0.3 → 11.0.3").
 */
class CheckForUpdatesUseCase @Inject constructor(
    private val repositoriesRepository: RepositoriesRepository,
    private val updateCheckService: UpdateCheckService,
    private val updatesRepository: UpdatesRepository,
    private val installedAppsRepository: InstalledAppsRepository,
    private val catalogRepository: CatalogRepository,
    private val playStoreRepository: PlayStoreRepository,
    private val settingsRepository: SettingsRepository,
    private val scanProgress: ScanProgressTracker,
) {
    suspend operator fun invoke(forceRefresh: Boolean = false): AppResult<UpdateScanReport> =
        scanProgress.track(ScanProgress(ScanProgress.Stage.REPOSITORIES)) {
            val refresh = repositoriesRepository.refreshAll(forceRefresh)
            scanProgress.update(ScanProgress(ScanProgress.Stage.INSTALLED_APPS))
            val report = updateCheckService.checkForUpdates()
            if (refresh is AppResult.Failure && report is AppResult.Success &&
                report.value.candidatesFound == 0 && !playStoreRepository.hasPlayAccess()
            ) {
                // Nothing could be compared because no source could be read.
                return@track AppResult.failure(refresh.error)
            }
            val base = when (report) {
                is AppResult.Failure -> return@track report
                is AppResult.Success -> report.value
            }

            val unconfirmed = HashSet<String>()
            val merged = addPlayCandidates(base, unconfirmed)
            merged.onSuccess { final ->
                // One atomic cleanup at the very end: rows that no source
                // re-confirmed disappear; rows whose source could not be asked
                // this time (network, rate limit) are kept as they are.
                updatesRepository.retainOnly(final.candidates.map { it.installed.packageName }.toSet() + unconfirmed)
                updatesRepository.setLastCheckedAt(System.currentTimeMillis())
            }
            merged
        }

    /**
     * Play pass. Every user app whose installed signing key does NOT match a
     * repository build is a Play app (installed by Play, by Nova from Play,
     * or sideloaded) — the installer package alone misses apps Nova itself
     * installed. Exact versions come from the native Play protocol through
     * any session (account or anonymous); the community mirrors only cover
     * packages Play did not answer for.
     */
    private suspend fun addPlayCandidates(
        report: UpdateScanReport,
        unconfirmed: MutableSet<String>,
    ): AppResult<UpdateScanReport> {
        // Legacy DISCOVERY rows ("Play page changed", available == installed)
        // are what showed "11.0.3 → 11.0.3". They are never offered anymore.
        updatesRepository.currentCandidates()
            .filter { it.confidence == UpdateConfidence.DISCOVERY }
            .forEach { updatesRepository.remove(it.installed.packageName) }

        val settings = settingsRepository.settings.first()
        if (!settings.playUpdatesEnabled) {
            return AppResult.success(report)
        }

        val installed = installedAppsRepository.observe().first().filterNot { it.isSystemApp }
        val repositoryManaged = report.candidates.map { it.installed.packageName }.toMutableSet()
        val catalogVersions = catalogRepository.getVersionsFor(installed.map { it.packageName })
        for (app in installed) {
            val signer = app.signingCertDigest ?: continue
            if (catalogVersions[app.packageName].orEmpty().any { it.signer.equals(signer, ignoreCase = true) }) {
                repositoryManaged += app.packageName
            }
        }
        val playApps = installed.filterNot { it.packageName in repositoryManaged }
        if (playApps.isEmpty()) return AppResult.success(report)

        val candidates = mutableListOf<UpdateCandidate>()
        val errors = report.errors.toMutableList()
        // A version already known to be signed differently from the
        // installed app keeps that verdict until a NEWER version appears.
        val previous = updatesRepository.currentCandidates().associateBy { it.installed.packageName }
        fun confidenceFor(app: InstalledApp, best: AppVersion): UpdateConfidence {
            val old = previous[app.packageName]
            if (old?.confidence == UpdateConfidence.FOREIGN_SIGNATURE &&
                old.available.versionCode == best.versionCode
            ) {
                return UpdateConfidence.FOREIGN_SIGNATURE
            }
            return if (best.isPaid) UpdateConfidence.PAID else UpdateConfidence.EXACT
        }
        val answered = mutableSetOf<String>()

        // 1) Native Play protocol — exact versionCodes for this device.
        if (playStoreRepository.hasPlayAccess()) {
            val chunks = playApps.map { it.packageName }.distinct().chunked(PLAY_BULK_CHUNK)
            var done = 0
            scanProgress.update(ScanProgress(ScanProgress.Stage.GOOGLE_PLAY, 0, playApps.size))
            val byPackage = playApps.associateBy { it.packageName }
            for (chunk in chunks) {
                val versions = runCatching { playStoreRepository.getVersionsFor(chunk) }.getOrElse {
                    errors += "Google Play lookup failed: ${it.message ?: "unknown error"}"
                    emptyMap()
                }
                for (pkg in chunk) {
                    val app = byPackage[pkg] ?: continue
                    val best = versions[pkg]?.maxByOrNull { it.versionCode } ?: continue
                    answered += pkg
                    if (isRealUpdate(app, best)) {
                        candidates += UpdateCandidate(
                            installed = app,
                            available = best,
                            source = SOURCE_PLAY,
                            confidence = confidenceFor(app, best),
                        )
                    }
                }
                done += chunk.size
                scanProgress.update(ScanProgress(ScanProgress.Stage.GOOGLE_PLAY, done, playApps.size))
            }
        }

        // 2) Community mirrors for what Play did not answer (apps not on
        //    Play for this device/region, or Play unreachable).
        val leftovers = playApps.filterNot { it.packageName in answered }
        if (leftovers.isNotEmpty()) {
            scanProgress.update(ScanProgress(ScanProgress.Stage.MIRRORS))
            val mirror = runCatching {
                playStoreRepository.mirrorVersionsFor(leftovers.map { it.packageName })
            }.getOrDefault(emptyMap())
            leftovers.filter { mirror[it.packageName].isNullOrEmpty() }.forEach { unconfirmed += it.packageName }
            for (app in leftovers) {
                val best = mirror[app.packageName]
                    ?.filter { isRealUpdate(app, it) }
                    ?.let { real ->
                        real.filterNot { SyntheticVersionCodes.isSynthetic(it.versionCode) }
                            .maxByOrNull { it.versionCode }
                            ?: real.maxByOrNull { it.versionCode }
                    }
                    ?: continue
                candidates += UpdateCandidate(
                    installed = app,
                    available = best,
                    source = SOURCE_PLAY,
                    confidence = confidenceFor(app, best),
                )
            }
        }

        candidates.forEach { updatesRepository.saveCandidate(it, UpdateState.DISCOVERED) }
        return mergeIntoReport(report, candidates, errors)
    }

    private fun isRealUpdate(app: InstalledApp, best: AppVersion): Boolean =
        com.novastore.app.core.model.UpdateRules.isRealUpdate(app, best)

    private fun mergeIntoReport(
        report: UpdateScanReport,
        playCandidates: List<UpdateCandidate>,
        errors: List<String>,
    ): AppResult<UpdateScanReport> {
        if (playCandidates.isEmpty()) {
            return AppResult.success(report.copy(errors = errors))
        }
        val playPackagesSet = playCandidates.map { it.installed.packageName }.toSet()
        val retained = report.candidates.filter { it.installed.packageName !in playPackagesSet }
        return AppResult.success(
            report.copy(
                candidates = retained + playCandidates,
                candidatesFound = retained.size + playCandidates.size,
                errors = errors,
            ),
        )
    }

    private companion object {
        const val PLAY_BULK_CHUNK = 50
    }
}
