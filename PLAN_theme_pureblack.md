# Phase 7.x.theme.pureblack — PureBlack/OLED toggle on Theme picker

## Context

Phase 7.x.theme (closed at commit `bfc5a6d`) shipped the rework Theme picker —
3-tab Light/Dark/System TabRow backed by an MVI surface with `ThemeState`
(`theme: AppTheme`, `isLoading: Boolean`) and `ThemeIntent.OnSelectTheme`.
The picker deliberately deferred the PureBlack/OLED variant — documented as
a follow-on `Phase 7.x.theme.pureblack` slice in both the `ThemeRepository`
KDoc and the Phase 7.x.theme close-out verdict.

The legacy `:shared/.../settings/domain/SettingsRepository.kt:62-97` already
exposes:

```kotlin
fun setPureBlack(enabled: Boolean)             // line 62
fun isPureBlack(): Boolean                     // line 77 — default true
val pureBlackFlow: Flow<Boolean>               // line 94 — default isPureBlack()
```

PureBlack is **orthogonal** to dark mode on the legacy facade: it's a
separate `SharedPreferences` boolean (`KEY_PURE_BLACK`) that flips the dark
scheme's surface colors to true black. When the active theme is Light, the
toggle is stored but has no visual effect. When Dark or System (resolving
to dark), the toggle drives a different `darkColorScheme` variant.

This slice extends the rework Theme stack with a 2nd preference + UI control
that:

1. Adds a 2nd field to `ThemeState` (`pureBlack: Boolean`).
2. Adds a 2nd intent (`ThemeIntent.OnTogglePureBlack(enabled)`).
3. Adds 2 methods to `ThemeRepository` (`observePureBlack()` +
   `setPureBlack`), delegating to the legacy facade.
4. Adds 2 use cases (`ObservePureBlackUseCase`, `SetPureBlackUseCase`).
5. Surfaces a Material 3 `Switch` row in `:ui` `ThemeScreen` under the
   TabRow.
6. Updates `themeReworkModule` to wire the new use cases.

Block-and-ask triggers all NOT met:
- (a) No contract library blocker — pure-black is already on the legacy
  facade with stable Flow + suspend-style write signatures.
- (b) No observable behavior change — the rework `Screen.ThemeRework` route
  is still parallel-only; legacy `Screen.Theme` (onboarding) is untouched;
  the legacy `SettingsScreen` PureBlack toggle remains the user-visible
  surface today. Toggling on either route flips the same boolean.
- (c) No compile risk — extending an existing repository interface with
  new methods is a back-compat operation when no other impl exists (none
  does — `ThemeRepositoryImpl` is the only `:data` impl, and `:domain` has
  no test doubles for this interface yet).
- (d) No SOLID violation — the 2 new methods are ISP-fine (they extend an
  already-coherent "theme surface" interface; PureBlack is a sub-aspect of
  app theming, not a separate concern). A separate `PureBlackRepository`
  interface would be over-segmentation given the single consumer
  (`ThemeViewModel`).

## Approach

### Why extend `ThemeRepository` rather than add a sibling interface

The PureBlack toggle is conceptually part of "how the app is themed" — it
modulates the dark color scheme. Putting it on a sibling
`PureBlackRepository` interface would force `ThemeViewModel` to take 4
use cases (vs 4 total here, but split across 2 interfaces) and would imply
two separate strangler-fig impls reaching into the SAME legacy facade. The
existing `:data` `ThemeRepositoryImpl` already takes the legacy
`SettingsRepository` constructor dep; adding 2 more delegating methods is
a no-cost extension.

The KDoc on `ThemeRepository` explicitly anticipated this: "PureBlack
toggle (KEY_PURE_BLACK on the legacy facade) is NOT part of this interface
... would be added in a follow-on Phase 7.x.theme.pureblack slice if user
requirements emerge." This slice fulfils that opening.

### Why 2 use cases (not 1 combined)

Separate `ObservePureBlackUseCase` + `SetPureBlackUseCase` mirrors the
existing `ObserveAppThemeUseCase` + `SetAppThemeUseCase` posture: one
read flow + one mutator per concern. Combining them into a single
`PureBlackUseCase` with both `invoke()` + `suspend invoke(enabled)`
overloads breaks SRP at the use-case layer (a use case represents ONE
domain action; observing and mutating are two actions).

### Why a `Switch` (not a 4th tab on the TabRow)

The TabRow models a tri-state selection (one of three exclusive options).
PureBlack is an INDEPENDENT toggle — orthogonal to the theme tri-state.
Adding a 4th tab "Dark+PureBlack" would be a Cartesian product hack that
fails when System resolves to light at runtime. A `Switch` is the correct
Material 3 affordance for an independent boolean. Placement: below the
TabRow with a label "Pure Black for dark mode".

### Switch enabled-state when theme = Light

