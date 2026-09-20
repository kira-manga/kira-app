package me.manga.kira.data.complaint.backend

/** Edit-only terminal status cells. No creation capacity/ID/parent outcome enters this vocabulary. */
internal enum class ComplaintEditRejection(
    val status: Int,
) {
    COMPLAINT_NOT_FOUND(NOT_FOUND),
    COMPLAINT_INVALID_TRANSITION(CONFLICT),
    COMPLAINT_NO_CHANGE(CONFLICT),
    COMPLAINT_DELETION_PENDING(CONFLICT),
    PRECONDITION_FAILED(PRECONDITION),
}

/** Original edit200 ACK; not current content or a reusable action tag, and deliberately no Location. */
internal class ComplaintEditAcknowledgement(
    val id: String,
    val version: Long,
    val etag: String,
) {
    override fun toString(): String = "ComplaintEditAcknowledgement(redacted)"
}

internal sealed interface ComplaintEditHttpResult {
    val request: ComplaintEditHttpRequest

    class Applied(
        override val request: ComplaintEditHttpRequest,
        val acknowledgement: ComplaintEditAcknowledgement,
    ) : ComplaintEditHttpResult {
        override fun toString(): String = "ComplaintEditHttpResult.Applied(redacted)"
    }

    /** Every direct error remains uncertain, even a receipt-backed no-change/stale-precondition response. */
    class HttpFailure(
        override val request: ComplaintEditHttpRequest,
        val status: Int,
        val problem: ComplaintEditProblem?,
    ) : ComplaintEditHttpResult {
        override fun toString(): String = "ComplaintEditHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintEditHttpRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintEditHttpResult {
        override fun toString(): String = "ComplaintEditHttpResult.Failed($reason)"
    }
}

/** Each alternative is bound to the exact sent status request, including a minimal no-echo rejection. */
internal sealed interface ComplaintEditStatusHttpResult {
    val request: ComplaintEditStatusRequest

    class Applied(
        override val request: ComplaintEditStatusRequest,
        val acknowledgement: ComplaintEditAcknowledgement,
    ) : ComplaintEditStatusHttpResult {
        override fun toString(): String = "ComplaintEditStatusHttpResult.Applied(redacted)"
    }

    class Rejected(
        override val request: ComplaintEditStatusRequest,
        val code: ComplaintEditRejection,
    ) : ComplaintEditStatusHttpResult {
        override fun toString(): String = "ComplaintEditStatusHttpResult.Rejected($code)"
    }

    class HttpFailure(
        override val request: ComplaintEditStatusRequest,
        val status: Int,
        val problem: ComplaintEditProblem?,
    ) : ComplaintEditStatusHttpResult {
        override fun toString(): String = "ComplaintEditStatusHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintEditStatusRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintEditStatusHttpResult {
        override fun toString(): String = "ComplaintEditStatusHttpResult.Failed($reason)"
    }
}

private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val PRECONDITION = 412
