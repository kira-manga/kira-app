# Renames

> Per Section 7.5 of `MIGRATION_PROMPT.md`. Every rename, even a typo fix, must be recorded here.
>
> **Default policy in this migration: preserve all original names** — typos and all. Only rename when (a) the original name would not compile on KMP (e.g., uses a reserved keyword), or (b) the user explicitly approves. Anything else is recorded as `preserved` here.

| # | Old name / location | Rename to | Reason | Status |
|---|---|---|---|---|
| 1 | `LibraryDeo.kt` (DAO; should be `LibraryDao`) | (preserved) | Typo in source. User has not approved rename. | preserved |
| 2 | `StatisticsDeo.kt` (DAO; should be `StatisticsDao`) | (preserved) | Typo in source. User has not approved rename. | preserved |
| 3 | `MangaDerailsViewModel.kt` (should be `MangaDetailsViewModel`) | (preserved) | Typo in source. User has not approved rename. | preserved |
| 4 | `di/network/ConnectivityMudule.kt` (should be `ConnectivityModule`) | (preserved) | Typo in source filename. Class name inside file already correct or follows the same misspelling — to be confirmed in Phase 5. User has not approved rename. | preserved |
| 5 | `admin/dgfhldghlghg.kt` (gibberish filename) | (deleted) | Confirmed empty (`class dgfhldghlghg {}`). Moved verbatim in Phase 4 batch 4.7 with a migration-note comment. **Retired in Phase 9.x.placeholder.retire** (commit `2646c87`): 3-pass grep confirmed zero Kotlin callers; orphan source dropped. | retired |
| 6 | `data/remote/af.kt` (cryptic filename) | (deleted) | Confirmed empty (`class af`). Moved verbatim in Phase 4 batch 4.7. **Retired in Phase 9.x.placeholder.retire** (commit `2646c87`): 3-pass grep confirmed zero Kotlin callers; orphan source dropped. | retired |
| 7 | `google_play_cores/ss.kt` (cryptic filename) | (deferred — Android-only) | Confirmed empty (`class ss {}`). NOT moved this batch because the parent `google_play_cores/*` package is `platform_specific_keep` (Android Play services). Will land in `androidMain` during Phase 8 with the rest of `google_play_cores`. | deferred to Phase 8 |
| 8 | Package `sources_repositry.in` (`in` is a Kotlin soft keyword) | (preserved with backtick escapes) | `in` is a Kotlin soft keyword. KMP compilation requires backtick escaping at use sites (`` `in` ``). The package name stays `in` to preserve source layout. | preserved (workaround) |
| 9 | Package `sources_repositry` (looks like typo for `sources_repository`) | (preserved) | Source uses `sources_repositry`. Preserved. | preserved |
| 10 | `presentation/features/complaint/viewmodes/` (should be `viewmodels`) | (preserved) | Typo in directory name. Preserved. | preserved |
| 11 | `presentation/features/download/ui/test2/` (mid-refactor folder) | (preserved) | Indicates a mid-refactor state. Preserve until Phase 4 audit confirms it's load-bearing. | preserved |
| 12 | `DownloadViewModelv2.kt` (suffix `v2`) | (preserved) | Mid-refactor naming. Preserve. | preserved |
| 13 | Class `presentation/common/componants/...` (should be `components`) | (preserved) | Directory name typo throughout source. Preserved. | preserved |
| 14 | `HistoryItemD.kt` (`D` suffix unclear) | (preserved) | Preserved unless Phase 4 reveals it's a name collision. | preserved |
| 15 | `domain/model/MyData.kt` (generic name) | (deleted) | Inspected in Phase 4 batch 4.2. Single-field data class `data class MyData(val date: LocalDate? = null)`. Possibly a serialization smoke test or scratchpad. Moved verbatim. **Retired in Phase 9.x.mydata.retire** (commit `0d50e74`): 3-pass grep confirmed zero Kotlin callers across the migrated codebase; orphan source dropped under the §200/§202 precedent. | retired |
| 16 | `BatotoEnRepositoryv2.kt`, `TeamXRepositoryv2.kt`, `AasqRepositoryv2.kt`, `AzoraRepositoryv2.kt`, `LavatoonsRepositoryv2.kt`, `MangaLekRepositoryv2.kt`, `ManhwatopRepositoryV2.kt`, `MangaBuddyRepositoryV2.kt`, `DilarV2Repository.kt` (mixed v2/V2 casing) | (preserved) | Source uses inconsistent casing — preserve all individually. | preserved |
| 17 | `presentation/features/onboarding/asas.kt` (cryptic filename) | (deleted) | Confirmed empty (`class asas`). Was missed by Phase 9.x.placeholder.retire's sweep (commit `2646c87`) because it lives under `presentation/features/onboarding/` instead of the admin/data_remote/sources_repositry paths targeted then. **Retired in Phase 9.x.placeholder.asas.retire** (commit `044b99e`): 3-pass grep confirmed zero Kotlin callers; orphan source dropped. | retired |

## Rules

1. Any new rename must be appended here with reason + status.
2. Never silently rename or delete a file.
3. `pending Phase 4` items must be resolved before Phase 4 is marked complete.
