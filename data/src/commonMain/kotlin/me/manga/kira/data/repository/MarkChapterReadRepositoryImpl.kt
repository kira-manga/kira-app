package me.manga.kira.data.repository

import me.manga.kira.core.logging.FlowLog
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.repository.MarkChapterReadRepository

/**
 * [MarkChapterReadRepository] strangler-fig delegate straight over the Room [ChapterDao]
 * chapter mark-read surface (`getChapterIdByUrl` / `markChapterAsRead`, both over the
 * `saved_chapters` table).
 *
 * Reader-convergence R3b; re-pointed at the DAO in RS-3 (task #738). Both the legacy reader and the
 * rework reader set the SAME `saved_chapters.isRead` column through this DAO, so:
 *  - read state stays consistent across the strangler-fig transition, and
 *  - the Library `readCount` + the UNREAD filter (`MangaDao.getAllChapterMetricsFlow` COUNT,
 *    consumed by `LibraryRepositoryImpl.observeLibrary`) re-derive automatically via Room
 *    invalidation — no extra wiring. Writing through a net-new url-keyed store instead would
 *    silently diverge those metrics; that is why the bridge keys on the Room `Long chapterId`.
 *
 * Chapter identity: the rework [me.manga.kira.domain.model.Chapter] is `url`-keyed; the store
 * needs the Room `Long` `saved_chapters.id`. [ChapterDao.getChapterIdByUrl] resolves url → id
 * (exactly as `ChapterBookmarkRepositoryImpl` does); a row exists only once the manga is
 * in-library, so a `null` id means "not in-library".
 *
 * Not-in-library behavior (preserves legacy semantics): [markRead] is a no-op — no auto-add-to-
 * library side effect. `markChapterAsRead` is likewise only effective for saved chapters, since
 * its `chapterId` is resolved from a saved row. [ChapterDao.markChapterAsRead] stamps the row's
 * `lastReadDate` via its own default `currentTime` argument (preserved by omitting it here, exactly
 * as the legacy facade did).
 *
 * Threading: Room suspend queries are main-safe (Room dispatches to its own executor), so — like
 * [ChapterBookmarkRepositoryImpl] — no explicit dispatcher pinning is needed.
 */
class MarkChapterReadRepositoryImpl(
    private val chapterDao: ChapterDao,
) : MarkChapterReadRepository {

    override suspend fun markRead(chapterUrl: String) {
        val chapterId = chapterDao.getChapterIdByUrl(chapterUrl) ?: run {
            FlowLog.log("Reader", "markRead", "chapter=$chapterUrl skipped=not-in-library")
            return
        }
        FlowLog.log("Reader", "markRead", "chapter=$chapterUrl id=$chapterId (also clears NEW)")
        chapterDao.markChapterAsRead(chapterId)
        // NEW-badge parity: clear the `saved_chapters.isNew` flag when the chapter is opened/read.
        // Native clears it on chapter click (LibraryDetailsViewModel.setIsNewChapter →
        // LibraryRepository.markChapterIsNew → ChapterDao.markChapterIsNew = `UPDATE saved_chapters
        // SET isNew = 0`); the rework reaches the same row through this single-chapter open/read
        // path. Reuses the existing ChapterDao.markChapterIsNew(chapterId) query — no migration.
        chapterDao.markChapterIsNew(chapterId)
    }

    /**
     * GAP-LIB-02 single read toggle. Resolves the url → Room id (null = not-in-library → no-op),
     * then flips the `isRead` flag both directions via the existing `toggleChaptersReadBatch`
     * (one-element list) DAO query — the same column the Library `readCount` / UNREAD filter
     * derive from, so the toggle stays consistent via Room invalidation. Unlike [markRead] (which
     * only sets the flag + stamps `lastReadDate`), the toggle does not stamp `lastReadDate` — it
     * mirrors the native per-chapter RemoveRedEye toggle, which only flipped the read column.
     */
    override suspend fun toggleRead(chapterUrl: String) {
        val chapterId = chapterDao.getChapterIdByUrl(chapterUrl) ?: return
        chapterDao.toggleChaptersReadBatch(listOf(chapterId))
    }

    /**
     * GAP-LIB-02 bulk mark-read for the multi-select bar. Resolves each url to its Room id (urls
     * with no in-library row are skipped), then marks the resolved set read in a single batched
     * write via the existing chunked `markChaptersRead` DAO wrapper. Idempotent.
     */
    override suspend fun markRead(chapterUrls: List<String>) {
        val ids = chapterDao.getChapterIdsByUrls(chapterUrls)
        if (ids.isEmpty()) return
        chapterDao.markChaptersRead(ids)
    }
}
