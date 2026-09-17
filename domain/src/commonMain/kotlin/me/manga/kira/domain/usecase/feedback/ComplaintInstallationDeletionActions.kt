package me.manga.kira.domain.usecase.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.repository.ComplaintInstallationDeletionPrompt as DeletionPrompt

/** Separate local recovery and remote erasure actions, bound to one installation owner. */
class ComplaintInstallationActions(
    val recovery: ComplaintInstallationRecoveryActions,
    val deletion: ComplaintInstallationDeletionActions,
)

/** A warning never starts deletion. Only exact confirmation and explicit continuation may send it. */
class ComplaintInstallationDeletionActions(
    val observe: ObserveComplaintInstallationDeletionUseCase,
    val request: RequestComplaintInstallationDeletionUseCase,
    val cancel: CancelComplaintInstallationDeletionUseCase,
    val confirm: ConfirmComplaintInstallationDeletionUseCase,
    val continueDeletion: ContinueComplaintInstallationDeletionUseCase,
)

class ObserveComplaintInstallationDeletionUseCase(
    private val repository: ComplaintInstallationDeletionRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintInstallationDeletionObservation> = repository.observeDeletion()
}

class RequestComplaintInstallationDeletionUseCase(
    private val repository: ComplaintInstallationDeletionRepository,
) {
    suspend operator fun invoke(): AppResult<DeletionPrompt> = repository.requestDeletion()
}

class CancelComplaintInstallationDeletionUseCase(
    private val repository: ComplaintInstallationDeletionRepository,
) {
    suspend operator fun invoke(prompt: DeletionPrompt): AppResult<Unit> = repository.cancelDeletion(prompt)
}

class ConfirmComplaintInstallationDeletionUseCase(
    private val repository: ComplaintInstallationDeletionRepository,
) {
    suspend operator fun invoke(prompt: DeletionPrompt): AppResult<ComplaintInstallationDeletionOutcome> =
        repository.confirmDeletion(prompt)
}

class ContinueComplaintInstallationDeletionUseCase(
    private val repository: ComplaintInstallationDeletionRepository,
) {
    suspend operator fun invoke(): AppResult<ComplaintInstallationDeletionOutcome> = repository.continueDeletion()
}
