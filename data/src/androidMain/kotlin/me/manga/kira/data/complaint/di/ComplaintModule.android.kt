package me.manga.kira.data.complaint.di

import com.google.firebase.firestore.FirebaseFirestore
import me.manga.kira.data.complaint.DisabledComplaintRepository
import me.manga.kira.platform.firebase.FirebaseServicesUnavailableException
import me.manga.kira.platform.firebase.firebaseServicesAvailable
import me.manga.kira.presentation.features.complaint.repository.ComplaintFirestoreDataSource
import me.manga.kira.presentation.features.complaint.repository.ComplaintRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// Select before resolving Firebase: an unprovisioned Debug graph must still construct safely.
actual fun complaintRepositoryModule(): Module = module {
    single<FirebaseFirestore> {
        if (!firebaseServicesAvailable(get())) throw FirebaseServicesUnavailableException()
        FirebaseFirestore.getInstance()
    }
    single<ComplaintRepository> {
        if (firebaseServicesAvailable(get())) ComplaintFirestoreDataSource(get()) else DisabledComplaintRepository
    }
}
