# Phase 7.x.language — Language selection screen rework (foundation)

## Context

WhatsNew thread is complete through `.foundation` + `.pager` + `.polish`
(commits `170a93f` → `af72a01`). Per the `/goal` Stop hook Rule 3 and the
Phase 7.x.whatsnew.polish End-of-slice verdict, next non-blocked candidate
is **Language selection** — a fresh feature port comparable in shape to
Statistics / History / Updates / Sources / Theme. The legacy
`LanguageSelectionScreen` is 228 LOC: a Material 3 list of supported
languages backed by a 1-flow / 1-mutator VM that delegates to
`SettingsRepository.languageFlow` + `setLanguage(code)` + the
`core.locale.applyApplicationLocale(code)` expect/actual.

**Foundation-only slice — explicit deferrals.** The legacy screen also
houses a "Request New Language" entry that opens a `FeedbackDialog`,
which submits a `ComplaintType.LANGUAGES` complaint through a separate
`ComplaintViewModel` and surfaces success/failure via a `SnackbarHost`.
That entire cross-cutting integration (FeedbackDialog +
ComplaintViewModel + Snackbar + 4 `Res.string.*` lookups +
`displayName()` Composable extension) is deferred to a follow-on
**Phase 7.x.language.request** sub-slice — matching the
Phase 7.x.about → Phase 7.x.about.whatsnewrow deferral pattern. The
foundation slice ports ONLY the language list + selection.

Block-and-ask triggers (a)-(d) all NOT met: no contract library blocker,
no observable behaviour change (legacy `Screen.Language` route preserved
verbatim with the FeedbackDialog still wired; rework
`Screen.LanguageRework` is parallel + debug-only), no compile risk, no
SOLID violation forced by the design.

## Approach

The legacy `LanguageViewModel` (`shared/.../language/ui/viewmodel/`)
is a thin pair around `SettingsRepository.languageFlow` +
`setLanguage(code)`:

```kotlin
class LanguageViewModel(private val settingsRepo: SettingsRepository) : ViewModel() {
    val selectedLanguageFlow: Flow<String> = settingsRepo.languageFlow
    fun selectLanguage(code: String) {
        viewModelScope.launch {
            settingsRepo.setLanguage(code)
            applyApplicationLocale(code)
        }
    }
}
```

The supported-language list lives in `LanguageScreenRoute.kt` (legacy
nav adapter) as a hand-curated `LANGUAGE_DISPLAY_NAMES: Map<String, String>`
of 11 entries (native endonyms keyed by IETF tag — `en`, `ar`, `de`,
`es`, `fr`, `in`, `it`, `ja`, `pt`, `ru`, `tr`). The legacy route resolves
the supported tags from `Res.array.supported_languages` then maps them
through that table.

### Layer-by-layer

**`:domain`** — 5 files at cap:

1. `Language.kt` — `data class Language(val code: String, val displayName: String)`.
   Reuses the legacy `LanguageOption` shape verbatim (only renamed —
   `LanguageOption` was a `:shared`-package class; the rework lives in
   `:domain`).

2. `LanguageRepository.kt` — interface with three methods:
   - `fun observeSelectedLanguageCode(): Flow<String>` — current code,
     empty string for "not yet selected" (the legacy default).
   - `fun getSupportedLanguages(): List<Language>` — the canonical 11
     entries; sync because the list is a compile-time constant.
   - `suspend fun setLanguage(code: String)` — persist + apply locale.

3. `ObserveSelectedLanguageUseCase.kt` — `operator fun invoke(): Flow<String>`
   delegating to `repo.observeSelectedLanguageCode()`.

4. `GetSupportedLanguagesUseCase.kt` — `operator fun invoke(): List<Language>`
   delegating to `repo.getSupportedLanguages()`.

5. `SetLanguageUseCase.kt` — `suspend operator fun invoke(code: String)`
   delegating to `repo.setLanguage(code)`.

   **Why three use cases not one** — same DIP/ISP posture as Theme
   (`ObserveAppTheme` + `SetAppTheme` + `ObservePureBlack` +
   `SetPureBlack`). Each is a stable test seam for the VM and a future
   composition site (e.g., a `LanguageWithFallbackUseCase` that combines
   the selected-code flow with a system-locale fallback could slot in
   without changing the VM).

**`:data`** — 1 file:

