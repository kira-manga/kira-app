package me.manga.kira.domain.model.identity

/**
 * Retained local parent identity plus the address whose ownership must be revalidated on use.
 *
 * The numeric ID is local-only, never a portable backup identity or an insertion hint. A writer
 * must verify the retained row and locator using its transaction-consistent accepted alias policy.
 */
data class SavedWorkIdentity(
    val id: Long,
    val locator: WorkLocator,
) {
    init {
        require(id > 0)
    }
}

/**
 * Retained saved-row fence for progress. A saved work can contain an unsaved chapter, so its
 * chapter ID is optional; null does not authorize attaching progress to a different parent.
 */
data class SavedProgressOwner(
    val work: SavedWorkIdentity,
    val chapterId: Long? = null,
) {
    init {
        require(chapterId == null || chapterId > 0)
    }
}
