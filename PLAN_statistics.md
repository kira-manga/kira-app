# Phase 7.x.statistics — Statistics screen rework (`:domain` → `:data` → `:presentation` → `:ui` + Koin/nav wiring)

## Context

The Reader stack is exhausted (Tasks #212-#237 all closed, ending with the
ktor3 per-byte progress port at HEAD `61d92ce`). Per the `/goal` Stop hook
Rule 3, the next non-blocked candidate is a fresh feature rework. Statistics
is chosen because:

- The reading-session timer port (Phase 6.4.x.statistics, Task #232) already
  landed `:domain` `ReadingSessionRepository` + use cases + strangler-fig
  `:data` impl over the legacy `:shared` `StatisticsRepository`. This new
  slice extends the **other half** of the same legacy class — the eight
  aggregate `Flow`s — so we reuse the existing dependency posture.
- The legacy Statistics screen is a pure stateless display of 8 aggregates
  (in-library count, read-duration string, completed/started entry counts,
  4 chapter counts). No mutations, no animations, no complex interactions —
  the cleanest possible new-feature rework target.
- Block-and-ask triggers (a)-(d) all NOT met: no contract library blocker,
  no observable behaviour change (legacy route preserved; rework route is
  debug-guarded), no compile risk, no SOLID violation forced by the design.

The legacy screen at `composeApp/.../features/statistics/ui/screens/Statistics
Screen.kt` takes 8 plain parameters from the legacy route's
`koinViewModel()`-resolved legacy VM (which unwraps 8 `StateFlow`s from
`StatisticsRepository`). The rework mirrors the existing
Library/Details/Reader pattern: clean `:domain` interface + 1 use case →
strangler-fig `:data` impl over the same legacy `StatisticsRepository` →
`:presentation` MVI surface → `:ui` composable → `:composeApp` Koin module
+ debug-guarded nav route.

## Approach

The legacy `StatisticsRepository` (at `shared/.../statistics/domain/
StatisticsRepository.kt:31-51`) already exposes the 8 aggregates as named
properties:

```kotlin
val inLibraryFlow: Flow<Int>           // total manga in library
val chaptersTotalFlow: Flow<Int>       // total chapters across all manga
val chaptersDownloadedFlow: Flow<Int>
val chaptersReadFlow: Flow<Int>
val chaptersBookmarkedFlow: Flow<Int>
val completedEntriesFlow: Flow<Int>    // manga with all chapters read
val startedEntriesFlow: Flow<Int>      // manga with ≥1 read chapter
val readDurationFlow: Flow<String>     // pre-formatted "Xh Ym"
```

The rework's `:data` impl combines these 8 flows into a single
`Flow<ReadingStatistics>` via `kotlinx.coroutines.flow.combine` (vararg
overload `combine(vararg flows: Flow<T>): Flow<Array<T>>` or two chained
calls). The `:domain` interface exposes one method:
`observe(): Flow<ReadingStatistics>`. The use case is a thin pass-through.

The `:presentation` VM subscribes to the use case in `init {}` and projects
each emission into a `StatisticsState` snapshot. No intents are needed for
the read-only display today; the MVI surface declares empty
`StatisticsIntent` and `StatisticsEffect` sealed interfaces (matching the
established pattern — future "OnClearReadTime" or "OnExport" can extend them
without ripping out the surface). `OnEnter` is NOT modelled because the
flow auto-subscribes in `init {}` and stays hot — same posture as
`LibraryViewModel` and `ReaderViewModel`'s observers.

The `:ui` composable is a near-port of the legacy `StatisticsScreen`'s
visual layout (StatsOverview big-number row + ItemsGroup sections for
Entries and Chapters with dividers) — using the rework `:ui` design tokens
(`LocalSpacing`, MaterialTheme) instead of the legacy's tokens. Stateless,
takes a `StatisticsState` and renders.

The `:composeApp` wiring follows the established 3-step pattern:
1. New `statisticsReworkModule` (mirroring `readerReworkModule`'s shape).
2. Aggregate into `allReworkModules()` (one-line append).
3. New `StatisticsReworkScreenRoute.kt` (mirroring `LibraryReworkScreen
   Route.kt`'s shape — no nav callbacks needed since Statistics has no
   outbound links).
4. Add a guarded debug nav entry alongside the existing
   `composable<Screen.Statistics>` in `App.kt` (the legacy route stays
   put — rework route is parallel, gated until Phase 9.x route-swap).

### Strangler-fig boundary

The `:data` impl reaches into `:shared`'s legacy `StatisticsRepository` —
SAME posture as `ReadingSessionRepositoryImpl` (Phase 6.4.x.statistics). The
existing `:data/build.gradle.kts` dependency on `:shared` is already in
place from that prior slice; no new build-config changes.

### Read-duration formatting

Legacy `readDurationFlow` already formats `"Xh Ym"` directly in the
`:shared` repository (with a TODO Phase 10 note about `stringResource`
localisation). The rework reuses the pre-formatted string verbatim —
deferring the i18n lift to Phase 10 (matches the legacy's own deferral).
The `ReadingStatistics` model carries the formatted string as
`readDuration: String` — not as `readMinutes: Int` — because the rework
view is a pure projection, and translating in the VM would re-introduce
the same i18n TODO the legacy already has.

### MVI surface decision: empty sealed interfaces

`StatisticsIntent` and `StatisticsEffect` are declared as empty sealed
interfaces. They CAN compile (sealed interfaces with no variants are valid
Kotlin) and they document the slice's extensibility hook. The alternative
("don't declare them") would force `StatisticsViewModel` to extend
`MviViewModel<StatisticsState, Nothing, Nothing>` — uglier signature, no
clearer semantics. The empty-sealed-interface pattern is OCP-friendly: a
future "OnClearReadTime" intent slots in without changing the VM's base
class.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for
slices touching common/desktop code, which this one does in `:ui`).

1. **Plan commit** — `PLAN_statistics.md` only (1 file, mirroring the
   plan-file pattern used by every prior multi-commit slice).

2. **`:domain` foundation** — 3 files, all new:
   - `domain/.../model/statistics/ReadingStatistics.kt` — data class with
     the 8 fields (`inLibrary: Int`, `readDuration: String`,
     `entriesStarted: Int`, `entriesCompleted: Int`, `chaptersTotal: Int`,
     `chaptersRead: Int`, `chaptersDownloaded: Int`,
     `chaptersBookmarked: Int`).
   - `domain/.../repository/ReadingStatisticsRepository.kt` — interface
     with `fun observe(): Flow<ReadingStatistics>`.
   - `domain/.../usecase/statistics/ObserveReadingStatisticsUseCase.kt` —
     `operator fun invoke(): Flow<ReadingStatistics>` delegating to the
     repository.

3. **`:data` strangler-fig impl** — 1 file, new:
   - `data/.../repository/ReadingStatisticsRepositoryImpl.kt` — class
     takes `legacy: StatisticsRepository` constructor arg; implements
     `observe()` by `combine`-ing the 8 legacy flows into the
     `ReadingStatistics` data class. KDoc mirrors `ReadingSessionRepository
     Impl`'s strangler-fig + DIP rationale.

4. **`:presentation` MVI** — 4 files, all new:
   - `presentation/.../statistics/StatisticsState.kt` — data class with
     the 8 fields + a `isLoading: Boolean` (initial-emission gap).
   - `presentation/.../statistics/StatisticsIntent.kt` — empty sealed
     interface implementing `MviIntent`.
   - `presentation/.../statistics/StatisticsEffect.kt` — empty sealed
     interface implementing `MviEffect`.
   - `presentation/.../statistics/StatisticsViewModel.kt` — extends
     `MviViewModel<StatisticsState, StatisticsIntent, StatisticsEffect>`;
     constructor takes one dep (`observeReadingStatistics:
     ObserveReadingStatisticsUseCase`); `init {}` launches the flow
     collector that updates state on each emission.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../statistics/StatisticsScreen.kt` — `@Composable` taking a
     `StatisticsViewModel` and rendering the StatsOverview card + two
     ItemsGroup sections (Entries, Chapters) using the rework `:ui`
     design tokens. Mirrors the legacy screen's visual structure
     (8 aggregates in the same panels) but uses `LocalSpacing`,
     MaterialTheme color/typography, and the rework's existing
     `Surface`/`Card` primitives. KDoc cross-references the legacy
     screen file for the parity intent.

6. **`:composeApp` Koin + nav** — up to 5 files at the cap:
   - `composeApp/.../di/StatisticsReworkModule.kt` (NEW) — module
     declaring `single<ReadingStatisticsRepository> {
     ReadingStatisticsRepositoryImpl(legacy = get()) }` +
     `factory { ObserveReadingStatisticsUseCase(get()) }` +
     `viewModel { StatisticsViewModel(get()) }`.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `statisticsReworkModule` to `allReworkModules()`.
   - `composeApp/.../navigation/routes/StatisticsReworkScreenRoute.kt`
     (NEW) — `@Composable fun` taking `NavController` +
     `NavBackStackEntry`; resolves `StatisticsViewModel` via
     `koinViewModel()`; calls `StatisticsScreen(viewModel)`. No nav
     callbacks (Statistics has no outbound links).
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add `object
     StatisticsRework : Screen("me.manga.kira.navigation.Screen.
     StatisticsRework")`.
   - `composeApp/.../App.kt` (MODIFIED) — add `composable<Screen.
     StatisticsRework> { ... StatisticsReworkScreenRoute(...) }`
     alongside the existing `composable<Screen.Statistics>` entry +
     the import.

   That's exactly 5 files, at the cap. If implementation discovers
   additional touch points (e.g., a nav-graph helper file that lists
   all destinations for tooling, or a debug-flag gate file), split
   into 6a (module + aggregate + Screen.kt + route file, 4 files) and
   6b (App.kt nav graph hookup + any other discovered file, 1-2
   files).

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §81 — Phase 7.x.statistics — Statistics
     screen rework` with subsections covering strategy, layer-by-layer
     surfaces, MVI shape rationale, strangler-fig boundary, files added,
     deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.statistics entry with per-file SOLID
     10-point checklist, end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation (legacy route preserved verbatim;
     rework route is parallel + debug-guarded), MVI contract (new slice,
     8-field state, no intents/effects today), strangler-fig integrity
     (one new `:data` impl reaches `:shared`, mirroring the existing
     `ReadingSessionRepositoryImpl` posture), next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/statistics/ReadingStatistics.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/ReadingStatisticsRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/statistics/ObserveReadingStatisticsUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ReadingStatisticsRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/statistics/StatisticsState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/statistics/StatisticsIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/statistics/StatisticsEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/statistics/StatisticsViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/statistics/StatisticsScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/StatisticsReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/StatisticsReworkScreenRoute.kt`
- `PLAN_statistics.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `statisticsReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `StatisticsRework` enum case.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.StatisticsRework>` block + import.
- `ARCHITECTURE.md` — append §81.
- `SOLID_AUDIT.md` — append Phase 7.x.statistics entry.

### Untouched (verify by read, not modified)

- `shared/.../features/statistics/domain/StatisticsRepository.kt` — legacy
  facade; the strangler-fig delegates to its 8 flows but does not modify it.
- `shared/.../data/local/dao/StatisticsDeo.kt` — legacy DAO; not touched
  (the `:data` impl goes through the legacy facade, not the DAO directly,
  same posture as `ReadingSessionRepositoryImpl`).
- `composeApp/.../features/statistics/ui/screens/StatisticsScreen.kt` —
  legacy screen; preserved for the legacy route. Phase 9.x route-swap
  retires it later.
- `composeApp/.../navigation/routes/StatisticsScreenRoute.kt` — legacy
  route; preserved. Phase 9.x route-swap retires it.

## Reuse

- **Strangler-fig posture**: lifted directly from `data/.../
  ReadingSessionRepositoryImpl.kt`'s class shape and KDoc structure.
  Same `:shared` dependency, same `single` Koin lifecycle, same "reach
  into legacy until Phase 9.x retirement" justification.
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `presentation/.../mvi/`. Same superclass every rework VM extends.
- **Koin module shape**: mirrors `readerReworkModule` — `single` for
  repo, `factory` for use case, `viewModel` for VM.
- **Nav route shape**: mirrors `LibraryReworkScreenRoute` — `koinViewModel
  ()` resolution + composable wrapper. No nav callbacks (Statistics is
  terminal).
- **Composable layout primitives**: reuse the rework `:ui`'s existing
  `LocalSpacing` design tokens, MaterialTheme color/typography, and the
  `Surface`/`Card` primitives already used by `LibraryScreen` and
  `DetailsScreen`. No new design tokens introduced.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate. Required for every commit because `:domain`/`:data`/
  `:presentation` changes propagate through to `:composeApp/commonMain`.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64. Required
  because commonMain changes link into the iOS framework.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64. Same rationale.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required for
  commit 5 (`:ui` composable) because the new screen file is in
  `:ui/commonMain` and links into the Desktop entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.StatisticsRework`
  via the guarded debug entry, verify the 8 aggregates render identically
  to the legacy `Screen.Statistics` screen for the same user data.
- Verify the read-duration string formats identically (e.g., "12h 34m")
  since both routes consume the same `readDurationFlow`.

Edge cases to mentally model during implementation:

- Empty library: all counts = 0, `readDuration = "0h 0m"`. The
  `combine` block must not gate on a single source emitting first —
  Room's `Flow<Int>` should emit the count immediately on subscription
  even if the underlying table is empty.
- Initial-emission gap: between subscription and first emission, the VM
  starts with a `StatisticsState(isLoading = true, ...)` default. Once
  the first `combine` emission arrives, the VM updates state with
  `isLoading = false` and the 8 fields populated.
- Concurrent updates: if multiple flows update simultaneously (e.g.,
  user finishes reading a chapter — `chaptersReadFlow` AND
  `completedEntriesFlow` AND `startedEntriesFlow` may all update from
  the same Room transaction), `combine` debounces internally and the VM
  sees a single coalesced emission. No double-render.

## Deferrals

- **No i18n lift** — `readDuration` stays as the legacy's pre-formatted
  string (with the existing `TODO Phase 10: stringResource(R.string.h_m)`
  in the legacy repository). Phase 10's i18n lift will handle both the
  legacy and rework consumers in one pass.
- **No per-manga / per-day granularity** — the slice ports the 8
  aggregates the legacy screen displays. Drill-down screens (e.g., a
  per-manga reading-time list) are not part of this slice and would be a
  separate `Phase 7.x.statistics.detail` follow-on if user-visible
  requirements emerge.
- **No "Clear read time" action** — the legacy screen doesn't have one;
  the rework doesn't add one. If a settings-screen rework later adds
  this, the empty `StatisticsIntent` sealed interface accepts an
  `OnClearReadTime` variant.
- **No chart / graph rendering** — the slice is pure number display, same
  as the legacy. Visualisation lifts to a future slice if needed.
- **No nav graph route-swap** — legacy `Screen.Statistics` stays bound to
  the legacy route. Phase 9.x route-swap is its own slice; this slice
  only adds the parallel `Screen.StatisticsRework` route.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface, one use
  case, one impl, one MVI surface part, one composable, one Koin
  module, one nav adapter).
- **OCP**: Empty sealed interfaces for `StatisticsIntent`/`Effect` keep
  the MVI surface closed under modification but open under extension.
  Repository interface is closed; impl is substitutable via Koin
  binding.
- **DIP**: `:presentation` depends on `:domain`'s interface, not
  `:data`'s impl. `:data`'s impl depends on legacy `:shared`'s
  `StatisticsRepository` because that's where the cell of truth lives —
  same strangler-fig posture as `ReadingSessionRepositoryImpl`,
  documented in its KDoc.
- **Layer boundary**: changes touch `:domain` (3 new files), `:data`
  (1 new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (1 new + 4 modified incl. close-out). No cross-layer
  reach beyond the strangler-fig `:data` → `:shared` permitted
  boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines.flow`. The `combine`-of-eight
  uses the vararg overload (which returns `Flow<Array<T>>` and gets
  unpacked into the data-class constructor) or two chained `combine`
  calls — both are safe non-`Any` constructs.
- **MVI contract**: new slice — adds `StatisticsState` + empty
  `StatisticsIntent`/`StatisticsEffect`. No reducer (state is
  flow-driven, not intent-driven). The base `MviViewModel`'s `submit`
  surface compiles even with `Intent = StatisticsIntent` (empty sealed
  interface — `submit(intent: StatisticsIntent)` accepts nothing but
  also rejects no caller because no one can construct an instance).
- **Strangler-fig**: ONE `:data` → `:shared` reach (the
  `StatisticsRepository` constructor injection in the impl). Same
  posture, same boundary as `ReadingSessionRepositoryImpl` — already
  established in Phase 6.4.x.statistics.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, the Reader's per-request listener, the Reader's decoder
  hints, OkHttp interceptor, or any of the prior load-bearing image-
  quality posture (Statistics has no images). No load-bearing risk.
