# Phase 9.ab.stale_kdoc.sweep — refresh stale KDoc references to retired symbols

Task #311. Pure doc-text slice; zero functional change; zero
behaviour-observable delta. Build gates run once at the end of the
sweep sub-commits to verify the KDoc edits didn't accidentally break
comment-block syntax.

## Context

The §142 / §143 / §144 / §145 retirement slices each landed KDoc
references-to-retired-symbol stale-prose as "acceptable per the
§142 policy" — non-load-bearing, descriptive history that doesn't
affect compilation or IDE autocomplete. The §145 close-out
enumerated this doc-sweep as the top non-blocked candidate.

This slice executes the sweep:

- Refreshes 13 stale KDoc references across 11 files
- Updates prose to reference the current rework symbol where one
  exists (e.g., `NotificationsViewModel` → `UpdatesViewModel`)
- Removes the stale reference where the prose's referent no longer
  exists in any form (e.g., `NotificationsUiState` is gone with no
  rework equivalent — the rework uses `UpdatesState`)
- Preserves the surrounding prose intent (the comment's purpose
  remains the same; only the symbol name updates)

## Recon

Grep over the rework branch surfaced 11 files containing references
to one of `NotificationsViewModel`, `TextViewModel`,
`OnboardingViewModel`, `UpdateItem`, `NotificationsUiState`:

### `:domain` (2 files)

1. `domain/.../repository/ThemeRepository.kt:26` — KDoc lists
   "(`SettingsViewModel`, `OnboardingViewModel`)" as legacy
   consumers of the legacy facade. `OnboardingViewModel` was
   retired in §143; replace with `LegacySettingsViewModel` and
   note the prior `OnboardingViewModel` is retired.
2. `domain/.../usecase/downloads/EnqueueDownloadUseCase.kt:26` —
   KDoc text references the now-deleted legacy `UpdateItem`
   composable for visual-parity context. Update to reference the
   current rework `UpdatesScreen` row composable.

### `:data` (2 files)

3. `data/.../repository/ThemeRepositoryImpl.kt:25` — KDoc lists
   "(`SettingsViewModel`, `OnboardingViewModel`)" as legacy
   consumers of the legacy facade. Same edit as #1.
4. `data/.../repository/LanguageRepositoryImpl.kt:26` — KDoc lists
   "(`SettingsViewModel`, `OnboardingViewModel`, the rework
   `ThemeRepositoryImpl`)". Drop `OnboardingViewModel`; preserve
   the live `SettingsViewModel` + rework `ThemeRepositoryImpl`
   references.

### `:presentation` (1 file)

5. `presentation/.../updates/UpdatesIntent.kt:74` — KDoc references
   the retired `NotificationsViewModel.deleteNotificationWithUndo`
   method. Update to reference the legacy
   `:shared` `NotificationRepository.deleteNotificationWithUndo` —
   the actual cell-of-truth, still live.

### `:shared` (2 files)

6. `shared/.../di/SharedModule.kt:345` — Comment "(same pattern as
   TextViewModel)" — `TextViewModel` retired in §144. Replace with
   a reference to one of the remaining `viewModel { ... }` bindings
   that follows the same pattern (e.g.,
   `SharedChaptersViewModel` which is the row's own
   anchor — making the parenthetical redundant — so simply
   drop the parenthetical).
7. `shared/.../notifications/domain/NotificationRepository.kt:26` —
   KDoc references the retired `NotificationsUiState` and "the UI
   layer". Update to reference the rework `UpdatesState` /
   `UpdatesScreen`.

### `:composeApp` (4 files)

8. `composeApp/.../di/ThemeReworkModule.kt:39` — KDoc lists
   "(OnboardingViewModel, SettingsViewModel, MainActivity's theme
   observer, etc.)". Drop `OnboardingViewModel`; the rest stay.
9. `composeApp/.../di/UpdatesReworkModule.kt:22, 34` — KDoc
   references the legacy `NotificationsViewModel` twice. Update to
   reference the rework `UpdatesViewModel` (which the module
   binds).
10. `composeApp/.../navigation/routes/ThemeSelectionScreenRoute.kt:
    31, 52, 104, 107` — Four KDoc references to
    `OnboardingViewModel`. Update prose to reference the rework
    `ThemeViewModel` where the comment described the rework, and
    note that the legacy `OnboardingViewModel` is retired where
    the comment described the prior posture.
11. `composeApp/.../navigation/routes/UpdatesScreenRoute.kt:17` —
    KDoc references the legacy `NotificationsViewModel`. Update to
    note retirement (the route binds the rework `UpdatesViewModel`
    via Koin).

## Approach

Pure KDoc text edits. Each edit replaces or refreshes prose
referencing a retired symbol. No imports change. No code logic
changes. No new files. No deletions.

For comment text that described "the legacy X (retired) / the
rework Y (current)" patterns, the replacement preserves the
contrast structure — just updates the names. For text that
listed retired-symbol-among-others as legacy consumers, the
edit drops the retired entry and preserves the live entries.

