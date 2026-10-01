package com.novastore.app.core.model

/**
 * User-controlled settings that drive the update engine.
 * Persisted via DataStore; never synced anywhere without explicit consent.
 */
data class UpdateSettings(
    val automaticUpdates: Boolean = false,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val batteryThreshold: Int = 20,
    val schedule: UpdateSchedule = UpdateSchedule.DAILY,
    val confirmBeforeInstallation: Boolean = true,
    val allowDowngrade: Boolean = false,
    val installationMode: InstallationMode = InstallationMode.AUTOMATIC,
    val mobileDataAllowed: Boolean = true,
    val downloadWhileCharging: Boolean = true,
    val maxConcurrentDownloads: Int = 2,
    val notificationsEnabled: Boolean = true,
    val anonymousMode: Boolean = true,
    /** Updates may also be resolved through Google Play (requires a Play session). */
    val playUpdatesEnabled: Boolean = true,
)
