package me.manga.kira.domain.model.feedback

/** Live input only. Parent identity is not ownership proof; never persist draft or parent prose. */
data class ComplaintReplyDraft(
    val parentId: String,
    val body: String,
) {
    override fun toString(): String = "ComplaintReplyDraft(redacted)"
}

/** Exact issuer-bound live reply, separate from report handles and never serialization/dispatch authority. */
interface ComplaintLiveReply

/** Preparing observes an existing installation; it does not enroll, persist or send the reply. */
sealed interface ComplaintReplyPreparation {
    class Ready(
        val reply: ComplaintLiveReply,
    ) : ComplaintReplyPreparation {
        override fun toString(): String = "ComplaintReplyPreparation.Ready(redacted)"
    }

    data class Invalid(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintReplyPreparation

    class Blocked(
        val failure: ComplaintReportFailure,
    ) : ComplaintReplyPreparation {
        override fun toString(): String = "ComplaintReplyPreparation.Blocked(redacted)"
    }
}
