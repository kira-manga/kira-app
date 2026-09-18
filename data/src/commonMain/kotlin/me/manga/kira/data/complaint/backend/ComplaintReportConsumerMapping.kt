package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditPreparation
import me.manga.kira.domain.model.feedback.ComplaintEditReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReplyPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportObservation
import me.manga.kira.domain.model.feedback.ComplaintReportPending
import me.manga.kira.domain.model.feedback.ComplaintReportPhase
import me.manga.kira.domain.model.feedback.ComplaintReportPreparation
import me.manga.kira.domain.model.feedback.ComplaintReportReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportRecovery
import me.manga.kira.domain.model.feedback.ComplaintReportField as DomainReportField
import me.manga.kira.domain.model.feedback.ComplaintReportRejection as DomainReportRejection

internal fun ReportFailure.consumerResult(): ComplaintReportFailure =
    ComplaintReportFailure(error, block?.let { ComplaintReportBlock.valueOf(it.name) })

internal fun ComplaintReportRequestResult.Rejected.consumerResult(): ComplaintReportPreparation.Invalid =
    ComplaintReportPreparation.Invalid(
        DomainReportField.valueOf(field.name),
        DomainReportRejection.valueOf(reason.name),
    )

internal fun ComplaintReplyRequestResult.Rejected.consumerResult(): ComplaintReplyPreparation.Invalid =
    ComplaintReplyPreparation.Invalid(
        DomainReportField.valueOf(field.name),
        DomainReportRejection.valueOf(reason.name),
    )

internal fun ComplaintEditRequestResult.Rejected.consumerResult(): ComplaintEditPreparation.Invalid =
    ComplaintEditPreparation.Invalid(
        DomainReportField.valueOf(field.name),
        DomainReportRejection.valueOf(reason.name),
    )

internal fun ReportActionState.consumerResult(): ComplaintReportApplication =
    when (this) {
        is ReportActionState.Applied -> ComplaintReportApplication.Applied(id, version)
        is ReportActionState.Rejected ->
            ComplaintReportApplication.Rejected(ComplaintReportReceiptRejection.valueOf(code.wireCode))
        is ReportActionState.Edit -> ComplaintReportApplication.Edit(application.consumerResult())
        is ReportActionState.OwnerDelete -> ComplaintReportApplication.OwnerDelete(application.consumerResult())
    }

private fun ComplaintOwnerDeleteActionState.consumerResult(): ComplaintOwnerDeleteApplication =
    when (this) {
        ComplaintOwnerDeleteActionState.Applied -> ComplaintOwnerDeleteApplication.Applied
        is ComplaintOwnerDeleteActionState.Rejected ->
            ComplaintOwnerDeleteApplication.Rejected(ComplaintOwnerDeleteReceiptRejection.valueOf(code.name))
    }

private fun ComplaintEditActionState.consumerResult(): ComplaintEditApplication =
    when (this) {
        is ComplaintEditActionState.Applied -> ComplaintEditApplication.Applied(id, version)
        is ComplaintEditActionState.Rejected ->
            ComplaintEditApplication.Rejected(ComplaintEditReceiptRejection.valueOf(code.name))
    }

internal fun ReportAttempt.consumerResult(issuer: ReportConsumerIssuer): ComplaintReportAttempt =
    when (this) {
        is ReportAttempt.Completed -> ComplaintReportAttempt.Completed(application.consumerResult())
        is ReportAttempt.Unresolved ->
            ComplaintReportAttempt.Unresolved(
                failure.consumerResult(),
                application?.consumerResult(),
                pending?.consumerResult(issuer),
            )
    }

internal fun ReportPendingObservation.consumerResult(issuer: ReportConsumerIssuer): ComplaintReportPending {
    val phase =
        when (decodeReport(slot).state) {
            PendingComplaintState.PREPARED -> ComplaintReportPhase.PREPARED
            PendingComplaintState.MAY_HAVE_DISPATCHED -> ComplaintReportPhase.MAY_HAVE_DISPATCHED
        }
    return ComplaintReportPending(ReportPendingHandle(issuer, this), phase)
}

internal fun ReportRecovery.consumerResult(issuer: ReportConsumerIssuer): ComplaintReportRecovery =
    ComplaintReportRecovery(
        entries().map {
            ComplaintReportObservation(
                ReportPendingObservation(it.slot, it.permit).consumerResult(issuer),
                it.attempt.consumerResult(issuer),
            )
        },
        stopped?.consumerResult(),
    )
