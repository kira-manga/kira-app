package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** Fixed OWNER_DELETE/status flow using the existing lane, session and request-bound retry proofs. */
internal class ComplaintOwnerDeleteExecution(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val http: ComplaintMutationHttp,
) {
    private val completion = ComplaintReportExchangeCompletion(coordinator, sessions)

    suspend fun dispatch(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportExecution {
        val first = coordinator.dispatchOwnerDelete(binding, session, sessions, http)
        if (first !is Outcome.Success) return binding.unresolved(reportLocalFailure(first))
        val exchange = first.value
        return if ((exchange.result as? ComplaintOwnerDeleteHttpResult.HttpFailure)?.status == UNAUTHORIZED) {
            retryAfterUnauthorized(exchange)
        } else {
            finishDelete(exchange)
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
                    is OwnerDeleteStatusRead.Ready -> finishStatus(result.exchange, retryLive)
                    is OwnerDeleteStatusRead.Failed -> binding.unresolved(result.failure)
                }
        }

    private suspend fun finishStatus(
        exchange: ReportExchange.OwnerDeleteStatus,
        retryLive: Boolean,
    ): ReportExecution =
        when (val result = exchange.result) {
            is ComplaintOwnerDeleteStatusHttpResult.Applied, is ComplaintOwnerDeleteStatusHttpResult.Rejected ->
                completion.apply(exchange)
            is ComplaintOwnerDeleteStatusHttpResult.HttpFailure ->
                if (retryLive && exchange.operationMissing()) {
                    dispatch(exchange.binding, exchange.session)
                } else {
                    exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
                }
            is ComplaintOwnerDeleteStatusHttpResult.Failed ->
                exchange.binding.unresolved(reportMutationFailure(result.reason))
        }

    private suspend fun retryAfterUnauthorized(exchange: ReportExchange.OwnerDelete): ReportExecution =
        when (val refreshed = completion.refresh(exchange)) {
            is ReportSessionResult.Failed -> exchange.binding.unresolved(reportSessionFailure(refreshed.failure))
            is ReportSessionResult.Ready ->
                when (val retry = coordinator.dispatchOwnerDelete(exchange.binding, refreshed.session, sessions, http)) {
                    is Outcome.Success -> finishDelete(retry.value)
                    else -> exchange.binding.unresolved(reportLocalFailure(retry))
                }
        }

    private suspend fun readStatus(
        binding: ReportActionBinding,
        session: ReportSession,
    ): OwnerDeleteStatusRead {
        val first = coordinator.readOwnerDeleteStatus(binding, session, sessions, http)
        if (first !is Outcome.Success) return OwnerDeleteStatusRead.Failed(reportLocalFailure(first))
        val exchange = first.value
        return if ((exchange.result as? ComplaintOwnerDeleteStatusHttpResult.HttpFailure)?.status != UNAUTHORIZED) {
            OwnerDeleteStatusRead.Ready(exchange)
        } else {
            when (val refreshed = completion.refresh(exchange)) {
                is ReportSessionResult.Ready ->
                    when (val retry = coordinator.readOwnerDeleteStatus(binding, refreshed.session, sessions, http)) {
                        is Outcome.Success -> OwnerDeleteStatusRead.Ready(retry.value)
                        else -> OwnerDeleteStatusRead.Failed(reportLocalFailure(retry))
                    }
                is ReportSessionResult.Failed -> OwnerDeleteStatusRead.Failed(reportSessionFailure(refreshed.failure))
            }
        }
    }

    private suspend fun finishDelete(exchange: ReportExchange.OwnerDelete): ReportExecution =
        when (val result = exchange.result) {
            is ComplaintOwnerDeleteHttpResult.Applied -> completion.apply(exchange)
            is ComplaintOwnerDeleteHttpResult.HttpFailure ->
                exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
            is ComplaintOwnerDeleteHttpResult.Failed -> exchange.binding.unresolved(reportMutationFailure(result.reason))
        }
}

private sealed interface OwnerDeleteStatusRead {
    class Ready(
        val exchange: ReportExchange.OwnerDeleteStatus,
    ) : OwnerDeleteStatusRead

    class Failed(
        val failure: ReportFailure,
    ) : OwnerDeleteStatusRead
}

private const val UNAUTHORIZED = 401
