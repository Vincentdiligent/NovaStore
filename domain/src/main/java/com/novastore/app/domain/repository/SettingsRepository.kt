package com.novastore.app.domain.repository

import com.novastore.app.core.model.UpdateSettings
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<UpdateSettings>
    suspend fun update(transform: (UpdateSettings) -> UpdateSettings)
}
