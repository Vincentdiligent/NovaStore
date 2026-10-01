package com.novastore.app.core.security.di

import com.novastore.app.core.security.AndroidDeviceProfileProvider
import com.novastore.app.core.security.ArtifactVerifier
import com.novastore.app.core.security.DefaultArtifactVerifier
import com.novastore.app.core.security.DeviceProfileProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    abstract fun bindArtifactVerifier(impl: DefaultArtifactVerifier): ArtifactVerifier

    @Binds
    abstract fun bindDeviceProfileProvider(impl: AndroidDeviceProfileProvider): DeviceProfileProvider
}
