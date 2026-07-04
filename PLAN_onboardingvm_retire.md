# Phase 9.y.onboardingvm_retire — Retire :shared OnboardingViewModel (Task #308)

## Context

Phase 9.x.onboarding.legacy_retire (§142, Task #307) deleted the 5 legacy
onboarding UI files inside `composeApp/.../presentation/features/
onboarding/`. With those gone, the `:shared` `OnboardingViewModel`
(located at `shared/.../presentation/features/onboarding/viewmodel/
OnboardingViewModel.kt`) is **structurally unreachable**: there is no
production code remaining that resolves `koinViewModel<OnboardingViewModel
>()`, `koinInject<OnboardingViewModel>()`, or `get<OnboardingViewModel>()`.

This slice retires the now-dead class + its `SharedModule.kt` Koin binding
in a single sweep. Identified as the **next non-blocked candidate** in the
Rule 3 ladder by the Phase 9.x.onboarding.legacy_retire close-out (§142
SOLID_AUDIT next-candidate enumeration).

## Reachability verification

Performed via Grep (post-Task #307):

- `OnboardingViewModel` symbol-grep across `**/*.kt`:
  - `shared/.../OnboardingViewModel.kt:10` — class declaration itself.
  - `shared/.../SharedModule.kt:23` — import.
  - `shared/.../SharedModule.kt:326` — `viewModel { OnboardingViewModel(
    get()) }` Koin binding.
  - `composeApp/.../navigation/routes/ThemeSelectionScreenRoute.kt:31,
    52, 104, 107` — KDoc comment text only.
  - `composeApp/.../di/ThemeReworkModule.kt:39` — KDoc comment text only.
  - `data/.../ThemeRepositoryImpl.kt:25` — KDoc comment text only.
  - `data/.../LanguageRepositoryImpl.kt:26` — KDoc comment text only.
  - `domain/.../ThemeRepository.kt:26` — KDoc comment text only.
- `OnboardingViewModel` symbol-grep across `**/*.{swift,h,m,kts,gradle,
  xml}`: NO MATCHES (no iOS-side, no Android-glue, no build-script
  reach).
- `koinViewModel|getViewModel|get\(\).*OnboardingViewModel|
  OnboardingViewModel\(\)` symbol-grep: only the binding line at
  `SharedModule.kt:326`. No resolution call sites in production code.

The class is a clean strangler-fig leaf: bound by Koin but not resolved
by anyone. The KDoc comment references in rework files are stale
references documenting historical context; they are harmless and a
future doc-sweep slice can refresh them (already noted in §142's
stale-reference posture section).

## Approach

Pure-retirement slice. Two files affected total:

- **DELETED**: `shared/.../OnboardingViewModel.kt` (28 lines — minimal VM
  exposing `darkMode: StateFlow<Boolean>` + `followSystem: StateFlow<
  Boolean>` + `toggleDarkMode(on)` + `toggleFollowSystem(on)`, all backed
  by `SettingsRepository`).
- **MODIFIED**: `shared/.../SharedModule.kt` — drop line 23 (import) +
  line 326 (Koin binding `viewModel { OnboardingViewModel(get()) }`).
  Surrounding lines are untouched.

The `SettingsRepository` Koin binding is unaffected — it's consumed by
other VMs (e.g., `SettingsViewModel`) which keep it live.

### Layer surfaces

- **`:core`** — unchanged.
- **`:domain`** — unchanged. The `ThemeRepository.kt:26` stale KDoc
  reference becomes one of the stale-reference comments per §142's
  policy (acceptable; future doc-sweep).
- **`:data`** — unchanged. Stale KDoc references in
  `ThemeRepositoryImpl.kt:25` + `LanguageRepositoryImpl.kt:26` become
  stale comments (same policy).
- **`:presentation`** — unchanged.
- **`:ui`** — unchanged.
- **`:platform`** — unchanged.
- **`:composeApp`** — unchanged. Stale KDoc references in
  `ThemeReworkModule.kt:39` + `ThemeSelectionScreenRoute.kt:31, 52,
  104, 107` become stale comments (same policy).
- **`:shared`** — 1 file DELETED + 1 file MODIFIED (the only module
  touched):
  - `shared/.../presentation/features/onboarding/viewmodel/Onboarding
    ViewModel.kt` — DELETED.
  - `shared/.../di/SharedModule.kt` — import + Koin binding removed.

### Strangler-fig boundary

This retirement shrinks the `:shared` strangler-fig surface by one VM
binding. The remaining `:shared` VMs (`NotificationsViewModel`,
`WebViewViewModel`, `HomeViewModel`, etc.) and the legacy repositories
they consume continue to participate in the boundary until their own
retirement slices.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_onboardingvm_retire.md` only (1 file).
2. **Retirement sweep** — 2 files:
   - `shared/.../OnboardingViewModel.kt` (DELETED).
   - `shared/.../di/SharedModule.kt` (MODIFIED — drop import + binding).
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §143 — Phase 9.y.onboardingvm_retire
     — Retire :shared OnboardingViewModel` section.
   - `SOLID_AUDIT.md` — append `# Phase 9.y.onboardingvm_retire — Retire
     :shared OnboardingViewModel (Task #308)` entry with the retirement-
     slice SOLID 10-point checklist + per-file verdicts.

## Critical files

### Deleted

- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/viewmodel/OnboardingViewModel.kt`

### Modified

- `shared/src/commonMain/kotlin/me/manga/yamiapk/di/SharedModule.kt` —
  remove line 23 (import) + line 326 (binding).
- `ARCHITECTURE.md` — append §143.
- `SOLID_AUDIT.md` — append Phase 9.y.onboardingvm_retire entry.

### Untouched (verified by recon)

- `:shared` `SettingsRepository` (the only dep of `OnboardingViewModel`)
  — still bound, still consumed by other VMs.
- Stale KDoc comment references in rework `:ui` / route adapter / `:data`
  / `:domain` / `ARCHITECTURE.md` / `SOLID_AUDIT.md` / migration log
  files — acceptable per §142's stale-reference policy.
- The onboarding-package empty directory tree on the filesystem
  (`shared/.../presentation/features/onboarding/viewmodel/`) — harmless
  cosmetic leftover after the file deletion. Git doesn't track empty
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
(e.g., a `koinInject<OnboardingViewModel>()` in `:shared`-internal init
logic that the symbol-grep missed). In that case, investigate the build
error and either:
(a) extend the deletion scope to retire the dangling consumer, or
(b) restore the deleted file (if the consumer is itself a load-bearing
facade), in which case this slice is parked pending a wider recon. The
recon above is thorough enough that (b) is not expected.

On-device smoke (deferred to user's Mac):

- Fresh install — launch through Welcome → Theme → Sources →
  RepoSettings(isFirstOpen=true) → Library. The rework path is the ONLY
  path; verify it works end-to-end with no visual or behavioural
  regression vs the post-§142 baseline.
- Settings → toggle Dark mode / Follow system — verify state persists
  (these are now exclusively backed by `:data` `ThemeRepositoryImpl`
  via the rework `SettingsViewModel`, not the retired `:shared`
  `OnboardingViewModel`).
- Restart-launch verifies the persistence settled.

## Deferrals

- **Stale KDoc doc-sweep** — the 5 rework-side files mentioning
  `OnboardingViewModel` in comment text + the migration log entries +
  ARCHITECTURE.md sections that still reference it become stale.
  Acceptable per §142's policy; not load-bearing. A future doc-sweep
  slice can refresh them.
- **Other `:shared` VM retirements** — `NotificationsViewModel`,
  `WebViewViewModel`, `HomeViewModel`, `DownloadViewModelv2`, and the
  rest of the `:shared` `Phase 9.6 ports` VMs remain bound and may
  still have live consumers. Per-VM recon required before each
  retirement.
- **Phase 9.x.onboarding.cleanup (collapse step 3 + step 4)** —
  cosmetic; out of scope. Would collapse the duplicate Finish button
  surface on the rework `SourcesScreen`. Deferred.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: This slice has one rule (retire 1 unreachable VM class +
  its Koin binding). No new files added, no behaviour changes, no
  abstraction introduced or removed.
- **OCP**: Removing closed-under-modification legacy code doesn't
  affect open/closed posture. The rework path's `ThemeViewModel` /
  `SettingsViewModel` are unchanged.
- **DIP**: No dependency-direction changes. `OnboardingViewModel`
  depended on `:shared` `SettingsRepository`; their relationship
  was internal to `:shared`. Retirement reduces, not changes, the
  graph.
- **Layer boundary**: changes touch `:shared` only (1 file deleted +
  1 file modified). No `:domain` / `:data` / `:presentation` / `:ui`
  / `:platform` / `:core` / `:composeApp` reach.
- **Banned features**: N/A — no code added.
- **MVI contract**: N/A — the retired VM was NOT an MVI VM (it
  predates the rework's MVI contract; it's a legacy `ViewModel`
  exposing direct flows + setters, no `Intent`/`State`/`Effect`
  surface). Retirement removes a non-MVI surface; no MVI contract
  delta.
- **Strangler-fig**: shrinks `:shared` surface by one binding. The
  remaining `:shared` VMs + repository facades are out of scope.
- **Load-bearing fixes preserved**: this slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the Reader's
  decoder hints, the OkHttp interceptor, or any of the prior load-
  bearing image-quality posture (the retired VM has no images).
  No load-bearing risk.

## Visual delta

None. The retired VM is no-longer-resolved-anywhere — no user sees its
output today. The sweep is invisible to end-users; it only cleans the
codebase.
