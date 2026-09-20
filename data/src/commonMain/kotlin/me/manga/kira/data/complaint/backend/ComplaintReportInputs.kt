package me.manga.kira.data.complaint.backend

/** Two fresh public UUIDv4 values, checked by the existing report identity rules during preparation. */
class ComplaintReportIdentifiers(
    val clientId: String,
    val idempotencyKey: String,
) {
    override fun toString(): String = "ComplaintReportIdentifiers(redacted)"
}

/**
 * Inert platform suppliers invoked once outside the credential mutex, never on graph construction/retry.
 * Report/reply uses the pair/diagnostics; edit and single-delete use only [editKey], never a new content ID.
 * A missing key-only supplier fails closed and does not fall back to invoking [identifiers].
 */
class ComplaintReportInputs(
    val identifiers: () -> ComplaintReportIdentifiers,
    val metadata: () -> ComplaintReportMetadataInput,
    val editKey: (() -> String)? = null,
) {
    override fun toString(): String = "ComplaintReportInputs(redacted)"
}
