package me.manga.kira.data.remote.complaint

/** Opaque cursor envelope only: no payload decoding, MAC/actor verification or inferred MAC size. */
internal data class ComplaintHistoryQuery private constructor(
    val limit: Int,
    val cursor: String?,
) {
    override fun toString(): String = "ComplaintHistoryQuery(redacted)"

    companion object {
        const val MAX_CURSOR_CHARACTERS = 2 * 1_024
        private const val MAX_LIMIT = 50
        private const val LIMIT_PREFIX = "limit="
        private const val CURSOR_PREFIX = "cursor="
        private val CURSOR_ENVELOPE = Regex("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        val maxCharacters = "limit=50&cursor=".length + MAX_CURSOR_CHARACTERS

        /** Raw, unescaped query grammar prevents encoded aliases and duplicate parameter names. */
        fun checked(value: String): ComplaintHistoryQuery? {
            if (value.length !in LIMIT_PREFIX.length + 1..maxCharacters) return null
            val parts = value.split('&')
            if (parts.size !in 1..2) return null
            var limit: Int? = null
            var cursor: String? = null
            for (part in parts) {
                when {
                    part.startsWith(LIMIT_PREFIX) && limit == null ->
                        limit = checkedLimit(part.removePrefix(LIMIT_PREFIX)) ?: return null
                    part.startsWith(CURSOR_PREFIX) && cursor == null ->
                        cursor = checkedCursor(part.removePrefix(CURSOR_PREFIX)) ?: return null
                    else -> return null
                }
            }
            return ComplaintHistoryQuery(limit ?: return null, cursor)
        }

        private fun checkedLimit(value: String): Int? =
            value.toIntOrNull()?.takeIf { it in 1..MAX_LIMIT && it.toString() == value }

        private fun checkedCursor(value: String): String? =
            value.takeIf { it.length <= MAX_CURSOR_CHARACTERS && CURSOR_ENVELOPE.matches(it) }
    }
}
