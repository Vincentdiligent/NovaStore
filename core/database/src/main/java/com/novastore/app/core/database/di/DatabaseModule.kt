package com.novastore.app.core.database.di

import android.content.Context
import androidx.room.Room
import com.novastore.app.core.database.NovaDatabase
import com.novastore.app.core.database.migration.ALL_MIGRATIONS
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NovaDatabase =
        Room.databaseBuilder(context, NovaDatabase::class.java, NovaDatabase.DATABASE_NAME)
            .addMigrations(*ALL_MIGRATIONS)
            // v3 re-keys the catalog cache per repository; it is rebuilt from the
            // repository indexes, so dropping old local data is safe.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideInstalledAppDao(db: NovaDatabase) = db.installedAppDao()

    @Provides
    fun provideCatalogDao(db: NovaDatabase) = db.catalogDao()

    @Provides
    fun provideUpdateDao(db: NovaDatabase) = db.updateDao()

    @Provides
    fun provideUpdateHistoryDao(db: NovaDatabase) = db.updateHistoryDao()

    @Provides
    fun provideDownloadDao(db: NovaDatabase) = db.downloadDao()

    @Provides
    fun provideRepositoryDao(db: NovaDatabase) = db.repositoryDao()

    @Provides
    fun providePlayFreshnessDao(db: NovaDatabase) = db.playFreshnessDao()

    @Provides
    fun providePackageTrustDao(db: NovaDatabase) = db.packageTrustDao()
}
