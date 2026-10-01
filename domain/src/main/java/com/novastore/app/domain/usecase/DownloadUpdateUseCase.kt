package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.downloader.api.DownloadRequest
import com.novastore.app.core.downloader.api.DownloadRequester
import com.novastore.app.core.downloader.api.DownloadSplit
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PlayStoreException
import com.novastore.app.core.model.SOURCE_APKCOMBO
import com.novastore.app.core.model.SOURCE_APKPURE
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.core.model.SOURCE_PLAY_WEB
import com.novastore.app.core.model.VersionComparator
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateState
import com.novastore.app.domain.repository.PlayStoreRepository
import com.novastore.app.domain.repository.UpdatesRepository
import java.io.File
import javax.inject.Inject

/**
 * Downloads the artifact set for one update candidate and waits for completion.
 *
 * Google Play candidates resolve their download URLs (base + split APKs) via
 * a fresh purchase/delivery request right before enqueueing — Play delivery
 * tokens are single-use, so stored URLs are never reused.
 *
 * A FAILED download never leaves the row in an in-flight state: QUEUED and
 * friends are exempt from scan cleanup (so active downloads survive), so a
 * failure that simply returned would leave the row stuck in "in flight"
 * forever — the exact bug behind "готов к установке" rows that never go away.
 */
