package me.manga.kira.domain.model.feedback

import me.manga.kira.core.error.AppError

/** A refusal is not permission to replace identity, delete evidence, or submit with another key. */
enum class ComplaintReportBlock {
    MISSING,
    STALE_BINDING,
    CONSENT_PENDING,
    STALE_CONSENT,
    CLEANUP_REQUIRED,
    REMOTE_DELETION_PENDING,
    RECONCILIATION_REQUIRED,
    ACTION_IN_PROGRESS,
    PENDING_CAPACITY_REACHED,
    LIVE_REQUEST_REQUIRED,
    RECEIPT_WINDOW_EXPIRED,
    NOT_UNREADABLE,
    INVALID_CANDIDATE,
}

/** Typed, content-free failure. Diagnostic rendering deliberately excludes even nested error causes. */
class ComplaintReportFailure(
    val error: AppError,
    val block: ComplaintReportBlock? = null,
) {
    override fun toString(): String = "ComplaintReportFailure(redacted)"
}

/** Terminal creation receipt, including reply observations in the shared metadata-only recovery pass. */
enum class ComplaintReportReceiptRejection {
    COMPLAINT_CAPACITY_REACHED,
    COMPLAINT_RESOURCE_ID_REUSED,
    COMPLAINT_PARENT_NOT_FOUND,
    COMPLAINT_DELETION_PENDING,
}

/** Acknowledgement facts only; an applied ID/version is not a current history row or edit/delete tag. */
sealed interface ComplaintReportApplication {
    class Applied(
        val id: String,
        val version: Long,
    ) : ComplaintReportApplication {
        override fun toString(): String = "ComplaintReportApplication.Applied(redacted)"
    }

    data class Rejected(
        val code: ComplaintReportReceiptRejection,
    ) : ComplaintReportApplication
}

/** Observed local phase only. The repository rechecks current durable state for every action. */
enum class ComplaintReportPhase { PREPARED, MAY_HAVE_DISPATCHED }

/** Opaque pending observation suitable for an explicit cancel or warned local-reset request. */
class ComplaintReportPending(
    val handle: ComplaintPendingReport,
    val phase: ComplaintReportPhase,
) {
    override fun toString(): String = "ComplaintReportPending(redacted)"
}

/** Ambiguity and local cleanup failure remain distinct from a terminal completed operation. */
sealed interface ComplaintReportAttempt {
    class Completed(
        val application: ComplaintReportApplication,
    ) : ComplaintReportAttempt {
        override fun toString(): String = "ComplaintReportAttempt.Completed(redacted)"
    }

    /** [knownApplication] may be applied/rejected even though durable pending cleanup failed. */
    class Unresolved(
        val failure: ComplaintReportFailure,
        val knownApplication: ComplaintReportApplication? = null,
        val pending: ComplaintReportPending? = null,
    ) : ComplaintReportAttempt {
        override fun toString(): String = "ComplaintReportAttempt.Unresolved(redacted)"
    }
}

/** One metadata-only observation, not reconstructed prose or an authoritative inventory member. */
class ComplaintReportObservation(
    val pending: ComplaintReportPending,
    val attempt: ComplaintReportAttempt,
) {
    override fun toString(): String = "ComplaintReportObservation(redacted)"
}

/** Bounded observations from one pass. An empty list is not new-mutation or clean-inventory authority. */
class ComplaintReportRecovery(
    observations: List<ComplaintReportObservation>,
    val stopped: ComplaintReportFailure? = null,
) {
    private val content = observations.toList()

    /** Returns a defensive copy; observations and handles are immutable, process-local values. */
    fun entries(): List<ComplaintReportObservation> = content.toList()

    override fun toString(): String = "ComplaintReportRecovery(redacted)"
}

/** The first submission includes its bounded pre-admission recovery observations. */
class ComplaintReportSubmission(
    val attempt: ComplaintReportAttempt,
    val recovery: ComplaintReportRecovery,
) {
    override fun toString(): String = "ComplaintReportSubmission(redacted)"
}