The toggle is **always interactive** regardless of active theme — mirrors
the legacy `SettingsScreen` PureBlack toggle, which is also always
interactive. A user can toggle PureBlack while in Light mode to pre-set
their preference; the visual effect appears when they switch to Dark or
System (resolving dark). Greying the switch out when theme=Light would be
parental-style UX, not what the legacy does.

### State default = `true`

`SettingsRepository.isPureBlack()` defaults to `true`. The rework
`ThemeState.pureBlack` initial value matches: `pureBlack: Boolean = true`.
First-run users see the switch ON.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for the
`:ui` commit).

1. **Plan commit** — `PLAN_theme_pureblack.md` only (1 file).

2. **`:domain` extension** — 3 files:
   - `domain/.../repository/ThemeRepository.kt` (MODIFIED) — add
     `fun observePureBlack(): Flow<Boolean>` + `suspend fun
     setPureBlack(enabled: Boolean)`. Update KDoc — remove the "PureBlack
     is NOT part of this interface" disclaimer; add a new ISP paragraph
     noting the 2 new methods stay co-located here because PureBlack is a
     sub-aspect of theming with a single consumer.
   - `domain/.../usecase/theme/ObservePureBlackUseCase.kt` (NEW) —
     `operator fun invoke(): Flow<Boolean>` delegating to repository.
   - `domain/.../usecase/theme/SetPureBlackUseCase.kt` (NEW) —
     `suspend operator fun invoke(enabled: Boolean)` delegating to
     repository.

3. **`:data` extension** — 1 file:
   - `data/.../repository/ThemeRepositoryImpl.kt` (MODIFIED) — implement
     `observePureBlack()` (returns `legacy.pureBlackFlow` directly) and
     `setPureBlack(enabled)` (calls `legacy.setPureBlack(enabled)`).
     Update KDoc paragraphs (SRP scope grows; PureBlack now in the
     2-of-13 legacy reach surface).

4. **`:presentation` extension** — 3 files:
   - `presentation/.../theme/ThemeState.kt` (MODIFIED) — add
     `pureBlack: Boolean = true` field.
   - `presentation/.../theme/ThemeIntent.kt` (MODIFIED) — add
     `data class OnTogglePureBlack(val enabled: Boolean) : ThemeIntent`
     variant. Update OCP KDoc — remove the "PureBlack" placeholder
     example, replace with a new placeholder (e.g., "OnResetToDefaults").
   - `presentation/.../theme/ThemeViewModel.kt` (MODIFIED) — add
     `observePureBlack` use case to constructor; add `setPureBlack` use
     case to constructor; combine the new flow into the `init {}`
     collector (chained `.onEach` block or a `combine` with the existing
     theme flow); add `OnTogglePureBlack` branch to `handle`'s `when`.

5. **`:ui` extension** — 1 file:
   - `ui/.../themepicker/ThemeScreen.kt` (MODIFIED) — add a
     `Row { Text("Pure Black for dark mode"); Switch(...) }` under the
     TabRow. Wire the switch's `onCheckedChange` to dispatch
     `ThemeIntent.OnTogglePureBlack(enabled)`. Update KDoc accordingly.

