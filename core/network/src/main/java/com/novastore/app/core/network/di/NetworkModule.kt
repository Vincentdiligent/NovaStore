package com.novastore.app.core.network.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.novastore.app.core.common.DispatcherProvider
import com.novastore.app.core.network.fdroid.FdroidIndexClient
import com.novastore.app.core.network.monitor.NetworkStatusMonitor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            // Some repository hosts and CDNs reject requests without a User-Agent.
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "NovaStore/1.0 (Android; F-Droid compatible client)")
                    .build(),
            )
        }
        .build()

    @Provides
    @Singleton
    fun provideFdroidIndexClient(
        okHttpClient: OkHttpClient,
        dispatcherProvider: DispatcherProvider,
    ): FdroidIndexClient = FdroidIndexClient(okHttpClient, dispatcherProvider)

    @Provides
    @Singleton
    fun provideNetworkStatusMonitor(@ApplicationContext context: Context): NetworkStatusMonitor =
        NetworkStatusMonitor(context)
}
