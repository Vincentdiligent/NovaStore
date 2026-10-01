package com.novastore.app.data.repository

import com.novastore.app.core.datastore.SettingsDataStore
import com.novastore.app.core.model.UpdateSettings
import com.novastore.app.core.network.monitor.NetworkStatusMonitor
import com.novastore.app.domain.repository.NetworkMonitor
import com.novastore.app.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : SettingsRepository {
    override val settings: Flow<UpdateSettings> = settingsDataStore.settings
    override suspend fun update(transform: (UpdateSettings) -> UpdateSettings) {
        settingsDataStore.update(transform)
    }
}

/** Domain NetworkMonitor implemented on top of the core network monitor. */
@Singleton
class NetworkMonitorImpl @Inject constructor(
    private val networkStatusMonitor: NetworkStatusMonitor,
) : NetworkMonitor {
    override val isOnline: Flow<Boolean> = networkStatusMonitor.observeOnline()
    override fun isCurrentlyOnline(): Boolean = networkStatusMonitor.isCurrentlyOnline()
    override fun isCurrentlyUnmetered(): Boolean = networkStatusMonitor.isCurrentlyUnmetered()
}