### Edit semantics

- `OnboardingViewModel` → drop reference (it is retired; if
  context requires naming a legacy consumer, fall back to
  `SettingsViewModel` which is still live).
- `NotificationsViewModel` → replace with rework
  `UpdatesViewModel` where the comment describes the rework path,
  or `NotificationRepository` where the comment describes the
  legacy data source (which is still live).
- `TextViewModel` → drop reference (it is retired; the comment's
  point about Koin SavedStateHandle auto-injection is now
  redundant since no other VM in the row uses SavedStateHandle —
  drop the parenthetical).
- `UpdateItem` → replace with the rework row composable (the row
  rendering logic lives inline in `ui/.../updates/UpdatesScreen.
  kt` post-§145).
- `NotificationsUiState` → replace with `UpdatesState` (the
  rework MVI state class).

## Commit roadmap

Five commits, all ≤5 files per the standing cap. Build gates run
after the last sweep commit (commit 4); KDoc text edits cannot
break compilation unless the edit malforms a comment-block
boundary, which a single end-of-sweep gate run catches.

1. **Plan commit** — `PLAN_kdoc_stalerefs.md` only (1 file).
2. **Sweep A** — `:domain` + `:data` files (4 files):
   - `domain/.../repository/ThemeRepository.kt`
   - `domain/.../usecase/downloads/EnqueueDownloadUseCase.kt`
   - `data/.../repository/ThemeRepositoryImpl.kt`
   - `data/.../repository/LanguageRepositoryImpl.kt`
3. **Sweep B** — `:presentation` + `:shared` files (3 files):
   - `presentation/.../updates/UpdatesIntent.kt`
   - `shared/.../di/SharedModule.kt`
   - `shared/.../notifications/domain/NotificationRepository.kt`
4. **Sweep C** — `:composeApp` files (4 files):
   - `composeApp/.../di/ThemeReworkModule.kt`
   - `composeApp/.../di/UpdatesReworkModule.kt`
   - `composeApp/.../navigation/routes/ThemeSelectionScreenRoute.kt`
   - `composeApp/.../navigation/routes/UpdatesScreenRoute.kt`

   **Build gates after commit 4** — all 4 (Android, iOS Arm64,
   iOS SimArm64, Desktop).

5. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §146 — Phase 9.ab.stale_kdoc.sweep`
     section.
   - `SOLID_AUDIT.md` — Phase 9.ab entry.

Total: 1 plan + 11 source files + 2 docs = 14 files across 5
commits. Each commit ≤5 files.

## Verification

After commit 4 only (KDoc text changes can't fail mid-sweep
without malforming comment syntax, which surfaces at the end):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop.

All four required because the touched files live in commonMain
which links into every target.

## SOLID & layer-boundary notes

- **SRP**: KDoc edits don't change responsibilities.
- **OCP**: KDoc edits don't affect extension/modification surface.
- **LSP**: N/A.
- **ISP**: KDoc edits don't change interface shape.
- **DIP**: KDoc edits don't change dependency direction.
- **Layer purity**: edits span `:domain`, `:data`, `:presentation`,
  `:shared`, `:composeApp`. Each edit stays within its file's
  existing layer; no new cross-layer references introduced.
- **Banned features**: none can be introduced by KDoc edits.
- **MVI contract**: unchanged.
- **Strangler-fig**: unchanged — `NotificationRepository`,
  `SettingsRepository`, etc. still live in `:shared`; the rework
  `:data` impls still strangler-fig over them. The KDoc edits
  document this posture more accurately, not less.
- **Load-bearing fixes preserved**: this slice does NOT touch any
  Coil/OkHttp/AVIF/Skia decoder configuration. No load-bearing
  risk.

## Visual delta

None. KDoc comments do not affect compiled output, IDE
autocomplete, or end-user behaviour. The slice's only visible
effect is to source-code readers (developers reading KDoc prose).

## Deferrals

- **ARCHITECTURE.md / SOLID_AUDIT.md prose references** — the
  retired-symbol names appear hundreds of times across these two
  large docs (audit history is descriptive of past slices and
  must preserve the symbol names that existed at the time). Those
  are out of scope; they are not "stale" — they are accurate
  historical records.
- **Comments referencing retired symbols inside comments that are
  themselves part of a larger explanation** — if a comment block
  is removed entirely as part of a future doc-cleanup, that's a
  separate effort. This slice only refreshes the symbol names; it
  does not restructure prose.

## Reuse

- **Stale-reference policy** — lifted from §142, which established
  "KDoc text referencing retired symbols is acceptable (not
  load-bearing)" as the standing rule. This slice elevates 13
  individual stale references from "accepted" to "refreshed" —
  same policy, finer state.
- **Sweep-pattern** — same 3-commit pattern as §142 / §143 /
  §144 / §145 (plan / sweep / docs), expanded to 5 commits to
  respect the 5-files cap on a wider sweep surface.
