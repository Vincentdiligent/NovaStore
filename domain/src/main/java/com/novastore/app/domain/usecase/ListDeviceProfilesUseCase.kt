package com.novastore.app.domain.usecase

import com.novastore.app.core.model.DeviceProfile
import com.novastore.app.domain.repository.DeviceProfileRepository
import javax.inject.Inject

/** Lists the bundled Google Play device profiles (sorted by display name). */
class ListDeviceProfilesUseCase @Inject constructor(
    private val deviceProfileRepository: DeviceProfileRepository,
) {
    suspend operator fun invoke(): List<DeviceProfile> = deviceProfileRepository.listDeviceProfiles()
}
