package com.novastore.app.core.downloader.di

import com.novastore.app.core.downloader.DownloadEngine
import com.novastore.app.core.downloader.api.DownloadRequester
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloaderModule {

    @Binds
    @Singleton
    abstract fun bindDownloadRequester(impl: DownloadEngine): DownloadRequester
}
