# Phase 7.x.history — History screen rework (`:domain` → `:data` → `:presentation` → `:ui` + Koin/nav wiring)

## Context

The Statistics slice (Phase 7.x.statistics, Task #238) closed cleanly at
HEAD `5eae2c8`. Per the `/goal` Stop hook Rule 3, the next non-blocked
candidate is a fresh feature rework. History is chosen because:

- Same legacy posture as Statistics: legacy `HistoryRepository` already
  ported to Koin in `:shared` (no Hilt residue), exposes 10 methods over
  a `HistoryDao`; the History screen renders a date-grouped lazy list
  with 4 callbacks (manga click, chapter click, delete entry, delete
  all).
- Smaller surface than Library/Details/Reader (which already shipped),
  larger than Statistics (which had no intents). Adds the missing
  "small slice with mutating intents + navigation effects" coverage to
  the rework pattern catalogue.
- Block-and-ask triggers (a)-(d) all NOT met: no contract library
  blocker, no observable behaviour change (legacy route preserved;
  rework route is parallel), no compile risk, no SOLID violation forced
  by the design.

The legacy screen at `composeApp/.../features/history/ui/screens/
HistoryScreen.kt` takes `HistoryUiState` + 4 callbacks. The legacy VM
at `shared/.../features/history/ui/viewmodel/HistoryViewModel.kt` owns
the `getAllHistory()` flow + delete actions. The legacy domain
`HistoryRepository` at `shared/.../features/history/domain/
HistoryRepository.kt` is a thin wrapper over `HistoryDao` (10 methods).

The rework mirrors the Statistics pattern: clean `:domain` interface +
use cases → strangler-fig `:data` impl over the same legacy
`HistoryRepository` → `:presentation` MVI surface (NOW WITH intents
and effects — unlike Statistics) → `:ui` composable → `:composeApp`
Koin module + parallel nav route.

## Approach

### Domain model — `HistoryEntry`

A pure data class mirroring the 14 fields of the legacy `HistoryItemD`
Room entity, but **without** Room annotations. The fields are the
union of what the screen renders (title, chapter, cover URL, date)
and what the navigation callbacks need (id, api, language, mangaId,
mangaUrl, chapterUrl, isDownloaded, localImagePaths, lastReadPage,
totalPages — the full payload of `Screen.ChapterImagesFragment`).

Carrying the navigation payload on the domain model is intentional:
the alternative ("strip nav-only fields, look them up at click time")
would require an extra DAO round-trip per click and introduce a
race between the click and the lookup (the list could have been
re-emitted in between with a different id). The 14-field domain
model is the cleanest single source of truth.

### Repository contract

```kotlin
interface HistoryRepository {
    fun observeHistory(): Flow<List<HistoryEntry>>
    suspend fun deleteEntry(entry: HistoryEntry)
    suspend fun deleteAll()
}
```

Three operations matching the legacy screen's three repository touches.
`deleteEntry(entry)` takes the full entity (not just an id) because the
legacy `HistoryDao.deleteHistory(historyItem: HistoryItemD)` is
entity-based; the strangler-fig impl maps `HistoryEntry → HistoryItemD`
via a mapper. Inserts / updates / per-manga queries stay on the legacy
repository — they are owned by the Reader and per-manga-detail flows
which haven't been reworked yet.

### Use cases

Three thin pass-through use cases (one per repository method) — same
posture as the Library and Reader slices:

- `ObserveHistoryUseCase(repo)` — `operator fun invoke(): Flow<List<HistoryEntry>>`
- `DeleteHistoryEntryUseCase(repo)` — `suspend operator fun invoke(entry: HistoryEntry)`
- `DeleteAllHistoryUseCase(repo)` — `suspend operator fun invoke()`

### MVI surface

Mirrors `LibraryViewModel`'s shape (which also has mutating intents +
nav effects):

**`HistoryState`** — `items: List<HistoryEntry>`, `isLoading: Boolean`.
No `error` field today (deletes are fire-and-forget; observe upstream
is Room which doesn't fail at observe-site; matches Statistics's
no-`error` posture).

**`HistoryIntent`** — sealed interface with 4 variants:
- `OnDeleteEntry(entry: HistoryEntry)` — fires delete via use case.
- `OnDeleteAll` — fires clear-all via use case.
- `OnMangaClick(entry: HistoryEntry)` — emits `NavigateToDetails`.
- `OnChapterClick(entry: HistoryEntry)` — emits `NavigateToReader`.

**`HistoryEffect`** — sealed interface with 2 variants:
- `NavigateToDetails(api: String, mangaUrl: String)` — the legacy
  `Screen.MangaDetails` payload.
