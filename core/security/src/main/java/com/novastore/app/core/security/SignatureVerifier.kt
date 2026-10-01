package com.novastore.app.core.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.CertificateInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Extracts and compares signing certificates of the installed package and
 * a downloaded APK archive. A mismatch blocks automatic updates.
 */
@Singleton
class SignatureVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    /** Digest of the certificate that signed the currently installed version. */
    suspend fun installedCertDigest(packageName: String): String? = withContext(dispatcherProvider.io) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            val info = context.packageManager.getPackageInfo(packageName, flags)
            extractDigest(info)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Digest of the certificate that signed a downloaded APK file. */
    suspend fun archiveCertDigest(apkFile: java.io.File): String? = withContext(dispatcherProvider.io) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return@withContext null
            extractDigest(info)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compares two hex digests. Null-safe: when the expected certificate
     * cannot be established, callers decide the fallback (never a silent
     * privileged update).
     */
    fun matches(expected: String?, actual: String?): Boolean? {
        if (expected == null || actual == null) return null
        return expected.equals(actual, ignoreCase = true)
    }

    /**
     * Digest of the certificate the package is currently signed with, in the
     * same form F-Droid indexes publish (lowercase hex SHA-256 of the DER
     * certificate). [info] must have been fetched with [SIGNING_FLAGS].
     */
    fun digestOf(info: PackageInfo): String? = try {
        extractDigest(info)
    } catch (_: Exception) {
        null
    }

    private fun extractDigest(info: PackageInfo): String? {
        val signatures: Array<out android.content.pm.Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return null
            // apkContentsSigners is the current signer, also after key rotation;
            // the rotation history starts with the oldest key.
            signingInfo.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val first = signatures?.firstOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun certificateInfo(digest: String): CertificateInfo = CertificateInfo(digest)

    companion object {
        @Suppress("DEPRECATION")
        val SIGNING_FLAGS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
    }
}
