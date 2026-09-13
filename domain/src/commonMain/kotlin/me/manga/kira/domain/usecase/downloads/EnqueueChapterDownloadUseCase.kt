package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Enqueue a single chapter for offline download within the captured owning [Manga].
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
 *    title + api metadata, exactly as the Updates download button does.
 *
 * Contract §6 SRP: one rule — resolve + enqueue one chapter. DIP: depends only on `:domain` seams.
 * Koin binds it as a `factory` in `detailsReworkModule`.
 */
class EnqueueChapterDownloadUseCase(
    private val chapterIdResolver: ChapterIdResolver,
    private val enqueueDownload: EnqueueDownloadUseCase,
) {
    suspend operator fun invoke(
        manga: Manga,
        chapterUrl: String,
    ): Result<Unit> {
        val chapterId = chapterIdResolver.resolveChapterId(manga, chapterUrl) ?: return Result.success(Unit)
        return enqueueDownload(chapterId = chapterId, mangaTitle = manga.title, api = manga.api)
    }
}
