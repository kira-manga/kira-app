package me.manga.kira.platform.download

/** Stable taskDescription identity, including the nonnegative page-index rule used after relaunch. */
internal data class IosTransferIdentity(
    val mangaId: Long,
    val chapterId: Long,
    val pageIndex: Int,
) {
    fun encode(): String = "$mangaId|$chapterId|$pageIndex"

    companion object {
        fun decode(description: String?): IosTransferIdentity? =
            description?.split('|')?.takeIf { it.size == COMPONENT_COUNT }?.let { parts ->
                val mangaId = parts[0].toLongOrNull()
                val chapterId = parts[1].toLongOrNull()
                val pageIndex = parts[2].toIntOrNull()
                if (mangaId != null && chapterId != null && pageIndex != null && pageIndex >= 0) {
                    IosTransferIdentity(mangaId, chapterId, pageIndex)
                } else {
                    null
                }
            }

        private const val COMPONENT_COUNT = 3
    }
}
