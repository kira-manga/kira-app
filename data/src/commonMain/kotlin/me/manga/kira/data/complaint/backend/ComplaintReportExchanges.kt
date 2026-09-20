package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block

/**
 * Exchange/retry bookkeeping owned by the existing action registry, under its credential mutex.
 * No lock, storage, session cache or independently callable dispatch authority is introduced.
 */
internal class ComplaintReportExchanges {
    private var last: ReportExchange? = null
    private var firstDispatch = false
    private var retry: ReportRetry? = null
    private var unauthorizedRetried = false

    fun clear() {
        advanced()
        firstDispatch = false
        unauthorizedRetried = false
    }

    fun advanced() {
        last = null
        retry = null
    }

    fun markedDispatch() {
        firstDispatch = true
    }

    fun createRequest(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ComplaintCreateHttpRequest {
        val record = dispatchRecord(binding, session)
        val report = binding.liveReport as? ComplaintCreationRequest ?: refuse(Block.INVALID_CANDIDATE)
        return (ComplaintCreateHttpRequest.checked(report, record) ?: refuse(Block.INVALID_CANDIDATE))
            .also { consumeDispatch() }
    }

    fun editRequest(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ComplaintEditHttpRequest {
        val record = dispatchRecord(binding, session)
        val edit = binding.liveReport as? ComplaintEditRequest ?: refuse(Block.INVALID_CANDIDATE)
        return (ComplaintEditHttpRequest.checked(edit, record) ?: refuse(Block.INVALID_CANDIDATE))
            .also { consumeDispatch() }
    }

    fun ownerDeleteRequest(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ComplaintOwnerDeleteHttpRequest {
        val record = dispatchRecord(binding, session)
        val deletion = binding.liveReport as? ComplaintOwnerDeleteRequest ?: refuse(Block.INVALID_CANDIDATE)
        return (ComplaintOwnerDeleteHttpRequest.checked(deletion, record) ?: refuse(Block.INVALID_CANDIDATE))
            .also { consumeDispatch() }
    }

    private fun dispatchRecord(
        binding: ReportActionBinding,
        session: ReportSession,
    ): PendingComplaintRecord {
        if (binding.stage != ReportActionStage.MAY_HAVE_DISPATCHED) refuse(Block.STALE_BINDING)
        val record = binding.pendingRecord ?: refuse(Block.STALE_BINDING)
        if (binding.liveReport == null) refuse(Block.LIVE_REQUEST_REQUIRED)
        if (!firstDispatch) {
            if (session.response.issuedAt > record.times.serverReceiptSafeUntil) refuse(Block.RECEIPT_WINDOW_EXPIRED)
            when (val proof = retry) {
                is ReportRetry.Unauthorized -> {
                    if (!unauthorizedRetried || proof.entry === session.entry) refuse(Block.RECONCILIATION_REQUIRED)
                }
                ReportRetry.NotFound -> Unit
                null -> refuse(Block.RECONCILIATION_REQUIRED)
            }
        }
        return record
    }

    private fun consumeDispatch() {
        firstDispatch = false
        advanced()
    }

    fun received(exchange: ReportExchange) {
        val bound =
            when (exchange) {
                is ReportExchange.Create -> exchange.result.request === exchange.request
                is ReportExchange.Status -> exchange.result.request === exchange.request
                is ReportExchange.Edit -> exchange.result.request === exchange.request
                is ReportExchange.EditStatus -> exchange.result.request === exchange.request
                is ReportExchange.OwnerDelete -> exchange.result.request === exchange.request
                is ReportExchange.OwnerDeleteStatus -> exchange.result.request === exchange.request
            }
        if (!bound) refuse(Block.STALE_BINDING)
        last = exchange
        retry = if (exchange.operationMissing()) ReportRetry.NotFound else null
    }

    fun requireLast(exchange: ReportExchange) {
        if (last !== exchange) refuse(Block.STALE_BINDING)
    }

    /** One matched401 refresh. A status401 never authorizes a mutation retry. */
    fun authorizeRefresh(exchange: ReportExchange) {
        if (last !== exchange || unauthorizedRetried || !exchange.unauthorized()) {
            refuse(Block.RECONCILIATION_REQUIRED)
        }
        unauthorizedRetried = true
        retry =
            when (exchange) {
                is ReportExchange.Create, is ReportExchange.Edit, is ReportExchange.OwnerDelete ->
                    ReportRetry.Unauthorized(exchange.session.entry)
                is ReportExchange.Status, is ReportExchange.EditStatus, is ReportExchange.OwnerDeleteStatus -> null
            }
    }
}

internal fun ReportExchange.operationMissing(): Boolean =
    when (this) {
        is ReportExchange.Status ->
            result is ComplaintCreateStatusHttpResult.HttpFailure &&
                result.status == NOT_FOUND && result.problem == ComplaintMutationProblem.OPERATION_NOT_FOUND
        is ReportExchange.EditStatus ->
            result is ComplaintEditStatusHttpResult.HttpFailure &&
                result.status == NOT_FOUND && result.problem == ComplaintEditProblem.OPERATION_NOT_FOUND
        is ReportExchange.OwnerDeleteStatus ->
            result is ComplaintOwnerDeleteStatusHttpResult.HttpFailure &&
                result.status == NOT_FOUND && result.problem == ComplaintOwnerDeleteProblem.OPERATION_NOT_FOUND
        is ReportExchange.Create, is ReportExchange.Edit, is ReportExchange.OwnerDelete -> false
    }

private fun ReportExchange.unauthorized(): Boolean =
    when (this) {
        is ReportExchange.Create -> (result as? ComplaintCreateHttpResult.HttpFailure)?.status == UNAUTHORIZED
        is ReportExchange.Status -> (result as? ComplaintCreateStatusHttpResult.HttpFailure)?.status == UNAUTHORIZED
        is ReportExchange.Edit -> (result as? ComplaintEditHttpResult.HttpFailure)?.status == UNAUTHORIZED
        is ReportExchange.EditStatus -> (result as? ComplaintEditStatusHttpResult.HttpFailure)?.status == UNAUTHORIZED
        is ReportExchange.OwnerDelete -> (result as? ComplaintOwnerDeleteHttpResult.HttpFailure)?.status == UNAUTHORIZED
        is ReportExchange.OwnerDeleteStatus ->
            (result as? ComplaintOwnerDeleteStatusHttpResult.HttpFailure)?.status == UNAUTHORIZED
    }

private sealed interface ReportRetry {
    class Unauthorized(
        val entry: InstallationSessionEntry,
    ) : ReportRetry

    data object NotFound : ReportRetry
}

private const val UNAUTHORIZED = 401
private const val NOT_FOUND = 404
