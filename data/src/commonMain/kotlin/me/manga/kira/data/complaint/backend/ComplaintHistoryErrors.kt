package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** All mappings deliberately omit raw exceptions, storage diagnostics, content and credential values. */
internal fun historyLocalFailure(outcome: Outcome<*>): AppResult.Failure = when (outcome) {
    is Outcome.StorageFailure -> AppResult.Failure(AppError.Storage.Io())
    is Outcome.Invalid -> malformedHistory()
    else -> historyUnavailable()
}

internal fun historySessionFailure(result: ComplaintSessionResult): AppResult.Failure = when (result) {
    is ComplaintSessionResult.HttpFailure -> AppResult.Failure(AppError.Network.Http(result.status))
    is ComplaintSessionResult.LocalFailure -> historyLocalFailure(result.outcome)
    is ComplaintSessionResult.Failed -> when (result.reason) {
        ComplaintSessionFailure.TIMEOUT -> AppResult.Failure(AppError.Network.Timeout())
        ComplaintSessionFailure.TRANSPORT -> AppResult.Failure(AppError.Network.NoConnectivity())
        ComplaintSessionFailure.INVALIDATED -> AppResult.Failure(AppError.Auth.Forbidden())
        ComplaintSessionFailure.CLOSED, ComplaintSessionFailure.EXPIRED -> historyUnavailable()
        else -> malformedHistory()
    }
    is ComplaintSessionResult.Ready -> historyUnavailable()
}

internal fun historyEnrollmentFailure(result: InstallationEnrollmentResult.Failure): AppResult.Failure = when (result) {
    is InstallationEnrollmentResult.HttpFailure -> AppResult.Failure(AppError.Network.Http(result.status))
    is InstallationEnrollmentResult.LocalFailure -> historyLocalFailure(result.outcome)
    is InstallationEnrollmentResult.Failed -> historySessionFailure(ComplaintSessionResult.Failed(result.reason))
    is InstallationEnrollmentResult.MaterialFailure -> historyUnavailable()
}
