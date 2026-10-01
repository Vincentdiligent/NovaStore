package com.novastore.app.core.updater

import com.novastore.app.core.model.CompatibilityVerdict
import com.novastore.app.core.model.IncompatibilityReason
import com.novastore.app.core.security.DeviceProfile
import com.novastore.app.core.security.DeviceProfileProvider
import com.novastore.app.core.model.AppVersion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-download device compatibility checks (prompt #18):
 * Android API, minSdk, ABI, storage.
 */
@Singleton
class CompatibilityChecker @Inject constructor(
    private val deviceProfileProvider: DeviceProfileProvider,
) {
    fun check(version: AppVersion, requiredSpaceBytes: Long): CompatibilityVerdict {
        val device = deviceProfileProvider.current()

        val minSdk = version.minSdk
        if (minSdk != null && device.apiLevel < minSdk) {
            return CompatibilityVerdict.incompatible(IncompatibilityReason.ANDROID_VERSION_TOO_LOW)
        }

        if (requiredSpaceBytes > 0 && device.freeSpaceBytes < requiredSpaceBytes + STORAGE_HEADROOM) {
            return CompatibilityVerdict.incompatible(IncompatibilityReason.INSUFFICIENT_STORAGE)
        }

        return CompatibilityVerdict.compatible()
    }

    /** ABI compatibility against the device's supported list. */
    fun checkAbi(supportedAbisOfApk: List<String>): CompatibilityVerdict {
        if (supportedAbisOfApk.isEmpty()) return CompatibilityVerdict.compatible()
        val device = deviceProfileProvider.current()
        val matches = supportedAbisOfApk.any { it in device.supportedAbis }
        return if (matches) {
            CompatibilityVerdict.compatible()
        } else {
            CompatibilityVerdict.incompatible(IncompatibilityReason.ABI_MISMATCH)
        }
    }

    companion object {
        private const val STORAGE_HEADROOM = 32L * 1024 * 1024
    }
}
