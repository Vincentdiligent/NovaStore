package com.novastore.app.data.playauth

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.telephony.TelephonyManager
import com.novastore.playapi.DeviceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Properties
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the device identity presented to Google Play.
 *
 * [NATIVE] (the default) describes THIS phone — its Android version, CPU
 * ABIs, screen density, features and libraries — so Play answers with the
 * exact build variant, version code and split APKs this device needs (the
 * Nova Store default). A spoofed Pixel profile makes
 * Play report the variant of a different device: a different versionCode
 * for the same release ("11.0.3 → 11.0.3" false updates) or splits for an
 * ABI/density the phone does not have (failed installs).
 *
 * Values Android does not expose (GL extensions, Play Store/Services
 * versions on phones without Google) are taken from the bundled
 * [FALLBACK_PROFILE], so the identity is always complete.
 */
@Singleton
class PlayDeviceProperties @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @Volatile
    private var cachedNative: Properties? = null

    /** Properties for the profile id stored in settings ([NATIVE] or a bundled file name). */
    fun resolve(profile: String?): Properties {
        if (profile.isNullOrBlank() || profile == NATIVE) {
            return native() ?: fallback()
        }
        return DeviceManager.loadProperties(profile) ?: native() ?: fallback()
    }

    /** Bundled modern profile used when everything else fails. */
    fun fallback(): Properties =
        DeviceManager.loadProperties(FALLBACK_PROFILE)
            ?: DeviceManager.loadProperties("px_3a.properties")
            ?: Properties()

    private fun native(): Properties? {
        cachedNative?.let { return copyOf(it) }
        val built = runCatching { buildNative() }.getOrNull() ?: return null
        cachedNative = built
        return copyOf(built)
    }

    private fun copyOf(source: Properties): Properties = Properties().apply { putAll(source) }

    private fun buildNative(): Properties {
        val base = fallback()
        val pm = context.packageManager
        val config = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

        val props = Properties()
        fun put(key: String, value: String?) {
            val fallbackValue = base.getProperty(key)
            props[key] = value?.takeIf { it.isNotBlank() } ?: fallbackValue ?: ""
        }

        put("UserReadableName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        put("Build.HARDWARE", Build.HARDWARE)
        put("Build.RADIO", runCatching { Build.getRadioVersion() }.getOrNull())
        put("Build.BOOTLOADER", Build.BOOTLOADER)
        put("Build.FINGERPRINT", Build.FINGERPRINT)
        put("Build.BRAND", Build.BRAND)
        put("Build.DEVICE", Build.DEVICE)
        put("Build.VERSION.SDK_INT", Build.VERSION.SDK_INT.toString())
        put("Build.VERSION.RELEASE", Build.VERSION.RELEASE)
        put("Build.MODEL", Build.MODEL)
        put("Build.MANUFACTURER", Build.MANUFACTURER)
        put("Build.PRODUCT", Build.PRODUCT)
        put("Build.ID", Build.ID)

        put("TouchScreen", config.touchscreen.toString())
        put("Keyboard", config.keyboard.toString())
        put("Navigation", config.navigation.toString())
        put("ScreenLayout", (config.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK).toString())
        put("HasHardKeyboard", (config.keyboard == Configuration.KEYBOARD_QWERTY).toString())
        put("HasFiveWayNavigation", (config.navigation == Configuration.NAVIGATION_DPAD).toString())

        put("Screen.Density", metrics.densityDpi.toString())
        put("Screen.Width", metrics.widthPixels.toString())
        put("Screen.Height", metrics.heightPixels.toString())

        put("Platforms", Build.SUPPORTED_ABIS.filter { it.isNotBlank() }.joinToString(","))
        put(
            "Features",
            pm.systemAvailableFeatures.mapNotNull { it.name }.filter { it.isNotBlank() }.sorted().joinToString(","),
        )
        put("SharedLibraries", pm.systemSharedLibraryNames?.filter { it.isNotBlank() }?.sorted()?.joinToString(","))
        put(
            "Locales",
            context.assets.locales.filter { it.isNotBlank() }.map { it.replace('-', '_') }.distinct().sorted()
                .joinToString(","),
        )

        val glVersion = activityManager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        put("GL.Version", glVersion.takeIf { it > 0 }?.toString())
        // Android offers no context-free way to list GL extensions.
        put("GL.Extensions", null)

        // Play Store / Services versions: the installed ones when newer than
        // the bundled profile (Play rejects clients that look outdated).
        val gsf = installedVersionCode(pm, "com.google.android.gms")
        val baseGsf = base.getProperty("GSF.version")?.toLongOrNull() ?: 0L
        put("GSF.version", maxOf(gsf ?: 0L, baseGsf).toString())
        val vendingCode = installedVersionCode(pm, "com.android.vending")
        val baseVending = base.getProperty("Vending.version")?.toLongOrNull() ?: 0L
        if (vendingCode != null && vendingCode > baseVending) {
            put("Vending.version", vendingCode.toString())
            put("Vending.versionString", installedVersionName(pm, "com.android.vending"))
        } else {
            put("Vending.version", base.getProperty("Vending.version"))
            put("Vending.versionString", base.getProperty("Vending.versionString"))
        }

        put("CellOperator", telephony?.networkOperator)
        put("SimOperator", telephony?.simOperator)
        put("Roaming", "mobile-notroaming")
        put("Client", "android-google")
        put("TimeZone", TimeZone.getDefault().id)
        put("LowRamDevice", if (activityManager?.isLowRamDevice == true) "1" else "0")
        put("MaxNumOfCPUCores", Runtime.getRuntime().availableProcessors().toString())
        val memory = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        put("TotalMemoryBytes", memory.totalMem.takeIf { it > 0 }?.toString())
        put("OtaInstalled", "false")
        return props
    }

    private fun installedVersionCode(pm: PackageManager, pkg: String): Long? = runCatching {
        val info = pm.getPackageInfo(pkg, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrNull()

    private fun installedVersionName(pm: PackageManager, pkg: String): String? =
        runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()

    companion object {
        /** Settings id of the native (this device) identity. */
        const val NATIVE = "native"

        /** Bundled profile completing values Android does not expose. */
        const val FALLBACK_PROFILE = "px_10_pro.properties"
    }
}
