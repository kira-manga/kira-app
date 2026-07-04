package me.manga.yamiapk.di.network

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.core.network_connectivity.ConnectivityObserver
import me.manga.yamiapk.core.network_connectivity.NetworkConnectivityObserver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectivityMudule {

    @Binds
    @Singleton
    abstract fun bindConnectivityObserver(
        observer: NetworkConnectivityObserver
    ): ConnectivityObserver
}