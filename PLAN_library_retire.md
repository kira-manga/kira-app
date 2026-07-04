# Phase 9.x.library.retire — Retire legacy Library screen + parallel debug route (Task #347)

## Context

Phase 9.x.library.swap (§180, Task #346, commit `769d6a0`) flipped the
user-facing `Screen.Library` nav entry to render the rework
`:ui/.../library/LibraryScreen` via the rework
`:presentation/.../library/LibraryViewModel`. The legacy
`composeApp/.../features/library/ui/screens/LibraryScreen.kt` composable
(+ its `LibraryItems.kt` + `MangaCard.kt` siblings) and the legacy
`:shared/.../features/library/ui/viewmodel/LibraryViewModel.kt` are now
unreachable through `Screen.Library`. The parallel
`Screen.LibraryRework` debug route (set up by §164 / Task #164 as a
guarded debug entry) is also functionally redundant — both adapters
resolve the same rework `LibraryViewModel` via Koin and render the
same rework `LibraryScreen`.

This slice retires all unreachable legacy Library UI files + the legacy
VM + the parallel debug route. Mirrors §145's `Phase 9.aa.updates.
legacy_retire` posture (delete unreachable legacy UI files for a
feature whose route was swapped) — same "delete dead UI now that the
route-swap is permanent" rationale.

## Reachability verification

Performed via Grep (post-§180):

### Legacy `:shared/.../LibraryViewModel.kt`

`features\.library\.ui\.viewmodel\.LibraryViewModel` symbol-grep:
- `shared/.../di/SharedModule.kt:82, 353` — import + `viewModel {
  LibraryViewModel(...) }` Koin binding. Dropped by this slice.
- `composeApp/.../features/library/ui/screens/LibraryScreen.kt` —
  legacy screen import (deleted by this slice).
- `composeApp/.../features/library/ui/screens/LibraryItems.kt` —
  legacy screen-helper import (deleted by this slice).

After this slice: zero code consumers. The rework
`:presentation/.../library/LibraryViewModel.kt` is a distinct class in
a distinct module — different package, different MVI surface, different
constructor signature — and is unaffected.

### Legacy `composeApp` `LibraryScreen.kt`

`presentation\.features\.library\.ui\.screens\.LibraryScreen` symbol-
grep:
- `composeApp/.../navigation/routes/LibraryScreenRoute.kt` — the
  pre-§180 import. After §180 the rewrite already dropped this import;
  re-verified by re-reading the post-§180 file.
- `PLAN_library_swap.md` / `AUDIT_GOAL.md` — doc-only references.

After this slice: zero code consumers.

### Legacy `LibraryItems.kt` / `MangaCard.kt`

`LibraryItems`/`MangaCard` symbol-grep:
- `LibraryItems` only imported by `LibraryScreen.kt` (legacy, deleted).
- `MangaCard` only imported by `LibraryItems.kt` (legacy, deleted) +
  its own KDoc/test files (none).

After this slice: zero code consumers for either.

### Legacy `AnimatedPreloader` / `DisplayOptionsSection`

Both consumed solely by legacy `LibraryScreen.kt` (deleted in commit
3). Zero code consumers after this slice.

### Legacy `IconWithCount` / `EmptyLibraryPlaceholder`

- `IconWithCount` only imported by `MangaCard.kt` (deleted in commit
  3).
- `EmptyLibraryPlaceholder` only imported by `LibraryItems.kt`
  (deleted in commit 3).

Both become dead after commit 3; deleted in commit 4.

### Legacy `DownloadProgressDialog`

Symbol-grep — only its own definition. Already dead code (no imports
anywhere). Deleted in commit 4.

### Legacy `library_sheet/{CustomFilterBottomSheet, FilterChipsRow, SortOptionsSection}` — OUT OF SCOPE

These 3 sheet components are still imported by `composeApp/.../features/
library_details/ui/screens/LibraryMangaScreen.kt:64-66`. They remain
reachable through the unrelated `Screen.LibraryMangaDetails` legacy
route. Out of scope for this slice — a future
`Phase 9.x.librarydetails.retire` (or its equivalent rework + swap)
will retire them as part of the LibraryMangaScreen retirement.

### `Screen.LibraryRework` enum case

Symbol-grep for `Screen\.LibraryRework`:
- `composeApp/.../App.kt:578` — the `composable<Screen.LibraryRework>`
  block (dropped by commit 2).
- `composeApp/.../navigation/Screen.kt:106-133` — the enum case
  declaration + 3 KDoc references (dropped by commit 2).
- `composeApp/.../navigation/routes/LibraryReworkScreenRoute.kt` —
  the parallel debug adapter file (deleted by commit 2).
- Various `*ReworkScreenRoute.kt` files in sibling features — KDoc
  references to "the same posture as `Screen.LibraryRework`" pattern.
  These remain as historical references; the cross-references are
  load-bearing only as documentation, not as live symbols.

The 6 cross-reference KDocs in sibling rework adapters
(`SourcesScreenRoute.kt`, `ThemeReworkScreenRoute.kt`,
`AboutReworkScreenRoute.kt`, `RepoSettingsScreenRoute.kt`,
`LanguageReworkScreenRoute.kt`, `SourcesReworkScreenRoute.kt`,
`UpdatesReworkScreenRoute.kt`, `HistoryReworkScreenRoute.kt`,
`StatisticsReworkScreenRoute.kt`, `ChapterImagesReworkScreenRoute.kt`,
`MangaDetailsReworkScreenRoute.kt`) reference the pattern by name; they
become stale comment text once `Screen.LibraryRework` is gone.
Acceptable per §142's stale-reference policy.

## Approach

Pure-retirement sweep. 1 file edited (App.kt + Screen.kt + SharedModule.kt
across 3 commits — but each is a focused edit, not an additive change)
+ 9 files DELETED. Mirrors §145's pattern but at larger scale (Updates
retire was 3 files; Library retire is 9 deletions + 3 edits).

### Layer surfaces

- **`:core`** — unchanged.
- **`:domain`** — unchanged.
- **`:data`** — unchanged.
- **`:presentation`** — unchanged. The rework
  `:presentation/.../library/LibraryViewModel.kt` (distinct class in
  distinct module) is unaffected.
- **`:ui`** — unchanged. The rework
  `:ui/.../library/LibraryScreen.kt` is in a distinct module.
- **`:platform`** — unchanged.
- **`:composeApp`** — 8 files DELETED + 2 files EDITED:
  - `App.kt` — drop import + `composable<Screen.LibraryRework>` block
  - `navigation/Screen.kt` — drop enum case + KDoc references
  - `navigation/routes/LibraryReworkScreenRoute.kt` — DELETED
  - `presentation/features/library/ui/screens/LibraryScreen.kt` — DELETED
  - `presentation/features/library/ui/screens/LibraryItems.kt` — DELETED
  - `presentation/features/library/ui/screens/MangaCard.kt` — DELETED
  - `presentation/features/library/ui/components/AnimatedPreloader.kt` — DELETED
  - `presentation/features/library/ui/components/library_sheet/DisplayOptionsSection.kt` — DELETED
  - `presentation/features/library/ui/components/IconWithCount.kt` — DELETED
  - `presentation/features/library/ui/components/EmptyLibraryPlaceholder.kt` — DELETED
  - `presentation/features/library/ui/components/DownloadProgressDialog.kt` — DELETED
- **`:shared`** — 1 file DELETED + 1 file EDITED:
  - `di/SharedModule.kt` — drop import + `viewModel { LibraryViewModel(...) }` binding
  - `presentation/features/library/ui/viewmodel/LibraryViewModel.kt` — DELETED

### Strangler-fig boundary

This retirement does NOT change the strangler-fig boundary — the
`:shared` `MangaDao` + `LibraryDeo` remain the cells of truth for the
`saved_manga` Room table; the rework `:data` `LibraryRepositoryImpl`
continues to bridge to them; the rework `:presentation`
`LibraryViewModel` continues to depend on `:domain` `LibraryRepository`.
The retirement removes a leaf VM + 7 leaf UI files that no longer
participate in the boundary.

## Commit roadmap

Five commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_library_retire.md` only (1 file).

2. **Parallel debug route retire** — 3 files:
   - DELETE `composeApp/.../navigation/routes/LibraryReworkScreenRoute.kt`
   - EDIT `composeApp/.../App.kt` (drop import + composable block)
   - EDIT `composeApp/.../navigation/Screen.kt` (drop enum case + KDoc refs)

3. **Legacy Library UI retire** — 5 files DELETED:
   - `composeApp/.../features/library/ui/screens/LibraryScreen.kt`
   - `composeApp/.../features/library/ui/screens/LibraryItems.kt`
   - `composeApp/.../features/library/ui/screens/MangaCard.kt`
   - `composeApp/.../features/library/ui/components/AnimatedPreloader.kt`
   - `composeApp/.../features/library/ui/components/library_sheet/DisplayOptionsSection.kt`

4. **Legacy VM + remaining dead components retire** — 5 files:
   - EDIT `shared/.../di/SharedModule.kt` (drop import + viewModel binding)
   - DELETE `shared/.../features/library/ui/viewmodel/LibraryViewModel.kt`
   - DELETE `composeApp/.../features/library/ui/components/IconWithCount.kt`
   - DELETE `composeApp/.../features/library/ui/components/EmptyLibraryPlaceholder.kt`
   - DELETE `composeApp/.../features/library/ui/components/DownloadProgressDialog.kt`

5. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §181 — Phase 9.x.library.retire`.
   - `SOLID_AUDIT.md` — append Phase 9.x.library.retire entry.

Sequencing matters: commit 3 must precede commit 4 because the legacy
`LibraryScreen.kt` + `LibraryItems.kt` + `MangaCard.kt` import the
legacy `LibraryViewModel` + `IconWithCount` + `EmptyLibraryPlaceholder`.
Deleting the VM or those components first would break compilation of
the still-on-disk legacy screens. Deleting the screens first leaves the
VM + helpers as dead-but-compiling files that commit 4 cleans up.

## Critical files

### Deleted (in slice order)

- `composeApp/.../navigation/routes/LibraryReworkScreenRoute.kt`
- `composeApp/.../features/library/ui/screens/LibraryScreen.kt`
- `composeApp/.../features/library/ui/screens/LibraryItems.kt`
- `composeApp/.../features/library/ui/screens/MangaCard.kt`
- `composeApp/.../features/library/ui/components/AnimatedPreloader.kt`
- `composeApp/.../features/library/ui/components/library_sheet/DisplayOptionsSection.kt`
- `shared/.../features/library/ui/viewmodel/LibraryViewModel.kt`
- `composeApp/.../features/library/ui/components/IconWithCount.kt`
- `composeApp/.../features/library/ui/components/EmptyLibraryPlaceholder.kt`
- `composeApp/.../features/library/ui/components/DownloadProgressDialog.kt`

### Modified

- `composeApp/.../App.kt` — drop import + `composable<Screen.LibraryRework>` block (commit 2).
- `composeApp/.../navigation/Screen.kt` — drop enum case + KDoc refs (commit 2).
- `shared/.../di/SharedModule.kt` — drop import + viewModel binding (commit 4).
- `ARCHITECTURE.md` — append §181 (commit 5).
- `SOLID_AUDIT.md` — append Phase 9.x.library.retire entry (commit 5).

### Untouched (verified by recon)

- `composeApp/.../features/library/ui/components/library_sheet/CustomFilterBottomSheet.kt`
- `composeApp/.../features/library/ui/components/library_sheet/FilterChipsRow.kt`
- `composeApp/.../features/library/ui/components/library_sheet/SortOptionsSection.kt`

These 3 sheet components are still imported by
`LibraryMangaScreen.kt:64-66`. Out of scope; a future
LibraryMangaScreen retire slice will retire them.

- `:ui/.../library/LibraryScreen.kt` (rework path) — distinct file in
  a distinct module; unaffected.
- `:presentation/.../library/LibraryViewModel.kt` (rework path) —
  distinct class in distinct module; unaffected.
- `composeApp/.../navigation/routes/LibraryScreenRoute.kt` — already
  rewired to the rework path by §180 (commit `c1a32c2`); nothing here
  changes.
- `shared/.../features/library/domain/LibraryRepository.kt` — still
  live + still consumed by the rework `:data`
  `LibraryRepositoryImpl` strangler-fig. Out of scope.
- `composeApp/.../di/LibraryReworkModule.kt` — still live (the rework
  Koin module — distinct from `:shared`'s `SharedModule.kt`). Out of
  scope.

## Verification

After every source commit (commits 2-4):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (because
  `:composeApp/commonMain` + `:shared/commonMain` changes link into the
  Desktop entry point).

If any gate is RED, the retirement uncovered a transitive consumer the
recon missed. Two recovery options:
(a) extend the deletion scope to retire the consumer, or
(b) restore the deleted files (if the consumer is itself a load-
bearing facade), in which case this slice is parked pending a wider
recon. The recon above is thorough — (b) is not expected.

On-device smoke (deferred to user's Mac):
- `Screen.Library` (bottom-nav tab) — verify the rework `LibraryScreen`
  renders the identical library grid (sort / filter / display toggles /
  category tabs / per-card actions / pull-to-refresh / Random / search
  / delete-confirmation work identically to the post-§180 baseline).
  Same Room table, same rework path; the deletion only affects code
  that wasn't running.

## Deferrals

- **3 sheet components in `library_sheet/`** — still in use by
  `LibraryMangaScreen.kt`. Their retirement awaits a future
  LibraryMangaScreen retire slice (which itself awaits the rework's
  feature parity audit for LibraryMangaScreen — out of scope here).
- **Stale KDoc references to `Screen.LibraryRework` pattern** — 11
  sibling `*ReworkScreenRoute.kt` files reference the pattern by
  name. Acceptable per §142's stale-reference policy. A future
  doc-sweep slice can refresh them in one pass.
- **`SharedModule.kt` line-renumbering** — the file's KDoc comments
  reference Phase 9.5/9.7/9.8/9.9/10.x lines by position. Dropping
  one `viewModel { ... }` line shifts subsequent line numbers. Doc
  references in the KDoc body are by phase number / VM name, not by
  line, so they remain valid.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: This slice has one rule (retire all unreachable legacy
  Library files + the parallel debug route in 3 source commits + 1
  docs commit). No new files added, no behaviour changes, no
  abstraction introduced or removed.
- **OCP**: Removing closed-under-modification legacy code doesn't
  affect open/closed posture. The rework path
  (`LibraryScreenRoute.kt` → `LibraryViewModel` → `LibraryState` →
  `:ui/.../LibraryScreen.kt`) is unchanged.
- **DIP**: No dependency-direction changes. The deleted files were
  all leaf consumers (UI + a VM + a Koin binding); their removal
  doesn't affect any inversion.
- **Layer boundary**: changes touch `:composeApp` (8 files deleted +
  2 files edited) + `:shared` (1 file deleted + 1 file edited). No
  `:domain` / `:data` / `:presentation` / `:ui` / `:platform` / `:core`
  reach.
- **Banned features**: N/A — no code added.
- **MVI contract**: N/A — the retired UI files were NOT MVI
  surfaces (they predate the rework's MVI contract; the legacy
  `LibraryScreen.kt` takes plain callbacks, not an
  `Intent`/`State`/`Effect` triple).
- **Strangler-fig**: shrinks `:composeApp/.../features/library/`
  subtree to just `library_sheet/{CustomFilterBottomSheet,
  FilterChipsRow, SortOptionsSection}` + the (untouched, out-of-
  scope) `library_details/` sibling subtree + shrinks
  `:shared/.../features/library/ui/viewmodel/` subtree to empty.
- **Load-bearing fixes preserved**: this slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the Reader's
  decoder hints, the OkHttp interceptor, or any of the prior load-
  bearing image-quality posture. No load-bearing risk.

## Visual delta

None. The retired UI files were no-longer-rendered-anywhere post-
§180 — no user sees their output today. The retire of the parallel
`Screen.LibraryRework` debug route is also user-invisible because that
route was developer-only (gated by a hidden trigger). The sweep is
invisible to end-users; it only cleans the codebase.
