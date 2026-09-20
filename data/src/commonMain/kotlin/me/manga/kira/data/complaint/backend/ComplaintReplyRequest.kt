package me.manga.kira.data.complaint.backend

internal sealed interface ComplaintReplyRequestResult {
    class Accepted(
        val request: ComplaintReplyRequest,
    ) : ComplaintReplyRequestResult {
        override fun toString(): String = "ComplaintReplyRequest.Accepted(redacted)"
    }

    class Rejected(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintReplyRequestResult {
        override fun toString(): String = "ComplaintReplyRequest.Rejected($field,$reason)"
    }

    data object InvalidParent : ComplaintReplyRequestResult
}

/** Live diagnostics/body only. The server's locked parent supplies type/subject/notice inheritance. */
internal class ComplaintReplyRequest private constructor(
    override val identity: ComplaintReportIdentity,
    val parentId: String,
    override val body: String,
    override val metadata: ComplaintReportMetadata,
) : ComplaintCreationRequest {
    override val operation: ComplaintReportOperation get() = ComplaintReportOperation.OWNER_REPLY

    override fun toString(): String = "ComplaintReplyRequest(redacted)"

    companion object {
        fun normalize(
            identity: ComplaintReportIdentity,
            parentId: String,
            body: String,
            metadata: ComplaintReportMetadataInput,
        ): ComplaintReplyRequestResult {
            val action =
                PendingComplaintAction.checked(
                    PendingComplaintOperation.CREATE_REPLY,
                    identity.clientId.canonical,
                    parentId,
                    null,
                ) ?: return ComplaintReplyRequestResult.InvalidParent
            return normalized(identity, checkNotNull(action.parentId), body, metadata)
        }

        private fun normalized(
            identity: ComplaintReportIdentity,
            parentId: String,
            body: String,
            metadata: ComplaintReportMetadataInput,
        ): ComplaintReplyRequestResult =
            try {
                ComplaintReplyRequestResult.Accepted(
                    ComplaintReplyRequest(
                        identity,
                        parentId,
                        ComplaintReportTextRules.replyBody(body),
                        ComplaintReportMetadata.normalize(metadata),
                    ),
                )
            } catch (failure: ComplaintReportTextRejected) {
                ComplaintReplyRequestResult.Rejected(failure.field, failure.reason)
            }
    }
}
