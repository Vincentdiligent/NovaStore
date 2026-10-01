package com.novastore.app.core.installer.di

import com.novastore.app.core.installer.RootAccessProvider
import com.novastore.app.core.installer.SuRootAccessProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class InstallerModule {

    @Binds
    abstract fun bindRootAccessProvider(impl: SuRootAccessProvider): RootAccessProvider
}
