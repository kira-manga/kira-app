package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Only direct report/reply201 JSON can exceed the accepted installation/problem budget. */
internal class ComplaintMutationReceiveBudget private constructor(
    private val declaredLength: Int?,
) : ComplaintReceiveBudget {
    override var receivedBytes: Int = 0
        private set

    override val remainingBytes: Int get() = Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES - receivedBytes

    override fun accept(byteCount: ULong): Boolean {
        if (byteCount > remainingBytes.toULong()) return false
        receivedBytes += byteCount.toInt()
        return true
    }

    override fun isComplete(): Boolean = declaredLength == null || receivedBytes == declaredLength

    companion object {
        private const val CREATED = 201L
        private const val MAX_HEADER_CHARACTERS = 128
        private val JSON = Regex("application/json(?:; *charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        fun checked(
            route: ComplaintMutationRoute,
            status: Long,
            headers: ComplaintMutationResponseHeaders,
        ): ComplaintReceiveBudget? =
            if (route != ComplaintMutationRoute.STATUS && status == CREATED && isJson(headers.media)) {
                acknowledgementBudget(headers.encoding, headers.length, headers.transfer)
            } else {
                ComplaintSessionReceiveBudget.checked(headers.encoding, headers.length, headers.transfer)
            }

        private fun isJson(media: List<String>): Boolean =
            media.size == 1 && media.single().length <= MAX_HEADER_CHARACTERS && JSON.matches(media.single())

        private fun acknowledgementBudget(
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintMutationReceiveBudget? =
            when {
                !listOf(encoding, length, transfer).all(::singleBoundedHeader) -> null
                encoding.isNotEmpty() && !encoding.single().equals("identity", ignoreCase = true) -> null
                transfer.isNotEmpty() &&
                    (length.isNotEmpty() || !transfer.single().equals("chunked", ignoreCase = true)) -> null
                length.isEmpty() -> ComplaintMutationReceiveBudget(null)
                else -> declaredBudget(length.single())
            }

        private fun declaredBudget(value: String): ComplaintMutationReceiveBudget? =
            value
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES }
                ?.let(::ComplaintMutationReceiveBudget)

        private fun singleBoundedHeader(values: List<String>): Boolean =
            values.size <= 1 && values.all { it.length <= MAX_HEADER_CHARACTERS }
    }
}

/** Selected response fields remain separate lists; grouping never combines or normalizes duplicate values. */
internal class ComplaintMutationResponseHeaders(
    val media: List<String>,
    val encoding: List<String>,
    val length: List<String>,
    val transfer: List<String>,
) {
    override fun toString(): String = "ComplaintMutationResponseHeaders(redacted)"
}
