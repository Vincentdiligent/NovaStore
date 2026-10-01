package com.novastore.app.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.novastore.app.core.database.dao.CatalogDao
import com.novastore.app.core.database.dao.DownloadDao
import com.novastore.app.core.database.dao.InstalledAppDao
import com.novastore.app.core.database.dao.PackageTrustDao
import com.novastore.app.core.database.dao.PlayFreshnessDao
import com.novastore.app.core.database.dao.RepositoryDao
import com.novastore.app.core.database.dao.UpdateDao
import com.novastore.app.core.database.dao.UpdateHistoryDao
import com.novastore.app.core.database.entity.AppVersionEntity
import com.novastore.app.core.database.entity.DownloadEntity
import com.novastore.app.core.database.entity.InstalledAppEntity
import com.novastore.app.core.database.entity.PackageTrustEntity
import com.novastore.app.core.database.entity.PlayFreshnessEntity
import com.novastore.app.core.database.entity.RemoteAppEntity
import com.novastore.app.core.database.entity.RepositoryEntity
import com.novastore.app.core.database.entity.UpdateEntity
import com.novastore.app.core.database.entity.UpdateHistoryEntity

@Database(
    entities = [
        InstalledAppEntity::class,
        RemoteAppEntity::class,
        AppVersionEntity::class,
        UpdateEntity::class,
        UpdateHistoryEntity::class,
        DownloadEntity::class,
        RepositoryEntity::class,
        PlayFreshnessEntity::class,
        PackageTrustEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun installedAppDao(): InstalledAppDao
    abstract fun catalogDao(): CatalogDao
    abstract fun updateDao(): UpdateDao
    abstract fun updateHistoryDao(): UpdateHistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun repositoryDao(): RepositoryDao
    abstract fun playFreshnessDao(): PlayFreshnessDao
    abstract fun packageTrustDao(): PackageTrustDao

    companion object {
        const val DATABASE_NAME = "nova-store.db"
    }
}
