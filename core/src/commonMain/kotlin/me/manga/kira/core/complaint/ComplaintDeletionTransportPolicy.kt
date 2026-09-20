package me.manga.kira.core.complaint

/** Frozen installation delete-all wire bounds; not a request, credential or activation authority. */
object ComplaintDeletionTransportPolicy {
    const val PATH = "/api/v1/installations/delete-all"
    const val IDEMPOTENCY_HEADER = "X-Kira-Idempotency-Key"
    const val MAX_REQUEST_BYTES = 4 * 1_024
    const val MAX_PROBLEM_BYTES = 16 * 1_024
    const val MIN_RETRY_SECONDS = 1
    const val MAX_RETRY_SECONDS = 60
}
