package com.novastore.app.core.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.NovaError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** Structural information parsed from a downloaded APK. */
data class ParsedApk(
    val packageName: String?,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val supportedAbis: List<String>,
)

/**
 * Parses a downloaded APK with PackageManager.getPackageArchiveInfo and
 * reads the native library folders from the ZIP directory to determine
 * supported architectures.
 */
@Singleton
class PackageVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatcherProvider: DispatcherProvider,
) {
    suspend fun parse(apk: File): ParsedApk? = withContext(dispatcherProvider.io) {
        @Suppress("DEPRECATION")
        val info: PackageInfo? = try {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        } catch (_: Exception) {
            null
        }
        if (info == null) return@withContext null

        val minSdk: Int? = try {
            val applicationInfo = info.applicationInfo
            if (applicationInfo != null) applicationInfo.minSdkVersion else null
        } catch (_: Exception) {
            null
        }
        val targetSdk: Int? = try {
            val applicationInfo = info.applicationInfo
            if (applicationInfo != null) applicationInfo.targetSdkVersion else null
        } catch (_: Exception) {
            null
        }

        ParsedApk(
            packageName = info.packageName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong(),
            versionName = info.versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            supportedAbis = readSupportedAbis(apk),
        )
    }

    /**
     * Reads the lib/<abi>/ entries of the APK without loading file contents.
     * An APK without native libraries is compatible with every ABI.
     */
    private fun readSupportedAbis(apk: File): List<String> {
        return try {
            ZipFile(apk).use { zip ->
                zip.entries()
                    .asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.length > 4 }
                    .map { it.name.substringAfter("lib/").substringBefore('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/** Device-side facts used by compatibility checks. */
data class DeviceProfile(
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val freeSpaceBytes: Long,
)

interface DeviceProfileProvider {
    fun current(): DeviceProfile
}

@Singleton
class AndroidDeviceProfileProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceProfileProvider {
    override fun current(): DeviceProfile {
        val stats = android.os.StatFs(context.cacheDir.absolutePath)
        return DeviceProfile(
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            freeSpaceBytes = stats.availableBytes,
        )
    }
}
