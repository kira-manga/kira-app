package me.manga.kira.domain.model.feedback

import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/** One read projection for target/tag validation only. It is not ownership or deletion authority. */
class ComplaintOwnerDeleteDraft(
    val target: ComplaintOwnerRow,
) {
    override fun toString(): String = "ComplaintOwnerDeleteDraft(redacted)"
}

/** Original issuer-bound, process-local single deletion. Cold metadata cannot reconstruct it. */
interface ComplaintLiveOwnerDelete

/** Preparation requires an existing installation and captures only one key, never prose or a new content ID. */
sealed interface ComplaintOwnerDeletePreparation {
    class Ready(
        val deletion: ComplaintLiveOwnerDelete,
    ) : ComplaintOwnerDeletePreparation {
        override fun toString(): String = "ComplaintOwnerDeletePreparation.Ready(redacted)"
    }

    class Blocked(
        val failure: ComplaintReportFailure,
    ) : ComplaintOwnerDeletePreparation {
        override fun toString(): String = "ComplaintOwnerDeletePreparation.Blocked(redacted)"
    }
}
