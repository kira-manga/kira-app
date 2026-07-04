package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Enqueue a single chapter for offline download, keyed by the chapter's canonical source `url`.
 *
 * GAP-LIB-03 (per-manga library chapter management on the rework Details screen). The native
 * `LibraryMangaScreen` exposed a per-chapter Download button; the rework Details screen only had a
 * header "Download all". This use case is the per-row equivalent — it composes the proven building
 * blocks:
 *  - [ChapterIdResolver] resolves the url-keyed [me.manga.kira.domain.model.Chapter] to its Room
 *    `saved_chapters.id` (the download subsystem keys on the `Long` id). `null` (no in-library row)
 *    → skip, returning [Result.success] (a chapter with no saved row can't be downloaded; same
 *    skip-quietly posture as [EnqueueAllChaptersDownloadUseCase]).
 *  - [EnqueueDownloadUseCase] enqueues the resolved chapter with the manga's denormalised
 *    [mangaTitle] + [api] metadata, exactly as the Updates download button does.
 *
 * Contract §6 SRP: one rule — resolve + enqueue one chapter. DIP: depends only on `:domain` seams.
 * Koin binds it as a `factory` in `detailsReworkModule`.
 */
class EnqueueChapterDownloadUseCase(
    private val chapterIdResolver: ChapterIdResolver,
    private val enqueueDownload: EnqueueDownloadUseCase,
) {
    suspend operator fun invoke(chapterUrl: String, mangaTitle: String, api: String): Result<Unit> {
        val chapterId = chapterIdResolver.resolveChapterId(chapterUrl) ?: return Result.success(Unit)
        return enqueueDownload(chapterId = chapterId, mangaTitle = mangaTitle, api = api)
    }
}
