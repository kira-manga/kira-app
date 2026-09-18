package me.manga.kira.data.complaint.backend

/** Two fresh public UUIDv4 values, checked by the existing report identity rules during preparation. */
class ComplaintReportIdentifiers(
    val clientId: String,
    val idempotencyKey: String,
) {
    override fun toString(): String = "ComplaintReportIdentifiers(redacted)"
}

/**
 * Inert platform suppliers. Only explicit report/reply preparation invokes these, once and outside the
 * credential mutex; neither graph construction nor retry reads platform metadata or allocates IDs.
 */
class ComplaintReportInputs(
    val identifiers: () -> ComplaintReportIdentifiers,
    val metadata: () -> ComplaintReportMetadataInput,
) {
    override fun toString(): String = "ComplaintReportInputs(redacted)"
}
