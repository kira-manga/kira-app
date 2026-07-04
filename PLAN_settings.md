# Phase 7.x.settings.foundation — Settings hub screen rework

## Context

Phase 7.x.complaint.actions just landed (Task #252, close-out `4a5bacb`).
Per the `/goal` Stop hook Rule 3, the next non-blocked candidate is a
fresh feature rework. Settings is chosen because:

- It's the largest remaining un-ported user-facing surface (legacy
  `composeApp/.../features/settings/ui/screens/SettingsScreen.kt` is 396
  lines).
- The Theme rework (Phase 7.x.theme, Task #242-243) and Language rework
  (Phase 7.x.language, Task #249-250) already ported the theme-picker
  and language-picker sub-portions as standalone parallel routes —
  Settings rework consumes those existing routes for its nav rows.
- No block-and-ask triggers fire: no contract library blocker, no
  observable behaviour change (legacy route preserved; rework route is
  debug-guarded), no compile risk, no SOLID violation forced by design.

The legacy `SettingsScreen.kt` is a single `LazyColumn` with 14
interactive rows organized into three semantic groups:

**General Settings (5 toggles)**:
- Downloaded-only (DataStore-backed)
- Incognito (DataStore-backed)
- Follow-system-theme (SharedPrefs-backed)
- Dark-mode (SharedPrefs-backed; conditional — only shown when
  follow-system is off)
- Pure-black (SharedPrefs-backed)

**Navigation Settings (6 rows)**:
- Feedbacks & Complaints → `Screen.Complaint` (legacy) or
  `Screen.ComplaintAdmin` (admin-role)
- Default reading mode → inline `ReadingModeDialog` (DataStore-backed)
- Statistics → `Screen.Statistics` (legacy) — Statistics rework exists
  as `Screen.StatisticsRework`
- Language → `Screen.LanguageScreen` (legacy) — Language rework exists
  as `Screen.LanguageRework`
- Downloads → `Screen.DownloadsScreen` (legacy; no rework yet)

**Other Settings (3 rows)**:
- Clear cache (with size display)
- Request feature/bug (inline dialog — delegates to Complaint surface)
- About → `Screen.AboutScreen` (legacy) — About rework exists as
  `Screen.AboutRework`

## Approach

This is a multi-slice rework. The FOUNDATION slice ports the screen
shell + the 5 toggle rows + the cache size+clear action + the 6 nav
rows wired to rework counterparts (Theme/Language/Statistics/Complaint/
About) where available and legacy where not (Downloads).

**Deferred to follow-on slices**:

- `Phase 7.x.settings.readingmode` — inline `ReadingModeDialog`
  surface (state machine + dialog composable + persistence)
- `Phase 7.x.settings.feedback` — "Request feature/bug" inline action
  routing into the existing Complaint surface (likely just a nav
  destination redirect, but kept separate for review localization)
- `Phase 7.x.settings.appheader` — app-icon header card (decorative;
  legacy uses `painterResource("ic_launcher_foreground")`)

The foundation's `:domain` introduces a SINGLE `SettingsRepository`
interface in the rework `:domain` layer (sibling, not extension, of the
legacy `:shared` `SettingsRepository` — same name in different
packages). Methods are split along ISP-sensible lines:

```kotlin
interface SettingsRepository {
    // Toggle observers (5 flows)
    fun observeDownloadedOnly(): Flow<Boolean>
    fun observeIncognito(): Flow<Boolean>
    fun observeFollowSystemTheme(): Flow<Boolean>
    fun observeDarkMode(): Flow<Boolean>
    fun observePureBlack(): Flow<Boolean>

    // Toggle setters (5 suspends, returns Result<Unit>)
    suspend fun setDownloadedOnly(value: Boolean): Result<Unit>
    suspend fun setIncognito(value: Boolean): Result<Unit>
    suspend fun setFollowSystemTheme(value: Boolean): Result<Unit>
    suspend fun setDarkMode(value: Boolean): Result<Unit>
    suspend fun setPureBlack(value: Boolean): Result<Unit>

    // Cache management
    fun observeCacheSize(): Flow<String>      // pre-formatted "12 MB"
    suspend fun clearLargeCache(): Result<Unit>
}
```

**Rationale for fused interface vs sibling repositories**: this slice
keeps all 13 methods on ONE interface because they're all "what the
Settings screen needs" — the consumer (`SettingsViewModel`) doesn't
benefit from a READ/WRITE split since it both reads and writes every
field. The legacy fuses them too. Splitting would over-segment when the
single consumer touches all surfaces. (Contrast with §94/§95 Complaint
slices where READ-only `ComplaintListRepository` and WRITE-only
`ComplaintActionRepository` are split because different consumers
genuinely touch only one side — the foundation's
`ObserveUserComplaintsUseCase` never needs the actions side.)

**Strangler-fig boundary**: the `:data` impl reaches into the legacy
`:shared` `SettingsRepository` for all toggle flows + setters + cache
methods. Mirrors the strangler-fig posture of every prior rework slice.
The legacy facade is itself a thin wrapper over `DataStoreHelper`
(multiplatform-settings) + `SharedPrefsHelper` (legacy boolean prefs)
+ `AppFileSystem` (okio-based cache size + cache clear); the rework
`:data` impl is a thin adapter that:
- Pass-through-maps all 5 toggle flows and setters
- Wraps `getCacheFolderSize()` + `formatSize()` into a single
  `observeCacheSize(): Flow<String>` that emits on subscription and on
  each `clearLargeCache()` invocation
- Wraps `clearFilesLargerThan1MB()` in a `runCatching {}` for uniform
  `Result<Unit>` return

`Phase 9.x` route-swap retires the legacy facade later.

The `:presentation` VM subscribes to the 6 flows (5 toggles + 1 cache
size) via `combine(...)` in `init {}` and projects each emission into a
`SettingsState` snapshot. The 6 toggle/clear actions each dispatch
through `SettingsIntent` and route to the matching setter; success/
failure routes through `SettingsEffect.ShowSuccess/ShowError` snackbar.

The `:ui` composable mirrors the legacy's visual layout (grouped
LazyColumn sections with section headers + dividers) using the rework
`:ui`'s design tokens (`LocalSpacing`, MaterialTheme). Stateless —
takes `SettingsState` + emits `SettingsIntent`.

The `:composeApp` wiring follows the established 3-step pattern: new
`settingsReworkModule` + aggregate into `allReworkModules()` + new
`SettingsReworkScreenRoute.kt` + new `Screen.SettingsRework` + new
`composable<Screen.SettingsRework>` block in `App.kt`.

### Nav rows wired to rework counterparts

For nav rows where a rework counterpart exists, the foundation
`SettingsScreen` routes to it; for nav rows where only legacy exists,
it routes to legacy:

- Statistics → `Screen.StatisticsRework` (rework exists)
- Language → `Screen.LanguageRework` (rework exists)
- About → `Screen.AboutRework` (rework exists)
- Theme picker (NEW row) → `Screen.ThemeRework` (rework exists)
- Complaint → `Screen.ComplaintRework` (rework exists)
- Downloads → `Screen.DownloadsScreen` (legacy only)

This way the rework Settings hub becomes the discoverability surface
for the previously-debug-only-reachable rework routes. (The legacy
Settings is unchanged; the rework Settings IS itself debug-reachable
too until Phase 9.x route-swap.)

## Commit roadmap

Six commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for
slices touching common/desktop code, which this one does in `:ui`).

