package me.manga.kira.domain.usecase.downloads

import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Cancel an in-flight / queued download for a single chapter, keyed by the chapter's source `url`.
 *
 * GAP-LIB-03 (per-manga library chapter management). The native per-chapter download affordance let
 * the user cancel a running/queued download from the chapter row. This use case resolves the
 * url-keyed [me.manga.kira.domain.model.Chapter] to its Room `saved_chapters.id` via
 * [ChapterIdResolver] (null = no in-library row → no-op success), then issues a queue-prune cancel
 * through [CancelDownloadUseCase].
 *
 * Scope note: the rework Details row does not distinguish QUEUED/COMPRESSING from RUNNING the way
 * the native dropdown did (which split cancel across `CancelDownloadUseCase` and
 * `CancelRunningDownloadUseCase`). Cancelling from the Details row routes through the queue-prune
 * [CancelDownloadUseCase], matching the rework Downloads screen's primary cancel affordance; the
 * RUNNING-specific interrupt remains reachable from the dedicated Downloads screen. This is recorded
 * as an accepted simplification in PLAN_LIB_chaptermgmt.md (no per-row RUNNING-interrupt on Details).
 *
 * Contract §6 SRP: one rule — resolve + cancel one chapter. Koin binds it as a `factory` in
 * `detailsReworkModule`.
 */
class CancelChapterDownloadUseCase(
    private val chapterIdResolver: ChapterIdResolver,
    private val cancelDownload: CancelDownloadUseCase,
) {
    suspend operator fun invoke(chapterUrl: String): Result<Unit> {
        val chapterId = chapterIdResolver.resolveChapterId(chapterUrl) ?: return Result.success(Unit)
        return cancelDownload(chapterId)
    }
}
