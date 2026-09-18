package me.manga.kira.presentation.settings.feedback.edit

import me.manga.kira.core.error.AppError
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintEditReceiptRejection
import me.manga.kira.domain.model.feedback.ComplaintReportApplication
import me.manga.kira.domain.model.feedback.ComplaintReportAttempt
import me.manga.kira.domain.model.feedback.ComplaintReportBlock
import me.manga.kira.domain.model.feedback.ComplaintReportFailure

/** Shape recognition only. The existing edit port still validates target/tag and every authority fence. */
internal fun ComplaintOwnerRow.backendEditContent(): ComplaintOwnerRow.Content? =
    (this as? ComplaintOwnerRow.Content)?.takeIf { it.isContractRecognized }

internal fun ComplaintOwnerRow.backendEditInitialState(): BackendComplaintEditState {
    val content = backendEditContent() ?: return BackendComplaintEditState()
    val subject =
        when (content) {
            is ComplaintOwnerRow.Report -> content.subject
            is ComplaintOwnerRow.Reply -> content.subject
            is ComplaintOwnerRow.NoticeReply -> null
        }
    return BackendComplaintEditState(
        draft = BackendComplaintEditText(subject, content.fields.body),
        activity = BackendComplaintEditActivity.EDITING,
    )
}

/** Direct412 is a retained observation, not a terminal receipt or permission to refresh/rebase. */
internal fun BackendComplaintEditObservation.afterFailure(
    failure: ComplaintReportFailure,
): BackendComplaintEditObservation =
    copy(
        failure = failure,
        conflictObserved = conflictObserved || failure.isEditConflict(),
    )

internal fun BackendComplaintEditObservation.afterAttempt(
    attempt: ComplaintReportAttempt,
): BackendComplaintEditObservation =
    when (attempt) {
        is ComplaintReportAttempt.Completed -> withApplication(attempt.application, null, terminal = true)
        is ComplaintReportAttempt.Unresolved -> withApplication(attempt.knownApplication, attempt.failure, terminal = false)
    }

/** Unlike mixed Settings recovery, an edit panel must never accept a creation/delete receipt as its own. */
private fun BackendComplaintEditObservation.withApplication(
    application: ComplaintReportApplication?,
    failure: ComplaintReportFailure?,
    terminal: Boolean,
): BackendComplaintEditObservation {
    val observed = (application as? ComplaintReportApplication.Edit)?.application
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
        conflictObserved = conflictObserved || failure?.isEditConflict() == true || retained.isEditConflict(),
    )
}

private fun ComplaintReportFailure.isEditConflict(): Boolean =
    (error as? AppError.Network.Http)?.statusCode == PRECONDITION_FAILED

private fun ComplaintEditApplication?.isEditConflict(): Boolean =
    (this as? ComplaintEditApplication.Rejected)?.code == ComplaintEditReceiptRejection.PRECONDITION_FAILED

private const val PRECONDITION_FAILED = 412
private const val RECEIPT_MISMATCH_CODE = "complaint_edit_receipt_mismatch"
