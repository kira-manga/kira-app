# Phase 7.x.theme — Theme selection screen rework (`:domain` → `:data` → `:presentation` → `:ui` + Koin/nav wiring)

## Context

Phase 7.x.sources is complete at HEAD `7d7cbe5` (task #241 closed). Per the
`/goal` Stop hook Rule 3, the next non-blocked candidate. Surveyed in the
Phase 7.x.sources close-out's "Next" block + a follow-on Explore of the
legacy onboarding stack:

- **Home rework**: ~750-line surface, multi-VM coordination, reaches the
  load-bearing `activeRepoFlow` / `repoTaps` / `BaseMangaRepository`
  registry. Faithful port would need a redesign abstraction over
  `BaseMangaRepository`. Reclassified as block-and-ask (b) — refactor
  would change observable behavior of load-bearing image-fetch paths
  (MEMORY `project_yami_okhttp_fetcher`).
- **Downloads rework**: blocked on `:platform` `DownloadRepositoryImpl`
  prep slice (WorkManager → commonMain SPI). Not a clean auto-continue.
- **RepoSettings rework**: surface entangled with `ComplaintViewModel` +
  the load-bearing per-host repo registry. Larger than theme.
- **Theme rework** (chosen): smallest viable slice. Legacy is 170-line
  screen + 134-line selector + 71-line route adapter = 375 total lines.
  Depends only on `SettingsRepository`. Establishes the canonical
  "settings sub-screen rework" shape that future settings slices reuse.

Block-and-ask triggers (a)-(d) all NOT met:
- (a) no contract library blocker — `SettingsRepository` is `:shared`
  commonMain-resident; the rework `:data` impl reaches into it the same
  way Sources/Statistics/History/Updates do over their legacy facades.
- (b) no observable behaviour change — legacy `Screen.ThemeSelection`
  onboarding route stays bound to the legacy composable; the rework
  route is parallel + debug-guarded; both routes write/read the same
  `KEY_THEME_MODE` + `KEY_THEME_SYSTEM` `SharedPrefs` keys, so toggles
  propagate between them.
- (c) no compile risk — `OnboardingViewModel`, `RepoSettingsViewModel`,
  the `pureBlackFlow`, and the rest of `SettingsRepository`'s 13-method
  surface stay verbatim. The rework `:data` impl exposes ONLY
  `observeAppTheme()` + `setAppTheme()`; it consumes exactly 2 of the
  legacy's 13 methods (`darkModeFlow`/`followSystemFlow` reads,
  `setDarkMode`/`setFollowSystem` writes).
- (d) no SOLID violation forced — the unified tri-state `AppTheme`
  abstraction collapses the legacy's awkward two-boolean rep into a
  single domain ADT (an ISP/SRP win). Strangler-fig boundary is
  identical to all six prior slices.

## Approach

The legacy `SettingsRepository` (at `shared/.../settings/domain/
SettingsRepository.kt:41-149`) stores theme as **two independent
booleans**:

```kotlin
val darkModeFlow: Flow<Boolean>        // KEY_THEME_MODE, default false
val followSystemFlow: Flow<Boolean>    // KEY_THEME_SYSTEM, default true
fun setDarkMode(enabled: Boolean)      // sync (SharedPrefs.putBoolean)
fun setFollowSystem(enabled: Boolean)  // sync (SharedPrefs.putBoolean)
```

The legacy `ThemeSelectionScreenRoute` does the boolean ↔ tri-state
translation in the route adapter:

```kotlin
val currentTheme = when {
    isFollowSystem -> AppTheme.System
    isDarkMode    -> AppTheme.Dark
    else          -> AppTheme.Light
}
// setter:
AppTheme.Light  -> followSystem=false, darkMode=false
AppTheme.Dark   -> followSystem=false, darkMode=true
AppTheme.System -> followSystem=true  (darkMode unchanged)
```

The rework relocates this translation to the **`:data` strangler-fig
impl**, so the domain layer only ever sees the unified `AppTheme` ADT.
This is the SRP/DIP win — the translation moves from a route-adapter to
the layer that owns the impl detail (the two-boolean representation is
an impl artifact of the legacy `SharedPrefs` storage).

The legacy mutators are **non-suspend** (sync `SharedPrefs.putBoolean`).
The `:domain` `setAppTheme` is declared `suspend` anyway — that's the
abstraction's room to grow (a future DataStore migration becomes a
non-event for callers), and matches the established pattern from
Sources/History/Updates (where the legacy mutators are also sync).

