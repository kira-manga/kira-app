package me.manga.kira.data.complaint.backend

/** Live target/key/tag only. A bodyless request still cannot be reconstructed from a pending slot. */
internal class ComplaintOwnerDeleteRequest private constructor(
    val action: PendingComplaintAction,
    val key: ComplaintReportKey,
    private val dataScope: ComplaintReportScope,
) : ComplaintOwnerRequest {
    val dataScopeId: String get() = dataScope.value
    val targetId: String get() = action.targetId
    val precondition: String get() = checkNotNull(action.canonicalPrecondition())

    fun pendingFingerprint(): PendingComplaintFingerprint =
        checkNotNull(PendingComplaintFingerprint.checked(1, ComplaintOwnerDeleteFingerprint.of(this).encoded))

    override fun toString(): String = "ComplaintOwnerDeleteRequest(redacted)"

    companion object {
        /** Reuse pure recognized-row/tag grammar only; retain no edit request, projection, shape or prose. */
        fun checked(
            target: ComplaintEditTarget,
            key: String,
            dataScopeId: String,
        ): ComplaintOwnerDeleteRequest? {
            val checkedKey = ComplaintReportKey.checked(key) ?: return null
            val scope = ComplaintReportScope.checked(dataScopeId) ?: return null
            val action =
                PendingComplaintAction.checked(
                    PendingComplaintOperation.DELETE_OWNED,
                    target.id,
                    null,
                    target.expectedVersion,
                ) ?: return null
            return ComplaintOwnerDeleteRequest(action, checkedKey, scope)
        }
    }
}
