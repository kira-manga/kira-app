package me.manga.kira.data.remote.complaint

/** List200 keeps its original cap; detail200 JSON has32KiB, and every problem keeps16KiB. */
internal class ComplaintHistoryReceiveBudget private constructor(
    private val declaredLength: Int?,
    private val maximumBytes: Int,
) : ComplaintReceiveBudget {
    override var receivedBytes: Int = 0
        private set

    override val remainingBytes: Int get() = maximumBytes - receivedBytes

    override fun accept(byteCount: ULong): Boolean {
        if (byteCount > remainingBytes.toULong()) return false
        receivedBytes += byteCount.toInt()
        return true
    }

    override fun isComplete(): Boolean = declaredLength == null || receivedBytes == declaredLength

    companion object {
        const val MAX_BYTES = 2 * 1_024 * 1_024
        const val MAX_DETAIL_BYTES = 32 * 1_024
        private const val SUCCESS_STATUS = 200L
        private const val MAX_HEADER_CHARACTERS = 128
        private val JSON = Regex("application/json(?:;[ \\t]*charset=(?:utf-8|\"utf-8\"))?", RegexOption.IGNORE_CASE)

        fun checked(
            status: Long,
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintReceiveBudget? =
            if (status == SUCCESS_STATUS) {
                successBudget(encoding, length, transfer, MAX_BYTES)
            } else {
                ComplaintSessionReceiveBudget.checked(encoding, length, transfer)
            }

        fun checkedDetail(
            status: Long,
            media: List<String>,
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintReceiveBudget? =
            if (status == SUCCESS_STATUS &&
                media.size == 1 &&
                media.single().length <= MAX_HEADER_CHARACTERS &&
                JSON.matches(media.single())
            ) {
                successBudget(encoding, length, transfer, MAX_DETAIL_BYTES)
            } else {
                ComplaintSessionReceiveBudget.checked(encoding, length, transfer)
            }

        private fun successBudget(
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
            maximumBytes: Int,
        ): ComplaintHistoryReceiveBudget? =
            when {
                !listOf(encoding, length, transfer).all(::singleBoundedHeader) -> null
                encoding.isNotEmpty() && !encoding.single().equals("identity", ignoreCase = true) -> null
                transfer.isNotEmpty() &&
                    (length.isNotEmpty() || !transfer.single().equals("chunked", ignoreCase = true)) -> null
                length.isEmpty() -> ComplaintHistoryReceiveBudget(null, maximumBytes)
                else -> declaredBudget(length.single(), maximumBytes)
            }

        private fun declaredBudget(
            value: String,
            maximumBytes: Int,
        ): ComplaintHistoryReceiveBudget? =
            value
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..maximumBytes }
                ?.let { ComplaintHistoryReceiveBudget(it, maximumBytes) }

        private fun singleBoundedHeader(values: List<String>): Boolean =
            values.size <= 1 && values.all { it.length <= MAX_HEADER_CHARACTERS }
    }
}
