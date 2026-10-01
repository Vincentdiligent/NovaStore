package com.novastore.app.core.security

import com.novastore.app.core.model.VerificationResult
import java.io.File

/**
 * Mandatory pre-installation verification pipeline:
 *   file exists → size → SHA-256 → package identity → version code →
 *   architecture → signing certificate compatibility.
 */
interface ArtifactVerifier {
    suspend fun verify(
        packageName: String,
        versionCode: Long,
        file: File,
        expectedSha256: String?,
        installedCertDigest: String?,
    ): VerificationResult
}
