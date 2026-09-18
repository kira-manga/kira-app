package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/**
 * One memory-only ordinary parent selection, not ownership or mutation authority.
 * The protocol still validates the ID and current parent. No body, tag or notice key is captured.
 */
class ComplaintReplyTarget private constructor(
    internal val parentId: String,
) {
    override fun toString(): String = "ComplaintReplyTarget(redacted)"

    companion object {
        /** Notice threads remain closed until their localization-key catalog is separately admitted. */
        fun capture(detail: ComplaintDetail): ComplaintReplyTarget? {
            val row = (detail as? ComplaintDetail.Owned)?.item ?: return null
            return when (row) {
                is ComplaintOwnerRow.Report,
                is ComplaintOwnerRow.Reply,
                -> if (row.isContractRecognized) ComplaintReplyTarget(row.id) else null
                is ComplaintOwnerRow.NoticeReply -> null
                else -> null
            }
        }
    }
}
