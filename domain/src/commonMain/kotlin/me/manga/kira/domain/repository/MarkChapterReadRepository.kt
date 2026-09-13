package me.manga.kira.domain.repository

import me.manga.kira.domain.model.Manga

/**
 * Mark a chapter as READ within its owning [Manga]. Persistence uses the exact manga URL
 * together with the chapter URL, never a chapter URL shared by another manga.
 *
 * Reader-convergence R3b. The rework Reader never set the `isRead` flag — a parity gap that breaks
 * the Library `readCount` + the UNREAD filter after the route-swap. The legacy reader marked the
 * current chapter read on page-advance / next-chapter via `HistoryViewModel.markChapterAsRead(id)`
 * → `MangaRepository.markChapterAsRead` → `ChapterDao.markChapterAsRead(id)` =
 * `UPDATE saved_chapters SET isRead=1, lastReadDate=... WHERE id=:id`. This verb restores it.
 *
 * Read state is persisted in the legacy Room `saved_chapters.isRead` column, which feeds the
 * Library `readCount` + the UNREAD filter (`MangaDao.getAllChapterMetricsFlow` COUNT). The `:data`
 * impl is a strangler-fig over that legacy store so both readers — and the Library metrics — stay
 * consistent through the migration (see `MarkChapterReadRepositoryImpl`).
 *
 * **In-library only.** A `saved_chapters` row exists only once its manga is in the library, so read
 * state is meaningful only for in-library chapters. [markRead] is a no-op for a chapter with no row
 * — preserving legacy semantics (the legacy reader's mark-read only affected saved chapters, since
 * the `chapterId` was resolved from a saved row).
 *
 * **NOT incognito-gated** (legacy parity): the legacy `markChapterAsRead` was not behind the
 * incognito flag — only the History insert was. Read state tracks library progress, not a browsing
 * trail, so it is written unconditionally.
 */
interface MarkChapterReadRepository {
    /** Sets the chapter's `isRead` flag; no-op when the chapter has no in-library row. */
    suspend fun markRead(
        manga: Manga,
        chapterUrl: String,
    )

    /**
     * Toggles the chapter's `isRead` flag (read↔unread), keyed by the chapter's canonical source
     * `url` within [manga]. No-op when that owner has no in-library chapter row.
     *
     * GAP-LIB-02 (per-manga library chapter management on the rework Details screen). The native
     * `LibraryMangaScreen` exposed a per-chapter RemoveRedEye toggle that flipped the read flag in
     * both directions; [markRead] only sets it. This verb restores the toggle direction so the
     * Details chapter row can both mark-read and mark-unread, keeping the Library `readCount` +
     * UNREAD filter (`MangaDao` COUNT) consistent through Room invalidation.
     */
    suspend fun toggleRead(
        manga: Manga,
        chapterUrl: String,
    )

    /**
     * Bulk-marks every chapter in [chapterUrls] as READ. No-op for any url with no in-library row;
     * urls that DO resolve are marked in a single batched DAO write. Backs the multi-select
     * "mark read" action on the rework Details chapter list (GAP-LIB-02). Idempotent — already-read
     * chapters stay read.
     */
    suspend fun markRead(
        manga: Manga,
        chapterUrls: List<String>,
    )
}