### `:domain` surface

```kotlin
enum class AppTheme { Light, Dark, System }

interface ThemeRepository {
    fun observeAppTheme(): Flow<AppTheme>
    suspend fun setAppTheme(theme: AppTheme)
}

class ObserveAppThemeUseCase(private val repo: ThemeRepository) {
    operator fun invoke(): Flow<AppTheme> = repo.observeAppTheme()
}

class SetAppThemeUseCase(private val repo: ThemeRepository) {
    suspend operator fun invoke(theme: AppTheme) = repo.setAppTheme(theme)
}
```

Note: `AppTheme` lives in `:domain` (not `:ui`/`:presentation`) because
it's the canonical state ADT — same posture as `ReadingMode` (placed in
`:domain/model/reader/`). The legacy's onboarding-local
`AppTheme` enum (in
`composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt:166`)
stays for the legacy route's consumers. The rework slice introduces a
SEPARATE `me.manga.kira.domain.model.theme.AppTheme` — both can
coexist because they're in different packages. Phase 9.x route-swap
will eventually retire the legacy one.

### `:data` strangler-fig impl

```kotlin
class ThemeRepositoryImpl(private val legacy: SettingsRepository) : ThemeRepository {
    override fun observeAppTheme(): Flow<AppTheme> =
        combine(legacy.darkModeFlow, legacy.followSystemFlow) { dark, system ->
            when {
                system -> AppTheme.System
                dark   -> AppTheme.Dark
                else   -> AppTheme.Light
            }
        }

    override suspend fun setAppTheme(theme: AppTheme) {
        when (theme) {
            AppTheme.Light  -> { legacy.setFollowSystem(false); legacy.setDarkMode(false) }
            AppTheme.Dark   -> { legacy.setFollowSystem(false); legacy.setDarkMode(true) }
            AppTheme.System -> { legacy.setFollowSystem(true) }
        }
    }
}
```

