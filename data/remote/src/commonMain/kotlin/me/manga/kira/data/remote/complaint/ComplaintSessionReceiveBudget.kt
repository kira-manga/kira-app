package me.manga.kira.data.remote.complaint

/** Bounds bytes handed to Ktor; this is not a bound on earlier TLS/socket/native allocation. */
internal class ComplaintSessionReceiveBudget private constructor(
    private val declaredLength: Int?,
) {
    var receivedBytes: Int = 0
        private set

    val remainingBytes: Int get() = MAX_BYTES - receivedBytes

    fun accept(byteCount: ULong): Boolean {
        if (byteCount > remainingBytes.toULong()) return false
        receivedBytes += byteCount.toInt()
        return true
    }

    fun isComplete(): Boolean = declaredLength == null || receivedBytes == declaredLength

    companion object {
        const val MAX_BYTES = 16 * 1_024
        private const val MAX_HEADER_CHARACTERS = 128

        fun checked(
            encoding: List<String>,
            length: List<String>,
            transfer: List<String>,
        ): ComplaintSessionReceiveBudget? =
            when {
                !listOf(encoding, length, transfer).all(::singleBoundedHeader) -> null
                encoding.isNotEmpty() && !encoding.single().equals("identity", ignoreCase = true) -> null
                transfer.isNotEmpty() &&
                    (length.isNotEmpty() || !transfer.single().equals("chunked", ignoreCase = true)) -> null
                length.isEmpty() -> ComplaintSessionReceiveBudget(null)
                else -> declaredBudget(length.single())
            }

        private fun declaredBudget(value: String): ComplaintSessionReceiveBudget? =
            value
                .takeIf { it.isNotEmpty() && it.all { character -> character in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 0..MAX_BYTES }
                ?.let(::ComplaintSessionReceiveBudget)

        private fun singleBoundedHeader(values: List<String>): Boolean =
            values.size <= 1 && values.all { it.length <= MAX_HEADER_CHARACTERS }
    }
}
