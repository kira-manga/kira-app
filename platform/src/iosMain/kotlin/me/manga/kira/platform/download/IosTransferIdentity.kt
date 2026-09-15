package me.manga.kira.platform.download

/** Stable taskDescription identity, including the nonnegative page-index rule used after relaunch. */
internal data class IosTransferIdentity(
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
    val attemptToken: String,
) {
    fun encode(): String = "v2|$mangaId|$chapterId|$pageIndex|$attemptToken"

    companion object {
        fun decode(description: String?): IosTransferIdentity? =
            description?.split('|')?.takeIf { it.size == COMPONENT_COUNT && it[0] == "v2" }?.let { parts ->
                val mangaId = parts[1].toLongOrNull()
                val chapterId = parts[2].toLongOrNull()
                val pageIndex = parts[3].toIntOrNull()?.takeIf { it >= 0 }
                val token = parts[4].takeIf { TOKEN.matches(it) }
                if (mangaId != null && chapterId != null && pageIndex != null && token != null) {
                    IosTransferIdentity(mangaId, chapterId, pageIndex, token)
                } else {
                    null
                }
            }

        private const val COMPONENT_COUNT = 5
        private val TOKEN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
