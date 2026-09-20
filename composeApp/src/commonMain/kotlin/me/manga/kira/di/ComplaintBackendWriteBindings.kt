package me.manga.kira.di

import me.manga.kira.domain.repository.ComplaintEditRepository
import me.manga.kira.domain.repository.ComplaintOwnerDeleteRepository
import me.manga.kira.domain.repository.ComplaintReplyRepository
import me.manga.kira.domain.repository.ComplaintReportRepository
import me.manga.kira.domain.usecase.feedback.ComplaintEditActions
import me.manga.kira.domain.usecase.feedback.ComplaintOwnerDeleteActions
import me.manga.kira.domain.usecase.feedback.ComplaintReplyActions
import me.manga.kira.domain.usecase.feedback.ComplaintReportActions
import me.manga.kira.domain.usecase.feedback.PrepareComplaintEditUseCase
import me.manga.kira.domain.usecase.feedback.PrepareComplaintOwnerDeleteUseCase
import me.manga.kira.domain.usecase.feedback.PrepareComplaintReplyUseCase
import me.manga.kira.domain.usecase.feedback.PrepareComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintEditUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintOwnerDeleteUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintReplyUseCase
import me.manga.kira.domain.usecase.feedback.RetryComplaintReportUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintEditUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintOwnerDeleteUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintReplyUseCase
import me.manga.kira.domain.usecase.feedback.SubmitComplaintReportUseCase
import org.koin.core.module.Module

/** Fixed verbs on the isolated candidate's SAME consumer; no selection, legacy reroute or second issuer. */
internal fun Module.complaintBackendWriteBindings() {
    single<ComplaintReportRepository> { get<ComplaintBackendGraph>().reports }
    single<ComplaintReplyRepository> { get<ComplaintBackendGraph>().replies }
    single<ComplaintEditRepository> { get<ComplaintBackendGraph>().edits }
    single<ComplaintOwnerDeleteRepository> { get<ComplaintBackendGraph>().ownerDeletes }
    factory { PrepareComplaintReportUseCase(get()) }
    factory { SubmitComplaintReportUseCase(get()) }
    factory { RetryComplaintReportUseCase(get()) }
    factory { PrepareComplaintReplyUseCase(get()) }
    factory { SubmitComplaintReplyUseCase(get()) }
    factory { RetryComplaintReplyUseCase(get()) }
    factory { PrepareComplaintEditUseCase(get()) }
    factory { SubmitComplaintEditUseCase(get()) }
    factory { RetryComplaintEditUseCase(get()) }
    factory { PrepareComplaintOwnerDeleteUseCase(get()) }
    factory { SubmitComplaintOwnerDeleteUseCase(get()) }
    factory { RetryComplaintOwnerDeleteUseCase(get()) }
    factory { ComplaintReportActions(prepare = get(), submit = get(), retry = get()) }
    factory { ComplaintReplyActions(prepare = get(), submit = get(), retry = get()) }
    factory { ComplaintEditActions(prepare = get(), submit = get(), retry = get()) }
    factory { ComplaintOwnerDeleteActions(prepare = get(), submit = get(), retry = get()) }
}
