package me.manga.kira.data.remote.complaint

/** Only history 200 can use the larger cap; all other statuses retain the installation budget. */
internal class ComplaintHistoryReceiveBudget private constructor(
    private val declaredLength: Int?,
) : ComplaintReceiveBudget {
    override var receivedBytes: Int = 0
        private set

    override val remainingBytes: Int get() = MAX_BYTES - receivedBytes

    override fun accept(byteCount: ULong): Boolean {
        if (byteCount > remainingBytes.toULong()) return false
        receivedBytes += byteCount.toInt()
        return true
    }

    override fun isComplete(): Boolean = declaredLength == null || receivedBytes == declaredLength

    companion object {
        const val MAX_BYTES = 2 * 1_024 * 1_024
        private const val SUCCESS_STATUS = 200L
        private const val MAX_HEADER_CHARACTERS = 128

        fun checked(
            status: Long,
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintReceiveBudget? =
            if (status == SUCCESS_STATUS) {
                successBudget(encoding, length, transfer)
            } else {
                ComplaintSessionReceiveBudget.checked(encoding, length, transfer)
            }

        private fun successBudget(
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintHistoryReceiveBudget? =
            when {
                !listOf(encoding, length, transfer).all(::singleBoundedHeader) -> null
                encoding.isNotEmpty() && !encoding.single().equals("identity", ignoreCase = true) -> null
                transfer.isNotEmpty() &&
                    (length.isNotEmpty() || !transfer.single().equals("chunked", ignoreCase = true)) -> null
                length.isEmpty() -> ComplaintHistoryReceiveBudget(null)
                else -> declaredBudget(length.single())
            }

        private fun declaredBudget(value: String): ComplaintHistoryReceiveBudget? =
            value
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..MAX_BYTES }
                ?.let(::ComplaintHistoryReceiveBudget)

        private fun singleBoundedHeader(values: List<String>): Boolean =
            values.size <= 1 && values.all { it.length <= MAX_HEADER_CHARACTERS }
    }
}
