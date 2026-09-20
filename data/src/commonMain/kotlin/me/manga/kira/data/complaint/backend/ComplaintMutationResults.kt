package me.manga.kira.data.complaint.backend

/** Content-free transport failures; none permits pending deletion, identity replacement or implicit retry. */
internal enum class ComplaintMutationFailure { CLOSED, TRANSPORT, TIMEOUT, INVALIDATED, RESPONSE }

/** Content-free terminal receipts. Report decoding never accepts a reply-only rejection. */
internal sealed interface ComplaintCreationRejection {
    val wireCode: String
    val status: Int
}

/** These two terminal receipt outcomes apply to either creation variant. */
internal enum class ComplaintCreateRejection : ComplaintCreationRejection {
    COMPLAINT_CAPACITY_REACHED,
    COMPLAINT_RESOURCE_ID_REUSED,
    ;

    override val wireCode: String get() = name
    override val status: Int get() = CONFLICT
}

/** Hidden and missing parents intentionally share one reply-only outcome. */
internal enum class ComplaintReplyRejection(
    override val status: Int,
) : ComplaintCreationRejection {
    COMPLAINT_PARENT_NOT_FOUND(NOT_FOUND),
    COMPLAINT_DELETION_PENDING(CONFLICT),
    ;

    override val wireCode: String get() = name
}

/** Minimal acknowledgement, not an owner row, action tag, or a current-resource version observation. */
internal class ComplaintCreateAcknowledgement(
    val id: String,
    val version: Long,
    val location: String,
    val etag: String,
) {
    override fun toString(): String = "ComplaintCreateAcknowledgement(redacted)"
}

internal sealed interface ComplaintCreateHttpResult {
    val request: ComplaintCreateHttpRequest

    class Applied(
        override val request: ComplaintCreateHttpRequest,
        val acknowledgement: ComplaintCreateAcknowledgement,
    ) : ComplaintCreateHttpResult {
        override fun toString(): String = "ComplaintCreateHttpResult.Applied(redacted)"
    }

    /** Even a receipt-backed direct409 remains non-terminal here; only matched status may clear evidence. */
    class HttpFailure(
        override val request: ComplaintCreateHttpRequest,
        val status: Int,
        val problem: ComplaintMutationProblem?,
    ) : ComplaintCreateHttpResult {
        override fun toString(): String = "ComplaintCreateHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintCreateHttpRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintCreateHttpResult {
        override fun toString(): String = "ComplaintCreateHttpResult.Failed($reason)"
    }
}

/** Every alternative is bound to the exact immutable sent request, including the minimal no-echo rejection. */
internal sealed interface ComplaintCreateStatusHttpResult {
    val request: ComplaintCreateStatusRequest

    class Applied(
        override val request: ComplaintCreateStatusRequest,
        val acknowledgement: ComplaintCreateAcknowledgement,
    ) : ComplaintCreateStatusHttpResult {
        override fun toString(): String = "ComplaintCreateStatusHttpResult.Applied(redacted)"
    }

    class Rejected(
        override val request: ComplaintCreateStatusRequest,
        val code: ComplaintCreationRejection,
    ) : ComplaintCreateStatusHttpResult {
        override fun toString(): String = "ComplaintCreateStatusHttpResult.Rejected($code)"
    }

    class HttpFailure(
        override val request: ComplaintCreateStatusRequest,
        val status: Int,
        val problem: ComplaintMutationProblem?,
    ) : ComplaintCreateStatusHttpResult {
        override fun toString(): String = "ComplaintCreateStatusHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintCreateStatusRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintCreateStatusHttpResult {
        override fun toString(): String = "ComplaintCreateStatusHttpResult.Failed($reason)"
    }
}

private const val NOT_FOUND = 404
private const val CONFLICT = 409
