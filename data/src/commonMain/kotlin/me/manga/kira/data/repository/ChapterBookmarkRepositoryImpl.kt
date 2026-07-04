package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.repository.ChapterBookmarkRepository

/**
 * [ChapterBookmarkRepository] strangler-fig delegate straight over the Room [ChapterDao]
 * chapter-bookmark surface (`getChapterIdByUrl` / `getChapterById` / `toggleChapterBookmark`,
 * all over the `saved_chapters` table).
 *
 * Phase 6.4.x.bookmark (task #217); re-pointed at the DAO in RS-3 (task #738). Both the legacy
 * reader and the rework reader flip the SAME `saved_chapters.isBookmarked` column through this DAO,
 * so:
 *  - bookmark state stays consistent across the strangler-fig transition, and
 *  - the Library `bookmarkedCount` badge (`MangaDao.getAllChapterMetricsFlow` COUNT, consumed by
 *    `LibraryRepositoryImpl.observeLibrary`) re-derives automatically via Room invalidation —
 *    no extra wiring. Writing through a net-new url-keyed store instead would silently diverge
 *    that badge; that is why the bridge keys on the Room `Long chapterId`.
 *
 * Chapter identity: the rework [me.manga.kira.domain.model.Chapter] is `url`-keyed; the store
 * needs the Room `Long` `saved_chapters.id`. [ChapterDao.getChapterIdByUrl] resolves url → id for
 * the one-shot [toggleBookmark]; a row exists only once the manga is in-library, so a `null` id
 * means "not in-library".
 *
 * Not-in-library behavior (preserves legacy semantics): [observeBookmark] emits `false`,
 * [toggleBookmark] is a no-op — no auto-add-to-library side effect.
 *
 * Bookmark-flow derivation: [observeBookmark] subscribes to the url-keyed
 * [ChapterDao.getChapterByUrl] Room flow and maps `it?.isBookmarked == true` — a deleted/absent
 * row maps to `false`. Keying the flow on `url` (not the `Long` id) keeps the stream membership-
 * reactive: if the `saved_chapters` row is created after the Reader attached (manga saved mid-
 * session), Room re-emits and the bookmark state re-binds, instead of the stream completing on a
 * single `false`.
 *
 * Threading: Room suspend queries + Room `Flow`s are main-safe (Room dispatches to its own
 * executor), so — like [ReadingSessionRepositoryImpl] — no explicit dispatcher pinning is needed.
 */
class ChapterBookmarkRepositoryImpl(
    private val chapterDao: ChapterDao,
) : ChapterBookmarkRepository {

    override fun observeBookmark(chapterUrl: String): Flow<Boolean> =
        chapterDao.getChapterByUrl(chapterUrl)
            .map { it?.isBookmarked == true }
            .distinctUntilChanged()

    override suspend fun toggleBookmark(chapterUrl: String): Boolean {
        val chapterId = chapterDao.getChapterIdByUrl(chapterUrl) ?: return false
        chapterDao.toggleChapterBookmark(chapterId)
        return true
    }
}
