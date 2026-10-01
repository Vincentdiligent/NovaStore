package com.novastore.app.data.di

import com.novastore.app.data.repository.AccountRepositoryImpl
import com.novastore.app.data.repository.CatalogRepositoryImpl
import com.novastore.app.data.repository.DeviceProfileRepositoryImpl
import com.novastore.app.data.repository.InstalledAppsRepositoryImpl
import com.novastore.app.data.repository.NetworkMonitorImpl
import com.novastore.app.data.repository.PlayStoreRepositoryImpl
import com.novastore.app.data.repository.RepositoriesRepositoryImpl
import com.novastore.app.data.repository.SettingsRepositoryImpl
import com.novastore.app.data.repository.UpdatesRepositoryImpl
import com.novastore.app.domain.repository.AccountRepository
import com.novastore.app.domain.repository.CatalogRepository
import com.novastore.app.domain.repository.DeviceProfileRepository
import com.novastore.app.domain.repository.InstalledAppsRepository
import com.novastore.app.domain.repository.NetworkMonitor
import com.novastore.app.domain.repository.PlayStoreRepository
import com.novastore.app.domain.repository.RepositoriesRepository
import com.novastore.app.domain.repository.SettingsRepository
import com.novastore.app.domain.repository.UpdateHistoryRepository
import com.novastore.app.domain.repository.UpdatesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun bindInstalledAppsRepository(impl: InstalledAppsRepositoryImpl): InstalledAppsRepository

    @Binds
    abstract fun bindCatalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    abstract fun bindUpdatesRepository(impl: UpdatesRepositoryImpl): UpdatesRepository

    @Binds
    abstract fun bindUpdateHistoryRepository(impl: UpdatesRepositoryImpl): UpdateHistoryRepository

    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindRepositoriesRepository(impl: RepositoriesRepositoryImpl): RepositoriesRepository

    @Binds
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    abstract fun bindNetworkMonitor(impl: NetworkMonitorImpl): NetworkMonitor

    @Binds
    abstract fun bindPlayStoreRepository(impl: PlayStoreRepositoryImpl): PlayStoreRepository

    @Binds
    abstract fun bindDeviceProfileRepository(impl: DeviceProfileRepositoryImpl): DeviceProfileRepository

    @Binds
    abstract fun bindUpdateDiscoveryService(impl: com.novastore.app.data.repository.PlayWebWatch): com.novastore.app.domain.repository.UpdateDiscoveryService

    @Binds
    abstract fun bindPackageTrustRepository(impl: com.novastore.app.data.repository.PackageTrustRepositoryImpl): com.novastore.app.domain.repository.PackageTrustRepository
}
