package me.manga.kira.domain.repository

/**
 * Narrow port (ISP) for clearing a chapter's NEW badge the moment it is opened — WITHOUT marking
 * it read. Native clears `isNew` on chapter click (`LibraryDetailsViewModel.setIsNewChapter` →
 * `markChapterIsNew`); the rework's only existing clear path is the reader's mark-read on advance,
 * which both fires too late (only when leaving a chapter) and conflates "opened" with "read". This
 * port exists so the Details screen can clear the badge on tap without the read side effect.
 *
 * No-op when the chapter URL has no in-library row (a non-saved manga has no `isNew` to clear).
 */
interface ChapterNewBadgeRepository {
    /** Clear the persisted NEW flag for the chapter identified by [chapterUrl]. */
    suspend fun clearNew(chapterUrl: String)
}
