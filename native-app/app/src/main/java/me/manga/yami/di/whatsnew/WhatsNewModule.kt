package me.manga.yamiapk.di.whatsnew

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.presentation.features.whatsnew.data.WhatsNewRemoteDataSource
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object WhatsNewModule {

    @Provides
    @Singleton
    fun provideWhatsNewRemoteDataSource(): WhatsNewRemoteDataSource {
        return WhatsNewRemoteDataSource()
    }
}