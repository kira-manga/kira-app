package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Prune a queued download for a single chapter within its captured owning [Manga].
 *
 * GAP-LIB-03 (per-manga library chapter management). The native per-chapter download affordance let
 * the user cancel a running/queued download from the chapter row. This use case resolves the
 * url-keyed [me.manga.kira.domain.model.Chapter] to its Room `saved_chapters.id` via
 * [ChapterIdResolver] (null = no in-library row → no-op success), then issues a queue-prune cancel
 * through [CancelDownloadUseCase].
 *
 * Details dispatches RUNNING/COMPRESSING rows through [CancelRunningDownloadUseCase] using IDs
 * supplied by its manga-scoped download observation. This resolver path handles queued/absent rows.
 *
 * Contract §6 SRP: one rule — resolve + cancel one chapter. Koin binds it as a `factory` in
 * `detailsReworkModule`.
 */
class CancelChapterDownloadUseCase(
    private val chapterIdResolver: ChapterIdResolver,
    private val cancelDownload: CancelDownloadUseCase,
) {
    suspend operator fun invoke(
        manga: Manga,
        chapterUrl: String,
    ): Result<Unit> {
        val chapterId = chapterIdResolver.resolveChapterId(manga, chapterUrl) ?: return Result.success(Unit)
        return cancelDownload(chapterId)
    }
}
