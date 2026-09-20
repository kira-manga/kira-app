package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.feedback.ComplaintRecoveryPrompt
import me.manga.kira.domain.repository.ComplaintInstallationRecoveryRepository

/** Explicit local-recovery requests and continuation; existing report actions own exact consent resolution. */
class ComplaintInstallationRecoveryActions(
    val requestUnreadable: RequestUnreadableComplaintRecoveryUseCase,
    val requestDeletionAbandonment: RequestComplaintDeletionAbandonmentUseCase,
    val resumeCleanup: ResumeComplaintInstallationCleanupUseCase,
)

/** Request a warning only if the existing coordinator freshly accepts unreadable local evidence. */
class RequestUnreadableComplaintRecoveryUseCase(
    private val repository: ComplaintInstallationRecoveryRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintRecoveryPrompt> = repository.requestUnreadableRecovery()
}

/** Request warned local abandonment of an already-pending deletion, never remote erasure. */
class RequestComplaintDeletionAbandonmentUseCase(
    private val repository: ComplaintInstallationRecoveryRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintRecoveryPrompt> = repository.requestDeletionAbandonment()
}

/** Run the existing persisted-cleanup check; Unit is deliberately not an identity-erased result. */
class ResumeComplaintInstallationCleanupUseCase(
    private val repository: ComplaintInstallationRecoveryRepository,
) {
    suspend operator fun invoke(): AppResult<Unit> = repository.resumeCleanup()
}
