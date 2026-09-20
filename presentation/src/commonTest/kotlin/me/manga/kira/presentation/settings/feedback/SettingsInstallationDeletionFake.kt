package me.manga.kira.presentation.settings.feedback

import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.repository.ComplaintInstallationDeletionObservation
import me.manga.kira.domain.repository.ComplaintInstallationDeletionPrompt
import me.manga.kira.domain.repository.ComplaintInstallationDeletionRepository
import me.manga.kira.domain.usecase.feedback.CancelComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ComplaintInstallationDeletionActions
import me.manga.kira.domain.usecase.feedback.ConfirmComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ContinueComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.ObserveComplaintInstallationDeletionUseCase
import me.manga.kira.domain.usecase.feedback.RequestComplaintInstallationDeletionUseCase
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome as DeletionOutcome

/** Domain-port fake for per-opening intent/lifetime tests, not a replacement deletion coordinator. */
internal class SettingsInstallationDeletionFake : ComplaintInstallationDeletionRepository {
    var nextPrompt: ComplaintInstallationDeletionPrompt = SettingsTestDeletionPrompt()
    var observation: AppResult<ComplaintInstallationDeletionObservation> =
        AppResult.Success(ComplaintInstallationDeletionObservation.Active)
    var observations = 0
    var requests = 0
    var continuations = 0
    val confirmed = mutableListOf<ComplaintInstallationDeletionPrompt>()
    val dismissed = mutableListOf<ComplaintInstallationDeletionPrompt>()
    var onObserve: suspend () -> AppResult<ComplaintInstallationDeletionObservation> = { observation }
    var onRequest: suspend () -> AppResult<ComplaintInstallationDeletionPrompt> = { AppResult.Success(nextPrompt) }
    var onConfirm: suspend (ComplaintInstallationDeletionPrompt) -> AppResult<DeletionOutcome> = {
        AppResult.Success(DeletionOutcome.Pending())
    }
    var onContinue: suspend () -> AppResult<DeletionOutcome> = {
        AppResult.Success(DeletionOutcome.Pending())
    }

    override suspend fun observeDeletion(): AppResult<ComplaintInstallationDeletionObservation> {
        observations++
        return onObserve()
    }

    override suspend fun requestDeletion(): AppResult<ComplaintInstallationDeletionPrompt> {
        requests++
        return onRequest()
    }

    override suspend fun cancelDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<Unit> {
        dismissed += prompt
        return AppResult.Success(Unit)
    }

    override suspend fun confirmDeletion(prompt: ComplaintInstallationDeletionPrompt): AppResult<DeletionOutcome> {
        confirmed += prompt
        return onConfirm(prompt)
    }

    override suspend fun continueDeletion(): AppResult<DeletionOutcome> {
        continuations++
        return onContinue()
    }

    fun actions(): ComplaintInstallationDeletionActions =
        ComplaintInstallationDeletionActions(
            ObserveComplaintInstallationDeletionUseCase(this),
            RequestComplaintInstallationDeletionUseCase(this),
            CancelComplaintInstallationDeletionUseCase(this),
            ConfirmComplaintInstallationDeletionUseCase(this),
            ContinueComplaintInstallationDeletionUseCase(this),
        )
}

internal class SettingsTestDeletionPrompt : ComplaintInstallationDeletionPrompt {
    override fun toString(): String = "SettingsTestDeletionPrompt(redacted)"
}