Mirrors the legacy route adapter's translation verbatim — including the
"System leaves darkMode unchanged" quirk (so re-toggling between System
and Dark preserves the user's prior Dark preference if they oscillate).

### `:presentation` MVI surface

```kotlin
data class ThemeState(
    val theme: AppTheme = AppTheme.System,
    val isLoading: Boolean = true,
) : MviState

sealed interface ThemeIntent : MviIntent {
    data class OnSelectTheme(val theme: AppTheme) : ThemeIntent
}

sealed interface ThemeEffect : MviEffect    // empty — no one-shot effects

class ThemeViewModel(
    private val observeAppTheme: ObserveAppThemeUseCase,
    private val setAppTheme: SetAppThemeUseCase,
) : MviViewModel<ThemeState, ThemeIntent, ThemeEffect>(ThemeState()) {
    init {
        observeAppTheme()
            .onEach { theme -> setState { copy(theme = theme, isLoading = false) } }
            .launchIn(viewModelScope)
    }
    override fun handle(intent: ThemeIntent) = when (intent) {
        is ThemeIntent.OnSelectTheme -> {
            viewModelScope.launch { setAppTheme(intent.theme) }
        }
    }
}
```

Same observer-in-`init {}` posture as Library/Statistics/Sources. The
OnSelectTheme handler is fire-and-forget — the legacy mutators are sync
SharedPrefs writes that complete in microseconds, and the resulting
flow re-emit will propagate the new value back into state.

### `:ui` composable

```kotlin
@Composable
fun ThemeScreen(viewModel: ThemeViewModel) {
    val state by viewModel.state.collectAsState()
    ThemeScreenContent(
        state = state,
        onIntent = viewModel::submit,
    )
}

@Composable
fun ThemeScreenContent(state: ThemeState, onIntent: (ThemeIntent) -> Unit) {
    Surface { /* Column { Header; TabRow { Light/Dark/System tabs } } */ }
}
```

**Scope deferrals from the legacy screen** (preserves observable
behavior on the legacy route by NOT touching it; the rework route gets
the cleaner picker):

- **`AnimatedBackground` overlay**: onboarding-specific flourish; the
  rework picker is a plain theme picker fit for embedding in the future
  Settings rework. The legacy onboarding route keeps its
  `AnimatedBackground` verbatim.
- **Notification permission UI**: onboarding-specific (the user is
  granting notifications during onboarding, not during a theme change).
  Lives in the legacy `ThemeSelector.kt` and stays there. The rework
  picker is theme-only.
- **`onContinue` button**: onboarding navigation — the rework route is
  parallel + debug-guarded, no onboarding chain. The legacy route keeps
  its Continue button.
- **`AppTheme.displayNameRes: StringResource`**: the legacy enum carries
  the string-resource handle on the enum itself. The rework `AppTheme`
  in `:domain` is pure ADT — no Compose Resources dep in `:domain`. The
  `:ui` composable owns the label-resource lookup via a local
  `when (theme)` expression. Matches the established convention that
  `:domain` is platform-free.

The `:ui` composable reuses existing rework `:ui` design tokens
(`LocalSpacing`, MaterialTheme) and primitives (`Surface`, `Card`,
Material3 `TabRow`/`Tab`). Mirrors the legacy `ThemeSelector`'s visual
structure (TabRow with 3 tabs, each with an icon + label) but stripped
to just the picker — no notification permission section.

### Strangler-fig boundary

- The rework `:data` impl's constructor takes one dep: the legacy
  `:shared` `SettingsRepository`. Same posture as all six prior slices.
- The rework reads 2 legacy flows + writes 2 legacy setters. The other
  11 methods on `SettingsRepository` (`incognitoFlow`, `pureBlackFlow`,
  `setPureBlack`, `clearFilesLargerThan1MB`, `getCacheFolderSize`,
  `formatSize`, `setLanguage`, etc.) stay verbatim for their existing
  consumers (`SettingsViewModel`, `OnboardingViewModel`).
- The two-boolean storage representation is the boundary. Future i18n /
  storage migrations would happen behind the `ThemeRepository`
  interface without disturbing callers.

### Scope deferrals from the slice

- **PureBlack toggle** — separate setting (`KEY_PURE_BLACK`), surfaced
  only in `SettingsScreen` (not the theme picker). Would be a follow-on
  `Phase 7.x.theme.pureblack` slice if user-visible requirements
  emerge. The rework `AppTheme` ADT could be widened to a richer
  `ThemePreferences(mode: ThemeMode, pureBlack: Boolean)` at that
  point; for now the slice ports only the three-state mode.
- **System-uiMode fallback for first-run dark detection** — the legacy
  `isDarkMode()` carries a `TODO Phase 9.6` note about restoring the
  `Configuration.UI_MODE_NIGHT_MASK` query. Out of scope for this slice
  (it's a `:platform` SystemThemeProvider expect/actual). The rework's
  first-run default is `AppTheme.System` (follows OS), same effective
  behavior.
- **i18n lift** — display labels (`theme_light`/`theme_dark`/
  `theme_system`) are looked up in the `:ui` composable via
  `stringResource(Res.string.*)`. Same posture as Sources/History/etc.
  No i18n changes in this slice.
- **Onboarding chain integration** — legacy onboarding route still
  hosts the legacy composable with `onContinue` → `Screen.Sources`. The
  rework route is parallel + debug-guarded. Phase 9.x route-swap is
  its own slice.

### MVI surface decision: empty `ThemeEffect`

Same OCP placeholder posture as Statistics/Sources. The slice doesn't
emit one-shot effects today (toggling theme is observable via
state-flow). An empty sealed interface `ThemeEffect : MviEffect` keeps
the VM signature clean (`MviViewModel<ThemeState, ThemeIntent,
ThemeEffect>`) and is OCP-friendly for a future "OnThemeChangeError"
toast variant if a storage write fails.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for the
`:ui` commit since the new screen file is in `:ui/commonMain` and links
into the Desktop entry point).

1. **Plan commit** — `PLAN_theme.md` only (1 file).

2. **`:domain` foundation** — 4 files, all new:
   - `domain/.../model/theme/AppTheme.kt` — `enum class AppTheme { Light, Dark, System }`.
   - `domain/.../repository/ThemeRepository.kt` — interface with `observeAppTheme()` + `setAppTheme()`.
   - `domain/.../usecase/theme/ObserveAppThemeUseCase.kt` — Flow pass-through.
   - `domain/.../usecase/theme/SetAppThemeUseCase.kt` — suspend pass-through.

3. **`:data` strangler-fig impl** — 1 file, new:
   - `data/.../repository/ThemeRepositoryImpl.kt` — class takes `legacy:
     SettingsRepository` constructor arg; `observeAppTheme()` combines
     the two boolean flows into a tri-state; `setAppTheme()` translates
     back via two sync legacy setters. KDoc mirrors prior strangler-fig
     impls' DIP/SRP rationale.

4. **`:presentation` MVI** — 4 files, all new:
   - `presentation/.../theme/ThemeState.kt` — data class with theme +
     isLoading.
   - `presentation/.../theme/ThemeIntent.kt` — sealed interface with
     `OnSelectTheme(theme: AppTheme)`.
   - `presentation/.../theme/ThemeEffect.kt` — empty sealed interface
     implementing `MviEffect`.
   - `presentation/.../theme/ThemeViewModel.kt` — extends
     `MviViewModel<ThemeState, ThemeIntent, ThemeEffect>`; init-block
     flow collector; one-branch `handle()`.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../theme/ThemeScreen.kt` — stateful `ThemeScreen(viewModel)` +
     stateless `ThemeScreenContent(state, onIntent)` + private
     `ThemePickerRow` helper. TabRow with 3 tabs (Light/Dark/System)
     using the icons matching the legacy (LightMode/DarkMode/
     SettingsBrightness). Stateless layer pure.

6. **`:composeApp` Koin + nav** — 5 files at the cap:
   - `composeApp/.../di/ThemeReworkModule.kt` (NEW) — module declaring
     `single<ThemeRepository> { ThemeRepositoryImpl(legacy = get()) }` +
     `factory { ObserveAppThemeUseCase(get()) }` +
     `factory { SetAppThemeUseCase(get()) }` +
     `viewModel { ThemeViewModel(get(), get()) }`.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `themeReworkModule` to `allReworkModules()`.
   - `composeApp/.../navigation/routes/ThemeReworkScreenRoute.kt` (NEW)
     — `@Composable fun` taking `NavController` + `NavBackStackEntry`;
     resolves `ThemeViewModel` via `koinViewModel()`; calls
     `ThemeScreen(viewModel)`. No outbound nav callbacks (terminal
     screen for the rework route).
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add `data object
     ThemeRework : Screen` enum case.
   - `composeApp/.../App.kt` (MODIFIED) — add `composable<Screen.
     ThemeRework> { ThemeReworkScreenRoute(navController, it) }`
     alongside the existing `composable<Screen.ThemeSelection>` entry +
     the import.

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §85 — Phase 7.x.theme — Theme selection
     screen rework` with subsections covering strategy, the
     boolean↔ADT translation rationale, layer-by-layer surfaces, MVI
     shape, strangler-fig boundary, files added, deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.theme entry with per-file SOLID
     10-point checklists, end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation, MVI contract, strangler-fig
     integrity, next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/theme/AppTheme.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/ThemeRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/theme/ObserveAppThemeUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/theme/SetAppThemeUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ThemeRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/theme/ThemeState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/theme/ThemeIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/theme/ThemeEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/theme/ThemeViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/theme/ThemeScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ThemeReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ThemeReworkScreenRoute.kt`
- `PLAN_theme.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `themeReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `ThemeRework` case.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.ThemeRework>` block + import.
- `ARCHITECTURE.md` — append §85.
- `SOLID_AUDIT.md` — append Phase 7.x.theme entry.

### Untouched (verify by read, not modified)

- `shared/.../settings/domain/SettingsRepository.kt` — legacy facade;
  the strangler-fig delegates to 2 of its 13 methods but does not
  modify it. The other 11 methods continue to serve
  `SettingsViewModel` / `OnboardingViewModel` verbatim.
- `shared/.../onboarding/viewmodel/OnboardingViewModel.kt` — legacy
  onboarding VM; preserved verbatim (still exposes `darkMode` +
  `followSystem` StateFlows for the legacy route).
- `composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt` —
  legacy screen; preserved for the legacy onboarding route. Phase 9.x
  route-swap retires it later.
- `composeApp/.../onboarding/theme_selection/ThemeSelector.kt` —
  legacy selector composable; preserved.
- `composeApp/.../navigation/routes/ThemeSelectionScreenRoute.kt` —
  legacy route; preserved. Phase 9.x route-swap retires it.

## Reuse

- **Strangler-fig posture**: lifted from `SourcesRepositoryImpl` /
  `ReadingStatisticsRepositoryImpl` / `HistoryRepositoryImpl` — same
  `:shared` dependency, same `single` Koin lifecycle, same "reach into
  legacy until Phase 9.x retirement" justification.
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `:presentation/.../mvi/`.
- **Koin module shape**: mirrors `sourcesReworkModule` — `single` for
  repo, `factory` for use cases, `viewModel` for VM.
- **Nav route shape**: mirrors `SourcesReworkScreenRoute` — terminal
  screen, both params `@Suppress("UNUSED_PARAMETER")`.
- **Composable layout**: reuses `LocalSpacing`, MaterialTheme,
  `Surface`, Material3 `TabRow`/`Tab`. No new design tokens introduced.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate. Required for every commit.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required
  for commit 5 (`:ui` composable) and commit 6 (App.kt nav graph).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.ThemeRework`,
  toggle through Light/Dark/System and verify the app's theme actually
  changes. Then navigate to the legacy `Screen.ThemeSelection` route
  and confirm the legacy picker shows the same value (proves both
  routes share the same `SharedPrefs` store).
- Verify that a System-→Dark-→System toggle preserves the user's prior
  Dark preference (the legacy quirk).

Edge cases to mentally model during implementation:

- **First-run defaults**: `darkModeFlow` defaults `false`, `followSystem
  Flow` defaults `true` → `combine` emits `AppTheme.System`. The VM's
  `isLoading` flips false after the first emission.
- **Concurrent storage writes**: setting `AppTheme.Light` writes
  `followSystem=false` then `darkMode=false` — two SharedPrefs writes
  in sequence. The `combine` operator may emit an intermediate
  `AppTheme.Dark` between the writes (when followSystem just became
  false but darkMode is still true). The intermediate flicker is
  invisible to the user because composable recomposition coalesces
  faster than the user can perceive, and the final emission lands on
  the correct value. Acceptable for this slice; if it becomes a
  problem, a batched setter on the legacy repo would be the fix.
- **Re-entry**: if the user navigates away and back, `init {}` re-runs
  for the new VM instance, the flow subscribes fresh, and the first
  emission populates state from the current SharedPrefs value. No
  stale-state issues.

## Deferrals

- **No PureBlack toggle** — separate setting, separate slice if needed.
- **No system-uiMode fallback restoration** — legacy's `Phase 9.6` TODO
  preserved verbatim; rework defaults to `AppTheme.System` (which
  effectively follows OS theme through MaterialTheme's
  `isSystemInDarkTheme()` consumer downstream).
- **No i18n lift** — display labels stay as `stringResource(Res.string.
  theme_light/dark/system)` lookups, same as the legacy.
- **No onboarding chain integration** — rework route is parallel +
  debug-guarded; legacy onboarding route stays bound to legacy
  composable.
- **No nav graph route-swap** — legacy `Screen.ThemeSelection` stays
  put. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface, two use
  cases, one impl, one MVI surface part each, one composable, one Koin
  module, one nav adapter).
- **OCP**: Empty sealed interface for `ThemeEffect`. `ThemeIntent` is
  sealed with one variant — extensible without modification. Repository
  interface is closed; impl substitutable via Koin.
- **DIP**: `:presentation` depends on `:domain`'s interface, not
  `:data`'s impl. `:data` depends on legacy `:shared`'s
  `SettingsRepository` — same strangler-fig posture as all six prior
  slices.
- **Layer boundary**: changes touch `:domain` (4 new), `:data` (1 new),
  `:presentation` (4 new), `:ui` (1 new), `:composeApp` (1 new +
  4 modified incl. close-out). No cross-layer reach beyond the
  strangler-fig `:data` → `:shared` permitted boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines.flow` (`combine`,
  `launchIn`, `onEach`).
- **MVI contract**: new slice — adds `ThemeState` + `ThemeIntent` +
  empty `ThemeEffect`. The base `MviViewModel<S, I, E>` is extended
  with `S = ThemeState`, `I = ThemeIntent`, `E = ThemeEffect`. The
  `handle(intent: ThemeIntent)` reducer is exhaustive (one branch on
  the one sealed variant).
- **Strangler-fig**: ONE `:data` → `:shared` reach (the
  `SettingsRepository` constructor injection). Same posture as all six
  prior slices. The boundary stays narrow: 2 of 13 legacy methods
  consumed.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, the per-host repo registry, OkHttp interceptor, AVIF
  decoder, HighQualitySkiaImageDecoder, or `:platform`. No
  load-bearing risk.
