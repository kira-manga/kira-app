package me.manga.kira.data.complaint.backend

internal sealed interface ComplaintEditRequestResult {
    class Accepted(
        val request: ComplaintEditRequest,
    ) : ComplaintEditRequestResult {
        override fun toString(): String = "ComplaintEditRequest.Accepted(redacted)"
    }

    class Rejected(
        val field: ComplaintReportField,
        val reason: ComplaintReportRejection,
    ) : ComplaintEditRequestResult {
        override fun toString(): String = "ComplaintEditRequest.Rejected($field,$reason)"
    }

    data object InvalidCandidate : ComplaintEditRequestResult
}

/** Live-only replacements. No creation ID, parent prose, diagnostics, moderation fields or refreshed tag. */
internal class ComplaintEditRequest private constructor(
    val target: ComplaintEditTarget,
    val key: ComplaintReportKey,
    val dataScope: ComplaintReportScope,
    val subject: String?,
    val body: String,
) : ComplaintOwnerRequest {
    val dataScopeId: String get() = dataScope.value

    fun pendingFingerprint(): PendingComplaintFingerprint =
        checkNotNull(PendingComplaintFingerprint.checked(1, ComplaintEditFingerprint.of(this).encoded))

    override fun toString(): String = "ComplaintEditRequest(redacted)"

    companion object {
        fun normalize(
            target: ComplaintEditTarget,
            key: String,
            dataScopeId: String,
            subject: String?,
            body: String,
        ): ComplaintEditRequestResult {
            val checkedKey = ComplaintReportKey.checked(key) ?: return ComplaintEditRequestResult.InvalidCandidate
            val scope = ComplaintReportScope.checked(dataScopeId) ?: return ComplaintEditRequestResult.InvalidCandidate
            if (target.shape == ComplaintEditShape.BODY_ONLY && subject != null) {
                return ComplaintEditRequestResult.InvalidCandidate
            }
            return normalized(target, checkedKey, scope, subject, body)
        }

        private fun normalized(
            target: ComplaintEditTarget,
            key: ComplaintReportKey,
            scope: ComplaintReportScope,
            subject: String?,
            body: String,
        ): ComplaintEditRequestResult =
            try {
                ComplaintEditRequestResult.Accepted(
                    ComplaintEditRequest(
                        target,
                        key,
                        scope,
                        normalizeSubject(target, subject),
                        ComplaintReportTextRules.editBody(body),
                    ),
                )
            } catch (failure: ComplaintReportTextRejected) {
                ComplaintEditRequestResult.Rejected(failure.field, failure.reason)
            }

        private fun normalizeSubject(
            target: ComplaintEditTarget,
            subject: String?,
        ): String? =
            when (target.shape) {
                ComplaintEditShape.BODY_ONLY -> null
                ComplaintEditShape.SUBJECT_AND_BODY ->
                    ComplaintReportTextRules.normalize(subject.orEmpty(), ComplaintReportField.SUBJECT)
            }
    }
}
