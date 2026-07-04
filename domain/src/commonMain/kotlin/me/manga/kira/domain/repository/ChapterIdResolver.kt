package me.manga.kira.domain.repository

/**
 * Resolves a chapter's canonical source `url` into the Room `saved_chapters.id` surrogate
 * (`Long`) that the download subsystem keys on.
 *
 * Phase 7.x.details.downloadall. The pure-domain [me.manga.kira.domain.model.Chapter] is
 * `url`-keyed — it deliberately carries no surrogate id (see `Chapter.kt` KDoc). The download
 * enqueue path ([DownloadsActionRepository.enqueueDownload] /
 * [me.manga.kira.domain.usecase.downloads.EnqueueDownloadUseCase]) keys on the Room
 * `chapterId: Long`. A resolution step is therefore required to bridge the two — exactly the
 * same `url` → `id` lookup that [me.manga.kira.data.repository.MarkChapterReadRepositoryImpl]
 * (RS-3) and `ChapterBookmarkRepositoryImpl` already perform via
 * `ChapterDao.getChapterIdByUrl`.
 *
 * **In-library only.** A `saved_chapters` row exists only once the chapter's manga is in the
 * library, so a chapter with no row resolves to `null`. Callers must treat `null` as "no Room
 * id — skip" (mirrors the single-enqueue path, where a missing `saved_chapters` row surfaces as
 * a failure: the chapter cannot be enqueued without its surrogate id). This preserves legacy
 * "download all" semantics, which only enqueued chapters the library knew about.
 *
 * ISP (contract §6): a minimal one-method seam. A consumer that only needs `url` → `id`
 * resolution (the per-manga enqueue-all use case) should not be forced to depend on the
 * write-side [DownloadsActionRepository] surface or any DAO. Sibling pattern to
 * [ChapterBookmarkRepository] / [MarkChapterReadRepository], which each wrap one
 * `ChapterDao` concern behind a narrow `:domain` interface.
 *
 * DIP (contract §6): the `:data` impl ([me.manga.kira.data.repository.ChapterIdResolverImpl])
 * is a thin strangler-fig over Room [me.manga.kira.data.local.dao.ChapterDao]; consumers
 * depend only on this interface.
 */
interface ChapterIdResolver {
    /**
     * Resolves [chapterUrl] to its Room `saved_chapters.id`, or `null` when no in-library row
     * exists for that url.
     */
    suspend fun resolveChapterId(chapterUrl: String): Long?

    /**
     * Bulk-resolves [chapterUrls] to their Room `saved_chapters.id`s in a single chunked query,
     * returning a `url -> id` map. Urls with no in-library row are absent from the map (same
     * "skip" semantics as a `null` from [resolveChapterId]). Lets the download-all path collapse N
     * per-chapter resolution round-trips into one query per chunk.
     */
    suspend fun resolveChapterIds(chapterUrls: List<String>): Map<String, Long>
}
