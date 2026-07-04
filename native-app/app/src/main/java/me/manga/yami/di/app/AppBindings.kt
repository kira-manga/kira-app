package me.manga.yamiapk.di.app

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.domain.auth.DeviceIdProvider
import me.manga.yamiapk.domain.auth.UserIdProvider
import me.manga.yamiapk.domain.device.AndroidDeviceInfoProvider
import me.manga.yamiapk.domain.device.DeviceInfoProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindings {
    @Binds
    @Singleton
    abstract fun bindUserIdProvider(
        deviceIdProvider: DeviceIdProvider
    ): UserIdProvider

    @Binds @Singleton
    abstract fun bindDeviceInfoProvider(
        impl: AndroidDeviceInfoProvider
    ): DeviceInfoProvider
}
