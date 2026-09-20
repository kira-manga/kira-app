package me.manga.kira.domain.model.feedback

import me.manga.kira.domain.model.complaint.ComplaintType

/** In-memory user input only. Never persist this draft or use its diagnostic string as content. */
data class ComplaintReportDraft(
    val type: ComplaintType = ComplaintType.TECHNICAL,
    val subject: String = "",
    val body: String = "",
) {
    override fun toString(): String = "ComplaintReportDraft(redacted)"
}

/** Issuer-bound, live-only normalized request. Not serializable, reconstructible or dispatch authority. */
interface ComplaintLiveReport

/** Issuer-bound observation of one exact pending record, never permission to delete or resend it. */
interface ComplaintPendingReport

/** Exact process-local local-reset prompt; confirming it does not erase anything on the server. */
interface ComplaintRecoveryPrompt

/** Validation fields share the report producer's existing normalization; there is no UI-side rewriter. */
enum class ComplaintReportField { SUBJECT, BODY, APP_VERSION, OS_VERSION, MANUFACTURER, DEVICE_MODEL }

/** Content-free rejection reasons from the existing normalized report producer. */
enum class ComplaintReportRejection {
    REQUIRED,
    TOO_SHORT,
    TOO_LONG,
    FORBIDDEN_CONTROL,
    MALFORMED_UNICODE,
    NORMALIZATION_MISMATCH,
}

/** Preparing observes an existing installation only; it does not enroll, persist, or dispatch. */
sealed interface ComplaintReportPreparation {
    class Ready(
        val report: ComplaintLiveReport,
    ) : ComplaintReportPreparation {
        override fun toString(): String = "ComplaintReportPreparation.Ready(redacted)"
    }

    data class Invalid(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintReportPreparation

    class Blocked(
        val failure: ComplaintReportFailure,
    ) : ComplaintReportPreparation {
        override fun toString(): String = "ComplaintReportPreparation.Blocked(redacted)"
    }
}
