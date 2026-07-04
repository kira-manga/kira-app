package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Observe + toggle a chapter's bookmark flag, keyed by the chapter's canonical source `url`
 * (the rework [me.manga.kira.domain.model.Chapter] identity — there is no surrogate id).
 *
 * Phase 6.4.x.bookmark (task #217). The rework Reader needs the bookmark verb the legacy Reader
 * had; bookmark state is persisted in the legacy Room `saved_chapters.isBookmarked` column, which
 * also feeds the Library `bookmarkedCount` badge (`MangaDao.getAllChapterMetricsFlow` COUNT). The
 * `:data` impl is a strangler-fig over that legacy store so both readers — and the badge — stay
 * consistent through the migration (see `ChapterBookmarkRepositoryImpl`).
 *
 * **In-library only.** A `saved_chapters` row exists only once its manga is in the library, so
 * bookmark state is meaningful only for in-library chapters. [observeBookmark] emits `false` and
 * [toggleBookmark] is a no-op for a chapter with no row — preserving legacy semantics (the legacy
 * reader's bookmark action was likewise only effective for saved manga). Callers should gate the
 * bookmark control on library membership, or surface the [toggleBookmark] `false` return as a
 * "add to Library first" hint (#15 — native shows a toast on this path).
 */
interface ChapterBookmarkRepository {
    /** Emits the chapter's bookmark flag; `false` when the chapter has no in-library row. */
    fun observeBookmark(chapterUrl: String): Flow<Boolean>

    /**
     * Flips the chapter's bookmark flag.
     *
     * @return `true` if an in-library `saved_chapters` row existed and was flipped; `false` if the
     *   chapter has no row (not in library) — a no-op the caller can surface as feedback (#15).
     */
    suspend fun toggleBookmark(chapterUrl: String): Boolean
}
