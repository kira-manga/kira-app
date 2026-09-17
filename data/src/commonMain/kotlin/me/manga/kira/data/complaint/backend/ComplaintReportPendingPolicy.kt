package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.platform.storage.InstallationPermanentFailure
import me.manga.kira.platform.storage.PendingComplaintSlot

/** Structural start values are not registered authority until the coordinator accepts their exact instance. */
internal fun reportStartBinding(
    permit: ReconciliationPermit,
    work: ReportWork,
    start: ReportStart,
): ReportActionBinding =
    when (start) {
        is ReportStart.New -> {
            reportRequest(start.report, permit)
            ReportActionBinding(permit, work, start.report, ReportActionObservation(null, null, ReportActionStage.NEW))
        }
        is ReportStart.Retained -> retainedReportBinding(permit, work, start)
    }

private fun retainedReportBinding(
    permit: ReconciliationPermit,
    work: ReportWork,
    start: ReportStart.Retained,
): ReportActionBinding {
    if (permit.snapshot.entries().none { it.sameAs(start.slot) }) refuse(Block.STALE_BINDING)
    val record = decodeReport(start.slot)
    start.liveReport?.let {
        if (!record.request.sameAs(reportRequest(it, permit))) refuse(Block.INVALID_CANDIDATE)
    }
    val stage =
        when (record.state) {
            PendingComplaintState.PREPARED -> ReportActionStage.PREPARED
            PendingComplaintState.MAY_HAVE_DISPATCHED -> ReportActionStage.MAY_HAVE_DISPATCHED
        }
    return ReportActionBinding(permit, work, start.liveReport, ReportActionObservation(start.slot, record, stage))
}

/** Pure recomputation only; callers still need the coordinator's current binding and durable proofs. */
internal fun reportRequest(
    report: ComplaintReportRequest,
    permit: ReconciliationPermit,
): PendingComplaintRequest {
    val identity = report.identity
    if (identity.dataScopeId != permit.record.material.dataScopeId || identity.clientId.value == identity.key.value) {
        refuse(Block.INVALID_CANDIDATE)
    }
    val action =
        PendingComplaintAction.checked(PendingComplaintOperation.CREATE_REPORT, identity.clientId.value, null, null)
            ?: refuse(Block.INVALID_CANDIDATE)
    val digest = ComplaintReportFingerprint.of(report)
    val fingerprint =
        PendingComplaintFingerprint.checked(digest.version, digest.encoded) ?: refuse(Block.INVALID_CANDIDATE)
    return PendingComplaintRequest.checked(action, identity.key.value, fingerprint) ?: refuse(Block.INVALID_CANDIDATE)
}

internal fun decodeReport(slot: PendingComplaintSlot): PendingComplaintRecord =
    when (val result = PendingComplaintRecordCodec.decode(slot)) {
        is PendingComplaintCodecResult.Value ->
            result.value.also {
                if (it.request.action.operation != PendingComplaintOperation.CREATE_REPORT) {
                    refuse(Block.RECONCILIATION_REQUIRED)
                }
            }
        PendingComplaintCodecResult.Corrupt -> permanent(InstallationPermanentFailure.CORRUPT)
        PendingComplaintCodecResult.TooLarge -> permanent(InstallationPermanentFailure.TOO_LARGE)
    }

/** A projection is not evidence: only the owning registry may apply its exact issued exchange. */
internal fun reportApplication(exchange: ReportExchange): ReportActionState =
    when (exchange) {
        is ReportExchange.Create ->
            when (val result = exchange.result) {
                is ComplaintCreateHttpResult.Applied ->
                    ReportActionState.Applied(result.acknowledgement.id, result.acknowledgement.version)
                else -> refuse(Block.RECONCILIATION_REQUIRED)
            }
        is ReportExchange.Status ->
            when (val result = exchange.result) {
                is ComplaintCreateStatusHttpResult.Applied ->
                    ReportActionState.Applied(result.acknowledgement.id, result.acknowledgement.version)
                is ComplaintCreateStatusHttpResult.Rejected -> ReportActionState.Rejected(result.code)
                else -> refuse(Block.RECONCILIATION_REQUIRED)
            }
    }