class DownloadUpdateUseCase @Inject constructor(
    private val downloadRequester: DownloadRequester,
    private val updatesRepository: UpdatesRepository,
    private val playStoreRepository: PlayStoreRepository,
) {
    /**
     * Upgrades a candidate to the best delivery route BEFORE downloading:
     * rows that came from a community mirror (or a web listing) are served by
     * Google Play itself whenever Play (account or anonymous session) knows
     * the app and offers the same or a newer release for this device —
     * original files, exact versionCode, correct splits, no page scripts or
     * captchas in the way. The returned candidate is the one to verify and
     * install. Unchanged when Play cannot help.
     */
    suspend fun prepare(candidate: UpdateCandidate): UpdateCandidate {
        val version = candidate.available
        if (version.source == SOURCE_PLAY || !isMirrorOrWeb(version.source)) return candidate
        val play = runCatching { playStoreRepository.resolvePlayLatest(version.packageName) }.getOrNull()
            ?: return candidate
        val installedCode = candidate.installed.versionCode
        if (installedCode > 0 && play.versionCode <= installedCode) return candidate
        val wanted = version.versionName?.trim()?.removePrefix("v")
        val offered = play.versionName?.trim()?.removePrefix("v")
        if (wanted != null && offered != null &&
            VersionComparator.compareVersionNames(offered, wanted) < 0
        ) {
            // Play (for this device) is behind the mirror — keep the mirror row.
            return candidate
        }
        val upgraded = candidate.copy(available = play, source = SOURCE_PLAY)
        if (installedCode > 0) {
            runCatching { updatesRepository.saveCandidate(upgraded, UpdateState.DISCOVERED) }
        }
        return upgraded
    }

    private fun isMirrorOrWeb(source: String): Boolean =
        source == SOURCE_APKPURE || source == SOURCE_APKCOMBO || source == SOURCE_PLAY_WEB

    suspend operator fun invoke(candidate: UpdateCandidate): AppResult<File> {
        val result = download(candidate)
        if (result is AppResult.Failure) {
            // QUEUED/DOWNLOADING/VERIFYING → FAILED is a legal transition;
            // FAILED rows are cleaned by the next scan instead of lingering
            // as immortal "in flight" entries.
            updatesRepository.transition(candidate.available.packageName, UpdateState.FAILED)
        }
        return result
    }

    private suspend fun download(candidate: UpdateCandidate): AppResult<File> {
        val version = candidate.available
        updatesRepository.transition(version.packageName, UpdateState.QUEUED)

        var url = version.downloadUrl
        var fileName = version.downloadUrl.substringAfterLast('/')
            .ifBlank { "${version.packageName}_${version.versionCode}.apk" }
        var splits: List<DownloadSplit> = emptyList()

        if (version.source == SOURCE_PLAY) {
            when (val purchased = purchase(version.packageName, version.versionCode)) {
                is AppResult.Failure -> return purchased
                is AppResult.Success -> {
                    val base = purchased.value.firstOrNull { !it.isSplit }
                        ?: return AppResult.failure(
                            NovaError.Metadata(
                                userMessage = "Google Play returned no download files (app may be paid or unavailable).",
                                packageName = version.packageName,
                            ),
                        )
                    url = base.url
                    fileName = base.name.ifBlank { fileName }
                    splits = purchased.value.filter { it.isSplit }
                        .map { DownloadSplit(name = it.name, url = it.url, size = it.sizeBytes.takeIf { s -> s > 0 }) }

                    // A fully completed set (base + every split) is reused as-is.
                    val completed = downloadRequester.getCompletedFiles(version.packageName, version.versionCode)
                    if (completed.size >= 1 + splits.size) {
                        return AppResult.success(completed.first())
                    }
                }
            }
        } else if (version.source == SOURCE_APKPURE || version.source == SOURCE_APKCOMBO || version.source == SOURCE_PLAY_WEB) {
            // Nova anonymous tier, unified mirror chain: APKPure by code →
            // APKPure by version name → APKCombo by name. No single mirror's
            // blind spot (Cloudflare page, JS-gated link, synthetic code)
            // ends the delivery here anymore.
            val mirror = runCatching {
                playStoreRepository.resolveMirrorChain(
                    version.packageName,
                    version.versionCode,
                    version.versionName,
                )
            }.getOrNull()
            if (mirror == null || mirror.isEmpty() || mirror.first().url.isBlank()) {
                // Last resort: Google Play's CURRENT build for this device
                // (anonymous session works too). Only when that is also
                // impossible does the user see an error — never a dead end
                // because one mirror hides its link behind page scripts.
                val play = runCatching { playStoreRepository.resolvePlayLatest(version.packageName) }.getOrNull()
                if (play != null && play.versionCode > candidate.installed.versionCode) {
                    return download(candidate.copy(available = play, source = SOURCE_PLAY))
                }
                return AppResult.failure(
                    NovaError.Metadata(
                        userMessage = "Neither Google Play nor the mirrors could deliver this version right now " +
                            "(it may be paid, bundle-only or region-blocked). Try again later or pick another version.",
                        packageName = version.packageName,
                    ),
                )
            }
            val base = mirror.first()
            url = base.url
            fileName = base.name.ifBlank { fileName }

            downloadRequester.getCompletedFile(version.packageName, version.versionCode)?.let { file ->
                return AppResult.success(file)
            }
        } else {
            // A completed download of this exact version is reused; it is verified again before install.
            downloadRequester.getCompletedFile(version.packageName, version.versionCode)?.let { file ->
                return AppResult.success(file)
            }
        }

        downloadRequester.enqueue(
            DownloadRequest(
                packageName = version.packageName,
                appName = candidate.installed.appName,
                versionCode = version.versionCode,
                versionName = version.versionName,
                url = url,
                fileName = fileName,
                sha256 = version.sha256,
                size = version.size,
                source = version.source,
                splits = splits,
            ),
        )
        return downloadRequester.awaitCompletion(version.packageName, version.versionCode)
    }

    private suspend fun purchase(packageName: String, versionCode: Long): AppResult<List<com.novastore.app.core.model.PlayDownloadFile>> =
        try {
            AppResult.success(playStoreRepository.purchaseDownloadFiles(packageName, versionCode))
        } catch (e: PlayStoreException) {
            AppResult.failure(e.error)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            AppResult.failure(NovaError.Network(userMessage = "Google Play download could not be prepared: ${t.message}", cause = t))
        }
}
