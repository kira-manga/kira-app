package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.domain.repository.ComplaintInstallationDeletionOutcome

internal fun deletionUnavailable(): AppError = AppError.Platform.FeatureUnavailable("complaint_installation_deletion")

internal fun deletionLocalError(result: Outcome<*>): AppError =
    when (result) {
        is Outcome.StorageFailure -> AppError.Storage.Io()
        is Outcome.Invalid -> AppError.Network.Serialization()
        else -> deletionUnavailable()
    }

internal fun deletionSessionError(result: ComplaintSessionResult): AppError =
    when (result) {
        is ComplaintSessionResult.HttpFailure -> AppError.Network.Http(result.status)
        is ComplaintSessionResult.LocalFailure -> deletionLocalError(result.outcome)
        is ComplaintSessionResult.Failed -> deletionTransportError(result.reason)
        is ComplaintSessionResult.Ready -> deletionUnavailable()
    }

internal fun deletionTransportError(reason: ComplaintSessionFailure): AppError =
    when (reason) {
        ComplaintSessionFailure.TIMEOUT -> AppError.Network.Timeout()
        ComplaintSessionFailure.TRANSPORT -> AppError.Network.NoConnectivity()
        ComplaintSessionFailure.INVALIDATED -> AppError.Auth.Forbidden()
        ComplaintSessionFailure.EXPIRED -> AppError.Auth.TokenExpired()
        ComplaintSessionFailure.CLOSED -> deletionUnavailable()
        else -> AppError.Network.Serialization()
    }

internal fun deletionPending(result: InstallationDeletionHttpResult): ComplaintInstallationDeletionOutcome.Pending =
    when (result) {
        is InstallationDeletionHttpResult.Accepted ->
            ComplaintInstallationDeletionOutcome.Pending(retryAfterSeconds = result.retryAfterSeconds)
        is InstallationDeletionHttpResult.HttpFailure ->
            ComplaintInstallationDeletionOutcome.Pending(error = AppError.Network.Http(result.status))
        is InstallationDeletionHttpResult.Failed ->
            ComplaintInstallationDeletionOutcome.Pending(error = deletionTransportError(result.reason))
        is InstallationDeletionHttpResult.Terminal -> error("Terminal result requires checked local cleanup")
    }

internal enum class InstallationDeletionCleanup {
    NONE,
    COMPLETED,
}
