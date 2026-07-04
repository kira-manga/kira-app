# Phase 7.x.sources.swap — Route-swap legacy onboarding Screen.Sources → rework SourcesScreen (Task #305)

## Context

`Phase 7.x.sources.onboardingseed` (Task #304, §139) just landed,
closing the rework Sources stack's final feature-parity gap vs the
legacy onboarding `SourcesScreen.kt`. The four onboarding-Sources
gap-lifts are now all complete:

- §121 (Phase 7.x.sources.complaint) — Request-Source dialog.
- §122 (Phase 7.x.sources.onboardingfinish) — Finish button.
- §123 (Phase 7.x.sources.infocard) — Upcoming-Languages info card.
- §139 (Phase 7.x.sources.onboardingseed) — default-language seed.

With these landed, the rework `SourcesScreen` has full feature
parity with the legacy onboarding `presentation/features/onboarding/
sources/SourcesScreen.kt`. The route adapter that currently binds
`Screen.Sources` to the legacy composable can now be rewritten to
render the rework stack instead — same shape as §286 / §287 / §288 /
§289 / §290 / §291 / §292 / §293 / §295 / §138 / §301 prior
route-swap slices (single-file scope).

## Strategy

Rewrite `composeApp/.../navigation/routes/SourcesScreenRoute.kt`
(the adapter bound to `Screen.Sources`) so it resolves the rework
`SourcesViewModel` via `koinViewModel()` and renders the rework
`:ui/.../sources/SourcesScreen` instead of the legacy onboarding
composable. Thread:

- `onboardingLanguageTag = userLanguageCode` — fires the §139
  `LaunchedEffect(onboardingLanguageTag)` → dispatches
  `SourcesIntent.OnSeedDefaultLanguage(tag)` → use case enables
  every source whose parenthesised tag matches the user's locale
  (falling back to `"(EN)"`). Identical semantics to the legacy
  `LaunchedEffect(userLanguageCode) { setLanguageEnabledDefault(...) }`
  at legacy `SourcesScreen.kt:124-127`.
- `onFinish = { navController.safeNavigate(Screen.RepoSettings(
  isFirstOpen = true)) }` — fires the §122 onboardingfinish Finish
  button (renders iff `onFinish != null`). Same nav target as the
  current legacy adapter (`SourcesScreenRoute.kt:58-60`).

The `RepoSettingsViewModel` ctor param + `koinInject` of
`DataStoreHelper` posture is preserved verbatim from the
established legacy adapter shape; the rework adapter drops the
`RepoSettingsViewModel` resolution (no longer needed) and gains
the rework `SourcesViewModel` resolution.

## Approach

Single-file rewrite of `SourcesScreenRoute.kt`. The file becomes
near-identical to the §124 `RepoSettingsScreenRoute.kt` shape (the
post-swap counterpart that already renders the rework Sources
screen), differing only in:

1. The `onFinish` target — this adapter navigates to
   `Screen.RepoSettings(isFirstOpen = true)` (the next onboarding
   step), NOT directly to `Screen.Library`. That nav choice
   preserves the legacy 4-step onboarding chain verbatim
   (Welcome → Theme → Sources → RepoSettings → Library).
2. The `onboardingLanguageTag` is passed (the §124 adapter does
   not — it's the in-settings entry where seeding is irrelevant).
3. No `isFirstOpen` argument to read (the `Screen.Sources` route
   has no parameters).

### Why preserve the 4-step chain (not collapse to 3 steps)

The §124 swap was deliberately designed for the rework Sources
screen to be reachable both from the onboarding chain (step 4 via
`Screen.RepoSettings(isFirstOpen = true)` with Finish→Library) and
from the in-settings entry (`Screen.RepoSettings(false)` from
`HomeScreenRoute.kt:193`, without Finish). Collapsing the chain
to 3 steps (step 3 onFinish→Library directly, bypassing step 4)
would be a structural onboarding-chain change beyond the scope of
a route-swap slice. The established route-swap pattern is
"renderer change, structural posture preserved".

### Why pass `onboardingLanguageTag` here but not in
`RepoSettingsScreenRoute`

The §139 seed is an onboarding concern — it auto-enables
locale-matched sources for users on their FIRST encounter with
the source list. The in-settings entry (`Screen.RepoSettings(false)`
from Home) is for users who've already been onboarded and are
adjusting their selections; running the seed there would be
surprising (re-enables sources the user may have explicitly
disabled). The optional `onboardingLanguageTag: String? = null`
param defaults to `null`, so non-onboarding entries get no seed.

This slice is the FIRST and ONLY caller that passes the param
non-null. The rework Sources screen's `LaunchedEffect(
onboardingLanguageTag)` key dedups via composition state — one
fire per language-code change, mirroring legacy
`SourcesScreen.kt:124-127` verbatim.

## Files added

(None — single-file rewrite.)

## Files modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/
  routes/SourcesScreenRoute.kt` — replace legacy renderer with
  rework renderer; same `Screen.Sources` binding, same `onFinish`
  nav target, add `onboardingLanguageTag` threading. Drop unused
  legacy `RepoSettingsViewModel` ctor param + legacy `SourcesScreen`
  import.

## Files untouched (verify by read, not modified)

- `presentation/features/onboarding/sources/SourcesScreen.kt`
  (legacy composable) — no longer user-reachable after this swap,
  but stays on disk until a future `Phase 9.x` cleanup sweep
  retires it alongside other retired legacy screens.
- `composeApp/.../App.kt` — the `composable<Screen.Sources>` block
  invokes `SourcesScreenRoute(navController, backStackEntry)` —
  argument shape unchanged. No touch needed.
- `Screen.kt` — `Screen.Sources` enum case unchanged.
- All other onboarding chain files (`WelcomeScreenRoute`,
  `ThemeSelectionScreenRoute`, `RepoSettingsScreenRoute`) —
  unchanged. The chain's structure (4 steps) and inter-step nav
  targets are preserved verbatim.

## Commit roadmap

Two commits, both ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_sources_swap.md` only (1 file, mirroring
   the plan-file pattern used by every prior multi-commit slice).

2. **Source commit** — 1 file:
   - `composeApp/.../navigation/routes/SourcesScreenRoute.kt`
     (MODIFIED) — single-file rewrite. KDoc rewritten to document
     the swap rationale + pre-conditions (§121/§122/§123/§139)
     + nav target preservation + cross-reference to the §124
     counterpart.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §140 — Phase 7.x.sources.swap`
     section covering strategy, layer-by-layer surfaces (one file
     in `:composeApp`), behaviour preservation, the 4-step chain
     preservation rationale, cross-references to §84/§121/§122/§123/§124/§139.
   - `SOLID_AUDIT.md` — Phase 7.x.sources.swap entry mirroring
     the §138 (theme.swap) audit structure: per-file SOLID
     10-point checklist, end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation, MVI contract integrity
     (no surface change), strangler-fig integrity (no new
     reaches), next-candidate enumeration.

## Critical files

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/
  routes/SourcesScreenRoute.kt` — full rewrite, ~60 lines.

### Untouched (verify by read)

- All other onboarding-chain route adapters.
- The legacy onboarding `SourcesScreen.kt` composable (preserved
  on disk; not user-reachable post-swap).
- `App.kt` (route binding shape unchanged).

## Reuse

- **Route-adapter shape**: lifted from `RepoSettingsScreenRoute.kt`
  (§124 — the rework Sources counterpart that already exists). The
  new adapter is essentially the same shape minus the
  `args.isFirstOpen` gate (this route has no args) plus the
  `onboardingLanguageTag` threading.
- **`onFinish` semantics**: lifted from the legacy
  `SourcesScreenRoute.kt:58-60` verbatim — `navController.
  safeNavigate(Screen.RepoSettings(isFirstOpen = true))`. Same
  legacy nav target, preserved.
- **`onboardingLanguageTag` threading**: lifted from the legacy
  `SourcesScreenRoute.kt:53` verbatim — `DataStoreHelper.
  languageFlow.collectAsState(initial = "")` reads the persisted
  user-selected language. The rework Sources screen applies the
  `.ifBlank { "en" }` fallback internally (via §139 use case), so
  the snapshot can be the raw flow value without an extra map.

## Verification

After the source commit (step 2):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  sim arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop.

All four gates required because this adapter file is in
`:composeApp/commonMain` and links into every target.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

1. Fresh install / first launch: navigate Welcome → Theme →
   Sources. Verify rework Sources screen renders with the language
   seed auto-applied (sources for the user's locale enabled, EN
   fallback for unsupported locales).
2. Tap Finish → verify navigation to `Screen.RepoSettings(
   isFirstOpen=true)` step (renders the rework Sources screen
   AGAIN with Finish→Library — pre-existing §124 behavior, not
   changed by this slice).
3. Tap Finish on the second screen → verify nav to Library +
   `first_launch` flag flipped to false.

### Edge cases to mentally model

- **Empty `languageFlow` (first launch, never set)**: `initial = ""`
  flows immediately; `onboardingLanguageTag = ""` triggers the
  `LaunchedEffect("")` block; the §139 use case coerces blank to
  `"en"` → enables `"(EN)"`-tagged sources. Same as legacy.
- **Locale change mid-session**: highly unlikely during onboarding
  (the language picker is step 1 / pre-Welcome). If it does
  happen, `LaunchedEffect(onboardingLanguageTag)` re-fires with
  the new tag and re-seeds — same posture as legacy
  `LaunchedEffect(userLanguageCode)`.
- **Already-onboarded user navigating back to `Screen.Sources`**:
  not reachable in the legacy nav graph (the onboarding chain
  pops the start destination on Finish). Even if reached, the
  seed is idempotent — enabling already-enabled sources is a
  no-op at the Room level.

## Deferrals

- **No collapse of the 4-step onboarding chain** — preserving
  Welcome → Theme → Sources → RepoSettings → Library verbatim.
  The step-3 / step-4 "two identical screens" redundancy is a
  pre-existing condition, not introduced by this slice. A future
  `Phase 9.x.onboarding.cleanup` slice can collapse if desired.
- **No retirement of the legacy onboarding `SourcesScreen.kt`** —
  stays on disk until the Phase 9.x cleanup sweep.
- **No retirement of `RepoSettingsViewModel`** — still bound by
  `SharedModule`, still referenced by the legacy
  `SourcesScreenRoute.kt` pre-swap. Post-swap, the legacy VM is
  only reached if some other caller resolves it via Koin — TBD
  in the cleanup sweep.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: The route adapter has one job — bridge the `Screen.
  Sources` nav entry to the rework `SourcesScreen` composable + own
  the route-level nav decisions (where to navigate on Finish).
- **OCP**: The rework `SourcesScreen` composable's signature is
  closed (the four onboarding params are optional with safe
  defaults — §122 / §139 added them additively). The adapter
  opens the extension surface by passing the two non-null
  callbacks.
- **DIP**: Adapter depends on the rework `SourcesViewModel` via
  `koinViewModel()` (not on a concrete `:data` impl), on
  `DataStoreHelper` via `koinInject()` (a `:core` storage
  facade). No concrete `:data` / `:shared` reaches from this
  file post-swap.
- **Layer boundary**: `:composeApp` only — the route adapter is
  the canonical home for this kind of swap.
- **Banned features**: No `!!`, `Any`, `lateinit`, `Thread`.
  `collectAsState` and `koinInject` / `koinViewModel` are
  Compose-canonical.
- **MVI contract**: No surface change to `SourcesIntent` /
  `SourcesEffect` / `SourcesState`. This is purely a nav-graph
  rewiring.
- **Strangler-fig**: No new `:data` → `:shared` reaches. The
  Sources slice's existing strangler-fig boundary (`:data`
  `SourcesRepositoryImpl` over `:shared` `SourcesRepository`) is
  preserved unchanged.
- **Load-bearing fixes preserved**: This slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the
  Reader's decoder hints, the OkHttp interceptor, or any of the
  prior load-bearing image-quality posture (Sources has no
  images). No load-bearing risk.
