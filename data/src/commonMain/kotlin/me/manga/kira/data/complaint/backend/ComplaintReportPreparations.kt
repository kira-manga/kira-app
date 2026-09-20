package me.manga.kira.data.complaint.backend

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Block
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome
import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import me.manga.kira.domain.model.feedback.ComplaintEditDraft
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteDraft
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeletePreparation
import me.manga.kira.domain.model.feedback.ComplaintReplyDraft
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportDraft
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation

/**
 * Preparation-only helper borrowing the consumer's exact issuer/coordinator/input suppliers.
 * Captures outside credential serialization, then rechecks the original permit before publication.
 */
internal class ComplaintReportPreparations(
    private val coordinator: InstallationCredentialCoordinator,
    private val inputs: ComplaintReportInputs,
    private val issuer: ReportConsumerIssuer,
) {
    suspend fun prepare(draft: ComplaintReportDraft): AppResult<ComplaintReportPreparation> {
        val observed = coordinator.beginReconciliation()
        if (observed !is Outcome.Success) {
            return AppResult.Success(ComplaintReportPreparation.Blocked(reportLocalFailure(observed).consumerResult()))
        }
        return when (val captured = capture(draft, observed.value)) {
            null ->
                AppResult.Success(
                    ComplaintReportPreparation.Blocked(reportUnavailable(Block.INVALID_CANDIDATE).consumerResult()),
                )
            is ComplaintReportRequestResult.Rejected -> AppResult.Success(captured.consumerResult())
            is ComplaintReportRequestResult.Accepted -> publish(captured.request, observed.value)
        }
    }

    suspend fun prepare(draft: ComplaintReplyDraft): AppResult<ComplaintReplyPreparation> {
        val observed = coordinator.beginReconciliation()
        if (observed !is Outcome.Success) {
            return AppResult.Success(ComplaintReplyPreparation.Blocked(reportLocalFailure(observed).consumerResult()))
        }
        return when (val captured = capture(draft, observed.value)) {
            null, ComplaintReplyRequestResult.InvalidParent ->
                AppResult.Success(
                    ComplaintReplyPreparation.Blocked(reportUnavailable(Block.INVALID_CANDIDATE).consumerResult()),
                )
            is ComplaintReplyRequestResult.Rejected -> AppResult.Success(captured.consumerResult())
            is ComplaintReplyRequestResult.Accepted -> publish(captured.request, observed.value)
        }
    }

    suspend fun prepare(draft: ComplaintEditDraft): AppResult<ComplaintEditPreparation> {
        val target = ComplaintEditTarget.from(draft.target) ?: return invalidEdit()
        if (target.shape == ComplaintEditShape.BODY_ONLY && draft.subject != null) return invalidEdit()
        val observed = coordinator.beginReconciliation()
        if (observed !is Outcome.Success) {
            return AppResult.Success(ComplaintEditPreparation.Blocked(reportLocalFailure(observed).consumerResult()))
        }
        val key = inputs.editKey?.invoke() ?: return invalidEdit()
        val captured =
            ComplaintEditRequest.normalize(
                target, key, observed.value.record.material.dataScopeId, draft.subject, draft.body,
            )
        return when (captured) {
            ComplaintEditRequestResult.InvalidCandidate -> invalidEdit()
            is ComplaintEditRequestResult.Rejected -> AppResult.Success(captured.consumerResult())
            is ComplaintEditRequestResult.Accepted -> publish(captured.request, observed.value)
        }
    }

    suspend fun prepare(draft: ComplaintOwnerDeleteDraft): AppResult<ComplaintOwnerDeletePreparation> {
        val target = ComplaintEditTarget.from(draft.target) ?: return invalidOwnerDelete()
        val observed = coordinator.beginReconciliation()
        if (observed !is Outcome.Success) {
            return AppResult.Success(
                ComplaintOwnerDeletePreparation.Blocked(reportLocalFailure(observed).consumerResult()),
            )
        }
        val key = inputs.editKey?.invoke() ?: return invalidOwnerDelete()
        val request =
            ComplaintOwnerDeleteRequest.checked(target, key, observed.value.record.material.dataScopeId)
                ?: return invalidOwnerDelete()
        return publish(request, observed.value)
    }

    private fun capture(
        draft: ComplaintReportDraft,
        origin: ReconciliationPermit,
    ): ComplaintReportRequestResult? =
        captureIdentity(origin)?.let {
            ComplaintReportRequest.normalize(it, draft.type, draft.subject, draft.body, inputs.metadata())
        }

    private fun capture(
        draft: ComplaintReplyDraft,
        origin: ReconciliationPermit,
    ): ComplaintReplyRequestResult? =
        captureIdentity(origin)?.let {
            ComplaintReplyRequest.normalize(it, draft.parentId, draft.body, inputs.metadata())
        }

    private fun captureIdentity(origin: ReconciliationPermit): ComplaintReportIdentity? {
        val ids = inputs.identifiers()
        if (ids.clientId == ids.idempotencyKey) return null
        return ComplaintReportIdentity.checked(ids.clientId, ids.idempotencyKey, origin.record.material.dataScopeId)
    }

    private suspend fun publish(
        request: ComplaintReportRequest,
        origin: ReconciliationPermit,
    ): AppResult<ComplaintReportPreparation> =
        when (val checked = coordinator.applyReconciliationIfCurrent(origin) {}) {
            is Outcome.Success ->
                AppResult.Success(ComplaintReportPreparation.Ready(ReportLiveHandle(issuer, request, origin)))
            else -> AppResult.Success(ComplaintReportPreparation.Blocked(reportLocalFailure(checked).consumerResult()))
        }

    private suspend fun publish(
        request: ComplaintReplyRequest,
        origin: ReconciliationPermit,
    ): AppResult<ComplaintReplyPreparation> =
        when (val checked = coordinator.applyReconciliationIfCurrent(origin) {}) {
            is Outcome.Success ->
                AppResult.Success(ComplaintReplyPreparation.Ready(ReplyLiveHandle(issuer, request, origin)))
            else -> AppResult.Success(ComplaintReplyPreparation.Blocked(reportLocalFailure(checked).consumerResult()))
        }

    private suspend fun publish(
        request: ComplaintEditRequest,
        origin: ReconciliationPermit,
    ): AppResult<ComplaintEditPreparation> =
        when (val checked = coordinator.applyReconciliationIfCurrent(origin) {}) {
            is Outcome.Success ->
                AppResult.Success(ComplaintEditPreparation.Ready(EditLiveHandle(issuer, request, origin)))
            else -> AppResult.Success(ComplaintEditPreparation.Blocked(reportLocalFailure(checked).consumerResult()))
        }

    private fun invalidEdit(): AppResult<ComplaintEditPreparation> =
        AppResult.Success(ComplaintEditPreparation.Blocked(reportUnavailable(Block.INVALID_CANDIDATE).consumerResult()))

    private suspend fun publish(
        request: ComplaintOwnerDeleteRequest,
        origin: ReconciliationPermit,
    ): AppResult<ComplaintOwnerDeletePreparation> =
        when (val checked = coordinator.applyReconciliationIfCurrent(origin) {}) {
            is Outcome.Success ->
                AppResult.Success(ComplaintOwnerDeletePreparation.Ready(OwnerDeleteLiveHandle(issuer, request, origin)))
            else ->
                AppResult.Success(ComplaintOwnerDeletePreparation.Blocked(reportLocalFailure(checked).consumerResult()))
        }

    private fun invalidOwnerDelete(): AppResult<ComplaintOwnerDeletePreparation> =
        AppResult.Success(
            ComplaintOwnerDeletePreparation.Blocked(reportUnavailable(Block.INVALID_CANDIDATE).consumerResult()),
        )
}
