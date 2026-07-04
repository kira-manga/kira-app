# Phase 9.x.onboarding.legacy_retire — Retire 5 legacy onboarding files (Task #307)

## Context

With `Phase 7.x.welcome` (§141, Task #306) just landed, every step of
the onboarding wizard now consumes the rework stack:

- Step 1 (Welcome): rework via §141.
- Step 2 (Theme): rework via §138 + §302 + §303.
- Step 3 (Sources): rework via §84 + §§121/122/123/139 + §140.
- Step 4 (RepoSettings(isFirstOpen = true)): rework via §124.

The 5 files inside `composeApp/.../presentation/features/onboarding/`
are no longer user-reachable from the nav graph:

1. `welcome/WelcomeScreen.kt` (legacy step-1 composable).
2. `theme_selection/ThemeSelectionScreen.kt` (legacy step-2 composable).
3. `theme_selection/ThemeSelector.kt` (helper consumed only by the
   legacy `ThemeSelectionScreen.kt:126`).
4. `sources/SourcesScreen.kt` (legacy step-3 onboarding composable).
5. `components/AnimatedBackground.kt` (decorative gradient sweep
   imported only by the three legacy screens above; the rework path
   intentionally omitted it across §122 / §138 / §141).

This slice retires all 5 files in a single sweep, matching the
"future Phase 9.x cleanup retires it alongside other retired legacy
screens" wording in the KDocs of §138 / §140 / §141 + the matching
ARCHITECTURE.md sections.

## Reachability verification

Performed via Grep:

- `^import me\.manga\.yamiapk\.presentation\.features\.onboarding\.` —
  only the 5 files import each other:
  - `WelcomeScreen.kt:33` → `AnimatedBackground`
  - `ThemeSelectionScreen.kt:39` → `AnimatedBackground`
  - `SourcesScreen.kt:59` → `AnimatedBackground`
  - `ThemeSelectionScreen.kt:126` → `ThemeSelector(...)` call site.
- The only other `import …onboarding…` hit is `shared/.../
  SharedModule.kt:23` → `OnboardingViewModel`. That VM lives in
  `:shared`, NOT `:composeApp`, and is a different cleanup concern
  (its remaining surface still consumes the same Room rows used by
  parts of the rework stack via the strangler-fig facade). **Not
  part of this slice.**
- `ThemeSelector` symbol-grep: only the call site inside
  `ThemeSelectionScreen.kt:126` + KDoc references inside rework
  files / docs / migration logs. **Safe to delete with
  `ThemeSelectionScreen.kt`.**
- `AnimatedBackground` symbol-grep: only the 3 legacy screens (as
  imports) + KDoc references inside route adapters / rework files /
  migration logs / `AnimatedPreloader.kt:35` (KDoc text only — no
  actual usage of the type). **Safe to delete.**

No production code outside `composeApp/.../presentation/features/
onboarding/` references any of the 5 files. KDoc cross-references in
rework files become stale comments (pointing to absent files) but
don't break compilation — those stale references are acceptable; a
future doc sweep can clean them up if desired.

## Approach

Pure-deletion slice. Five files removed, no replacements. The legacy
onboarding directory tree (`welcome/`, `theme_selection/`,
`sources/`, `components/`) becomes empty after the sweep — `git rm`
on the 5 files will also leave the now-empty directories, which `git`
won't track (empty dirs aren't tracked in git). The filesystem may
retain empty directories; they're harmless and Phase 9.y can sweep
them later if desired.

### Layer surfaces

- **`:domain`** — unchanged. No domain reach.
- **`:data`** — unchanged. No `:data` reach.
- **`:presentation`** (`:presentation` module) — unchanged. None of
  the 5 legacy files live in the `:presentation` module; they're in
  `:composeApp/.../presentation/features/onboarding/` (legacy
  package). That subtree is what's being cleaned.
- **`:ui`** — unchanged.
- **`:composeApp`** — 5 files DELETED:
  - `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/
    features/onboarding/welcome/WelcomeScreen.kt`
  - `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/
    features/onboarding/theme_selection/ThemeSelectionScreen.kt`
  - `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/
    features/onboarding/theme_selection/ThemeSelector.kt`
  - `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/
    features/onboarding/sources/SourcesScreen.kt`
  - `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/
    features/onboarding/components/AnimatedBackground.kt`

No `composable<Screen.*>` entry changes in `App.kt` (the route
adapters that USED to render these legacy composables have already
been swapped to render the rework `:ui` equivalents — §138, §140,
§141 each rewrote its own route adapter body).

### Strangler-fig boundary

