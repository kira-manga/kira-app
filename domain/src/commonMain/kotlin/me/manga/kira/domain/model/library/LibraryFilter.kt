package me.manga.kira.domain.model.library

/**
 * Filter axis for the Library grid.
 *
 * Each value names a predicate the VM evaluates against [me.manga.kira.domain.model.LibraryManga]
 * before the sort step in `applyView`. `ALL` is the identity (passes every row) and is the default
 * for [me.manga.kira.presentation.library.LibraryState.filter].
 *
 * Closed-set enum, exhaustive `when` enforced by the Kotlin compiler — adding a future value
 * (e.g. [BOOKMARKED], see below) forces every consuming arm to extend in the same commit.
 *
 * Semantic mapping to existing `LibraryManga` fields:
 *  - [ALL]        → identity, no predicate.
 *  - [DOWNLOADED] → `hasDownloads` (true when ≥1 chapter is fully downloaded locally).
 *  - [UNREAD]     → `unreadCount > 0` (at least one unread chapter).
 *  - [STARTED]    → `unreadCount < totalChapters` (read count > 0, i.e. user has opened ≥1 chapter).
 *  - [BOOKMARKED] → `bookmarkedCount > 0` (at least one chapter bookmarked). Added in Task #321's
 *                   tier-b lift; the `bookmarkedCount` field was plumbed through the mapper in
 *                   Commit A (`b5cc5b3`) without yet exposing it via this enum to keep the schema
 *                   lift isolated from the enum/`when`-arm extensions.
 *  - [COMPLETED]  → `totalChapters > 0 && unreadCount == 0` (every chapter read; guards against the
 *                   "0 of 0" edge case that would falsely flag empty libraries as completed).
 *
 * Declaration order matches native `LibraryViewModel.FilterType` (ALL, DOWNLOADED, UNREAD, STARTED,
 * BOOKMARKED, COMPLETED) so the filter chips render in the same order as the native sheet — the
 * chip row and the label helper both iterate `entries` in declaration order (P3 parity, audit
 * p3/library "Filter chip ordering"). Persistence is by-name, so the reorder does not affect any
 * stored filter preference.
 *
 * Mirrors the §150 (tier-a sort foundation) / §151 (tier-b sort lift) split: foundation closes the
 * scope that compiles today; tier-b lifts the schema. The two-slice cadence keeps each commit
 * small and bypasses block-and-ask trigger (a) [contract library blocker] / (c) [feature stops
 * compiling] by avoiding cross-layer schema work in a feature-shape commit. BOOKMARKED is now
 * wire-compatible with the legacy `LibraryViewModel.kt:96-103` `FilterTypes.BOOKMARKED` enum by
 * name — a future Phase 9.x route-swap will see the rework cover the full 6-axis legacy surface.
 *
 * **Audit-trail postscript** (Phase 9.x.library.staleKdocSweep.cascade,
 * Task #454, 2026-05-28): two stale legacy-path predictions above were
 * both resolved by post-§253 retire events:
 *  - Line 29 cites "the legacy `LibraryViewModel.kt:96-103`
 *    `FilterTypes.BOOKMARKED` enum" — the legacy
 *    `shared/.../features/library/ui/viewmodel/LibraryViewModel.kt` was
 *    retired in Phase 9.x.library.retire (§347, commit `2debbec`
 *    "(4/5): legacy VM + dead components"); verified by a filesystem
 *    check returning zero hits.
 *  - The "future Phase 9.x route-swap" prediction also landed: the
 *    rework Library is the LIVE Library surface post-Phase 9.x.library.swap
 *    (§346) — the wire-compatibility note above was confirmed retrospectively
 *    when the route-swap eliminated the legacy entry without enum
 *    renaming.
 * The semantic-mapping table and BOOKMARKED-via-bookmarkedCount predicate
 * stand on their own merits — they're documented inline above and are
 * independent of which legacy file originally carried the parity name.
 * Phase 10's i18n lift remains the canonical opportunity to localize the
 * filter labels. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical record
 * of the design lineage; the filter enum continues to drive applyView
 * correctly through the legacy retire.
 */
enum class LibraryFilter {
    ALL,
    DOWNLOADED,
    UNREAD,
    STARTED,
    BOOKMARKED,
    COMPLETED,
}