1. **Plan commit** — `PLAN_settings.md` only (1 file).

2. **`:domain` foundation** — 2 files, all new:
   - `domain/.../repository/SettingsRepository.kt` — interface (13
     methods: 5 observe + 5 set + 1 observeCacheSize + 1 clearLargeCache).
   - `domain/.../usecase/settings/ObserveSettingsUseCase.kt` — combined
     observer use case returning `Flow<SettingsSnapshot>` (data class
     bundling all 6 observable fields).
   - `domain/.../model/settings/SettingsSnapshot.kt` — bundle data class
     (6 fields: downloadedOnly + incognito + followSystem + darkMode +
     pureBlack + cacheSize).
   - `domain/.../usecase/settings/UpdateSettingsToggleUseCase.kt` — one
     suspend function with a `SettingsToggle` enum payload (DOWNLOADED_
     ONLY / INCOGNITO / FOLLOW_SYSTEM / DARK_MODE / PURE_BLACK) +
     boolean value, routing to the matching repository setter.
   - `domain/.../usecase/settings/ClearCacheUseCase.kt` — thin
     pass-through to `clearLargeCache()`.

   Actually that's 5 files — at the cap. Will split if implementation
   discovers an additional needed file; otherwise tight at 5.

3. **`:data` strangler-fig impl** — 1 file, new:
   - `data/.../repository/SettingsRepositoryImpl.kt` — class takes
     `legacy: LegacySettingsRepository` constructor arg; implements
     all 13 methods. `observeCacheSize()` combines a refresh-on-
     subscription flow with re-emission after `clearLargeCache()`
     via a `MutableSharedFlow<Unit>` refresh trigger or a simple
     `Flow.onStart` + manual recomposition.

