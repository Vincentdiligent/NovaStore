package com.novastore.app.data.repository

import com.novastore.playapi.DeviceManager
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.model.DeviceProfile
import com.novastore.app.domain.repository.DeviceProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Lists the Google Play device identities bundled in :core:playapi.
 * Display names come from the UserReadableName= line of each .properties
 * profile; files without one fall back to a prettified file name.
 */
@Singleton
class DeviceProfileRepositoryImpl @Inject constructor(
    private val dispatcherProvider: DispatcherProvider,
) : DeviceProfileRepository {

    override suspend fun listDeviceProfiles(): List<DeviceProfile> = withContext(dispatcherProvider.io) {
        DeviceManager.listDevices().map { fileName ->
            val readable = DeviceManager.loadProperties(fileName)
                ?.getProperty("UserReadableName")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            DeviceProfile(
                fileName = fileName,
                displayName = readable ?: fileName.removeSuffix(".properties").replace('_', ' '),
            )
        }.sortedBy { it.displayName.lowercase() }.let { bundled ->
            listOf(
                DeviceProfile(
                    fileName = com.novastore.app.data.playauth.PlayDeviceProperties.NATIVE,
                    displayName = "★ " + (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim(),
                ),
            ) + bundled
        }
    }
}
