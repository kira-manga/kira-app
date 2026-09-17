package me.manga.kira.data.complaint.backend

import me.manga.kira.core.error.AppError
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** Fixed CREATE/status flows only. Every admission, retry and application still belongs to the coordinator. */
internal class ComplaintReportExecution(
    private val coordinator: InstallationCredentialCoordinator,
    private val sessions: InstallationSessionManager,
    private val http: ComplaintMutationHttp,
) {
    suspend fun create(binding: ReportActionBinding): ReportExecution {
        val authenticated = sessions.reportSession(binding)
        if (authenticated is ReportSessionResult.Failed) {
            return binding.unresolved(reportSessionFailure(authenticated.failure))
        }
        val session = (authenticated as ReportSessionResult.Ready).session
        return if (binding.stage == ReportActionStage.NEW) {
            when (val result = coordinator.prepareReport(binding, session, sessions)) {
                is Outcome.Success -> dispatch(result.value, session)
                else -> binding.unresolved(reportLocalFailure(result))
            }
        } else {
            dispatch(binding, session)
        }
    }

    suspend fun status(
        binding: ReportActionBinding,
        retryLive: Boolean,
    ): ReportExecution {
        if (binding.stage == ReportActionStage.PREPARED) {
            return if (retryLive && binding.liveReport != null) {
                create(binding)
            } else {
                binding.unresolved(reportUnavailable(Block.LIVE_REQUEST_REQUIRED))
            }
        }
        return when (val authenticated = sessions.reportSession(binding)) {
            is ReportSessionResult.Failed -> binding.unresolved(reportSessionFailure(authenticated.failure))
            is ReportSessionResult.Ready ->
                when (val result = readStatus(binding, authenticated.session)) {
                    is ReportStatusRead.Ready -> finishStatus(result.exchange, retryLive)
                    is ReportStatusRead.Failed -> binding.unresolved(result.failure)
                }
        }
    }

    private suspend fun finishStatus(
        exchange: ReportExchange.Status,
        retryLive: Boolean,
    ): ReportExecution {
        val result = exchange.result
        return when {
            retryLive &&
                result is ComplaintCreateStatusHttpResult.HttpFailure &&
                result.status == NOT_FOUND &&
                result.problem == ComplaintMutationProblem.OPERATION_NOT_FOUND ->
                dispatch(exchange.binding, exchange.session)
            result is ComplaintCreateStatusHttpResult.Applied || result is ComplaintCreateStatusHttpResult.Rejected ->
                apply(exchange)
            result is ComplaintCreateStatusHttpResult.HttpFailure ->
                exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
            result is ComplaintCreateStatusHttpResult.Failed ->
                exchange.binding.unresolved(reportMutationFailure(result.reason))
            else -> exchange.binding.unresolved(reportUnavailable())
        }
    }

    private suspend fun dispatch(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportExecution {
        val first = coordinator.dispatchReport(binding, session, sessions, http)
        if (first !is Outcome.Success) return binding.unresolved(reportLocalFailure(first))
        val exchange = first.value
        val unauthorized = (exchange.result as? ComplaintCreateHttpResult.HttpFailure)?.status == UNAUTHORIZED
        return if (unauthorized) retryCreateAfterUnauthorized(exchange) else finishCreate(exchange)
    }

    private suspend fun retryCreateAfterUnauthorized(exchange: ReportExchange.Create): ReportExecution {
        val refreshed = refresh(exchange)
        if (refreshed is ReportSessionResult.Failed) {
            return exchange.binding.unresolved(reportSessionFailure(refreshed.failure))
        }
        return when (
            val retry =
                coordinator.dispatchReport(
                    exchange.binding,
                    (refreshed as ReportSessionResult.Ready).session,
                    sessions,
                    http,
                )
        ) {
            is Outcome.Success -> finishCreate(retry.value)
            else -> exchange.binding.unresolved(reportLocalFailure(retry))
        }
    }

    private suspend fun readStatus(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportStatusRead {
        val first = coordinator.readReportStatus(binding, session, sessions, http)
        if (first !is Outcome.Success) return ReportStatusRead.Failed(reportLocalFailure(first))
        val exchange = first.value
        return if ((exchange.result as? ComplaintCreateStatusHttpResult.HttpFailure)?.status != UNAUTHORIZED) {
            ReportStatusRead.Ready(exchange)
        } else {
            when (val refreshed = refresh(exchange)) {
                is ReportSessionResult.Ready ->
                    when (val retry = coordinator.readReportStatus(binding, refreshed.session, sessions, http)) {
                        is Outcome.Success -> ReportStatusRead.Ready(retry.value)
                        else -> ReportStatusRead.Failed(reportLocalFailure(retry))
                    }
                is ReportSessionResult.Failed -> ReportStatusRead.Failed(reportSessionFailure(refreshed.failure))
            }
        }
    }

    private suspend fun refresh(exchange: ReportExchange): ReportSessionResult =
        when (val allowed = coordinator.authorizeReportRefresh(exchange, sessions)) {
            is Outcome.Success -> {
                sessions.invalidateReportSession(exchange.session)
                sessions.reportSession(exchange.binding)
            }
            is Outcome.Refused -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
            is Outcome.StorageFailure -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
            is Outcome.Invalid -> ReportSessionResult.Failed(ComplaintSessionResult.LocalFailure(allowed))
        }

    private suspend fun finishCreate(exchange: ReportExchange.Create): ReportExecution =
        when (val result = exchange.result) {
            is ComplaintCreateHttpResult.Applied -> apply(exchange)
            is ComplaintCreateHttpResult.HttpFailure ->
                exchange.binding.unresolved(ReportFailure(AppError.Network.Http(result.status)))
            is ComplaintCreateHttpResult.Failed -> exchange.binding.unresolved(reportMutationFailure(result.reason))
        }

    private suspend fun apply(exchange: ReportExchange): ReportExecution =
        when (val applied = coordinator.applyReportOutcome(exchange, sessions)) {
            is Outcome.Success ->
                ReportExecution(
                    applied.value.binding,
                    ReportAttempt.Completed(exchange.binding.liveReport, applied.value.application),
                )
            else -> exchange.binding.unresolved(reportLocalFailure(applied))
        }
}

private sealed interface ReportStatusRead {
    class Ready(
        val exchange: ReportExchange.Status,
    ) : ReportStatusRead

    class Failed(
        val failure: ReportFailure,
    ) : ReportStatusRead
}

private const val UNAUTHORIZED = 401
private const val NOT_FOUND = 404