6. **`:composeApp` extension** — 1 file:
   - `composeApp/.../di/ThemeReworkModule.kt` (MODIFIED) — add `factory {
     ObservePureBlackUseCase(get()) }` + `factory { SetPureBlackUseCase
     (get()) }`. Update `viewModel { ThemeViewModel(get(), get(), get(),
     get()) }` — 4 args now. Update KDoc.

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `§86 — Phase 7.x.theme.pureblack — PureBlack
     toggle extension` subsection.
   - `SOLID_AUDIT.md` — Phase 7.x.theme.pureblack entry: per-file
     checklist for the 7 modified/new files + end-of-slice verdict.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/theme/ObservePureBlackUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/theme/SetPureBlackUseCase.kt`
- `PLAN_theme_pureblack.md`

### Modified

- `domain/.../repository/ThemeRepository.kt`
- `data/.../repository/ThemeRepositoryImpl.kt`
- `presentation/.../theme/ThemeState.kt`
- `presentation/.../theme/ThemeIntent.kt`
- `presentation/.../theme/ThemeViewModel.kt`
- `ui/.../themepicker/ThemeScreen.kt`
- `composeApp/.../di/ThemeReworkModule.kt`
- `ARCHITECTURE.md`
- `SOLID_AUDIT.md`

### Untouched (verify by read, not modified)

- `shared/.../settings/domain/SettingsRepository.kt` — legacy facade;
  the strangler-fig delegates to existing `pureBlackFlow` + `setPureBlack`
  methods. No modification.
- `composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt` — route
  adapter resolves `ThemeViewModel` via `koinViewModel()`; the new
  constructor arity is transparent at the call site.
- `composeApp/.../navigation/Screen.kt` — `Screen.ThemeRework` route ID
  unchanged.
- `composeApp/.../App.kt` — `composable<Screen.ThemeRework>` entry
  unchanged.
- `composeApp/.../di/ReworkModules.kt` — `allReworkModules()` list
  unchanged (`themeReworkModule` already there).

## Reuse

- **Strangler-fig posture**: lifted from Phase 7.x.theme's
  `ThemeRepositoryImpl`. Same `:shared` legacy facade dep; same `single`
  Koin lifecycle; same "reach into legacy until Phase 9.x retirement"
  justification. Two more methods on the impl, two more methods on the
  interface — same structural pattern.
- **MVI extension shape**: validates the OCP-friendly empty/sealed
  interface design from Phase 7.x.theme — adding a new intent variant is
  an append; the VM's exhaustive `when` flags the missing branch at
  compile time.
- **Use case shape**: copies the `ObserveAppThemeUseCase` /
  `SetAppThemeUseCase` template — one-line pass-through with KDoc
  explaining DIP + factory lifecycle.
- **Switch composable**: standard Material 3 `Switch` + `Row` layout —
  matches the legacy `SettingsScreen`'s PureBlack switch row. No new
  design tokens.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required for
  commit 5 (`:ui` composable) because the screen file is in
  `:ui/commonMain` and links into the Desktop entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build, navigate to `Screen.ThemeRework`, verify the PureBlack switch
  defaults to ON for first-run users.
- Toggle the switch with Dark theme active — verify the dark color scheme
  swaps to true-black surfaces.
- Toggle PureBlack while in Light theme — verify no visual change but the
  switch state persists (re-open the picker to confirm).
- Toggle PureBlack on the legacy `SettingsScreen` PureBlack row — re-open
  the rework picker and verify the switch reflects the new value (proves
  the same-prefs strangler-fig coexistence).

Edge cases:

- Rapid double-tap on the switch: each tap dispatches an
  `OnTogglePureBlack` intent with the new value; the upstream
  `pureBlackFlow` re-emits each value in order; the VM updates state
  reactively. No debouncing needed.
- First-run: `isPureBlack()` returns `true` (default), `pureBlackFlow`
  emits `true` on first subscription; the switch renders ON immediately.

## Deferrals

- **No i18n lift** — the switch's "Pure Black for dark mode" label is an
  inline English string. Phase 10's i18n lift swaps both legacy and
  rework strings in one pass.
- **No `darkColorScheme` plumbing** — this slice only persists +
  surfaces the toggle in the picker. The actual `:ui` `YamiTheme`'s
  `darkColorScheme` selection that consumes `pureBlackFlow` already
  exists for the legacy `composeApp/.../theme/Theme.kt` consumer; the
  rework picker doesn't need to wire that — the upstream pref flow is
  the single source of truth, and the existing theme observer in
  `MainActivity` (or wherever the active scheme is decided) already
  reads `pureBlackFlow`. No new render-side wiring needed.
- **No "preview" panel** — the picker shows the switch, not a
  preview of the resulting color scheme. Could be a future
  enhancement; outside this slice.
- **No nav route-swap** — legacy `Screen.Theme` (onboarding) stays
  bound to the legacy route. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each new file has one rule. Each modified file gains a
  cohesive 2nd surface (PureBlack alongside theme tri-state — same
  concern: how the app is themed).
- **OCP**: validates the empty/sealed-interface design from Phase
  7.x.theme — `ThemeIntent` gains `OnTogglePureBlack` as an append;
  the VM's `when` reducer flags any missing branch at compile time.
- **DIP**: `:presentation` depends on `:domain`'s use cases.
  `:data`'s impl depends on legacy `:shared`'s `SettingsRepository`
  — strangler-fig boundary, same as Phase 7.x.theme.
- **ISP**: `ThemeRepository` gains 2 methods. Total surface = 4
  methods (2 read flows + 2 mutators) — still focused on "theme
  surface for the picker". A `PureBlackRepository` sibling would
  be over-segmentation given the single consumer.
- **Layer boundary**: changes touch `:domain` (2 new + 1 modified),
  `:data` (1 modified), `:presentation` (3 modified), `:ui` (1
  modified), `:composeApp` (1 modified + 2 close-out). No
  cross-layer reach beyond strangler-fig.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`.
- **MVI contract**: extends existing state + intent. No new effects.
  The empty `ThemeEffect` stays empty.
- **Strangler-fig**: ONE new `:data` → `:shared` reach (2 more
  delegated methods on existing `ThemeRepositoryImpl`). Same
  boundary.
- **Load-bearing fixes**: this slice touches NO load-bearing paths
  (Coil ImageLoader, per-host repo registry, OkHttp interceptor,
  Reader decoder hints, maxBitmapSize). No load-bearing risk.
