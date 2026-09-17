package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

/** Zero-byte 202/204 or a bounded problem, counted before forwarding into Ktor's receive queue. */
internal class ComplaintDeletionReceiveBudget private constructor(
    private val maximum: Int,
    private val declaredLength: Int?,
) : ComplaintReceiveBudget {
    override var receivedBytes: Int = 0
        private set

    override val remainingBytes: Int get() = maximum - receivedBytes

    override fun accept(byteCount: ULong): Boolean {
        if (byteCount > remainingBytes.toULong()) return false
        receivedBytes += byteCount.toInt()
        return true
    }

    override fun isComplete(): Boolean = declaredLength == null || receivedBytes == declaredLength

    companion object {
        private const val ACCEPTED = 202L
        private const val NO_CONTENT = 204L
        private const val MIN_ERROR = 400L
        private const val MAX_ERROR = 599L
        private const val MAX_HEADER_CHARACTERS = 128
        private val JSON = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)
        private val PROBLEM =
            Regex("application/problem\\+json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        fun checked(
            status: Long,
            headers: ComplaintDeletionResponseHeaders,
        ): ComplaintDeletionReceiveBudget? {
            val empty = status == ACCEPTED || status == NO_CONTENT
            if ((!empty && status !in MIN_ERROR..MAX_ERROR) || !validHeaders(headers, empty)) return null
            val maximum = if (empty) 0 else Policy.MAX_PROBLEM_BYTES
            val length = headers.length.singleOrNull() ?: return ComplaintDeletionReceiveBudget(maximum, null)
            return length
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..maximum }
                ?.let { ComplaintDeletionReceiveBudget(maximum, it) }
        }

        private fun validHeaders(
            headers: ComplaintDeletionResponseHeaders,
            empty: Boolean,
        ): Boolean =
            with(headers) {
                listOf(media, encoding, length, transfer).all(::singleBoundedHeader) &&
                    (encoding.isEmpty() || encoding.single().equals("identity", ignoreCase = true)) &&
                    (transfer.isEmpty() ||
                        length.isEmpty() && transfer.single().equals("chunked", ignoreCase = true)) &&
                    validMedia(media, empty)
            }

        private fun validMedia(
            media: List<String>,
            empty: Boolean,
        ): Boolean =
            if (empty) {
                media.isEmpty() || JSON.matches(media.single())
            } else {
                media.size == 1 && PROBLEM.matches(media.single())
            }

        private fun singleBoundedHeader(values: List<String>): Boolean =
            values.size <= 1 &&
                values.all { value ->
                    value.length <= MAX_HEADER_CHARACTERS && value.all { it in ' '..'~' || it == '\t' }
                }
    }
}

/** Separate selected lists preserve duplicate/framing errors instead of normalizing them. */
internal class ComplaintDeletionResponseHeaders(
    val media: List<String>,
    val encoding: List<String>,
    val length: List<String>,
    val transfer: List<String>,
) {
    override fun toString(): String = "ComplaintDeletionResponseHeaders(redacted)"
}
