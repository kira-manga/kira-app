package me.manga.kira.domain.model.library

/**
 * Category-tab axis for the Library grid.
 *
 * Each value names a per-manga affinity flag the VM evaluates against
 * [me.manga.kira.domain.model.LibraryManga] BEFORE the [LibraryFilter] step in `applyView`.
 * [NAN] is the identity (passes every row — "no category narrowing") and is the default for
 * [me.manga.kira.presentation.library.LibraryState.category].
 *
 * Closed-set enum, exhaustive `when` enforced by the Kotlin compiler — adding a future value
 * (e.g. a `READ_LATER` if a fourth affinity column ever gets added to `SavedMangaEntity`) forces
 * every consuming arm (the VM's `applyView` filter step, the `:ui` tab-row, a future
 * persistence mapper) to extend in the same commit.
 *
 * Semantic mapping to the existing `LibraryManga.isLiked` / `isWatchingNow` fields (which the
 * `:data` mapper now passes through from the long-standing `SavedMangaEntity.isLiked` /
 * `isWatchingNow` columns — no schema migration required):
 *  - [NAN]           → identity, no predicate. Matches the legacy `FilterTabs.NAN` "all"
 *                      tab — every library row passes through regardless of affinity flags.
 *  - [LIKED]         → `isLiked == true`. Mirrors the legacy `FilterTabs.LIKED` tab; the
 *                      `isLiked` column is toggled by the Details screen's heart icon.
 *  - [WATCHING_NOW]  → `isWatchingNow == true`. Mirrors the legacy `FilterTabs.WATCHING_NOW`
 *                      tab; the `isWatchingNow` column is toggled by the legacy
 *                      "watching now" mark (still owned by `:shared` until a later slice
 *                      ports that mutation to `:domain`).
 *
 * Orthogonality vs [LibraryFilter]: category narrows by per-manga affinity (heart, watching);
 * filter narrows by per-chapter status (downloaded, unread, completed, bookmarked). Both apply
 * simultaneously — the VM intersects them in `applyView`. Legacy `FilterTabs` and `FilterTypes`
 * are the same two orthogonal axes (`LibraryViewModel.kt:96-103` `FilterTypes` vs `:107-110`
 * `FilterTabs`); the rework matches that split.
 *
 * §150 ladder rung 9 (category-tabs foundation). Persistence lift follows in a separate
 * `category.persist` slice mirroring the §154 (filter persist) / §157 (display persist) shape:
 * fifth observe/set pair on [me.manga.kira.domain.repository.LibraryPrefsRepository], fresh
 * `library_category` disk key, fresh `String.toLibraryCategory()` fallback mapper. Keeping the
 * foundation and persistence in separate commits matches the §150/§154/§157 cadence: foundation
 * closes the value-space that compiles today; persistence lifts the disk surface.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster133.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-thirteenth sibling of the cluster57-132
 * sweep — third file of the wave-24 opener `:domain/model/library/`
 * 4-leaf-model batch alongside SortDirection plus GridDensity plus
 * LibraryDisplay):
 *  (a) "Category-tab-axis-for-the-Library-grid + each-value-names-per-
 *  manga-affinity-flag-the-VM-evaluates-against-LibraryManga-BEFORE-the-
 *  LibraryFilter-step-in-applyView + NAN-is-the-identity-passes-every-
 *  row-no-category-narrowing + default-for-LibraryState.category" —
 *  LIVE-NOT-STALE + FULFILLED-PREDICTION. Verified via grep: LibraryState.
 *  category default value is LibraryCategory.NAN; LibraryViewModel.
 *  applyView evaluates the category predicate BEFORE LibraryFilter
 *  narrowing; LibraryScreen.kt L607-628 carries the
 *  `LibraryCategory.entries.indexOf(category)` selected-index derivation
 *  plus the per-value `LibraryCategory.label()` extension ("All" for NAN
 *  to match legacy "no narrowing" semantic, "Liked", "Watching"). The
 *  three-way affinity axis (NAN / LIKED / WATCHING_NOW) is preserved
 *  byte-for-byte across legacy FilterTabs.
 *  (b) "Orthogonality vs LibraryFilter + category-narrows-by-per-manga-
 *  affinity-heart-watching + filter-narrows-by-per-chapter-status-
 *  downloaded-unread-completed-bookmarked + both-apply-simultaneously-
 *  VM-intersects-them-in-applyView + legacy-FilterTabs-and-FilterTypes-
 *  are-the-same-two-orthogonal-axes" — LIVE-NOT-STALE + FULFILLED-
 *  PREDICTION. The two-axis intersection is preserved verbatim in
 *  LibraryViewModel.applyView — category narrowing precedes filter
 *  narrowing; both apply simultaneously; legacy FilterTabs vs FilterTypes
 *  split is mirrored. Data-layer mapper LibraryMappers.kt threads the
 *  isLiked + isWatchingNow columns from SavedMangaEntity through to
 *  LibraryManga without schema migration as predicted.
 *  (c) "§150 ladder rung 9 (category-tabs foundation) + persistence-
 *  lift-follows-in-separate-category.persist-slice-mirroring-§154-§157-
 *  shape + fifth-observe-set-pair-on-LibraryPrefsRepository + fresh-
 *  library_category-disk-key + fresh-String.toLibraryCategory-fallback-
 *  mapper" — LIVE-NOT-STALE + FULFILLED-PREDICTION. Verified via grep:
 *  ObserveLibraryCategoryUseCase plus SetLibraryCategoryUseCase landed
 *  (cluster130 sweep siblings); LibraryPrefsRepository.observeCategory +
 *  setCategory present; LibraryPrefsRepositoryImpl carries the
 *  library_category disk key plus the String.toLibraryCategory fallback
 *  mapper. The §150-rung-9 forecast that persistence lifts in a separate
 *  slice mirroring §154 + §157 is FULFILLED verbatim — Task #325 (Phase
 *  7.x.library.category.persist) landed the persistence wire as predicted.
 *  Three classifications STAND on their own merits. Original Phase 7.x.
 *  library.category.foundation-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
enum class LibraryCategory {
    NAN,
    // P2 parity fix (audit p2/library, "Category tabs ordering"): native FilterTabs enum order is
    // NAN, WATCHING_NOW, LIKED (native LibraryViewModel.kt:88-89), which drives the tab render order
    // (All, Watching Now, Likes) via `entries`. The rework had WATCHING_NOW/LIKED swapped, rendering
    // the 2nd/3rd tabs in the wrong slots. The `when` arms in the VM's applyView and the `:ui`
    // tab-row are value-keyed (not ordinal-keyed), so this reorder is behaviour-preserving for the
    // filter pipeline; it only fixes the visual/positional tab order to match native.
    WATCHING_NOW,
    LIKED,
}
