package me.manga.kira.domain.model.library

/**
 * Density of the Library grid — how big each cover card is, and consequently how many cards fit
 * per row at a given viewport width.
 *
 * Each value names the user's preference for card size; the actual `dp` mapping that drives
 * `androidx.compose.foundation.lazy.grid.GridCells.Adaptive(minSize = ...)` lives in `:ui` (next
 * to the existing `LibraryFilter.label()` / `LibrarySort.label()` extensions in `LibraryScreen.kt`
 * — same `:ui`-resident-mapping posture). Keeps `:domain` platform/dp-neutral.
 *
 * Closed-set enum, exhaustive `when` enforced by the Kotlin compiler — adding a future value
 * (e.g. an `EXTRA_COMPACT`) forces every consuming arm (the `:ui` size mapping today, a future
 * `:data` persistence mapper later) to extend in the same commit.
 *
 * Semantic mapping (the `:ui` chooses the precise dp):
 *  - [COMPACT]     → more cards per row, smaller covers. Suits users with large libraries.
 *  - [COMFORTABLE] → the default — matches the foundation slice's pre-rework 120.dp adaptive cell.
 *  - [SPACIOUS]    → fewer, larger covers. Suits users with smaller libraries who want richer
 *                    visuals.
 *
 * SRP: this enum names the DISPLAY DENSITY, nothing else. The dp size mapping, the UI control
 * that lets the user pick a density, and the future persistence wire are all separate concerns
 * owned by their respective layers (`:ui`, `:ui`, `:data`).
 *
 * OCP: adding a new density is an enum-value append + a new `when` branch in `GridDensity.minSize()`
 * (the `:ui` extension) + (eventually) a new mapper arm in `LibraryPrefsRepositoryImpl`. No
 * call-site changes elsewhere.
 *
 * §150 ladder rung 7 (display-preferences foundation). Persistence lift follows in a separate
 * `display.persist` slice mirroring the §154 (filter persist) shape — fourth observe/set pair on
 * `LibraryPrefsRepository`, fresh `library_grid_density` key, fresh `String.toGridDensity()`
 * fallback mapper. Keeping the foundation and persistence in separate commits matches the
 * §150/§154 split: foundation closes the value-space that compiles today, persistence lifts the
 * disk surface.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster133.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twelfth sibling of the cluster57-132
 * sweep — second file of the wave-24 opener `:domain/model/library/`
 * 4-leaf-model batch alongside SortDirection plus LibraryCategory plus
 * LibraryDisplay):
 *  (a) "§6 SRP this-enum-names-the-DISPLAY-DENSITY-nothing-else + dp-
 *  size-mapping-the-UI-control-that-lets-the-user-pick-a-density-and-
 *  the-future-persistence-wire-are-all-separate-concerns-owned-by-their-
 *  respective-layers + actual-dp-mapping-drives-GridCells.Adaptive-
 *  minSize-lives-in-:ui-next-to-existing-LibraryFilter.label-LibrarySort.
 *  label-extensions-in-LibraryScreen.kt + keeps-:domain-platform-dp-
 *  neutral" — LIVE-NOT-STALE + FULFILLED-PREDICTION. LibraryScreen.kt
 *  L577-580 carries the `GridDensity.minSize()` extension function:
 *  COMPACT to 96.dp, COMFORTABLE to 120.dp, SPACIOUS to 160.dp. The
 *  GridCells.Adaptive(minSize = gridDensity.minSize()) wiring lands at
 *  LibraryScreen.kt L776. The KDoc forecast that the dp mapping lives
 *  in :ui is FULFILLED verbatim — the :domain enum names the density
 *  bucket only.
 *  (b) "§150 ladder rung 7 (display-preferences foundation) +
 *  persistence-lift-follows-in-separate-display.persist-slice-mirroring-
 *  §154-filter-persist-shape + fourth-observe-set-pair-on-LibraryPrefs-
 *  Repository + fresh-library_grid_density-key + fresh-String.toGrid-
 *  Density-fallback-mapper" — LIVE-NOT-STALE + FULFILLED-PREDICTION.
 *  Verified via grep: ObserveLibraryGridDensityUseCase plus SetLibrary-
 *  GridDensityUseCase landed (cluster129 sweep siblings); LibraryPrefs-
 *  Repository.observeGridDensity + setGridDensity present; LibraryPrefs-
 *  RepositoryImpl carries the `library_grid_density` disk key plus the
 *  String.toGridDensity fallback mapper. The §150-rung-7 forecast that
 *  persistence lifts in a separate slice mirroring §154 is FULFILLED
 *  verbatim — Task #323 (Phase 7.x.library.display.persist) landed the
 *  persistence wire as predicted.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  library.display.foundation-era prose preserved verbatim per the
 *  audit-trail-preservation convention.
 */
enum class GridDensity {
    COMPACT,
    COMFORTABLE,
    SPACIOUS,
}
