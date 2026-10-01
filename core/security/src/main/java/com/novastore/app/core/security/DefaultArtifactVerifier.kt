package com.novastore.app.core.security

import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.CertificateInfo
import com.novastore.app.core.model.NovaError
import com.novastore.app.core.model.VerificationResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * The full verification pipeline executed before every installation.
 * Trusted source or not, root or not — verification is never skipped.
 */
@Singleton
class DefaultArtifactVerifier @Inject constructor(
    private val hashVerifier: HashVerifier,
    private val signatureVerifier: SignatureVerifier,
    private val packageVerifier: PackageVerifier,
    private val dispatcherProvider: DispatcherProvider,
) : ArtifactVerifier {

    override suspend fun verify(
        packageName: String,
        versionCode: Long,
        file: File,
        expectedSha256: String?,
        installedCertDigest: String?,
    ): VerificationResult = withContext(dispatcherProvider.io) {
        // 1. File exists
        if (!file.exists() || file.length() == 0L) {
            return@withContext VerificationResult.Invalid(NovaError.InvalidPackage)
        }

        // 2. Size sanity (a valid APK is at least a few KB)
        if (file.length() < MIN_APK_BYTES) {
            return@withContext VerificationResult.Invalid(NovaError.InvalidPackage)
        }

        // 3. SHA-256 against source-provided checksum
        if (expectedSha256 != null && HashVerifier.isValidSha256Hex(expectedSha256)) {
            val actual = hashVerifier.sha256(file)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return@withContext VerificationResult.Invalid(NovaError.ChecksumMismatch)
            }
        }

        // 4. Package identity and version
        val parsed = packageVerifier.parse(file)
            ?: return@withContext VerificationResult.Invalid(NovaError.InvalidPackage)
        if (parsed.packageName != packageName) {
            return@withContext VerificationResult.Invalid(NovaError.InvalidPackage)
        }
        if (parsed.versionCode != versionCode) {
            return@withContext VerificationResult.Invalid(
                NovaError.Metadata(
                    userMessage = "The downloaded artifact declares a different version than expected.",
                    packageName = packageName,
                ),
            )
        }

        // 5. Signing certificate compatibility with the installed version
        val archiveDigest = signatureVerifier.archiveCertDigest(file)
        if (installedCertDigest != null && archiveDigest != null) {
            val match = signatureVerifier.matches(installedCertDigest, archiveDigest)
            if (match == false) {
                return@withContext VerificationResult.Invalid(NovaError.SignatureMismatch)
            }
        }

        VerificationResult.Valid(
            packageName = parsed.packageName ?: packageName,
            versionCode = parsed.versionCode,
            sha256 = expectedSha256 ?: hashVerifier.sha256(file),
            certificate = archiveDigest?.let { CertificateInfo(it) },
        )
    }

    companion object {
        private const val MIN_APK_BYTES = 4096L
    }
}