6. `LanguageRepositoryImpl.kt` — strangler-fig over the legacy `:shared`
   `SettingsRepository`. Constructor takes `legacy: SettingsRepository`.
   - `observeSelectedLanguageCode()` returns `legacy.languageFlow` directly
     (no translation — types match).
   - `getSupportedLanguages()` returns the hand-curated 11-entry list
     (native endonyms) — moved here from the legacy
     `LANGUAGE_DISPLAY_NAMES` map in `LanguageScreenRoute.kt`. **The rework
     repo is the single source of truth for the rework path**; the legacy
     route adapter's table stays put for the legacy route (no shared
     reference — duplication is a deliberate strangler-fig posture so the
     legacy route is untouched). When Phase 9.x retires the legacy route,
     the legacy map deletes with it.
   - `setLanguage(code)` calls `legacy.setLanguage(code)` THEN
     `applyApplicationLocale(code)` — matches the legacy VM's pairing
     verbatim. The `applyApplicationLocale` expect/actual lives in
     `shared/.../core/locale/LocaleSwitcher.kt`; `:data` already
     `implementation`-depends on `:shared` for the strangler-fig posture
     (see `data/build.gradle.kts`).

**`:presentation`** — 4 files:

7. `LanguageState.kt` — data class:
   ```kotlin
   data class LanguageState(
       val isLoading: Boolean = true,
       val languages: List<Language> = emptyList(),
       val selectedCode: String = "",
   ) : MviState
   ```
   `isLoading` covers the subscription-to-first-emission gap on the
   `observeSelectedLanguageCode()` flow. `languages` is populated once
   in `init {}` from the synchronous `GetSupportedLanguagesUseCase`.

8. `LanguageIntent.kt` — sealed interface with one variant:
   ```kotlin
   data class OnSelectLanguage(val code: String) : LanguageIntent
   ```
   `OnSelectLanguage` is `data class` (not `data object`) because the
   code is a runtime value, not a finite enumeration — same posture as
   `SourcesIntent.OnToggleSource` / `UpdatesIntent.OnMarkAsRead`.

9. `LanguageEffect.kt` — empty sealed interface, same OCP rationale as
   `ThemeEffect` / `SourcesEffect` / `StatisticsEffect`. Future
   `Phase 7.x.language.request` will add `OnLanguageRequested(success:
   Boolean)` here when the FeedbackDialog wiring lands; the empty seal
   keeps the OCP hook in place without forcing
   `MviViewModel<S, I, Nothing>`.

10. `LanguageViewModel.kt` — extends
    `MviViewModel<LanguageState, LanguageIntent, LanguageEffect>` with
    three deps (the three use cases). `init {}` block:
    - Reads `getSupportedLanguages()` synchronously and writes the list
      into state.
    - `observeSelectedLanguage()` `.onEach { ... }.launchIn(viewModelScope)`
      — projects each emission into `selectedCode` + clears `isLoading`.
    - `handle(intent)` matches `OnSelectLanguage(code)` →
      `viewModelScope.launch { setLanguage(code) }`. Fire-and-forget;
      the upstream `languageFlow` re-emits via the legacy DataStore
      write, so the picker's selected-radio state updates reactively.

**`:ui`** — 1 file:

