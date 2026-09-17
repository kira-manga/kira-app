package me.manga.kira.data.complaint.backend

/** Content-free transport failures; none permits pending deletion, identity replacement or implicit retry. */
internal enum class ComplaintMutationFailure { CLOSED, TRANSPORT, TIMEOUT, INVALIDATED, RESPONSE }

/** Only these two CREATE terminal receipt outcomes are allowed by the frozen protocol. */
internal enum class ComplaintCreateRejection { COMPLAINT_CAPACITY_REACHED, COMPLAINT_RESOURCE_ID_REUSED }

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
        val code: ComplaintCreateRejection,
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
