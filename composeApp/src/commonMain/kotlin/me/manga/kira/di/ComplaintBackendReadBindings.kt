package me.manga.kira.di

import me.manga.kira.domain.repository.ComplaintDetailRepository
import me.manga.kira.domain.repository.ComplaintListRepository
import me.manga.kira.domain.usecase.complaint.LoadComplaintDetailUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import org.koin.core.module.Module

/** Installed only by the isolated candidate graph; both narrow ports resolve its same owned reader. */
internal fun Module.complaintBackendReadBindings() {
    single<ComplaintListRepository> { get<ComplaintBackendGraph>().history }
    single<ComplaintDetailRepository> { get<ComplaintBackendGraph>().details }
    factory { ObserveUserComplaintsUseCase(get()) }
    factory { LoadComplaintDetailUseCase(get()) }
}
