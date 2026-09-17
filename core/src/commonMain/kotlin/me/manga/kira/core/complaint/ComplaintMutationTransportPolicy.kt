package me.manga.kira.core.complaint

/** Shared W08 wire constants, not a client, dispatcher, activation flag or fingerprint implementation. */
object ComplaintMutationTransportPolicy {
    /** Logical CREATE route; an explicitly checked deployment prefix may precede it. */
    const val CREATE_PATH = "/api/v1/complaints"

    /** Observational operation-status route under the same checked deployment prefix. */
    const val STATUS_PATH = "/api/v1/complaint-operations/status"

    /** Exactly one canonical lower-case v4 UUID on CREATE; forbidden on status requests. */
    const val IDEMPOTENCY_HEADER = "X-Kira-Idempotency-Key"

    /** Raw outgoing request limit, before JSON parsing or native dispatch. */
    const val MAX_REQUEST_BYTES = 16 * 1_024

    /** Only a direct CREATE201 JSON acknowledgement can use this larger response limit. */
    const val MAX_CREATE_ACKNOWLEDGEMENT_BYTES = 32 * 1_024

    /** Every operation-status response and every owner-facing problem has this smaller limit. */
    const val MAX_STATUS_OR_PROBLEM_BYTES = 16 * 1_024
}
