package me.manga.kira.domain.model.library

import me.manga.kira.domain.model.LibraryManga

/**
 * The ordering criterion the user has chosen for the Library grid.
 *
 * Each mode names the [LibraryManga] field that drives the comparator — see
 * `me.manga.kira.presentation.library.LibraryViewModel.applyView` for the
 * pipeline that consumes this enum.
 *
 * SRP: this enum names the ORDERING, nothing else. Pairs with [SortDirection] (ascending vs
 * descending) and the optional random-seed state field on the ViewModel for the RANDOM mode's
 * stable-shuffle behaviour. Display strings live in the `:ui` layer (string resources lift in
 * Phase 10).
 *
 * OCP: adding a new mode is an enum-value append + a new `when` branch in `applyView` and
 * `LibrarySort.label()`. No call-site changes elsewhere.
 *
 * **Audit-trail postscript** (Phase 9.x.library.staleKdocSweep.cascade,
 * Task #454, 2026-05-28): two stale line-anchored citations into the
 * §347-retired legacy LibraryViewModel.kt appear on per-variant KDocs
 * below:
 *  - The [LAST_READ] KDoc cites "the legacy `MAX(c.lastReadDate)` pipeline
 *    at `LibraryViewModel.kt:429`".
 *  - The [RANDOM] KDoc cites "the legacy `LibraryViewModel.kt:438`
 *    `sort == SortType.RANDOM || asc` branch".
 * The legacy `shared/.../features/library/ui/viewmodel/LibraryViewModel.kt`
 * was retired in Phase 9.x.library.retire (§347, commit `2debbec`
 * "(4/5): legacy VM + dead components"); verified by a filesystem check
 * returning zero hits for that path. The behavioural rationales stand on
 * their own merits — LAST_READ's null-mapping-to-MAX_VALUE strategy and
 * RANDOM's ignore-direction-and-use-seed strategy are documented inline
 * above and are independent of which legacy file originally implemented
 * the parity precedent. Original §253-era prose preserved verbatim per
 * the audit-trail-preservation convention — the citations are historical
 * record of the design lineage; the sort enum continues to drive the
 * applyView pipeline correctly through the legacy retire.
 */
// Declaration order is load-bearing: the Library sort sheet renders one chip per
// `LibrarySort.entries` in declaration order, so this list matches native `SortType`
// (ALPHABETIC, TOTAL_CHAPTERS, LAST_READ, UNREAD_COUNT, DATE_ADDED, RANDOM) for chip-order parity.
// Reordering is safe: persistence is by-name (LibraryPrefsRepositoryImpl writes `sort.name`) and
// every `when` over this enum in applyView/librarySortLabel is value-keyed, not position-keyed.
enum class LibrarySort {
    /** Lexicographic by [LibraryManga.manga].title, case-insensitive. */
    ALPHABETIC,

    /**
     * Numerical by [LibraryManga.totalChapters]. Mirrors the legacy `SortType.TOTAL_CHAPTERS`
     * branch — orders by absolute chapter count (NOT unread/read ratio).
     */
    TOTAL_CHAPTERS,

    /**
     * Chronological by [LibraryManga.lastReadAt] — the timestamp of the most-recently read
     * chapter. "Never read" entries (`lastReadAt = null`) sort to the bottom in ASCENDING
     * direction (Kotlin's `sortedBy` puts nulls first by default; we explicitly map
     * `lastReadAt` to `Long.MAX_VALUE` for nulls so they sink under read entries in
     * ascending order — descending swaps them to the top, which mirrors the legacy
     * `MAX(c.lastReadDate)` pipeline at `LibraryViewModel.kt:429`).
     */
    LAST_READ,

    /** Numerical by [LibraryManga.unreadCount]. */
    UNREAD_COUNT,

    /** Chronological by [LibraryManga.addedAt]. */
    DATE_ADDED,

    /**
     * Stable random order. The shuffle is computed against a seed stored in
     * `LibraryState.randomSeed` so re-emissions of the library [kotlinx.coroutines.flow.Flow]
     * (e.g., after a refresh) keep the same order. Picking RANDOM again regenerates the seed
     * to give the user a new shuffle. [SortDirection] is ignored for this mode (mirrors the
     * legacy `LibraryViewModel.kt:438` `sort == SortType.RANDOM || asc` branch).
     */
    RANDOM,
}
