package me.manga.kira.domain.usecase.details

import me.manga.kira.domain.repository.ChapterIdResolver

/**
 * Resolves a chapter's canonical source `url` into its Room `saved_chapters.id`, or `null` when
 * no in-library row exists for that url.
 *
 * DIP seam (contract §6): the Details VM depends on this use case, never on
 * [ChapterIdResolver] directly — mirroring the established "VM → use case → repository" shape of
 * the screen's other collaborators. Thin one-call wrapper; the in-library-only semantics live in
 * the resolver's KDoc and are unchanged.
 */
class ResolveChapterIdUseCase(
    private val resolver: ChapterIdResolver,
) {
    suspend operator fun invoke(chapterUrl: String): Long? =
        resolver.resolveChapterId(chapterUrl)
}
