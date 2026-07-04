package me.manga.kira.data.complaint.di

import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreRestDataSource
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// iOS complaint impl: the KMP-portable Ktor Firestore REST datasource (no Firebase iOS SDK). Reuses
// the Koin-injected HttpClient (Darwin engine).
actual fun complaintRepositoryModule(): Module = module {
    single<ComplaintRepository> { ComplaintFirestoreRestDataSource(get()) }
}
