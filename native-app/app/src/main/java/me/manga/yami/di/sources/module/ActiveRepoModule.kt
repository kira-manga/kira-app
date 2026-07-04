package me.manga.yamiapk.di.sources.module

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.di.sources.provider.ActiveRepoProvider
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object ActiveRepoModule {
    @Provides
    @Singleton
    fun provideActiveRepo(
        // suppress the wildcard so Hilt sees exactly Set<BaseMangaRepository>
        repos: @JvmSuppressWildcards Set<BaseMangaRepository>,
        sharedPrefs: SharedPrefsHelper
    ): ActiveRepoProvider =
        ActiveRepoProvider(repos, sharedPrefs)
}