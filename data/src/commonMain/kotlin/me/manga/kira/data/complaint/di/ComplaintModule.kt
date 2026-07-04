package me.manga.kira.data.complaint.di

import me.manga.kira.presentation.features.complaint.usecase.DeleteComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.GetAllComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.GetUserComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase
import me.manga.kira.presentation.features.complaint.usecase.UpdateComplaintUseCase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Complaint (feedback) feature bindings, relocated from `:shared` (strangler-fig Phase 5).
 *
 * The five use cases are platform-independent and resolve `ComplaintRepository` — bound per-target
 * by [complaintRepositoryModule] — via `get()`. The repository is per-target: Android uses the
 * Firebase Firestore SDK (`ComplaintFirestoreDataSource`), iOS + Desktop use the Ktor Firestore
 * REST impl (`ComplaintFirestoreRestDataSource`). Both modules are appended to `allReworkModules()`.
 */
val complaintUseCasesModule: Module = module {
    factory { GetUserComplaintUseCase(get()) }
    factory { GetAllComplaintUseCase(get()) }
    factory { SendComplaintUseCase(get()) }
    factory { UpdateComplaintUseCase(get()) }
    factory { DeleteComplaintUseCase(get()) }
}

/** Per-target `ComplaintRepository` binding (Firebase SDK on Android, Ktor REST on iOS/Desktop). */
expect fun complaintRepositoryModule(): Module
