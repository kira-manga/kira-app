package me.manga.kira.data.complaint.di

import com.google.firebase.firestore.FirebaseFirestore
import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreDataSource
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// Android complaint impl: the Firebase Firestore SDK. FirebaseFirestore.getInstance() auto-inits
// from google-services.json on first call (same pattern as RemoteDocStore.android).
actual fun complaintRepositoryModule(): Module = module {
    single<FirebaseFirestore> { FirebaseFirestore.getInstance() }
    single<ComplaintRepository> { ComplaintFirestoreDataSource(get()) }
}
