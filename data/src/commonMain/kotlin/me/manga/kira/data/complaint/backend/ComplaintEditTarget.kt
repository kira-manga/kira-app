package me.manga.kira.data.complaint.backend

import me.manga.kira.domain.model.complaint.ComplaintOwnerRow

internal enum class ComplaintEditShape { SUBJECT_AND_BODY, BODY_ONLY }

/** Target/tag syntax and recognized shape only. Neither the read projection nor this value proves ownership. */
internal class ComplaintEditTarget private constructor(
    val action: PendingComplaintAction,
    val shape: ComplaintEditShape,
) {
    val id: String get() = action.targetId
    val expectedVersion: Long get() = checkNotNull(action.expectedVersion)
    val precondition: String get() = checkNotNull(action.canonicalPrecondition())

    override fun toString(): String = "ComplaintEditTarget(redacted)"

    companion object {
        fun from(row: ComplaintOwnerRow): ComplaintEditTarget? {
            val content = row as? ComplaintOwnerRow.Content ?: return null
            if (!content.isContractRecognized) return null
            val shape =
                when (content) {
                    is ComplaintOwnerRow.Report, is ComplaintOwnerRow.Reply -> ComplaintEditShape.SUBJECT_AND_BODY
                    is ComplaintOwnerRow.NoticeReply -> ComplaintEditShape.BODY_ONLY
                }
            return checked(content.id, content.fields.version, content.fields.actionTag, shape)
        }

        fun checked(
            id: String,
            version: Long,
            actionTag: String,
            shape: ComplaintEditShape,
        ): ComplaintEditTarget? {
            val action =
                PendingComplaintAction.checked(PendingComplaintOperation.EDIT_CONTENT, id, null, version)
                    ?: return null
            // Backend accepts bounded outer SP/HTAB only. The outgoing request always uses canonicalPrecondition.
            if (actionTag.length > MAX_PRECONDITION_CHARS ||
                actionTag.trim(' ', '\t') != action.canonicalPrecondition()
            ) {
                return null
            }
            return ComplaintEditTarget(action, shape)
        }
    }
}

private const val MAX_PRECONDITION_CHARS = 256
