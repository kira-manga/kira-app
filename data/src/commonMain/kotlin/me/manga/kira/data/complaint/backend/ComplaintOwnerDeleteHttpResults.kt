package me.manga.kira.data.complaint.backend

/** Only the independently reviewed single-delete terminal status cells. */
internal enum class ComplaintOwnerDeleteRejection(
    val status: Int,
) {
    COMPLAINT_NOT_FOUND(NOT_FOUND),
    COMPLAINT_DELETION_PENDING(CONFLICT),
    PRECONDITION_FAILED(PRECONDITION),
}

/** Each204/error is tied to the exact sent request; no synthetic id/version/tag acknowledgement exists. */
internal sealed interface ComplaintOwnerDeleteHttpResult {
    val request: ComplaintOwnerDeleteHttpRequest

    class Applied(
        override val request: ComplaintOwnerDeleteHttpRequest,
    ) : ComplaintOwnerDeleteHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteHttpResult.Applied(redacted)"
    }

    /** Every direct error remains uncertain, including receipt-backed not-found or stale-precondition. */
    class HttpFailure(
        override val request: ComplaintOwnerDeleteHttpRequest,
        val status: Int,
        val problem: ComplaintOwnerDeleteProblem?,
    ) : ComplaintOwnerDeleteHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintOwnerDeleteHttpRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintOwnerDeleteHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteHttpResult.Failed($reason)"
    }
}

/** Minimal no-echo status outcomes still retain their exact original status request object. */
internal sealed interface ComplaintOwnerDeleteStatusHttpResult {
    val request: ComplaintOwnerDeleteStatusRequest

    class Applied(
        override val request: ComplaintOwnerDeleteStatusRequest,
    ) : ComplaintOwnerDeleteStatusHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteStatusHttpResult.Applied(redacted)"
    }

    class Rejected(
        override val request: ComplaintOwnerDeleteStatusRequest,
        val code: ComplaintOwnerDeleteRejection,
    ) : ComplaintOwnerDeleteStatusHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteStatusHttpResult.Rejected($code)"
    }

    class HttpFailure(
        override val request: ComplaintOwnerDeleteStatusRequest,
        val status: Int,
        val problem: ComplaintOwnerDeleteProblem?,
    ) : ComplaintOwnerDeleteStatusHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteStatusHttpResult.HttpFailure($status,$problem)"
    }

    class Failed(
        override val request: ComplaintOwnerDeleteStatusRequest,
        val reason: ComplaintMutationFailure,
    ) : ComplaintOwnerDeleteStatusHttpResult {
        override fun toString(): String = "ComplaintOwnerDeleteStatusHttpResult.Failed($reason)"
    }
}

private const val NOT_FOUND = 404
private const val CONFLICT = 409
private const val PRECONDITION = 412
