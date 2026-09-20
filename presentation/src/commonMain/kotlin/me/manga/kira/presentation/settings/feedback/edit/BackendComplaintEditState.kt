package me.manga.kira.presentation.settings.feedback.edit

import me.manga.kira.domain.model.feedback.ComplaintEditApplication
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.domain.model.feedback.ComplaintReportField
import me.manga.kira.presentation.mvi.MviState

/** One opening can prepare one live edit; terminal/closed states cannot start a replacement. */
enum class BackendComplaintEditActivity { UNAVAILABLE, EDITING, WORKING, LIVE, TERMINAL, CLOSED }

/** Memory-only replacement text. A null subject denotes a typed body-only notice reply. */
data class BackendComplaintEditText(
    val subject: String? = null,
    val body: String = "",
) {
    override fun toString(): String = "BackendComplaintEditText(redacted)"
}

/** Original edit receipt and observations only, never a current row, fresh tag or retry permission. */
data class BackendComplaintEditObservation(
    val receipt: ComplaintEditApplication? = null,
    val failure: ComplaintReportFailure? = null,
    val completed: Boolean = false,
    val conflictObserved: Boolean = false,
    val recoveryObserved: Boolean = false,
) {
    val cleanupPending: Boolean get() = receipt != null && !completed

    override fun toString(): String = "BackendComplaintEditObservation(redacted)"
}

/** Redacted, unsaved UI state; the original target/tag and opaque live handle stay private to the VM. */
data class BackendComplaintEditState(
    val draft: BackendComplaintEditText = BackendComplaintEditText(),
    val activity: BackendComplaintEditActivity = BackendComplaintEditActivity.UNAVAILABLE,
    val result: BackendComplaintEditObservation = BackendComplaintEditObservation(),
    val invalidField: ComplaintReportField? = null,
) : MviState {
    val editable: Boolean get() = activity == BackendComplaintEditActivity.EDITING
    val busy: Boolean get() = activity == BackendComplaintEditActivity.WORKING
    val canRetry: Boolean get() = activity == BackendComplaintEditActivity.LIVE
    val closed: Boolean get() = activity == BackendComplaintEditActivity.CLOSED
    val hasDraft: Boolean get() = activity != BackendComplaintEditActivity.UNAVAILABLE && !closed

    override fun toString(): String = "BackendComplaintEditState(redacted)"
}
