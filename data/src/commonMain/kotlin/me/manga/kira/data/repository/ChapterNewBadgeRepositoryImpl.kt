package me.manga.kira.data.repository

import me.manga.kira.core.logging.FlowLog
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.repository.ChapterNewBadgeRepository

/**
 * [ChapterNewBadgeRepository] strangler-fig delegate over the Room [ChapterDao]: resolves the
 * url-keyed domain chapter to its `saved_chapters.id` and clears `isNew` via the existing
 * `markChapterIsNew` query (`UPDATE saved_chapters SET isNew = 0`). A `null` id means the chapter
 * has no in-library row, so clearing is a no-op — same not-in-library posture as
 * [MarkChapterReadRepositoryImpl]. Crucially this does NOT set `isRead`, so opening a chapter clears
 * its NEW badge without marking it read (native clears `isNew` on chapter click, not read).
 */
class ChapterNewBadgeRepositoryImpl(
    private val chapterDao: ChapterDao,
) : ChapterNewBadgeRepository {

    override suspend fun clearNew(chapterUrl: String) {
        val chapterId = chapterDao.getChapterIdByUrl(chapterUrl) ?: return
        FlowLog.log("Details", "clearNew", "chapter=$chapterUrl id=$chapterId")
        chapterDao.markChapterIsNew(chapterId)
    }
}
