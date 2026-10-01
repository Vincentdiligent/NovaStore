package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import com.novastore.app.core.model.InstallResult
import com.novastore.app.core.model.InstallationMode
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateHistoryRecord
import com.novastore.app.core.model.UpdateHistoryResult
import com.novastore.app.core.model.UpdateState
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdateHistoryRepository
import com.novastore.app.domain.repository.UpdatesRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * UPDATE ALL: orchestrates the full pipeline
 *   download → verify → (optional user confirmation) → install → confirm → record.
 *
 * A failure on one item never aborts the queue: the failure is recorded,
 * the remaining updates continue and a summary is returned.
 */
class UpdateAllUseCase @Inject constructor(
    private val updatesRepository: UpdatesRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadUpdate: DownloadUpdateUseCase,
    private val verifyArtifact: VerifyArtifactUseCase,
    private val installPackage: InstallPackageUseCase,
    private val updateHistory: UpdateHistoryRepository,
) {
    data class ItemResult(
        val packageName: String,
        val appName: String,
        val outcome: Outcome,
        val error: NovaError? = null,
    )

    enum class Outcome {
        INSTALLED,
        AWAITING_USER_CONFIRMATION,
        FAILED,
        SKIPPED,
    }

    data class Summary(
        val total: Int,
        val installed: Int,
        val awaitingConfirmation: Int,
        val failed: Int,
        val results: List<ItemResult>,
    )

    suspend operator fun invoke(): AppResult<Summary> {
        val settings = settingsRepository.settings.first()

        val current = updatesRepository.currentCandidates()
        if (current.isEmpty()) {
            return AppResult.success(Summary(0, 0, 0, 0, emptyList()))
        }

        val results = mutableListOf<ItemResult>()
        // DISCOVERY candidates are invitations to check, never to install —
        // "Update all" only touches confirmed (EXACT) updates.
        val actionable = current.filter {
            it.confidence == com.novastore.app.core.model.UpdateConfidence.EXACT
        }
        // Pipeline: up to PARALLEL_DOWNLOADS apps are prepared + downloaded
        // at the same time; verification + installation (which may show the
        // system confirmation dialog) runs strictly one app at a time, in the
        // order downloads finish. The old loop did everything serially — a
        // long initial wait, then apps trickling in one by one.
        val downloadGate = kotlinx.coroutines.sync.Semaphore(PARALLEL_DOWNLOADS)
        val installLock = kotlinx.coroutines.sync.Mutex()
        results += kotlinx.coroutines.coroutineScope {
            actionable.map { candidate ->
                async { processOne(candidate, settings, downloadGate, installLock) }
            }.awaitAll()
        }

        val installed = results.count { it.outcome == Outcome.INSTALLED }
        val awaiting = results.count { it.outcome == Outcome.AWAITING_USER_CONFIRMATION }
        val failed = results.count { it.outcome == Outcome.FAILED }
        return AppResult.success(
            Summary(
                total = actionable.size,
                installed = installed,
                awaitingConfirmation = awaiting,
                failed = failed,
                results = results,
            ),
        )
    }

    private suspend fun processOne(
        stored: UpdateCandidate,
        settings: com.novastore.app.core.model.UpdateSettings,
        downloadGate: kotlinx.coroutines.sync.Semaphore,
        installLock: kotlinx.coroutines.sync.Mutex,
    ): ItemResult {
        // 1. Resolve the best route + download (parallel, bounded).
        val (candidate, download) = downloadGate.withPermit {
            val prepared = downloadUpdate.prepare(stored)
            prepared to downloadUpdate(prepared)
        }
        val packageName = candidate.installed.packageName
        val appName = candidate.installed.appName
        // 2+3. Verify and install one app at a time.
        return installLock.withLock { verifyAndInstall(candidate, download, settings, packageName, appName) }
    }

    private suspend fun verifyAndInstall(
        candidate: UpdateCandidate,
        download: AppResult<java.io.File>,
        settings: com.novastore.app.core.model.UpdateSettings,
        packageName: String,
        appName: String,
    ): ItemResult {
        val file = when (download) {
            is AppResult.Failure -> {
                recordHistory(candidate, UpdateHistoryResult.FAILED, download.error.userMessage)
                return ItemResult(packageName, appName, Outcome.FAILED, download.error)
            }
            is AppResult.Success -> download.value
        }

        // 2. Verify (checksum, package identity, version, signature)
        val verified = when (val verification = verifyArtifact(candidate, file)) {
            is AppResult.Failure -> {
                if (verification.error is NovaError.SignatureMismatch) {
                    updatesRepository.saveCandidate(
                        candidate.copy(confidence = com.novastore.app.core.model.UpdateConfidence.FOREIGN_SIGNATURE),
                        com.novastore.app.core.model.UpdateState.DISCOVERED,
                    )
                }
                recordHistory(candidate, UpdateHistoryResult.FAILED, verification.error.userMessage)
                return ItemResult(packageName, appName, Outcome.FAILED, verification.error)
            }
            is AppResult.Success -> verification.value
        }

        // 3. Install + post-install verification. With "confirm before
        // installation" the standard Android installer is used, whose system
        // dialog is the confirmation; otherwise the configured mode (which may
        // install silently with root) applies.
        val mode = if (settings.confirmBeforeInstallation) InstallationMode.STANDARD else settings.installationMode
        return when (val install = installPackage(verified.plan, mode)) {
            is AppResult.Failure -> {
                recordHistory(candidate, UpdateHistoryResult.FAILED, install.error.userMessage)
                ItemResult(packageName, appName, Outcome.FAILED, install.error)
            }
            is AppResult.Success -> {
                val installed = install.value is InstallResult.Success
                recordHistory(
                    candidate,
                    if (installed) UpdateHistoryResult.SUCCESS else UpdateHistoryResult.FAILED,
                    if (installed) null else NovaError.UserActionRequired.userMessage,
                )
                if (installed) {
                    ItemResult(packageName, appName, Outcome.INSTALLED)
                } else {
                    ItemResult(packageName, appName, Outcome.AWAITING_USER_CONFIRMATION)
                }
            }
        }
    }

    private companion object {
        const val PARALLEL_DOWNLOADS = 3
    }

    private suspend fun recordHistory(
        candidate: UpdateCandidate,
        result: UpdateHistoryResult,
        error: String?,
    ) {
        updateHistory.record(
            UpdateHistoryRecord(
                packageName = candidate.installed.packageName,
                appName = candidate.installed.appName,
                oldVersion = candidate.installed.versionName,
                newVersion = candidate.available.versionName,
                timestamp = System.currentTimeMillis(),
                source = candidate.source,
                result = result,
                error = error,
            ),
        )
    }
}
