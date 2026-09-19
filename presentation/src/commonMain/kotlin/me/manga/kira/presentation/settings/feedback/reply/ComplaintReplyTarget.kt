package me.manga.kira.presentation.settings.feedback.reply

import me.manga.kira.domain.model.complaint.BackendNoticeKey
import me.manga.kira.domain.model.complaint.ComplaintDetail
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

/**
 * One memory-only parent selection, not ownership or mutation authority.
 * The protocol still validates the ID and current parent. No body, tag or notice key is captured.
 */
class ComplaintReplyTarget private constructor(
    internal val parentId: String,
) {
    override fun toString(): String = "ComplaintReplyTarget(redacted)"

    companion object {
        /** Known notice threads and recognized owned content only; the selected child's ID stays its own. */
        fun capture(detail: ComplaintDetail): ComplaintReplyTarget? {
            val parentId =
                when (detail) {
                    is ComplaintDetail.Notice ->
                        detail.item.takeIf { BackendNoticeKey.fromKey(it.noticeKey) != null }?.id
                    is ComplaintDetail.Owned ->
                        detail.item.takeIf { row ->
                            when (row) {
                                is ComplaintOwnerRow.NoticeReply ->
                                    row.isContractRecognized && BackendNoticeKey.fromKey(row.noticeKey) != null
                                is ComplaintOwnerRow.Report, is ComplaintOwnerRow.Reply -> row.isContractRecognized
                                else -> false
                            }
                        }?.id
                    ComplaintDetail.Unavailable -> null
                }
            return parentId?.let(::ComplaintReplyTarget)
        }
    }
}