- `NavigateToReader(entry: HistoryEntry)` — carries the entry so the
  route adapter can construct the full `Screen.ChapterImagesFragment`
  payload without an extra lookup (mirrors the legacy route adapter's
  posture verbatim, see `HistoryScreenRoute.kt:44-60`).

**Why the navigation flows through effects** (not direct callbacks
like Library does for cover-tap):
- Library's `LibraryEffect.NavigateToDetails(manga)` IS the established
  effect-based pattern; the rework Library screen uses an
  `onNavigateToDetails: (Manga) -> Unit` adapter callback to surface
  the effect. History follows the same path for symmetry.
- The alternative (passing two raw `(HistoryEntry) -> Unit` callbacks
  through the composable) would couple `:ui` to "what the click does"
  rather than "click happened" — a leaky MVI boundary the established
  pattern deliberately avoids.

### `:ui` composable — `HistoryScreen.kt`

A near-port of the legacy `HistoryScreen.kt` visual structure:
- **Scaffold + TopAppBar** with a "Delete all" action.
- **LazyColumn** of date-grouped rows. Group headers display
  "Today" / "Yesterday" / "N days ago" / "Mon dd, yyyy" via the same
  date math the legacy uses (`kotlinx.datetime.daysUntil` +
  manual month-abbrev mapping).
- **Row composable** per entry: cover (Coil `AsyncImage` via singleton
  `ImageLoader`) + title + chapter + relative date + delete `IconButton`.

**Deviations from the legacy `HistoryItem.kt`**:
- **No `rememberSourceImageRequest`**: that helper is a `:composeApp`-
  level utility wired to `SourcesRepository`. `:ui` deliberately stays
  layer-agnostic. The singleton `ImageLoader` already has
  `CoilSourceHeaderInterceptor` registered (set up in `App.kt`), so
  per-source headers are attached transparently for the rework Library
  cover (same posture, see `LibraryCardCover` KDoc) and will work
  identically here.
- **No `Icons.Default.DeleteForever` / `Icons.Outlined.Delete`**: same
  `:ui`-no-icons posture as Statistics. The delete actions are rendered
  as `TextButton`s with literal "Clear all" / "Delete" labels. The ~6 MB
  `compose.materialIconsExtended` dep stays out.
- **No `stringResource` lookups**: literal English strings inline.
  Phase 10's i18n lift will swap them at the same time it handles the
  legacy `Res.string.*` lookups in `:composeApp`.
- **Date-format helper inline** as `internal` functions in the same file
  — small enough that a dedicated `HistoryDateFormat.kt` file would be
  premature abstraction.

### Strangler-fig boundary

The `:data` impl reaches into `:shared`'s legacy `HistoryRepository`
— SAME posture as `ReadingSessionRepositoryImpl` (Phase 6.4.x.
statistics) and `ReadingStatisticsRepositoryImpl` (Phase 7.x.statistics).
The existing `:data/build.gradle.kts` dependency on `:shared` is
already in place; no new build-config changes.

The mapper (`data/.../mapper/HistoryMappers.kt`) lives in `:data` —
mappers are infrastructure, they translate between domain models and
persistence shapes. Same posture as `LibraryMappers.kt` and
`MangaDetailsMappers.kt`.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after
every source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop
for slices touching common/desktop code, which this one does in `:ui`).

1. **Plan commit** — `PLAN_history.md` only (1 file).

2. **`:domain` foundation** — 5 files at the cap, all new:
   - `domain/.../model/history/HistoryEntry.kt` — 14-field data class.
   - `domain/.../repository/HistoryRepository.kt` — interface with
     3 methods.
   - `domain/.../usecase/history/ObserveHistoryUseCase.kt`.
   - `domain/.../usecase/history/DeleteHistoryEntryUseCase.kt`.
   - `domain/.../usecase/history/DeleteAllHistoryUseCase.kt`.

3. **`:data` strangler-fig impl** — 2 files, both new:
   - `data/.../mapper/HistoryMappers.kt` — `HistoryItemD.toDomain()` +
     `HistoryEntry.toEntity()`.
   - `data/.../repository/HistoryRepositoryImpl.kt` — constructor takes
     legacy `HistoryRepository`; implements 3 domain methods. KDoc
     mirrors `ReadingStatisticsRepositoryImpl`'s strangler-fig +
     DIP rationale.