4. **`:presentation` MVI** — 4 files, all new:
   - `presentation/.../settings/SettingsState.kt` — data class with the
     6 observable fields + an `isLoading: Boolean` for the initial-
     emission gap.
   - `presentation/.../settings/SettingsIntent.kt` — sealed interface
     with variants: `OnToggle(SettingsToggle, Boolean)`,
     `OnClearCache`, plus the 6 nav-row signal intents
     (`OnNavigateToTheme`, `OnNavigateToLanguage`,
     `OnNavigateToStatistics`, `OnNavigateToAbout`,
     `OnNavigateToComplaint`, `OnNavigateToDownloads`).
   - `presentation/.../settings/SettingsEffect.kt` — sealed interface
     with `ShowSuccessMessage(message)` and `ShowErrorMessage(message)`
     variants (mirrors §95's pattern).
   - `presentation/.../settings/SettingsViewModel.kt` — extends
     `MviViewModel<SettingsState, SettingsIntent, SettingsEffect>`;
     constructor takes 3 deps (`observeSettings: ObserveSettings
     UseCase`, `updateToggle: UpdateSettingsToggleUseCase`,
     `clearCache: ClearCacheUseCase`); `init {}` collects the
     `observeSettings` flow and projects each emission into state. The
     6 nav intents emit corresponding `SettingsEffect.Navigate*`
     effects which the route adapter handles (route adapter pattern
     keeps the VM free of `NavController` knowledge).

   Actually the nav effects need to be `SettingsEffect` variants. Let
   me restructure: `SettingsEffect` = ShowSuccess + ShowError +
   NavigateToTheme + NavigateToLanguage + NavigateToStatistics +
   NavigateToAbout + NavigateToComplaint + NavigateToDownloads (8
   variants total).

5. **`:ui` composable** — 1 file, new:
   - `ui/.../settings/SettingsScreen.kt` — `@Composable` taking a
     `SettingsViewModel` and rendering the LazyColumn shell with
     grouped sections (General toggles + Navigation rows + Other).
     Each toggle row is a custom composable wrapping
     `Switch(checked, onCheckedChange)` that fires
     `SettingsIntent.OnToggle(toggle, value)`. Each nav row fires the
     matching `SettingsIntent.OnNavigateTo*`. Cache size display
     uses the `state.cacheSize` string. Snackbar host + effect
     collector for success/error messages (same pattern as §95).

6. **`:composeApp` Koin + nav + close-out** — up to 5 files at the cap:
   - `composeApp/.../di/SettingsReworkModule.kt` (NEW) — module
     declaring `single<SettingsRepository> { SettingsRepositoryImpl
     (legacy = get()) }` + `factory { ObserveSettingsUseCase(get())
     }` + `factory { UpdateSettingsToggleUseCase(get()) }` +
     `factory { ClearCacheUseCase(get()) }` + `viewModel {
     SettingsViewModel(get(), get(), get()) }`.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `settingsReworkModule` to `allReworkModules()`.
   - `composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt`
     (NEW) — `@Composable fun` taking `NavController` +
     `NavBackStackEntry`; resolves `SettingsViewModel` via
     `koinViewModel()`; calls `SettingsScreen(viewModel, onNavigate
     ...)`. The 6 nav effects are routed via `LaunchedEffect`
     consuming `viewModel.effects` and calling
     `navController.navigate(Screen.X)` for the matching destination.
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add `object
     SettingsRework : Screen("me.manga.kira.navigation.Screen.
     SettingsRework")`.
   - `composeApp/.../App.kt` (MODIFIED) — add `composable<Screen.
     SettingsRework> { ... SettingsReworkScreenRoute(...) }`
     alongside the existing `composable<Screen.Setting>` entry.

   Exactly 5 files at the cap. Close-out (`ARCHITECTURE.md` §96 +
   `SOLID_AUDIT.md` Phase 7.x.settings.foundation entry) lands as a
   SEPARATE 7th commit — splitting the wiring + close-out across two
   commits keeps each at ≤5 files.

7. **Close-out** — 2 files (ARCHITECTURE.md + SOLID_AUDIT.md).

That's a 7-commit slice. May collapse to 6 if implementation discovers
file-touch overlaps (similar to how Phase 7.x.complaint.actions
collapsed from 7 to 6).

## Critical files

### New (12 files)

- `domain/.../repository/SettingsRepository.kt`
- `domain/.../model/settings/SettingsSnapshot.kt`
- `domain/.../usecase/settings/ObserveSettingsUseCase.kt`
- `domain/.../usecase/settings/UpdateSettingsToggleUseCase.kt`
- `domain/.../usecase/settings/ClearCacheUseCase.kt`
- `data/.../repository/SettingsRepositoryImpl.kt`
- `presentation/.../settings/SettingsState.kt`
- `presentation/.../settings/SettingsIntent.kt`
- `presentation/.../settings/SettingsEffect.kt`
- `presentation/.../settings/SettingsViewModel.kt`
- `ui/.../settings/SettingsScreen.kt`
- `composeApp/.../di/SettingsReworkModule.kt`
- `composeApp/.../navigation/routes/SettingsReworkScreenRoute.kt`
- `PLAN_settings.md`

### Modified

- `composeApp/.../di/ReworkModules.kt` — append `settingsReworkModule`.
- `composeApp/.../navigation/Screen.kt` — add `SettingsRework` enum.
- `composeApp/.../App.kt` — add `composable<Screen.SettingsRework>`.
- `ARCHITECTURE.md` — append §96.
- `SOLID_AUDIT.md` — append Phase 7.x.settings.foundation entry.

### Untouched (verify by read, not modified)

- `shared/.../features/settings/domain/SettingsRepository.kt` — legacy
  facade; strangler-fig delegates to its methods but does NOT modify.
- `shared/.../features/settings/ui/viewmodel/SettingsViewModel.kt` —
  legacy VM; preserved for legacy route.
- `composeApp/.../features/settings/ui/screens/SettingsScreen.kt` —
  legacy screen; preserved.
- `composeApp/.../navigation/routes/SettingsRoute.kt` — legacy route;
  preserved.

## Reuse

- **Strangler-fig posture**: lifted from prior rework slices'
  `:data` impl classes. Same legacy `:shared` SettingsRepository
  injection, same `single` Koin lifecycle.
- **MVI base class**: extends `MviViewModel<S, I, E>`.
- **Koin module shape**: mirrors prior rework modules.
- **Nav route shape**: mirrors `LibraryReworkScreenRoute`; the 6 nav
  effects collector pattern is new but follows the same
  `LaunchedEffect(viewModel) { effects.collectLatest { ... } }`
  pattern as `ComplaintScreen.kt` (§95).
- **Snackbar host + effect collector**: lifted verbatim from §95's
  `ComplaintScreen.kt` (success/error message routing).

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — required.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — required.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — required.
- `gradlew.bat :composeApp:compileKotlinDesktop` — required for commit
  5 (`:ui` composable) and onwards.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.SettingsRework`,
  verify the 5 toggles reflect the same DataStore/SharedPrefs state as
  the legacy `Screen.Setting` screen. Toggle each; verify legacy reads
  the new value (cross-route invariant — same upstream storage).
- Tap each nav row, verify it navigates to the matching rework
  counterpart (Theme/Language/Statistics/About/Complaint) or legacy
  (Downloads).
- Tap Clear Cache, verify the size string updates after the action.

## Deferrals

- **No i18n lift** — all section headers, row labels, toggle names,
  snackbar messages stay inline literal. Phase 10 lift handles both
  legacy and rework consumers in one pass.
- **No ReadingModeDialog** — inline reading-mode picker dialog deferred
  to `Phase 7.x.settings.readingmode`. The "Default reading mode" nav
  row is omitted from the foundation rework (will be added back in the
  follow-on slice).
- **No Request-feedback dialog** — the legacy "Request feature/bug"
  inline dialog deferred to `Phase 7.x.settings.feedback`. The rework
  foundation omits this row.
- **No app-icon header card** — the legacy decorative app-icon header
  deferred to `Phase 7.x.settings.appheader`.
- **No conditional dark-mode visibility** — the legacy hides dark-mode
  toggle when follow-system is on. The rework foundation shows all 5
  toggles unconditionally for simpler state-machine; the conditional
  visibility is a UX polish to add in a follow-on if desired.
- **No nav graph route-swap** — legacy `Screen.Setting` stays bound to
  the legacy route. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one interface, one model, one
  use case per file, one impl, one MVI surface part, one composable,
  one Koin module, one nav adapter).
- **OCP**: Sealed-interface intents/effects. Adding a 6th toggle (e.g.,
  "Notification sound") is a new variant; existing 5 unchanged.
- **DIP**: `:presentation` depends on `:domain`'s interface, not
  `:data`'s impl. `:data`'s impl depends on legacy `:shared`'s
  `SettingsRepository` (strangler-fig).
- **Layer boundary**: changes touch `:domain` (5 new files), `:data`
  (1 new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (1 new + 1 new + 2 modified incl. close-out).
- **Banned features**: no `!!`, no `Any`, no `lateinit`, no `Thread`.
- **MVI contract**: new slice — adds `SettingsState`, 8-variant
  `SettingsIntent`, 8-variant `SettingsEffect`, 3-arg VM. Effects-
  driven nav (route adapter collects effects, calls `navController.
  navigate`). State is flow-driven (init-time flow collector), not
  intent-driven.
- **Strangler-fig**: ONE `:data` → `:shared` reach (the legacy
  SettingsRepository injection in the impl). Mirrors §94's posture.
- **Load-bearing fixes preserved**: this slice touches NO Coil
  ImageLoader, no Reader decoder hints, no OkHttp interceptor, no
  load-bearing image-quality posture. No load-bearing risk.
