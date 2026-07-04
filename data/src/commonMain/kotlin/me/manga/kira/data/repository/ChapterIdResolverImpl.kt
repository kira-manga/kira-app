package me.manga.kira.data.repository

import me.manga.kira.data.local.dao.ChapterDao
import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * [ChapterIdResolver] strangler-fig delegate straight over the Room [ChapterDao]
 * `getChapterIdByUrl` query (over the `saved_chapters` table).
 *
 * Phase 7.x.details.downloadall. Single-line pass-through — the same `url` → `id` resolution
 * `ChapterBookmarkRepositoryImpl` (Phase 6.4.x.bookmark) and `MarkChapterReadRepositoryImpl`
 * (RS-3) perform. A `saved_chapters` row exists only once the chapter's manga is in the
 * library, so a `null` result means "not in-library" — the enqueue-all use case treats that as
 * "skip", preserving legacy "download all" semantics (only library-known chapters were
 * enqueued).
 *
 * Threading: Room suspend queries are main-safe (Room dispatches to its own executor), so — like
 * [ChapterBookmarkRepositoryImpl] / [MarkChapterReadRepositoryImpl] — no explicit dispatcher
 * pinning is needed here. The enqueue-all use case still pins its whole resolve+enqueue loop to a
 * background dispatcher per its own KDoc.
 */
class ChapterIdResolverImpl(
    private val chapterDao: ChapterDao,
) : ChapterIdResolver {

    override suspend fun resolveChapterId(chapterUrl: String): Long? =
        chapterDao.getChapterIdByUrl(chapterUrl)

    override suspend fun resolveChapterIds(chapterUrls: List<String>): Map<String, Long> =
        chapterDao.getChapterIdMapByUrls(chapterUrls)
}
