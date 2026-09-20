package me.manga.kira.domain.model.identity

/**
 * Conditional-write identity obtained by an explicit reading-session open.
 *
 * Writers must revalidate both durable generations and the retained-owner fence in their owning
 * transaction. Work removal advances the work generation, including for unseen chapters; a chapter
 * clear advances only that chapter's generation. Old handles cannot recreate cleared progress.
 * A deliberate later session obtains current fences without resetting generations or cleanup intent.
 * Zero is a valid acquired generation, not permission for callers to invent a default handle.
 */
data class ProgressHandle(
    val chapter: ChapterLocator,
    val workGeneration: Long,
    val chapterGeneration: Long,
    val retainedOwner: SavedProgressOwner? = null,
) {
    init {
        require(workGeneration >= 0)
        require(chapterGeneration >= 0)
    }
}

/**
 * Within-chapter position: null is absence, while zero is an explicitly stored first page.
 * Reading this snapshot for export must not open a session or reset either fence.
 */
data class ProgressSnapshot(
    val handle: ProgressHandle,
    val pageIndex: Int?,
) {
    init {
        require(pageIndex == null || pageIndex >= 0)
    }
}
