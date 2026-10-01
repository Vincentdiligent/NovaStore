package com.novastore.app.domain.usecase

import com.novastore.app.core.common.AppResult
import com.novastore.app.core.downloader.api.DownloadRequester
import com.novastore.app.core.installer.XapkExtractor
import com.novastore.app.core.model.ArtifactType
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.PackageArtifact
import com.novastore.app.core.model.PackageInstallationPlan
import com.novastore.app.core.model.SOURCE_PLAY
import com.novastore.app.core.model.UpdateCandidate
import com.novastore.app.core.model.UpdateState
import com.novastore.app.core.model.VerificationResult
import com.novastore.app.core.security.ArtifactVerifier
import com.novastore.app.core.security.PackageVerifier
import com.novastore.app.core.security.SignatureVerifier
import com.novastore.app.domain.repository.UpdatesRepository
import java.io.File
import javax.inject.Inject

/** A fully verified artifact set, ready for installation. */
data class VerifiedPackage(
    val plan: PackageInstallationPlan,
    val sha256: String?,
)

/**
 * Verifies a downloaded artifact: size, SHA-256, package identity,
 * version code and signing certificate compatibility. Verification is
 * mandatory for every install, trusted source or not.
 *
 * Google Play split APKs ride along with the verified base: they originate
 * from the same purchase/delivery response and the same download session,
 * and Android itself rejects a split set that does not match the base's
 * package and signature at install time.
 */
class VerifyArtifactUseCase @Inject constructor(
    private val artifactVerifier: ArtifactVerifier,
    private val downloadRequester: DownloadRequester,
    private val updatesRepository: UpdatesRepository,
    private val xapkExtractor: XapkExtractor,
    private val packageVerifier: PackageVerifier,
    private val signatureVerifier: SignatureVerifier,
) {
    suspend operator fun invoke(candidate: UpdateCandidate, file: File): AppResult<VerifiedPackage> {
        val version = candidate.available
        updatesRepository.transition(version.packageName, UpdateState.VERIFYING)

        if (xapkExtractor.isXapk(file)) {
            return verifyXapkContainer(candidate, file)
        }

        val result = artifactVerifier.verify(
            packageName = version.packageName,
            versionCode = version.versionCode,
            file = file,
            expectedSha256 = version.sha256,
            installedCertDigest = candidate.installed.signingCertDigest,
        )

        return when (result) {
            is VerificationResult.Valid -> {
                updatesRepository.transition(version.packageName, UpdateState.VERIFIED)
                AppResult.success(
                    VerifiedPackage(
                        plan = PackageInstallationPlan(
                            packageName = result.packageName,
                            versionCode = result.versionCode,
                            versionName = version.versionName,
                            source = version.source,
                            artifacts = buildArtifacts(version, file, result.sha256),
                        ),
                        sha256 = result.sha256,
                    ),
                )
            }
            is VerificationResult.Invalid -> {
                updatesRepository.transition(version.packageName, UpdateState.FAILED)
                AppResult.failure(result.error)
            }
        }
    }

    /**
     * XAPK containers (APKPure bundles) are unpacked first, then the SAME
     * guarantees apply as for plain APKs: package identity and signature are
     * verified on the BASE apk, and the whole set (base + device-matched
     * config splits) installs as one PackageInstaller session.
     *
     * The versionCode check is deliberately lenient on one point: mirror
     * metadata codes do not always equal the base apk's real code, so either
     * the manifest's or the base's code must match the candidate. The plan
     * itself always carries the REAL code read from the base — the
     * post-install verification compares against that.
     */
    private suspend fun verifyXapkContainer(
        candidate: UpdateCandidate,
        container: File,
    ): AppResult<VerifiedPackage> {
        val version = candidate.available
        val contents = try {
            xapkExtractor.extract(container)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            updatesRepository.transition(version.packageName, UpdateState.FAILED)
            return AppResult.failure(NovaError.InvalidPackage)
        }

        val parsed = packageVerifier.parse(contents.baseApk)
        if (parsed == null || parsed.packageName != version.packageName) {
            contents.extractedDir.deleteRecursively()
            updatesRepository.transition(version.packageName, UpdateState.FAILED)
            return AppResult.failure(NovaError.InvalidPackage)
        }

        val realVersionCode = parsed.versionCode
        val manifestCode = contents.versionCode
        if (realVersionCode != version.versionCode && manifestCode != version.versionCode) {
            contents.extractedDir.deleteRecursively()
            updatesRepository.transition(version.packageName, UpdateState.FAILED)
            return AppResult.failure(
                NovaError.Metadata(
                    userMessage = "The downloaded bundle declares a different version than expected.",
                    packageName = version.packageName,
                ),
            )
        }

        // Signing certificate compatibility with the installed version.
        val archiveDigest = signatureVerifier.archiveCertDigest(contents.baseApk)
        if (candidate.installed.signingCertDigest != null && archiveDigest != null) {
            if (signatureVerifier.matches(candidate.installed.signingCertDigest, archiveDigest) == false) {
                contents.extractedDir.deleteRecursively()
                updatesRepository.transition(version.packageName, UpdateState.FAILED)
                return AppResult.failure(NovaError.SignatureMismatch)
            }
        }

        updatesRepository.transition(version.packageName, UpdateState.VERIFIED)
        val artifacts = listOf(contents.baseApk) + contents.splitApks
        return AppResult.success(
            VerifiedPackage(
                plan = PackageInstallationPlan(
                    packageName = parsed.packageName ?: version.packageName,
                    versionCode = realVersionCode,
                    versionName = contents.versionName ?: version.versionName,
                    source = version.source,
                    cleanupDir = contents.extractedDir.absolutePath,
                    artifacts = artifacts.map { apk ->
                        PackageArtifact(
                            fileName = apk.name,
                            url = "",
                            sha256 = null,
                            size = apk.length(),
                            artifactType = ArtifactType.SPLIT_APK,
                            localPath = apk.absolutePath,
                        )
                    },
                ),
                sha256 = null,
            ),
        )
    }

    /** Base artifact plus (for Play installs) the split APKs staged next to it. */
    private suspend fun buildArtifacts(
        version: com.novastore.app.core.model.AppVersion,
        baseFile: File,
        sha256: String?,
    ): List<PackageArtifact> {
        val base = PackageArtifact(
            fileName = baseFile.name,
            url = version.downloadUrl,
            sha256 = sha256,
            size = baseFile.length(),
            localPath = baseFile.absolutePath,
        )
        if (version.source != SOURCE_PLAY) return listOf(base)

        val splits = downloadRequester.getCompletedFiles(version.packageName, version.versionCode)
            .drop(1) // base first, splits after
            .filter { it.exists() && it.length() > 0 }
            .map { split ->
                PackageArtifact(
                    fileName = split.name,
                    url = "",
                    sha256 = null,
                    size = split.length(),
                    artifactType = ArtifactType.SPLIT_APK,
                    localPath = split.absolutePath,
                )
            }
        return listOf(base) + splits
    }
}
