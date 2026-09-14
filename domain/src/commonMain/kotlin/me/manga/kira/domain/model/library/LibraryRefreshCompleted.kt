package me.manga.kira.domain.model.library

/**
 * Observed completion of an entire library snapshot, not a partial refresh report.
 * A nonempty snapshot is a successful refresh even when [newChapterCount] is zero.
 * An empty snapshot is a successful no-op and must not advance the last-success timestamp.
 */
data class LibraryRefreshCompleted(
    val snapshotSize: Int,
    val newChapterCount: Int,
) {
    init {
        require(snapshotSize >= 0 && newChapterCount >= 0)
        require(snapshotSize > 0 || newChapterCount == 0)
    }
}
