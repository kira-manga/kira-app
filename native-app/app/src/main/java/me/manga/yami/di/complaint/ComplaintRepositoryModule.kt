package me.manga.yamiapk.di.complaint

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintFirestoreDataSource
import me.manga.yamiapk.presentation.features.complaint.repository.ComplaintRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ComplaintRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindComplaintRepository(
        impl: ComplaintFirestoreDataSource
    ): ComplaintRepository
}