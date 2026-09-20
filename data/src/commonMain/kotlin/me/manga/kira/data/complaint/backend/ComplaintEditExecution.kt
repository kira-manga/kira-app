package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** Fixed EDIT/status flow only; no new lane, credential owner, enrollment path or automatic error replay. */
internal class ComplaintEditExecution(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val http: ComplaintMutationHttp,
) {
    private val completion = ComplaintReportExchangeCompletion(coordinator, sessions)

    suspend fun dispatch(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportExecution {
        val first = coordinator.dispatchEdit(binding, session, sessions, http)
        if (first !is Outcome.Success) return binding.unresolved(reportLocalFailure(first))
        val exchange = first.value
        return if ((exchange.result as? ComplaintEditHttpResult.HttpFailure)?.status == UNAUTHORIZED) {
            retryAfterUnauthorized(exchange)
        } else {
            finishEdit(exchange)
        }
    }

    suspend fun status(
        binding: ReportActionBinding,
        retryLive: Boolean,
    ): ReportExecution =
        when (val authenticated = sessions.reportSession(binding)) {
            is ReportSessionResult.Failed -> binding.unresolved(reportSessionFailure(authenticated.failure))
            is ReportSessionResult.Ready ->
                when (val result = readStatus(binding, authenticated.session)) {
                    is EditStatusRead.Ready -> finishStatus(result.exchange, retryLive)
                    is EditStatusRead.Failed -> binding.unresolved(result.failure)
                }
        }

    private suspend fun finishStatus(
        exchange: ReportExchange.EditStatus,
        retryLive: Boolean,
    ): ReportExecution =
        when (val result = exchange.result) {
            is ComplaintEditStatusHttpResult.Applied, is ComplaintEditStatusHttpResult.Rejected -> completion.apply(exchange)
            is ComplaintEditStatusHttpResult.HttpFailure ->
                if (retryLive && exchange.operationMissing()) {
                    dispatch(exchange.binding, exchange.session)
                } else {
                    exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
                }
            is ComplaintEditStatusHttpResult.Failed -> exchange.binding.unresolved(reportMutationFailure(result.reason))
        }

    private suspend fun retryAfterUnauthorized(exchange: ReportExchange.Edit): ReportExecution =
        when (val refreshed = completion.refresh(exchange)) {
            is ReportSessionResult.Failed -> exchange.binding.unresolved(reportSessionFailure(refreshed.failure))
            is ReportSessionResult.Ready ->
                when (val retry = coordinator.dispatchEdit(exchange.binding, refreshed.session, sessions, http)) {
                    is Outcome.Success -> finishEdit(retry.value)
                    else -> exchange.binding.unresolved(reportLocalFailure(retry))
                }
        }

    private suspend fun readStatus(
        binding: ReportActionBinding,
        session: ReportSession,
    ): EditStatusRead {
        val first = coordinator.readEditStatus(binding, session, sessions, http)
        if (first !is Outcome.Success) return EditStatusRead.Failed(reportLocalFailure(first))
        val exchange = first.value
        return if ((exchange.result as? ComplaintEditStatusHttpResult.HttpFailure)?.status != UNAUTHORIZED) {
            EditStatusRead.Ready(exchange)
        } else {
            when (val refreshed = completion.refresh(exchange)) {
                is ReportSessionResult.Ready ->
                    when (val retry = coordinator.readEditStatus(binding, refreshed.session, sessions, http)) {
                        is Outcome.Success -> EditStatusRead.Ready(retry.value)
                        else -> EditStatusRead.Failed(reportLocalFailure(retry))
                    }
                is ReportSessionResult.Failed -> EditStatusRead.Failed(reportSessionFailure(refreshed.failure))
            }
        }
    }

    private suspend fun finishEdit(exchange: ReportExchange.Edit): ReportExecution =
        when (val result = exchange.result) {
            is ComplaintEditHttpResult.Applied -> completion.apply(exchange)
            is ComplaintEditHttpResult.HttpFailure ->
                exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
            is ComplaintEditHttpResult.Failed -> exchange.binding.unresolved(reportMutationFailure(result.reason))
        }
}

private sealed interface EditStatusRead {
    class Ready(
        val exchange: ReportExchange.EditStatus,
    ) : EditStatusRead

    class Failed(
        val failure: ReportFailure,
    ) : EditStatusRead
}

private const val UNAUTHORIZED = 401