Unchanged. None of these 5 legacy `:composeApp` UI files participate
in the `:data` → `:shared` strangler-fig boundary. The `:shared`
`OnboardingViewModel` (which DOES participate in the strangler-fig
through its consumption of `:shared` repository facades) stays bound
in `SharedModule.kt` because other consumers may still reference
parts of its surface; that's a separate cleanup.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_onboarding_retire.md` only (1 file).
2. **Delete sweep** — 5 files deleted (exactly at the cap):
   - `composeApp/.../onboarding/welcome/WelcomeScreen.kt`
   - `composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt`
   - `composeApp/.../onboarding/theme_selection/ThemeSelector.kt`
   - `composeApp/.../onboarding/sources/SourcesScreen.kt`
   - `composeApp/.../onboarding/components/AnimatedBackground.kt`
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §142 — Phase 9.x.onboarding.
     legacy_retire — Retire 5 legacy onboarding files` section.
   - `SOLID_AUDIT.md` — append `# Phase 9.x.onboarding.legacy_retire
     — Retire 5 legacy onboarding files (Task #307)` entry with the
     deletion-slice SOLID 10-point checklist (per-file "still passes
     because file removed entirely" verdict).

## Critical files

### Deleted

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/welcome/WelcomeScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/theme_selection/ThemeSelectionScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/theme_selection/ThemeSelector.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/sources/SourcesScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/onboarding/components/AnimatedBackground.kt`

### Modified

- `ARCHITECTURE.md` — append §142.
- `SOLID_AUDIT.md` — append Phase 9.x.onboarding.legacy_retire entry.

### Untouched

- `shared/.../presentation/features/onboarding/viewmodel/OnboardingViewModel.kt`
  — lives in `:shared`, different module, surface still consumed by
  parts of the strangler-fig facade. Out of scope.
- `shared/.../di/SharedModule.kt:23` — Koin binding for the
  `OnboardingViewModel`. Out of scope.
- All route adapters that render the rework `:ui` onboarding screens
  (`WelcomeScreenRoute.kt` post-§141, `ThemeSelectionScreenRoute.kt`
  post-§138, `SourcesScreenRoute.kt` post-§140, `RepoSettingsScreen
  Route.kt` post-§124) — unchanged.
- Stale KDoc cross-references inside rework `:ui` / route-adapter /
  `ARCHITECTURE.md` files that mention the now-deleted legacy paths.
  These become stale comments pointing to absent files. Acceptable;
  doesn't break compilation. A future doc sweep can refresh them.

## Verification

After the delete sweep:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (because
  `:composeApp/commonMain` changes link into the Desktop entry point).

If any gate is RED, the deletion uncovered a transitive reference
(e.g., a `presentation/.../OnboardingViewModel` consumer in
`:composeApp` that depends on the removed types indirectly through
`:shared` re-exports — unlikely, but possible). In that case,
investigate the build error and either:
(a) extend the deletion scope to retire the dangling consumer, or
(b) restore the deleted file (if the consumer is itself a load-
bearing facade), in which case this slice is parked pending a wider
recon. The recon above is thorough enough that (b) is not expected.

On-device smoke (deferred to user's Mac):

- Fresh install — launch through Welcome → Theme → Sources →
  RepoSettings(isFirstOpen=true) → Library. The rework path is now
  the ONLY path; verify it works end-to-end with no visual or
  behavioural regression vs the rework path that landed in §141.
- Restart-launch after onboarding completes goes directly to Library
  (no onboarding chain re-entry).

## Deferrals

- **`:shared` `OnboardingViewModel` retirement** — out of scope. The
  VM still participates in the strangler-fig surface via its
  consumption of `:shared` repository facades, and other call sites
  in `:composeApp` may still reach it (e.g., legacy debug entries,
  or `:shared`-side initial-load paths). A future Phase 9.y slice
  can audit and retire the VM if the audit confirms no remaining
  consumers.
- **Stale KDoc references inside rework files / ARCHITECTURE.md /
  SOLID_AUDIT.md** — these point to absent files post-sweep. Not
  load-bearing; doesn't break compilation. A future doc sweep can
  refresh them; for now, the close-out docs explicitly note the
  stale-references decision.
- **Phase 9.x.onboarding.cleanup (collapse step 3 + step 4)** —
  cosmetic; out of scope. Would collapse the duplicate Finish
  button surface on the rework `SourcesScreen`. Deferred to a
  separate slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: This slice has one rule (delete 5 unreachable files).
  No new files added, no behaviour changes, no abstraction
  introduced or removed.
- **OCP**: Removing closed-under-modification legacy code doesn't
  affect open/closed posture. The rework path's extension surface
  (`onGetStarted`, `onContinue`, `onFinish`, `onboardingLanguageTag`)
  is unchanged.
- **DIP**: No dependency-direction changes. Legacy files depended
  only on each other and Compose primitives; their deletion doesn't
  alter the strangler-fig boundary.
- **Layer boundary**: changes touch `:composeApp` only (5 files
  deleted). No `:domain`/`:data`/`:presentation`/`:ui`/`:platform`/
  `:core`/`:shared` reach.
- **Banned features**: N/A — no code added.
- **MVI contract**: N/A — no MVI surface change.
- **Strangler-fig**: N/A — the 5 legacy files don't participate in
  the boundary.
- **Load-bearing fixes preserved**: this slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the Reader's
  decoder hints, the OkHttp interceptor, or any of the prior load-
  bearing image-quality posture (the 5 legacy onboarding files have
  no images). No load-bearing risk.

## Visual delta

None. The legacy files being deleted are no-longer-user-reachable —
no user sees their visual output today (post-§138/§140/§141). The
sweep is invisible to end-users; it only cleans the codebase.