4. **`:presentation` MVI** — 4 files, all new:
   - `presentation/.../history/HistoryState.kt` — 2 fields.
   - `presentation/.../history/HistoryIntent.kt` — 4 variants.
   - `presentation/.../history/HistoryEffect.kt` — 2 variants.
   - `presentation/.../history/HistoryViewModel.kt` — extends
     `MviViewModel<S, I, E>`; constructor takes 3 use cases; `init {}`
     collector subscribes to `observeHistory()`; `handle()` switches on
     the 4 intents.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../history/HistoryScreen.kt` — Scaffold + LazyColumn with
     date-grouped rows, Coil `AsyncImage` cover, delete actions wired to
     intents. Mirrors the legacy screen's visual structure but uses
     `LocalSpacing` + Material 3 primitives + literal labels.

6. **`:composeApp` Koin + nav** — 5 files at the cap:
   - `composeApp/.../di/HistoryReworkModule.kt` (NEW) — module
     declaring `single<HistoryRepository> {
     HistoryRepositoryImpl(legacy = get()) }` + 3 `factory`
     use cases + `viewModel { HistoryViewModel(get(), get(), get()) }`.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `historyReworkModule` to `allReworkModules()`.
   - `composeApp/.../navigation/routes/HistoryReworkScreenRoute.kt`
     (NEW) — composable taking `NavController` + `NavBackStackEntry`;
     resolves `HistoryViewModel` via `koinViewModel()`; collects
     effects via `LaunchedEffect` and forwards to the screen with
     two `onNavigate*` callbacks that translate to legacy
     `Screen.MangaDetails` / `Screen.ChapterImagesFragment` routes.
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add `object
     HistoryRework : Screen("me.manga.kira.navigation.Screen.
     HistoryRework")` with inline KDoc.
   - `composeApp/.../App.kt` (MODIFIED) — add `composable<Screen.
     HistoryRework> { ... HistoryReworkScreenRoute(...) }` alongside
     existing `composable<Screen.History>` entry + the import.

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §82 — Phase 7.x.history — History
     screen rework`.
   - `SOLID_AUDIT.md` — Phase 7.x.history entry with per-file SOLID
     10-point checklist, end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation, MVI contract (new slice, full
     intent + effect surface), strangler-fig integrity, load-bearing
     fixes preserved, next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/history/HistoryEntry.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/HistoryRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/history/ObserveHistoryUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/history/DeleteHistoryEntryUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/history/DeleteAllHistoryUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/mapper/HistoryMappers.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/HistoryRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/history/HistoryState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/history/HistoryIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/history/HistoryEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/history/HistoryViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/history/HistoryScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/HistoryReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/HistoryReworkScreenRoute.kt`
- `PLAN_history.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `historyReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `HistoryRework` route.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.HistoryRework>` block + import.
- `ARCHITECTURE.md` — append §82.
- `SOLID_AUDIT.md` — append Phase 7.x.history entry.

### Untouched (verify by read, not modified)

- `shared/.../features/history/domain/HistoryRepository.kt` — legacy
  facade; the strangler-fig delegates to its `getAllHistory()`,
  `deleteHistory()`, `deleteAllHistory()` methods but does not modify
  it.
- `shared/.../data/local/dao/HistoryDao.kt` — legacy DAO; not touched
  (the `:data` impl goes through the legacy facade, not the DAO
  directly).
- `shared/.../data/local/entity/HistoryItemD.kt` — Room entity stays
  put; the rework `HistoryEntry` is a parallel domain model, not a
  replacement.
- `composeApp/.../features/history/ui/screens/HistoryScreen.kt` +
  `HistoryItem.kt` — legacy screen + row; preserved for the legacy
  route. Phase 9.x route-swap retires them later.
- `composeApp/.../navigation/routes/HistoryScreenRoute.kt` — legacy
  route; preserved. Phase 9.x route-swap retires it.

## Reuse

- **Strangler-fig posture**: lifted directly from
  `ReadingStatisticsRepositoryImpl` (Phase 7.x.statistics) — same
  `:shared` dependency, same `single` Koin lifecycle, same KDoc shape.
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `presentation/.../mvi/`. Same superclass every rework VM extends.
- **MVI shape**: intents-and-effects shape lifted from
  `LibraryViewModel` (which also has mutating intents + nav effects).
  No new MVI conventions.
- **Koin module shape**: mirrors `statisticsReworkModule` — `single`
  for repo, `factory` for use cases, `viewModel` for VM (only diff:
  three use cases instead of one).
- **Nav route shape**: hybrid of `LibraryReworkScreenRoute` (uses
  `LaunchedEffect` on `viewModel.effects` to forward navigation
  effects) and `StatisticsReworkScreenRoute` (passes `viewModel`
  directly to the screen).
- **Composable layout primitives**: reuse `LocalSpacing` design
  tokens, Material 3 `Card` / `Scaffold` / `LazyColumn` / `TopAppBar`
  / `TextButton` already used by every rework screen. No new design
  tokens introduced.
- **Cover image**: `AsyncImage` via the singleton `ImageLoader`
  (same posture as Library / Details cover). The
  `CoilSourceHeaderInterceptor` attached to the singleton handles
  per-source auth headers transparently.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate. Required for every commit because `:domain`/`:data`/
  `:presentation` changes propagate through to `:composeApp/commonMain`.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
  Required because commonMain changes link into the iOS framework.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64. Same rationale.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required
  for commit 5 (`:ui` composable) because the new screen file is in
  `:ui/commonMain` and links into the Desktop entry point.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.HistoryRework`
  via a developer trigger, verify:
  - The list renders identically to the legacy `Screen.History`
    screen for the same user data.
  - Date group headers ("Today", "Yesterday", etc.) match the legacy.
  - Tapping the cover navigates to `Screen.MangaDetails` (legacy).
  - Tapping the row body navigates to `Screen.ChapterImagesFragment`
    (legacy) with the full payload populated correctly.
  - Tapping the per-row delete removes the entry; the list re-emits.
  - Tapping "Clear all" empties the list; the list re-emits.

Edge cases to mentally model during implementation:

- Empty history: `state.items.isEmpty()` → render an empty-state
  message. Mirrors the legacy posture (which renders an empty
  LazyColumn, same outcome — but the explicit message is a small UX
  upgrade and matches the Statistics rework's loading branch posture).
- Incognito mode interaction: the legacy VM gates inserts behind
  `incognitoFlow.first()`. The rework slice only OBSERVES + DELETES,
  not INSERTS — incognito only affects insert flow which stays on
  the legacy VM. No behaviour change.
- Concurrent delete during list emission: if the user deletes entry
  X while a Room re-emission is in flight, the next emission will
  not include X. The MVI reducer projects each emission verbatim;
  no double-render risk.
- Cover load failure: `AsyncImage` silently renders nothing → the
  placeholder tinted background remains. Matches the rework Library
  cover posture.

## Deferrals

- **No i18n lift** — string labels stay as English literals (same
  posture as the Statistics rework). Phase 10's i18n lift will swap
  both legacy and rework consumers.
- **No per-manga drill-down** — the slice ports only what the legacy
  History screen displays (flat date-grouped list). Per-manga
  reading-time aggregates exist on the Statistics screen; per-manga
  history filters live on the manga detail screen (already reworked).
- **No incognito toggle on the screen** — incognito is a settings-screen
  concern; the rework Statistics / History screens consume the effect
  (no inserts during incognito) but don't surface a toggle.
- **No swipe-to-delete gesture** — the legacy uses a tap-on-icon button;
  the rework follows the same UX. Swipe-to-delete would be a separate
  micro-slice if user-visible requirements emerge.
- **No "share history" or "export" action** — out of scope.
- **No nav graph route-swap** — legacy `Screen.History` stays bound to
  the legacy route. Phase 9.x route-swap is its own slice; this slice
  only adds the parallel `Screen.HistoryRework` route.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface, one use
  case, one impl, one mapper file, one MVI surface part, one
  composable, one Koin module, one nav adapter).
- **OCP**: `HistoryIntent` and `HistoryEffect` are sealed interfaces
  closed under modification, open under extension. Future variants
  (e.g., `OnExport`, `NavigateToSettings`) slot in without rewiring
  consumers.
- **DIP**: `:presentation` depends on `:domain`'s `HistoryRepository`
  interface, not `:data`'s impl. `:data`'s impl depends on legacy
  `:shared`'s `HistoryRepository` (strangler-fig).
- **Layer boundary**: changes touch `:domain` (5 new), `:data`
  (2 new), `:presentation` (4 new), `:ui` (1 new), `:composeApp`
  (2 new + 3 modified incl. close-out). No cross-layer reach beyond
  the strangler-fig `:data` → `:shared` permitted boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines.flow`. Deletes are
  `suspend` functions invoked from `viewModelScope.launch`.
- **MVI contract**: new slice — adds `HistoryState` (2 fields) +
  `HistoryIntent` (4 variants) + `HistoryEffect` (2 variants). The
  `handle()` reducer is a 4-arm `when` over the sealed `HistoryIntent`.
- **Strangler-fig**: ONE `:data` → `:shared` reach (the
  `HistoryRepository` constructor injection in the impl). Same
  posture, same boundary as `ReadingStatisticsRepositoryImpl`.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader singleton, OkHttp interceptor, AVIF decoder
  registration, Reader per-byte progress, or any prior load-bearing
  image-quality posture. Cover thumbnails ride the established
  singleton with no per-request overrides.
