package me.manga.kira.data.remote.complaint

import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

/** Direct owner-delete204 is explicitly empty; only creation201/edit200 JSON get the larger budget. */
internal class ComplaintMutationReceiveBudget private constructor(
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
        private const val CREATED = 201L
        private const val OK = 200L
        private const val NO_CONTENT = 204L
        private const val MAX_HEADER_CHARACTERS = 128
        private val JSON = Regex("application/json(?:; *charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        fun checked(
            route: ComplaintMutationRoute,
            status: Long,
            headers: ComplaintMutationResponseHeaders,
        ): ComplaintReceiveBudget? {
            if (route == ComplaintMutationRoute.OWNER_DELETE && status == NO_CONTENT) {
                return ownerDeleteBudget(headers)
            }
            val maximum =
                when (route) {
                    ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY ->
                        Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES.takeIf { status == CREATED }
                    ComplaintMutationRoute.EDIT -> Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES.takeIf { status == OK }
                    ComplaintMutationRoute.OWNER_DELETE, ComplaintMutationRoute.STATUS -> null
                }
            return if (maximum != null && isJson(headers.media)) {
                acknowledgementBudget(headers.encoding, headers.length, headers.transfer, maximum)
            } else {
                ComplaintSessionReceiveBudget.checked(headers.encoding, headers.length, headers.transfer)
            }
        }

        private fun ownerDeleteBudget(headers: ComplaintMutationResponseHeaders): ComplaintMutationReceiveBudget? =
            when {
                headers.media.isNotEmpty() || headers.length.isNotEmpty() || headers.transfer.isNotEmpty() -> null
                !singleBoundedHeader(headers.encoding) -> null
                headers.encoding.isNotEmpty() && !headers.encoding.single().equals("identity", ignoreCase = true) -> null
                else -> ComplaintMutationReceiveBudget(Policy.MAX_OWNER_DELETE_ACKNOWLEDGEMENT_BYTES, 0)
            }

        private fun isJson(media: List<String>): Boolean =
            media.size == 1 && media.single().length <= MAX_HEADER_CHARACTERS && JSON.matches(media.single())

        private fun acknowledgementBudget(
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
            maximum: Int,
        ): ComplaintMutationReceiveBudget? =
            when {
                !listOf(encoding, length, transfer).all(::singleBoundedHeader) -> null
                encoding.isNotEmpty() && !encoding.single().equals("identity", ignoreCase = true) -> null
                transfer.isNotEmpty() &&
                    (length.isNotEmpty() || !transfer.single().equals("chunked", ignoreCase = true)) -> null
                length.isEmpty() -> ComplaintMutationReceiveBudget(maximum, null)
                else -> declaredBudget(length.single(), maximum)
            }

        private fun declaredBudget(
            value: String,
            maximum: Int,
        ): ComplaintMutationReceiveBudget? =
            value
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..maximum }
                ?.let { ComplaintMutationReceiveBudget(maximum, it) }

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
