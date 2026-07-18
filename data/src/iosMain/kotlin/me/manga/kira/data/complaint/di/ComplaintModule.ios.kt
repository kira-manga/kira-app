package me.manga.kira.data.complaint.di

import kotlinx.cinterop.BetaInteropApi
import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreRestConfig
import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreRestDataSource
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSBundle
import platform.Foundation.NSDictionary
import platform.Foundation.create

// iOS complaint impl: the KMP-portable Ktor Firestore REST datasource (no Firebase iOS SDK). Reuses
// the Koin-injected HttpClient (Darwin engine).
actual fun complaintRepositoryModule(): Module =
    module {
        single<ComplaintRepository> { ComplaintFirestoreRestDataSource(get(), firebaseComplaintConfig()) }
    }

@OptIn(BetaInteropApi::class)
private fun firebaseComplaintConfig(): ComplaintFirestoreRestConfig {
    val path = NSBundle.mainBundle.pathForResource("GoogleService-Info", ofType = "plist")
    val values = path?.let { NSDictionary.create(contentsOfFile = it) }
    return ComplaintFirestoreRestConfig(
        projectId = values?.objectForKey("PROJECT_ID") as? String ?: "",
        apiKey = values?.objectForKey("API_KEY") as? String ?: "",
    )
}
