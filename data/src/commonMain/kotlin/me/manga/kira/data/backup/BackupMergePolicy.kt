package me.manga.kira.data.backup

import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity

/**
 * Pure merge decisions for backup import — the lambdas handed to `BackupDao.importMangaMerging` /
 * `importHistoryMerging`. Kept free of I/O so every rule is directly unit-testable.
 *
 * Ground rules (owner-approved): a merge never deletes or regresses local data. Boolean progress
 * flags OR together, timestamps keep whichever side is more advanced, and local metadata wins
 * over the backup's copy (the local row may have been refreshed more recently than the backup).
 */
internal object BackupMergePolicy {

    /** Local metadata kept; only progress/engagement fields advance. */
    fun mergeManga(local: SavedMangaEntity, incoming: SavedMangaEntity): SavedMangaEntity =
        local.copy(
            isLiked = local.isLiked || incoming.isLiked,
            isWatchingNow = local.isWatchingNow || incoming.isWatchingNow,
            lastOpenTimestamp = maxOf(local.lastOpenTimestamp, incoming.lastOpenTimestamp),
            savedTimestamp = minNonZero(local.savedTimestamp, incoming.savedTimestamp),
        )

    /**
     * Read/bookmark flags OR together, the newer read timestamp wins; the download columns and
     * transient badge state (`isDownloaded`/`localImagePaths`/`isNew`/`fetchedAt`) stay LOCAL —
     * restoring a packed download flips them separately, only after its CBZ is in place.
     */
    fun mergeChapter(local: SavedChapterEntity, incoming: SavedChapterEntity): SavedChapterEntity =
        local.copy(
            isRead = local.isRead || incoming.isRead,
            isBookmarked = local.isBookmarked || incoming.isBookmarked,
            lastReadDate = maxOf(local.lastReadDate, incoming.lastReadDate),
        )

    /** History is one row per manga: the side that read more recently defines the position. */
    fun shouldReplaceHistory(local: HistoryItemD, incoming: HistoryItemD): Boolean =
        incoming.lastReadDate > local.lastReadDate

    /**
     * Restore the backup's per-chapter resume page iff the chapter was just created from the
     * backup, or the backup's read state is newer than what the local row had before the merge,
     * or the local device has no saved position at all. [localSavedPage] is the pre-existing
     * local `ReadProgressRepository` value (callers may skip loading it when [chapterWasNew]).
     */
    fun shouldRestoreResumePage(
        chapterWasNew: Boolean,
        incomingLastReadDate: Long,
        localLastReadDateBefore: Long,
        localSavedPage: Int?,
    ): Boolean =
        chapterWasNew || incomingLastReadDate > localLastReadDateBefore || localSavedPage == null

    /** "Saved earliest" for savedTimestamp — but 0 means "unknown", never wins. */
    fun minNonZero(a: Long, b: Long): Long = when {
        a == 0L -> b
        b == 0L -> a
        else -> minOf(a, b)
    }
}
