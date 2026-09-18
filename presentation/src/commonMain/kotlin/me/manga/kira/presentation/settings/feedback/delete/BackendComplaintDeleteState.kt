package me.manga.kira.presentation.settings.feedback.delete

import me.manga.kira.domain.model.feedback.ComplaintOwnerDeleteApplication
import me.manga.kira.domain.model.feedback.ComplaintReportFailure
import me.manga.kira.presentation.mvi.MviState

/** One single-target operation, not installation deletion. No replacement after confirmation. */
enum class BackendComplaintDeleteActivity { UNAVAILABLE, CONFIRMING, WORKING, LIVE, TERMINAL, CLOSED }

/** Memory-only display of the original target; never a tag, identity or mutation capability. */
data class BackendComplaintDeletePreview(
    val subject: String? = null,
    val body: String = "",
) {
    override fun toString(): String = "BackendComplaintDeletePreview(redacted)"
}

/** Original single-delete receipt and conflict/cleanup observations, never proof of current row state. */
data class BackendComplaintDeleteObservation(
    val receipt: ComplaintOwnerDeleteApplication? = null,
    val failure: ComplaintReportFailure? = null,
    val completed: Boolean = false,
    val conflictObserved: Boolean = false,
    val recoveryObserved: Boolean = false,
) {
    val cleanupPending: Boolean get() = receipt != null && !completed

    override fun toString(): String = "BackendComplaintDeleteObservation(redacted)"
}

/** Unsaved display state; the captured tag and opaque live operation stay private to this opening. */
data class BackendComplaintDeleteState(
    val preview: BackendComplaintDeletePreview = BackendComplaintDeletePreview(),
    val activity: BackendComplaintDeleteActivity = BackendComplaintDeleteActivity.UNAVAILABLE,
    val result: BackendComplaintDeleteObservation = BackendComplaintDeleteObservation(),
) : MviState {
    val canConfirm: Boolean get() = activity == BackendComplaintDeleteActivity.CONFIRMING
    val busy: Boolean get() = activity == BackendComplaintDeleteActivity.WORKING
    val canRetry: Boolean get() = activity == BackendComplaintDeleteActivity.LIVE
    val closed: Boolean get() = activity == BackendComplaintDeleteActivity.CLOSED
    val hasTarget: Boolean get() = activity != BackendComplaintDeleteActivity.UNAVAILABLE && !closed

    override fun toString(): String = "BackendComplaintDeleteState(redacted)"
}
