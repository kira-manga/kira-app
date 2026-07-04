# Phase 7.x.whatsnew — WhatsNew screen rework (foundation slice)

## Context

Phase 7.x.about (Task #244) closed at HEAD `666d538` deferring the
Whats-new row on About to a follow-on slice that ports the legacy
`WhatsNewScreen` first. This is that slice's **foundation** sub-slice —
it ships ONLY the title+description rendering surface and explicit
deferrals for the media-heavy parts.

Block-and-ask triggers (a)-(d) all NOT met. The slice scopes honestly
around the WhatsNew surface's complexity:

- Legacy `WhatsNewScreen` + components total **~1300 LOC** across 14
  files (model, data, VM, FeatureCard, FullscreenMediaViewer,
  ImageComponents, VideoComponents, WhatsNewComponents,
  WhatsNewDrawableRegistry).
- Legacy `WhatsNewViewModel` (257 LOC) hosts complex orchestration:
  version-change "should-show" gating + remote-feature fetch via
  `WhatsNewRemoteDataSource` (Ktor) + language-aware localization via
  `DataStoreHelper.languageFlow` + fallback to `getDefaultFeatures()`
  (which returns `emptyList()` in the current port) + prefs-backed
  last-seen tracking.
- Media rendering needs (deferred): `DrawableResource` binding (compose-
  resources), Coil URL image loading, video playback (requires a
  `:platform` MediaPlayer SPI not yet present), `HorizontalPager`
  swipe UX, fullscreen media viewer.

The verdict from Phase 7.x.about §87 framed Phase 7.x.whatsnew as a
"small terminal screen — similar shape to About." That framing was
optimistic — the surface is materially larger. This slice ports the
**foundation** honestly: the smallest viable end-to-end stack (model +
repository + 2 use cases + MVI + minimal `:ui` + Koin/nav) that proves
the rework architecture works for WhatsNew, while leaving the media-
heavy parts to follow-on sub-slices.

## Approach

The legacy data flow has three "cells of truth":

1. **Remote features** — `WhatsNewRemoteDataSource.fetchWhatsNewFeatures
   (languageCode): Result<WhatsNewResponse>` (Ktor HTTP GET on a remote
   JSON endpoint, with `try/catch` returning `Result.failure` on any
   error).
2. **Default features** — `getDefaultFeatures(): List<WhatsNewFeature>`
   (top-level fun in `:shared`; currently returns `emptyList()` — the
   upstream port commented out the actual defaults during the KMP
   migration).
3. **Last-seen prefs** — two keys in `SharedPrefsHelper`:
   `"whats_new_last_shown_version_name"` (String) + `"whats_new_last_
   shown_timestamp"` (Long). Reads + writes are synchronous.

The rework's `:data` impl strangler-figs over those three cells. The
impl exposes two methods on the `:domain` `WhatsNewRepository`:

```kotlin
suspend fun getFeatures(): List<WhatsNewFeature>
suspend fun markSeen()
```

`getFeatures()` calls `remoteDataSource.fetchWhatsNewFeatures(languageCode)
` with a hardcoded `"en"` (language localization deferred), and on any
failure falls back to `getDefaultFeatures()` (which is empty today).

`markSeen()` writes the current `appVersionProvider.versionName` to the
version-name key + the current `Clock.System.now()` to the timestamp
key. Same key names as the legacy VM — round-trips with the legacy
surface (if both routes are reachable, marking-seen on either sticks).

The `:domain` model `WhatsNewFeature` ports the legacy data class
**verbatim** (title + description + mediaType + imageResName +
imageResNameList + imageUrl + imageUrlList + videoUrl + isNew +
version). The foundation `:ui` renders ONLY title + description + the
"NEW" badge if `isNew == true`; the other fields are carried in the
model for follow-on slices to consume without breaking the contract.

`MediaType` (legacy `IMAGE` / `VIDEO` / `LIST` / `URL` enum) ports
verbatim into `:domain/.../model/whatsnew/`.

The `:presentation` MVI surface:
- `WhatsNewState(isLoading: Boolean = true, features: List<WhatsNewFeature> = emptyList())`
- `WhatsNewIntent.OnRetry` (data object) + `WhatsNewIntent.OnMarkSeen` (data object)
- `WhatsNewEffect` empty sealed interface (no error effect today —
  failures fall back to empty list silently, matching legacy posture)
- `WhatsNewViewModel`'s `init {}` launches `viewModelScope.launch { ... }`
  that calls `getFeatures()` once and updates state. `handle(OnRetry)`
  re-launches the same load. `handle(OnMarkSeen)` fires-and-forgets
  `markWhatsNewSeen()` on `viewModelScope`.

The `:ui` `WhatsNewScreen` is a Scaffold + TopAppBar + LazyColumn of
Cards. Each Card displays `title` + `description` + optional "NEW"
chip. On screen exit, the route adapter does NOT auto-mark-seen
(deferred to a follow-on slice once the should-show gating is wired).

The `:composeApp` wiring follows the established 5-file shape:
1. `WhatsNewReworkModule.kt` (NEW)
2. `ReworkModules.kt` (MODIFIED — append `whatsNewReworkModule`)
3. `WhatsNewReworkScreenRoute.kt` (NEW)
4. `Screen.kt` (MODIFIED — append `WhatsNewRework`)
5. `App.kt` (MODIFIED — register `composable<Screen.WhatsNewRework>`)

### Strangler-fig boundary

The `:data` impl reaches into `:shared` for THREE legacy facades:
- `WhatsNewRemoteDataSource` (already Koin-bound `single` in
  `SharedModule.kt`)
- `SharedPrefsHelper` (already Koin-bound `single`)
- `AppVersionProvider` (already Koin-bound `single` via `PlatformModule`)

Plus one top-level function call: `getDefaultFeatures()` (no DI;
called directly as a static function from `:shared`).

This is the highest fan-out into `:shared` of any rework `:data` impl
to date. The fan-out is justified by the legacy VM owning the same
collaboration — the rework impl ports that collaboration. Each leg
is documented in the impl's KDoc.

### Mapping legacy types to `:domain` types

The legacy `WhatsNewRemoteDataSource.fetchWhatsNewFeatures()` returns
`Result<WhatsNewResponse>`. The `WhatsNewResponse` contains a list of
`RemoteFeature`. The legacy VM maps each `RemoteFeature` through
`remoteDataSource.getLocalizedFeature(remoteFeature, languageCode)` to
get a `LocalizedFeature`, which then maps to `WhatsNewFeature` field-
by-field.

The rework `:data` impl does the SAME mapping in `getFeatures()` —
loop over remote features, localize each, map to `:domain`
`WhatsNewFeature`. The localization call defers to the legacy
`getLocalizedFeature(...)` method as-is (no rework `:domain`
localization model — that's a Phase 10 i18n concern).

### Why no `OnLoad` intent (init {}-driven load)

Same posture as `AboutViewModel` / `StatisticsViewModel` / Reader's
resume-position pre-load. The features are needed unconditionally the
moment the screen mounts; an `OnLoad` would just push the trigger to
`LaunchedEffect(Unit)` for no behavioural gain.

`OnRetry` IS modelled (as an `MviIntent` data object) — the user can
re-trigger a fetch if the initial load returned empty. The reducer
re-launches the same `getFeatures()` call.

### Why `OnMarkSeen` is an intent (not auto-triggered)

`markSeen()` is a side-effect that mutates global prefs state. Coupling
it to a screen-lifecycle signal (`DisposableEffect` cleanup,
`LaunchedEffect(Unit)`, etc.) would either:
- Run too early (user sees the screen but hasn't actually consumed it)
- Run too late (user backs out without the prefs write committing)

The explicit intent gives the `:ui` layer (or the parent that owns
the WhatsNew dialog/screen) control over when to mark-seen. The
foundation `:ui` does NOT call `submit(OnMarkSeen)` from any composable
— the screen is debug-only and the should-show gating is deferred, so
mark-seen has no functional impact today. A follow-on slice wires it
when the should-show gating lands.

## Commit roadmap

Six commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for
the `:ui` commit + the `:composeApp` wiring commit).

1. **Plan commit** — `PLAN_whatsnew.md` only (1 file).

2. **`:domain` + `:data` foundation** — 5 files at the cap:
   - `domain/.../model/whatsnew/MediaType.kt` (NEW — enum)
   - `domain/.../model/whatsnew/WhatsNewFeature.kt` (NEW — data class,
     ports legacy verbatim)
   - `domain/.../repository/WhatsNewRepository.kt` (NEW — interface
     with `suspend fun getFeatures(): List<WhatsNewFeature>` + `suspend
     fun markSeen()`)
   - `domain/.../usecase/whatsnew/GetWhatsNewFeaturesUseCase.kt` (NEW —
     thin pass-through)
   - `domain/.../usecase/whatsnew/MarkWhatsNewSeenUseCase.kt` (NEW —
     thin pass-through)

   (Note: the `:data` impl is its own commit because adding 6 files
   in one commit would exceed the cap.)

3. **`:data` strangler-fig impl** — 1 file, NEW:
   - `data/.../repository/WhatsNewRepositoryImpl.kt` — takes four
     constructor args (`remoteDataSource: LegacyWhatsNewRemoteDataSource`,
     `prefs: LegacySharedPrefsHelper`, `appVersionProvider:
     LegacyAppVersionProvider`, all aliased per the established
     pattern). `getFeatures()` body: call remote with `"en"`, on
     success map → `:domain` model, on failure fall back to
     `getDefaultFeatures()`. `markSeen()` body: prefs writes.

4. **`:presentation` MVI** — 4 files, all NEW:
   - `presentation/.../whatsnew/WhatsNewState.kt`
   - `presentation/.../whatsnew/WhatsNewIntent.kt` (OnRetry + OnMarkSeen)
   - `presentation/.../whatsnew/WhatsNewEffect.kt` (empty sealed)
   - `presentation/.../whatsnew/WhatsNewViewModel.kt`

5. **`:ui` composable** — 1 file, NEW:
   - `ui/.../whatsnew/WhatsNewScreen.kt` — Scaffold + LazyColumn of
     Cards rendering title + description + optional "NEW" chip. Uses
     `LocalSpacing` + MaterialTheme tokens. NO icons / images / video /
     pager / fullscreen.

6. **`:composeApp` Koin + nav** — 5 files at cap:
   - `composeApp/.../di/WhatsNewReworkModule.kt` (NEW)
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED)
   - `composeApp/.../navigation/routes/WhatsNewReworkScreenRoute.kt` (NEW)
   - `composeApp/.../navigation/Screen.kt` (MODIFIED — append `WhatsNewRework`)
   - `composeApp/.../App.kt` (MODIFIED — register composable block)

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §88
   - `SOLID_AUDIT.md` — append Phase 7.x.whatsnew entry

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/whatsnew/MediaType.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/whatsnew/WhatsNewFeature.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/WhatsNewRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/whatsnew/GetWhatsNewFeaturesUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/whatsnew/MarkWhatsNewSeenUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/WhatsNewRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/whatsnew/WhatsNewViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/whatsnew/WhatsNewScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/WhatsNewReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/WhatsNewReworkScreenRoute.kt`
- `PLAN_whatsnew.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `whatsNewReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — append `WhatsNewRework`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — register `composable<Screen.WhatsNewRework>` block + import.
- `ARCHITECTURE.md` — append §88.
- `SOLID_AUDIT.md` — append Phase 7.x.whatsnew entry.

### Untouched (verify by read, not modified)

- `shared/.../whatsnew/data/WhatsNewRemoteDataSource.kt` — legacy
  fetcher; the strangler-fig calls its `fetchWhatsNewFeatures()` +
  `getLocalizedFeature()` but does not modify it.
- `shared/.../whatsnew/data/getDefaultFeatures.kt` — legacy default
  fallback; `:data` impl calls it directly.
- `shared/.../whatsnew/viewmodel/WhatsNewViewModel.kt` — legacy VM;
  preserved for the legacy route.
- `composeApp/.../features/whatsnew/ui/WhatsNewScreen.kt` — legacy
  screen; preserved for the legacy route. Phase 9.x route-swap
  retires later.
- `composeApp/.../features/whatsnew/ui/components/*.kt` — legacy
  components; preserved.

## Reuse

- **Strangler-fig posture**: lifted from `AboutRepositoryImpl` /
  `ThemeRepositoryImpl` / `ReadingStatisticsRepositoryImpl`. Same
  `import ... as Legacy...` aliasing for readability.
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `presentation/.../mvi/`. Same superclass every rework VM extends.
- **Koin module shape**: mirrors `aboutReworkModule` — `single` for
  repo, `factory` for use cases, `viewModel` for VM.
- **Nav route shape**: mirrors `AboutReworkScreenRoute` — terminal
  screen, no nav callbacks. Effect-bridging not needed (empty effect
  sealed today).
- **Composable layout primitives**: reuse the rework `:ui`'s existing
  `LocalSpacing` design tokens, MaterialTheme color/typography, and
  the `Card` / `Scaffold` / `TopAppBar` / `LazyColumn` primitives
  already used across the prior rework screens.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid`
- `gradlew.bat :composeApp:compileKotlinIosArm64`
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64`
- `gradlew.bat :composeApp:compileKotlinDesktop` — required for
  commit 5 (`:ui`) + commit 6 (`:composeApp` wiring).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.WhatsNewRework`
  via a guarded debug entry, verify the features list renders title +
  description for each feature. With the current `getDefaultFeatures
  ()` returning empty + no live remote endpoint, the screen likely
  renders an empty state — that's expected.
- Verify the empty-state UX is acceptable (LazyColumn with no items
  + a "No new features" message; or just an empty column — TBD in
  `:ui` commit).

Edge cases to mentally model:

- **Empty list from both remote + default**: the foundation `:ui` should
  render a "No new features in this version" placeholder. If the
  legacy default-features fun stays returning empty, the foundation
  slice's UI will ALWAYS render empty until a follow-on slice wires the
  remote endpoint correctly. Acknowledged.
- **Remote failure**: `getFeatures()` catches via the `Result` API in
  the legacy data source + the `:data` impl's `.onFailure { ... }`
  branch. Falls through to `getDefaultFeatures()` (empty). No error
  surface in the foundation MVI; `WhatsNewEffect` is empty.
- **markSeen race with markSeen**: idempotent — both writes set the
  same prefs value. No race condition.

## Deferrals

- **HorizontalPager swipe UX** — legacy uses `HorizontalPager` +
  `PageIndicators` + `NavigationButtons`. Foundation `:ui` uses
  `LazyColumn` for a stacked vertical list. A follow-on slice
  `Phase 7.x.whatsnew.pager` ports the pager interactions.
- **Image rendering** — `WhatsNewFeature.imageResName` /
  `imageResNameList` carries compose-resources drawable names;
  `imageUrl` / `imageUrlList` carries remote URLs. Rendering requires
  either a `:ui/commonMain/composeResources` binding for the
  drawable names (cross-cutting binary change) or threading
  `painterResource` from the route adapter (couples `:ui` to
  `:composeApp`). Coil URL loading uses the same singleton ImageLoader
  Reader / Library use today. A follow-on slice
  `Phase 7.x.whatsnew.images` lifts both.
- **Video playback** — legacy `VideoComponents.kt` (171 LOC) uses
  ExoPlayer on Android, AVKit on iOS, JavaFX on Desktop. The rework
  `:platform` module has no MediaPlayer SPI today. A separate
  `Phase 5.x.mediaplayer` slice ports the SPI first, then a follow-on
  `Phase 7.x.whatsnew.video` slice consumes it.
- **FullscreenMediaViewer** — couples to image + video rendering; lifts
  in a follow-on `Phase 7.x.whatsnew.fullscreen` slice after the
  image + video sub-slices land.
- **Should-show gating** — legacy VM's `checkIfShouldShowWhatsNew()` /
  `shouldShowWhatsNew: StateFlow<Boolean>` triggers the dialog on
  version-change. Foundation rework screen is debug-only entry; the
  gating isn't relevant yet. A follow-on `Phase 7.x.whatsnew.gate`
  slice wires the auto-trigger when the route-swap (Phase 9.x) lands.
- **Language-aware localization** — legacy VM reads `DataStoreHelper.
  languageFlow.first()` to pass the user's selected language to the
  remote data source's `getLocalizedFeature(...)`. Foundation slice
  hardcodes `"en"`. Lifts in a follow-on `Phase 7.x.whatsnew.i18n`
  slice that may align with the larger Phase 10 i18n lift.
- **No i18n lift** — inline string literals ("What's new", "NEW", "No
  new features") mirror the legacy's deferred i18n. Phase 10's lift
  handles both surfaces.
- **No nav graph route-swap** — legacy `Screen.WhatsNewScreen` stays
  bound. Phase 9.x route-swap is its own slice.
- **No About row wire-up** — the deferred Whats-new row on the rework
  About screen (Phase 7.x.about §87.7) needs a follow-on slice that
  threads a `navController.navigate(Screen.WhatsNewRework)` callback
  into the About route adapter. Foundation slice doesn't touch About.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface, one
  use case, one impl, one MVI surface part, one composable, one Koin
  module, one nav adapter).
- **OCP**: Empty `WhatsNewEffect` sealed interface accepts future
  `ShowError` variant without changing the VM base class.
  `WhatsNewIntent` accepts future `OnDismiss` / `OnFeatureTap` etc.
  variants. Repository interface is closed; impl is substitutable
  via Koin binding.
- **DIP**: `:presentation` depends on `:domain`'s use cases; `:data`
  impl depends on legacy `:shared`'s facades because that's where the
  cells of truth live — same strangler-fig posture, higher fan-out.
- **Layer boundary**: changes touch `:domain` (5 new files), `:data`
  (1 new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (2 new + 3 modified incl. close-out). No cross-layer
  reach beyond the strangler-fig `:data` → `:shared` permitted
  boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. The
  `Result<>` mapping uses `.fold` or `.onSuccess.onFailure`. The
  `getFeatures()` body wraps the remote call in a `try/catch` only as
  a defensive net since the legacy already wraps in `Result.failure`.
- **MVI contract**: new slice — adds `WhatsNewState` (2 fields:
  `isLoading`, `features`), `WhatsNewIntent` (2 variants: `OnRetry`,
  `OnMarkSeen`), `WhatsNewEffect` (empty sealed). VM `init {}` launches
  load; `handle()` exhaustively switches on intent.
- **Strangler-fig**: FOUR `:data` → `:shared` reaches (3 Koin-injected
  facades + 1 top-level fun call). Highest fan-out of any rework
  `:data` impl. Documented in the impl's KDoc.
- **Load-bearing fixes preserved**: this slice touches NO load-bearing
  paths. No Coil URL loading (deferred). No load-bearing risk.