11. `LanguageScreen.kt` — Material 3 `LazyColumn` of language rows.
    Each row: title (`displayName`) + subtitle (`code`) + a trailing
    `Icons.Default.Done` icon shown only when `code == state.selectedCode`
    (mirrors the legacy `StatsItem` row's `icon = if (code == selected)
    Icons.Default.Done else null` pattern). Tap a row →
    `onIntent(LanguageIntent.OnSelectLanguage(code))`.

    - **Loading branch** centered `CircularProgressIndicator` while
      `state.isLoading == true` (covers the sub-frame gap before the
      first `languageFlow` emission).
    - **No top app bar back arrow** — same posture as the other rework
      `:ui` screens; back is the system back. The route adapter has
      no `onBack` parameter (matches `ThemeReworkScreenRoute` —
      terminal display-and-select screen).
    - **No `Request Language` row** — deferred to
      `Phase 7.x.language.request`. The list ends at the last supported
      language; no `Add` icon at the bottom in the foundation slice.
    - **No `Res.string.*` lookups** — labels are inline literals
      ("Language" header). Phase 10 i18n lift swaps both legacy and
      rework consumers in one pass — matches the Theme / About /
      Sources / etc. posture.
    - Stateless inner `LanguageScreenContent(state, onIntent)` for
      preview / test substitution.
    - Material 3 `Icons.Default.Done` — already on the rework `:ui`
      classpath (used by other rework screens). Material 3 `RadioButton`
      is NOT used in the foundation; the icon-tick visual matches the
      legacy `StatsItem`'s native pattern and avoids introducing a new
      visual primitive.

**`:composeApp`** — 5 files at cap:

12. `LanguageReworkModule.kt` (NEW) — Koin module:
    ```kotlin
    val languageReworkModule: Module = module {
        single<LanguageRepository> { LanguageRepositoryImpl(legacy = get()) }
        factory { ObserveSelectedLanguageUseCase(get()) }
        factory { GetSupportedLanguagesUseCase(get()) }
        factory { SetLanguageUseCase(get()) }
        viewModel { LanguageViewModel(get(), get(), get()) }
    }
    ```

13. `ReworkModules.kt` (MODIFIED) — append `languageReworkModule` to the
    `allReworkModules()` list.

14. `LanguageReworkScreenRoute.kt` (NEW) — route adapter, mirrors
    `ThemeReworkScreenRoute`'s shape. Resolves `LanguageViewModel` via
    `koinViewModel()` and calls `LanguageScreen(viewModel)`. No nav
    callbacks (terminal screen).

15. `Screen.kt` (MODIFIED) — add `object LanguageRework :
    Screen("me.manga.kira.navigation.Screen.LanguageRework")` with
    a KDoc block matching the surrounding rework-screen entries.

16. `App.kt` (MODIFIED) — add `composable<Screen.LanguageRework>` block
    with `SideEffect { onBottomBarVisibleChange(true) }` (Language
    picker is reachable from the bottom-nav-visible Settings entry in
    the legacy graph; mirror that posture). Plus the import for
    `LanguageReworkScreenRoute`.

   Exactly 5 files at the cap.

### Strangler-fig boundary

The `:data` impl reaches into `:shared`'s legacy
`SettingsRepository.languageFlow` + `setLanguage(code)` AND
`core.locale.applyApplicationLocale(code)`. SAME posture as
`ThemeRepositoryImpl` (already reads `SettingsRepository.darkModeFlow` +
`followSystemFlow` + writes `setDarkMode` / `setFollowSystem`). No new
`:data` → `:shared` dependency — already in place from
`data/build.gradle.kts`'s `implementation(project(":shared"))`.

### MVI surface decisions

- **`LanguageState.languages`** carries the supported list, NOT an
  externally-passed `availableLanguages` parameter on the `:ui`
  composable. Reason: keeps the `:ui` composable nav-host-agnostic and
  unit-testable with a single `LanguageState` snapshot. The legacy
  pattern (`LanguageSelectionScreen(availableLanguages: List<LanguageOption>,
  ...)`) coupled the composable to the route's `stringArrayResource`
  lookup; the rework moves that lookup into `:data` where the list is
  a compile-time constant.

- **`isLoading`** covers ONLY the `observeSelectedLanguageCode()` first-
  emission gap. `getSupportedLanguages()` is synchronous, so the list
  is in state from frame 1. The loading branch could in principle skip
  the spinner entirely — but matching the rework-screen convention
  (`ThemeState` / `SourcesState` / `UpdatesState` all have `isLoading`)
  keeps `isLoading = false` semantics uniform: "the upstream pref flow
  has emitted at least once".

- **`OnSelectLanguage(code: String)`** carries the post-tap code (not a
  "toggle to next" verb) — reducer is idempotent regardless of
  state-vs-intent ordering. Re-tapping the currently-selected language
  is a no-op at the DataStore level (Android short-circuits equal-value
  writes).

### Read-current-language quirk

Legacy `languageFlow` defaults to empty string `""` on first run
(non-nullable in commonMain). The rework treats `""` identically — no
language is shown as "selected" until the user picks one OR the
DataStore has been seeded by the legacy route's first selection. The
`:ui` composable's `selectedCode == ""` ⇒ no Done-icon row matches the
legacy `selectedLanguage == ""` ⇒ no Done-icon behaviour.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after
every source commit (Android + iOS Arm64 + iOS SimulatorArm64 + Desktop —
the slice touches `:data` / `:domain` / `:presentation` / `:ui`
common-source, which links into all four targets).

1. **Plan commit** — `PLAN_language.md` only (1 file).

2. **`:domain` foundation** — 5 files at cap:
   - `domain/.../model/language/Language.kt` (NEW)
   - `domain/.../repository/LanguageRepository.kt` (NEW)
   - `domain/.../usecase/language/ObserveSelectedLanguageUseCase.kt` (NEW)
   - `domain/.../usecase/language/GetSupportedLanguagesUseCase.kt` (NEW)
   - `domain/.../usecase/language/SetLanguageUseCase.kt` (NEW)

3. **`:data` strangler-fig impl** — 1 file:
   - `data/.../repository/LanguageRepositoryImpl.kt` (NEW)

4. **`:presentation` MVI** — 4 files:
   - `presentation/.../language/LanguageState.kt` (NEW)
   - `presentation/.../language/LanguageIntent.kt` (NEW)
   - `presentation/.../language/LanguageEffect.kt` (NEW)
   - `presentation/.../language/LanguageViewModel.kt` (NEW)

5. **`:ui` composable** — 1 file:
   - `ui/.../language/LanguageScreen.kt` (NEW)

6. **`:composeApp` Koin + nav** — 5 files at cap:
   - `composeApp/.../di/LanguageReworkModule.kt` (NEW)
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED — append)
   - `composeApp/.../navigation/Screen.kt` (MODIFIED — add LanguageRework)
   - `composeApp/.../navigation/routes/LanguageReworkScreenRoute.kt` (NEW)
   - `composeApp/.../App.kt` (MODIFIED — composable block + import)

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §92 — Phase 7.x.language.
   - `SOLID_AUDIT.md` — append Phase 7.x.language entry with per-file
     SOLID 10-point checklist + End-of-slice verdict.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/language/Language.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/LanguageRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/language/ObserveSelectedLanguageUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/language/GetSupportedLanguagesUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/language/SetLanguageUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/LanguageRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/language/LanguageViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/language/LanguageScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/LanguageReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/LanguageReworkScreenRoute.kt`
- `PLAN_language.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `languageReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `LanguageRework` object.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.LanguageRework>` block + import.
- `ARCHITECTURE.md` — append §92.
- `SOLID_AUDIT.md` — append Phase 7.x.language entry.

### Untouched

- `shared/.../features/language/data/LanguageOption.kt` — legacy
  data class; the rework introduces its own `:domain` `Language` model.
  Legacy stays put for the legacy route.
- `shared/.../features/language/ui/viewmodel/LanguageViewModel.kt` —
  legacy VM stays bound to the legacy route. Phase 9.x route-swap retires
  it later.
- `composeApp/.../features/language/ui/screens/LanguageSelectionScreen.kt` —
  legacy screen; preserved for the legacy route. Phase 9.x route-swap
  retires it.
- `composeApp/.../navigation/routes/LanguageScreenRoute.kt` — legacy
  route + the `LANGUAGE_DISPLAY_NAMES` map. Preserved verbatim. Phase
  9.x retires.
- `shared/.../presentation/features/complaint/**` — ComplaintViewModel /
  ComplaintType / FeedbackDialog. All deferred to
  `Phase 7.x.language.request`.

## Reuse

- **Strangler-fig posture**: lifted directly from `ThemeRepositoryImpl`'s
  class shape (constructor takes `legacy: SettingsRepository`, delegates
  through it).
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `presentation/.../mvi/` — same superclass every rework VM extends.
- **Koin module shape**: mirrors `themeReworkModule` — `single` for the
  repo, `factory` for each use case, `viewModel` for the VM.
- **Nav route shape**: mirrors `ThemeReworkScreenRoute` — terminal
  screen, no nav callbacks, two unused parameters preserved for the
  uniform route-adapter signature.
- **Composable layout primitives**: reuse `LocalSpacing` design tokens,
  Material 3 `Scaffold` / `TopAppBar` / `LazyColumn` / `Icon` — already
  used by every other rework `:ui` screen. No new design tokens or
  primitives.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required for
  the `:ui` commit because the new screen file is in `:ui/commonMain`
  and links into the Desktop entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Navigate to `Screen.LanguageRework` via the guarded debug entry,
  verify the 11 supported languages render with their native endonyms,
  tap a row → the Done icon moves to the new row, language persists
  across kill-restart.
- Verify side-by-side with `Screen.Language` (legacy route) — both
  reflect the same persisted state because both read/write the same
  `DataStoreHelper.languageFlow`.
- iOS / Desktop: `applyApplicationLocale` is a no-op (per its KDoc) —
  the persisted preference takes effect on next launch. Verify the
  prefs is written.

Edge cases to mentally model during implementation:

- First-run / empty selection: `selectedCode == ""` — no row gets the
  Done icon. Matches the legacy `Locale.getDefault().language` →
  empty-string fallback at port time.
- Re-tap currently-selected language: `setLanguage(code)` is called
  with the same value; DataStore short-circuits equal-value writes, the
  upstream flow does not re-emit, the state remains unchanged. No
  feedback loop.
- Concurrent updates: if the user taps Language A then Language B
  before the first DataStore write commits, both writes execute on
  `viewModelScope` and DataStore serialises them; the upstream
  `languageFlow` emits the final value (B). The intermediate value (A)
  may or may not emit; Compose coalesces.

## Deferrals

- **`Phase 7.x.language.request`** — "Request New Language" row +
  FeedbackDialog + ComplaintViewModel + SnackbarHost + 4
  `Res.string.*` lookups (`request_language`, `request_add_language`,
  `enter_your_language`, `request_submitted_successfully` /
  `request_failed` / `retry`) + the `ComplaintType.LANGUAGES.displayName()`
  Composable extension. Wholly cross-cutting integration —
  intentionally split into its own slice to keep the foundation under
  the 5-file cap and to allow the rework `LanguageScreen` to ship
  end-to-end before the dialog wiring lands.
- **No i18n lift** — header label ("Language") stays inline literal.
  Phase 10 i18n lift handles both routes in one pass.
- **No RTL-mirror affordance** — Material 3 `LazyColumn` already
  inherits RTL layout direction from the `MaterialTheme.LayoutDirection`.
  No explicit mirror needed.
- **No "system language" entry** — the legacy list also lacks one;
  matching the legacy behaviour. The `applyApplicationLocale("")` path
  exists (would reset to system) but no list row triggers it. A future
  slice could add a "System default" row that writes `""`.
- **No nav graph route-swap** — legacy `Screen.Language` stays bound
  to the legacy `LanguageScreenRoute`. Phase 9.x route-swap is its own
  slice; this slice only adds the parallel `Screen.LanguageRework`.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each new file has one rule (one ADT, one interface, one use
  case, one impl, one MVI surface part, one composable, one Koin
  module, one nav adapter).
- **OCP**: empty sealed `LanguageEffect` reserves OCP-friendly hooks
  for the follow-on `.request` slice. The `LanguageIntent` seal is
  closed under modification (one variant today); adding e.g.
  `OnRequestLanguage` is an append without changing the VM base class.
- **DIP**: `:presentation` depends on `:domain`'s three use cases +
  interface. `:data` depends on legacy `:shared`'s `SettingsRepository`
  + `core.locale.applyApplicationLocale` because that's where the
  language preference + locale-switching effect live today — same
  strangler-fig posture as `ThemeRepositoryImpl`.
- **Layer boundary**: changes touch `:domain` (5 new files), `:data`
  (1 new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (2 new + 3 modified incl. close-out). No cross-layer
  reach beyond the strangler-fig `:data` → `:shared` permitted
  boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines.flow`. The
  `applyApplicationLocale` call is on the platform's main coroutine
  scope via `viewModelScope.launch` → use case `suspend` invocation —
  no manual thread management.
- **MVI contract**: new slice — adds `LanguageState` +
  `LanguageIntent.OnSelectLanguage` + empty `LanguageEffect`. The base
  `MviViewModel`'s `submit` surface compiles cleanly with `Intent =
  LanguageIntent` (one variant).
- **Strangler-fig**: ONE `:data` → `:shared` reach (the
  `SettingsRepository` constructor injection + the
  `applyApplicationLocale` static call). Same posture, same boundary
  as `ThemeRepositoryImpl`. No new `:data` → `:shared` dependency
  declaration needed.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, the Reader's per-request listener, the Reader's decoder
  hints, OkHttp interceptor, AVIF decoder,
  HighQualitySkiaImageDecoder, or `:platform` — Language is pure
  preference + locale-switcher plumbing. No load-bearing risk.
