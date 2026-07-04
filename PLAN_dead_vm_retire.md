# Phase 9.z.dead_vm_retire — Retire :shared NotificationsViewModel + TextViewModel (Task #309)

## Context

Phase 9.y.onboardingvm_retire (§143, Task #308) closed at `6b13969`,
retiring the `:shared` `OnboardingViewModel` after the legacy onboarding
screens were deleted in §142. The §143 next-candidate enumeration named
`Phase 9.z.<vm>_retire` as the natural Rule 3 ladder follow-on —
recon-required per-VM retirements for the remaining `:shared` VMs.

Recon (below) reveals **two** clean candidates that share the
NotificationsViewModel-style posture (no caller, only Koin binding +
KDoc references): `NotificationsViewModel` and `TextViewModel`. Per the
§142 precedent (5 legacy files in one sweep) bundling both into a single
slice is more efficient than doing them sequentially and stays well
under the 5-file cap.

## Reachability verification

Performed via Grep across `**/*.kt` AND `**/*.{swift,h,m,kts,gradle,xml}`:

### NotificationsViewModel

- `shared/.../NotificationsViewModel.kt:17` — class declaration itself.
- `shared/.../SharedModule.kt:22` — import.
- `shared/.../SharedModule.kt:324` — `viewModel { NotificationsViewModel(
  get()) }` Koin binding.
- `composeApp/.../UpdatesReworkModule.kt:22, 34` — KDoc comment text only.
- `composeApp/.../UpdatesScreen.kt:62` — KDoc comment text only (legacy
  screen file body takes plain parameters, NOT a VM reference).
- `composeApp/.../UpdatesScreenRoute.kt:17` — KDoc comment text only
  (post-`Phase 7.x.updates.swap` adapter resolves the REWORK
  `UpdatesViewModel`, NOT this class).
- `presentation/.../UpdatesIntent.kt:74` — KDoc comment text only.
- `koinViewModel<NotificationsViewModel>()` symbol-grep: 1 match, in
  KDoc-comment context only (line 62 of legacy `UpdatesScreen.kt`).
- `:.*NotificationsViewModel\s*=\s*koinViewModel` symbol-grep: NO
  MATCHES. No live resolution site.
- iOS / Android-glue / Gradle / XML grep: NO MATCHES.

### TextViewModel

- `shared/.../TextViewModel.kt:9` — class declaration itself.
- `shared/.../SharedModule.kt:27` — import.
- `shared/.../SharedModule.kt:344` — `viewModel { TextViewModel(get()) }`
  Koin binding.
- `shared/.../SharedModule.kt:343, 350` — KDoc comment text only (lines
  reference TextViewModel-style SavedStateHandle pattern in adjacent
  comments).
- `:.*TextViewModel\s*=\s*koinViewModel` symbol-grep: NO MATCHES. No
  live resolution site.
- iOS / Android-glue / Gradle / XML grep: NO MATCHES.

Both classes are clean strangler-fig leaves: bound by Koin but not
resolved by anyone. The KDoc references are stale-reference comments
documenting historical context; harmless and acceptable per §142's
stale-reference policy (a future doc-sweep slice can refresh them).

## Approach

Pure-retirement slice. Three files affected total:

- **DELETED**: `shared/.../NotificationsViewModel.kt` (99 lines — exposes
  `uiState: StateFlow<NotificationsUiState>` + `markAsRead` /
  `markAllAsRead` / `deleteAll` / `delete` / `deleteWithUndo` /
  `undoDelete` / `confirmDelete` mutations, all backed by
  `NotificationRepository`).
- **DELETED**: `shared/.../TextViewModel.kt` (28 lines — minimal counter
  stub exposing `number: StateFlow<Int>` + `increase()` backed by
  `SavedStateHandle["counter"]`. Likely a Phase 9.3-era debug stub
  that was never deleted).
- **MODIFIED**: `shared/.../SharedModule.kt` — drop:
  - line 22 (import `NotificationsViewModel`)
  - line 27 (import `TextViewModel`)
  - line 324 (binding `viewModel { NotificationsViewModel(get()) }`)
  - line 343 (orphan comment "// TextViewModel takes a SavedStateHandle
    which Koin auto-provides for viewModel { ... }.")
  - line 344 (binding `viewModel { TextViewModel(get()) }`)

  Surrounding lines (the Phase-9.6 / Phase-9.3 / Phase-9.8 `// ----`
  band comments + the remaining bindings) are untouched. The lines at
  349-354 that reference TextViewModel by name in a comparison
  ("same pattern as TextViewModel") become stale; acceptable per §142
  policy.

The `NotificationRepository` Koin binding is unaffected — it is consumed
by the rework `UpdatesRepositoryImpl` (a strangler-fig over the legacy
facade) via `updatesReworkModule`, plus other legacy consumers in the
unswapped chain. `SavedStateHandle` is platform-provided by Koin
automatically; no binding to remove.

### Layer surfaces

- **`:core`** — unchanged.
- **`:domain`** — unchanged.
- **`:data`** — unchanged. The two stale KDoc reference lines in
  `FeedbackRepositoryImpl.kt:28` + `ComplaintListRepositoryImpl.kt:18`
  reference `ComplaintViewModel`, not the retired classes.
- **`:presentation`** — unchanged. The `UpdatesIntent.kt:74` KDoc
  reference becomes stale.
- **`:ui`** — unchanged.
- **`:platform`** — unchanged.
- **`:composeApp`** — unchanged. The 4 KDoc references in
  `UpdatesReworkModule.kt` / `UpdatesScreen.kt` / `UpdatesScreenRoute.kt`
  become stale.
- **`:shared`** — 2 files DELETED + 1 file MODIFIED (the only module
  touched):
  - `shared/.../presentation/features/notifications/ui/viewmodel/
    NotificationsViewModel.kt` — DELETED.
  - `shared/.../presentation/features/text/viewmodel/TextViewModel.kt`
    — DELETED.
  - `shared/.../di/SharedModule.kt` — 2 imports + 2 binding lines + 1
    orphan comment removed.

### Strangler-fig boundary

This retirement shrinks the `:shared` strangler-fig surface by **two**
VM bindings. The remaining `:shared` VMs (`HomeViewModel`,
`RepoSettingsViewModel`, `DownloadViewModelv2`, `WebViewViewModel`,
`MangaViewModel`, `LibraryViewModel`, `LibraryDetailsViewModel`,
`RefreshViewModel`, `ReaderViewModel`, `ChaptersViewModel`,
`SharedChaptersViewModel`, `MangaDerailsViewModel`, `HistoryViewModel`,
`StatisticsViewModel`, `LanguageViewModel`, `SettingsViewModel`,
`WhatsNewViewModel`, `ComplaintViewModel`, `AdminComplaintViewModel`)
continue to participate in the boundary — each has at least one live
caller (verified by the `koinViewModel<X>=` resolution-site grep
above) and requires its own retire-after-screen-retire sequence
before retirement can land.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_dead_vm_retire.md` only (1 file).
2. **Retirement sweep** — 3 files:
   - `shared/.../NotificationsViewModel.kt` (DELETED).
   - `shared/.../TextViewModel.kt` (DELETED).
   - `shared/.../di/SharedModule.kt` (MODIFIED — drop 2 imports + 2
     bindings + 1 orphan comment).
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §144 — Phase 9.z.dead_vm_retire —
     Retire :shared NotificationsViewModel + TextViewModel` section.
   - `SOLID_AUDIT.md` — append `# Phase 9.z.dead_vm_retire — Retire
     :shared NotificationsViewModel + TextViewModel (Task #309)` entry
     with the retirement-slice SOLID 10-point checklist + per-file
     verdicts.

## Critical files

### Deleted

- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/notifications/ui/viewmodel/NotificationsViewModel.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/text/viewmodel/TextViewModel.kt`

### Modified

- `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt` —
  remove 2 imports + 2 bindings + 1 orphan comment.
- `ARCHITECTURE.md` — append §144.
- `SOLID_AUDIT.md` — append Phase 9.z.dead_vm_retire entry.

### Untouched (verified by recon)

- `:shared` `NotificationRepository` (the only dep of
  `NotificationsViewModel`) — still bound, still consumed by the rework
  `UpdatesRepositoryImpl` (strangler-fig over this facade) plus other
  legacy consumers.
- `:shared` `SavedStateHandle` (the only dep of `TextViewModel`) —
  Koin auto-provides this for `viewModel { ... }` factories; no binding
  to remove.
- Stale KDoc comment references in rework `:ui` / route adapter /
  `:data` / `:presentation` / `ARCHITECTURE.md` / `SOLID_AUDIT.md` /
  migration log files — acceptable per §142's stale-reference policy.
- The empty package directory trees on the filesystem
  (`shared/.../presentation/features/notifications/ui/viewmodel/` and
  `shared/.../presentation/features/text/viewmodel/`) after the file
  deletions — harmless cosmetic leftovers. Git doesn't track empty
  dirs.

## Verification

After the retirement sweep:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (because
  `:shared/commonMain` changes link into the Desktop entry point).

If any gate is RED, the retirement uncovered a transitive consumer
(e.g., a `koinInject<NotificationsViewModel>()` in `:shared`-internal
init logic that the symbol-grep missed). In that case, investigate the
build error and either:
(a) extend the deletion scope to retire the dangling consumer, or
(b) restore the deleted file (if the consumer is itself a load-bearing
facade), in which case this slice is parked pending a wider recon. The
recon above is thorough enough that (b) is not expected for either VM.

On-device smoke (deferred to user's Mac):

- Updates feed — open the bottom-nav Updates tab. The rework
  `UpdatesViewModel` (Koin-bound via `updatesReworkModule`) should
  render the same grouped notification list, with mark-read /
  mark-all-read / delete / delete-with-undo / per-row download
  affordances all behaving identically to the post-§134 baseline.
- No surface anywhere depends on `TextViewModel` (it was a debug
  counter stub never wired into production navigation); on-device
  parity is trivially preserved.

## Deferrals

- **Stale KDoc doc-sweep** — the 4 rework-side files mentioning
  `NotificationsViewModel` + the 2 `SharedModule.kt` lines (343/350)
  mentioning `TextViewModel` by name become stale. Acceptable per
  §142's policy; not load-bearing. A future doc-sweep slice can refresh
  them alongside the §142/§143 stale-KDoc list.
- **Other `:shared` VM retirements** — every remaining `:shared` VM
  has at least one live `koinViewModel<X>()` resolution site
  (enumerated above). Per-VM retirement requires the corresponding
  legacy screen file to be retired first (analogous to §142 retiring
  the 5 legacy onboarding screens before §143 could retire the
  `OnboardingViewModel`). Out of scope here.
- **Phase 9.x.onboarding.cleanup (collapse step 3 + step 4)** —
  cosmetic; out of scope.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: This slice has one rule (retire 2 unreachable VM classes +
  their Koin bindings). No new files added, no behaviour changes, no
  abstraction introduced or removed.
- **OCP**: Removing closed-under-modification legacy code doesn't
  affect open/closed posture. The rework path's `UpdatesViewModel`
  (which subsumes the `NotificationsViewModel` surface) is unchanged.
- **DIP**: No dependency-direction changes. `NotificationsViewModel`
  depended on `:shared` `NotificationRepository`; `TextViewModel`
  depended on `SavedStateHandle` (Koin-provided). Both relationships
  were internal to `:shared`. Retirement reduces, not changes, the
  graph.
- **Layer boundary**: changes touch `:shared` only (2 files deleted +
  1 file modified). No `:domain` / `:data` / `:presentation` / `:ui`
  / `:platform` / `:core` / `:composeApp` reach.
- **Banned features**: N/A — no code added.
- **MVI contract**: N/A — neither retired VM was an MVI VM (both
  predate the rework's MVI contract — `NotificationsViewModel`
  exposes a single `StateFlow<NotificationsUiState>` + direct method
  mutations, `TextViewModel` exposes `StateFlow<Int>` + an
  `increase()` method; neither has an `Intent`/`State`/`Effect`
  surface). Retirement removes two non-MVI surfaces; no MVI contract
  delta.
- **Strangler-fig**: shrinks `:shared` surface by two bindings. The
  remaining `:shared` VMs + repository facades are out of scope.
- **Load-bearing fixes preserved**: this slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the Reader's
  decoder hints, the OkHttp interceptor, or any of the prior load-
  bearing image-quality posture (neither retired VM has any image
  handling). No load-bearing risk.

## Visual delta

None. Both retired VMs are no-longer-resolved-anywhere — no user sees
their output today. The sweep is invisible to end-users; it only
cleans the codebase.
