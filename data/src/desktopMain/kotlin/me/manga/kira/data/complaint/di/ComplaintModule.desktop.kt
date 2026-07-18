package me.manga.kira.data.complaint.di

import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreRestConfig
import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreRestDataSource
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// Desktop complaint impl: the same KMP-portable Ktor Firestore REST datasource iOS uses (the JVM
// has no first-party Firestore client SDK). Reuses the Koin-injected HttpClient (CIO engine).
actual fun complaintRepositoryModule(): Module =
    module {
        single<ComplaintRepository> {
            ComplaintFirestoreRestDataSource(
                httpClient = get(),
                config =
                    ComplaintFirestoreRestConfig(
                        projectId = System.getenv("KIRA_FIREBASE_PROJECT_ID").orEmpty(),
                        apiKey = System.getenv("KIRA_FIREBASE_API_KEY").orEmpty(),
                    ),
            )
        }
    }
