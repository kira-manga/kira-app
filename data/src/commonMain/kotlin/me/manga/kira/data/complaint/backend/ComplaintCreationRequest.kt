package me.manga.kira.data.complaint.backend

/** Only the two normalized owner creations implement this wire contract; edit has a separate sibling. */
internal sealed interface ComplaintCreationRequest : ComplaintOwnerRequest {
    val identity: ComplaintReportIdentity
    val operation: ComplaintReportOperation
    val body: String
    val metadata: ComplaintReportMetadata
}

/** Pure recomputation, never durable-slot, credential or dispatch authority. */
internal fun ComplaintCreationRequest.pendingFingerprint(): PendingComplaintFingerprint {
    val encoded =
        when (this) {
            is ComplaintReportRequest -> ComplaintReportFingerprint.of(this).encoded
            is ComplaintReplyRequest -> ComplaintReplyFingerprint.of(this).encoded
        }
    return checkNotNull(PendingComplaintFingerprint.checked(1, encoded))
}
