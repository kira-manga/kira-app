package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot

/**
 * Coordinator-owned state only. Every method is called while the existing credential mutex is held;
 * the coordinator supplies fresh credential/consent/work checks before and after suspending writes.
 * There is no independent lock, HTTP callback, storage format or ordinary-admission exception here.
 */
@Suppress("TooManyFunctions")
internal class ComplaintReportActionBindings(
    private val pending: PendingComplaintActionStore,
) {
    private var activeWork: ReportWork? = null
    private var expectedPermit: ReconciliationPermit? = null
    private var current: ReportActionBinding? = null
    private val exchanges = ComplaintReportExchanges()
    private val reconciliation = ComplaintReportReconciliationPass()

    fun observe(
        permit: ReconciliationPermit,
        work: ReportWork,
    ) {
        if (!work.isCurrent()) refuse(Block.STALE_BINDING)
        if (activeWork != null && activeWork !== work) refuse(Block.ACTION_IN_PROGRESS)
        if (expectedPermit?.sameAs(permit) == false) refuse(Block.STALE_BINDING)
        if (activeWork == null) reconciliation.clear()
        activeWork = work
        expectedPermit = permit
    }

    fun begin(
        permit: ReconciliationPermit,
        work: ReportWork,
        start: ReportStart,
    ): ReportActionBinding {
        if (current != null) refuse(Block.ACTION_IN_PROGRESS)
        observe(permit, work)
        if (start is ReportStart.New) {
            if (permit.snapshot.size == PendingComplaintSnapshot.MAX_SLOTS) refuse(Block.PENDING_CAPACITY_REACHED)
            reconciliation.consume(permit)
        }
        val binding = reportStartBinding(permit, work, start)
        if (!work.beginAction()) refuse(Block.STALE_BINDING)
        exchanges.clear()
        current = binding
        return binding
    }

    fun requireCurrent(
        binding: ReportActionBinding,
        unsentCancellation: Boolean = false,
    ) {
        val live = if (unsentCancellation) binding.work.openForUnsentCancellation() else binding.work.isCurrent()
        if (current !== binding || !live) refuse(Block.STALE_BINDING)
    }

    suspend fun prepare(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportActionBinding {
        if (binding.stage != ReportActionStage.NEW) refuse(Block.STALE_BINDING)
        val report = binding.liveReport ?: refuse(Block.LIVE_REQUEST_REQUIRED)
        val credential = binding.permit.record
        val tuple =
            PendingComplaintBinding.checked(
                credential.material.installationId,
                credential.credentialVersion,
                credential.localGeneration,
                credential.material.dataScopeId,
            ) ?: refuse(Block.INVALID_CANDIDATE)
        val issuedAt = session.response.issuedAt
        val times = PendingComplaintTimes.checked(issuedAt, issuedAt) ?: refuse(Block.INVALID_CANDIDATE)
        val record = PendingComplaintRecord.prepared(tuple, reportRequest(report, binding.permit), times)
        val change =
            PendingComplaintTransitions.prepare(record, credential) as? PendingComplaintChange.CreateIfMissing
                ?: refuse(Block.INVALID_CANDIDATE)
        val inventory = pending.createPendingCoordinated(binding.permit.snapshot, change.replacement)
        return advance(binding, inventory, change.replacement, record, ReportActionStage.PREPARED)
    }

    suspend fun rebasePrepared(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ReportActionBinding {
        if (binding.stage != ReportActionStage.PREPARED) return binding
        val record = binding.pendingRecord ?: refuse(Block.STALE_BINDING)
        return if (record.times.sessionIssuedAt == session.response.issuedAt) {
            binding
        } else {
            val change =
                PendingComplaintTransitions.rebasePrepared(
                    binding.slot ?: refuse(Block.STALE_BINDING),
                    binding.permit.record,
                    session.response.issuedAt,
                ) as? PendingComplaintChange.Replace ?: refuse(Block.INVALID_CANDIDATE)
            val inventory =
                pending.replacePendingCoordinated(binding.permit.snapshot, change.expected, change.replacement)
            val rebased = decodeReport(change.replacement)
            advance(binding, inventory, change.replacement, rebased, ReportActionStage.PREPARED)
        }
    }

    suspend fun markDispatch(binding: ReportActionBinding): ReportActionBinding {
        if (binding.stage != ReportActionStage.PREPARED) return binding
        val report = binding.liveReport ?: refuse(Block.LIVE_REQUEST_REQUIRED)
        val change =
            PendingComplaintTransitions.markMayHaveDispatched(
                binding.slot ?: refuse(Block.STALE_BINDING),
                binding.permit.record,
                reportRequest(report, binding.permit),
            ) as? PendingComplaintChange.Replace ?: refuse(Block.INVALID_CANDIDATE)
        val inventory = pending.replacePendingCoordinated(binding.permit.snapshot, change.expected, change.replacement)
        val next =
            advance(
                binding,
                inventory,
                change.replacement,
                decodeReport(change.replacement),
                ReportActionStage.MAY_HAVE_DISPATCHED,
            )
        exchanges.markedDispatch()
        return next
    }

    /** Called after both durable proofs and a fresh session/work recheck, never as a request factory alone. */
    fun createRequest(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ComplaintCreateHttpRequest = exchanges.createRequest(binding, session)

    fun editRequest(
        binding: ReportActionBinding,
        session: ReportSession,
    ): ComplaintEditHttpRequest = exchanges.editRequest(binding, session)

    fun statusRequest(binding: ReportActionBinding): ComplaintCreateStatusRequest {
        if (binding.stage != ReportActionStage.MAY_HAVE_DISPATCHED) refuse(Block.RECONCILIATION_REQUIRED)
        reconciliation.forget(binding)
        return ComplaintCreateStatusRequest.checked(binding.pendingRecord ?: refuse(Block.STALE_BINDING))
            ?: refuse(Block.INVALID_CANDIDATE)
    }

    fun editStatusRequest(binding: ReportActionBinding): ComplaintEditStatusRequest {
        if (binding.stage != ReportActionStage.MAY_HAVE_DISPATCHED) refuse(Block.RECONCILIATION_REQUIRED)
        reconciliation.forget(binding)
        return ComplaintEditStatusRequest.checked(binding.pendingRecord ?: refuse(Block.STALE_BINDING))
            ?: refuse(Block.INVALID_CANDIDATE)
    }

    fun receivedCreate(
        binding: ReportActionBinding,
        session: ReportSession,
        request: ComplaintCreateHttpRequest,
        result: ComplaintCreateHttpResult,
    ): ReportExchange.Create = ReportExchange.Create(binding, session, request, result).also(exchanges::received)

    fun receivedEdit(
        binding: ReportActionBinding,
        session: ReportSession,
        request: ComplaintEditHttpRequest,
        result: ComplaintEditHttpResult,
    ): ReportExchange.Edit = ReportExchange.Edit(binding, session, request, result).also(exchanges::received)

    fun authorizeRefresh(exchange: ReportExchange) = exchanges.authorizeRefresh(exchange)

    fun receivedStatus(
        binding: ReportActionBinding,
        session: ReportSession,
        request: ComplaintCreateStatusRequest,
        result: ComplaintCreateStatusHttpResult,
    ): ReportExchange.Status = ReportExchange.Status(binding, session, request, result).also(::receivedStatus)

    fun receivedEditStatus(
        binding: ReportActionBinding,
        session: ReportSession,
        request: ComplaintEditStatusRequest,
        result: ComplaintEditStatusHttpResult,
    ): ReportExchange.EditStatus = ReportExchange.EditStatus(binding, session, request, result).also(::receivedStatus)

    private fun receivedStatus(exchange: ReportExchange) {
        exchanges.received(exchange)
        if (exchange.operationMissing()) reconciliation.record(exchange.binding)
    }

    suspend fun apply(exchange: ReportExchange): ReportCompletion {
        exchanges.requireLast(exchange)
        val binding = exchange.binding
        val application = reportApplication(exchange)
        val slot = binding.slot ?: refuse(Block.STALE_BINDING)
        if (!binding.work.apply(slot, application)) refuse(Block.STALE_BINDING)
        currentCoroutineContext().ensureActive()
        requireCurrent(binding)
        val inventory = pending.deletePendingCoordinated(binding.permit.snapshot, slot)
        val next = advance(binding, inventory, null, null, ReportActionStage.COMPLETED)
        return ReportCompletion(next, application)
    }

    suspend fun cancelPrepared(binding: ReportActionBinding): ReportActionBinding {
        val change =
            PendingComplaintTransitions.cancelPrepared(
                binding.slot ?: refuse(Block.STALE_BINDING),
                binding.permit.record,
            ) as? PendingComplaintChange.Delete ?: refuse(Block.RECONCILIATION_REQUIRED)
        val inventory = pending.deletePendingCoordinated(binding.permit.snapshot, change.expected)
        return advance(binding, inventory, null, null, ReportActionStage.COMPLETED)
    }

    fun cancel() {
        activeWork?.cancel()
    }

    /** Keeps the exact batch anchor and occupied work lane; only this action registration ends. */
    fun release(binding: ReportActionBinding) {
        requireCurrent(binding)
        current = null
        exchanges.clear()
    }

    fun finish(work: ReportWork) {
        if (activeWork === work) {
            reconciliation.clear()
            activeWork = null
            expectedPermit = null
            current = null
            exchanges.clear()
        }
        // Failed admission may not have registered a binding, but still owns the caller's lane.
        work.finish()
    }

    private fun advance(
        binding: ReportActionBinding,
        inventory: PendingComplaintSnapshot,
        slot: PendingComplaintSlot?,
        record: PendingComplaintRecord?,
        stage: ReportActionStage,
    ): ReportActionBinding =
        ReportActionBinding(
            ReconciliationPermit(binding.permit.record, inventory, binding.permit.issuer),
            binding.work,
            binding.liveReport,
            ReportActionObservation(slot, record, stage),
        ).also {
            current = it
            expectedPermit = it.permit
            exchanges.advanced()
        }
}
