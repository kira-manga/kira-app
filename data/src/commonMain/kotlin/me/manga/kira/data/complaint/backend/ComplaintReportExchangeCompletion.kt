package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** Named, state-free operations on the same coordinator/session owner; never an authenticated callback. */
internal class ComplaintReportExchangeCompletion(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
) {
    suspend fun refresh(exchange: ReportExchange): ReportSessionResult =
        when (val allowed = coordinator.authorizeReportRefresh(exchange, sessions)) {
            is Outcome.Success -> {
                sessions.invalidateReportSession(exchange.session)
                sessions.reportSession(exchange.binding)
            }
            is Outcome.Refused -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
            is Outcome.StorageFailure -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
            is Outcome.Invalid -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
        }

    suspend fun apply(exchange: ReportExchange): ReportExecution =
        when (val applied = coordinator.applyReportOutcome(exchange, sessions)) {
            is Outcome.Success ->
                ReportExecution(
                    applied.value.binding,
                    ReportAttempt.Completed(exchange.binding.liveReport, applied.value.application),
                )
            else -> exchange.binding.unresolved(reportLocalFailure(applied))
        }
}
