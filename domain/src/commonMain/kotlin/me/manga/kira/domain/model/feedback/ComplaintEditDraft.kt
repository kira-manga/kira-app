package me.manga.kira.domain.model.feedback

import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/**
 * Live replacement text and one backend read projection. The projection is not mutation authority.
 * Ordinary content requires [subject]; notice-thread replies require its absence. Never persist this draft.
 */
class ComplaintEditDraft(
    val target: ComplaintOwnerRow,
    val subject: String?,
    val body: String,
) {
    override fun toString(): String = "ComplaintEditDraft(redacted)"
}

/** Issuer-bound, process-local edit. It retains the original target/tag/key; it cannot be reconstructed. */
interface ComplaintLiveEdit

/** Preparing requires an existing installation; it neither enrolls nor persists or dispatches an edit. */
sealed interface ComplaintEditPreparation {
    class Ready(
        val edit: ComplaintLiveEdit,
    ) : ComplaintEditPreparation {
        override fun toString(): String = "ComplaintEditPreparation.Ready(redacted)"
    }

    data class Invalid(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintEditPreparation

    class Blocked(
        val failure: ComplaintReportFailure,
    ) : ComplaintEditPreparation {
        override fun toString(): String = "ComplaintEditPreparation.Blocked(redacted)"
    }
}
