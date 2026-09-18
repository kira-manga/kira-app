package me.manga.kira.presentation.settings.feedback.delete

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure

/** Recognition is not authority: the existing port still validates target/tag and every owner fence. */
internal fun ComplaintOwnerRow.backendDeleteContent(): ComplaintOwnerRow.Content? =
    (this as? ComplaintOwnerRow.Content)?.takeIf { it.isContractRecognized }

internal fun ComplaintOwnerRow.backendDeleteInitialState(): BackendComplaintDeleteState {
    val content = backendDeleteContent() ?: return BackendComplaintDeleteState()
    val subject =
        when (content) {
            is ComplaintOwnerRow.Report -> content.subject
            is ComplaintOwnerRow.Reply -> content.subject
            is ComplaintOwnerRow.NoticeReply -> null
        }
    return BackendComplaintDeleteState(
        preview = BackendComplaintDeletePreview(subject, content.fields.body),
        activity = BackendComplaintDeleteActivity.CONFIRMING,
    )
}

internal fun BackendComplaintDeleteObservation.afterFailure(
    failure: ComplaintReportFailure,
): BackendComplaintDeleteObservation =
    copy(failure = failure, conflictObserved = conflictObserved || failure.isDeleteConflict())

internal fun BackendComplaintDeleteObservation.afterAttempt(
    attempt: ComplaintReportAttempt,
): BackendComplaintDeleteObservation =
    when (attempt) {
        is ComplaintReportAttempt.Completed -> withApplication(attempt.application, null, terminal = true)
        is ComplaintReportAttempt.Unresolved -> withApplication(attempt.knownApplication, attempt.failure, terminal = false)
    }

/** Creation/edit receipts from mixed recovery are never confirmation of this single deletion. */
private fun BackendComplaintDeleteObservation.withApplication(
    application: ComplaintReportApplication?,
    failure: ComplaintReportFailure?,
    terminal: Boolean,
): BackendComplaintDeleteObservation {
    val observed = (application as? ComplaintReportApplication.OwnerDelete)?.application
    val retained = observed ?: receipt
    val checkedFailure =
        if (application != null && observed == null) {
            ComplaintReportFailure(
                AppError.Unexpected(RECEIPT_MISMATCH_CODE),
                ComplaintReportBlock.RECONCILIATION_REQUIRED,
            )
        } else {
            failure
        }
    return copy(
        receipt = retained,
        failure = checkedFailure,
        completed = terminal && observed != null,
        conflictObserved = conflictObserved || failure?.isDeleteConflict() == true || retained.isDeleteConflict(),
    )
}

private fun ComplaintReportFailure.isDeleteConflict(): Boolean =
    (error as? AppError.Network.Http)?.statusCode == PRECONDITION_FAILED

private fun ComplaintOwnerDeleteApplication?.isDeleteConflict(): Boolean =
    (this as? ComplaintOwnerDeleteApplication.Rejected)?.code == ComplaintOwnerDeleteReceiptRejection.PRECONDITION_FAILED

private const val PRECONDITION_FAILED = 412

private const val RECEIPT_MISMATCH_CODE = "complaint_delete_receipt_mismatch"
