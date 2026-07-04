# Phase 7.x.sources — Sources screen rework

## Context

Phase 7.x.updates is complete at HEAD `efc13cd` (task #240 closed). Per the
`/goal` Stop hook Rule 3, the next non-blocked slice. Candidates surveyed
in the Phase 7.x.updates close-out's "Next" block + a follow-on Explore:

- **Home rework**: ~750-line surface, multi-VM coordination (4 VMs), rich
  surface (source tabs, pagination, save-toggle, search overlay). Too big
  for a single slice — would itself need plan-mode and likely sub-slices.
- **Downloads rework**: ~530-line surface, clean 3-tab list-with-CRUD shape
  — but **blocked**: `DownloadRepositoryImpl` lives in `androidMain` using
  `WorkManager`; lifting to commonMain requires a `:platform` SPI prep
  slice (genuinely substantial). Not a clean auto-continue target.
- **Sources rework** (chosen): smallest surface of the three (~330 lines
  legacy + 280-line legacy repo facade). The screen is heavily entangled
  with RepoSettingsViewModel + ComplaintViewModel + onboarding flow + the
  load-bearing `findRepoByHost` path used by the Coil interceptor — BUT
  the actual on-screen surface the user sees is narrow: a grouped list of
  sources with per-source on/off toggles + per-language bulk on/off
  toggles. A scoped strangler-fig over JUST that read+toggle surface is
  clean.

Block-and-ask triggers (a)-(d) all NOT met:
- (a) no contract library blocker — the legacy `SourcesRepository` is
  commonMain-resident; the rework `:data` impl can reach into it the same
  way History/Updates/Statistics do over their legacy facades.
- (b) no observable behaviour change — legacy `Screen.Sources` route is
  unchanged; rework route is parallel + debug-guarded; both consume the
  same `sources` Room table so toggles propagate between them.
- (c) no compile risk — the legacy `findRepoByHost`,
  `activeRepoFlow`, `repoTaps` paths used by the Coil interceptor /
  Home / Search / many other screens are UNTOUCHED. The rework `:data`
  impl exposes ONLY `observeSources()` + `setSourceEnabled()` +
  `setLanguageEnabled()`; the legacy facade keeps serving everything
  else verbatim.
- (d) no SOLID violation forced — the narrow scope is itself an ISP win
  (the rework interface declares only what the screen needs).

## Approach

The legacy `SourcesRepository` (at `shared/.../repo_settings/domain/
SourcesRepository.kt:41-280`) is the central source-routing facade — it
owns active-repo tracking, per-source `WORKING/STOPPED` state, the
load-bearing `findRepoByHost` used by the Coil image interceptor (memory
`project_yami_okhttp_fetcher`), and the source list itself. The Sources
**screen's** narrow needs against it are:

- Read: `allSources: Flow<List<SourcesEntity>>` — the per-source list with
  `name`, `language`, `isEnabled`, `priority`, etc.
- Toggle: `enableDisAbleSource(name: String, enabled: Boolean)` — single
  per-source mutator (the legacy method has a typo in the name; the
  rework's `setSourceEnabled` is the clean equivalent).
- Per-language toggle: NOT exposed on the legacy repo — it lives in
  `RepoSettingsViewModel` and iterates over the per-language source set
  calling `enableDisAbleSource` for each. The rework's
  `setLanguageEnabled` use case lives at the `:domain`/use-case layer and
  delegates to the rework repository's per-source toggle in a loop (no
  legacy facade call needed beyond the per-source one).

### Strangler-fig boundary

- The rework `:data` impl's constructor takes one dep: the legacy
  `:shared` `SourcesRepository`. Same posture as
  `HistoryRepositoryImpl` / `UpdatesRepositoryImpl` /
  `ReadingStatisticsRepositoryImpl` / `ReadingSessionRepositoryImpl`.
- `observeSources()` maps the legacy `allSources` flow through a mapper
  that converts `SourcesEntity` → rework `Source` domain ADT. The
  `siteState`, `baseUrl`, `baseVersion`, `imageBaseUrl`, `imageUrlVersion`
  fields are dropped — the screen doesn't render them.
- `setSourceEnabled(api: String, enabled: Boolean)` forwards verbatim to
  `legacy.enableDisAbleSource(api, enabled)`.
- The load-bearing `findRepoByHost` + `activeRepoFlow` + `repoTaps` paths
  used by the Coil interceptor / Home / Search are NOT touched. The
  legacy facade keeps serving everything else exactly as before. The
  rework `:data` impl's surface is intentionally narrow.

### Scope deferrals

The rework deliberately drops, for this slice:

- **Complaint dialog**: the legacy "request adding source" feedback
  dialog (with `ComplaintViewModel`) — defer to a future
  `Phase 7.x.sources.complaint` if user-visible requirements emerge. The
  legacy `Screen.Sources` route still has it for users who hit that path.
- **Onboarding-specific UI**: the `AnimatedBackground`, the
  `"Finish"` button (which navigates onward in the onboarding flow), the
  `"Languages coming soon"` info card, the gradient overlay — all
  onboarding-flavour. The rework slice is a STANDALONE source-list-with-
  toggles surface; the onboarding flow stays on the legacy route until
  Phase 9.x route-swap. Bottom-bar visible for the rework (unlike
  Reader); same posture as History/Updates/Statistics reworks.
- **Language auto-detection (`setLanguageEnabledDefault`)**: the
  one-shot "if the user picked Arabic in onboarding step 1, default-enable
  Arabic sources on first visit" seed. The rework route is reached AFTER
  onboarding (it's debug-only until Phase 9.x), so the user's enabled
  sources already exist in the Room `sources` table — no seed needed.
  The legacy onboarding flow keeps doing this seed for fresh installs.
- **Add-source dialog**: the legacy `request_adding_source` row — same
  as complaint dialog deferral.

### MVI shape decision

The slice extends the History/Updates canonical list-screen-with-CRUD
shape with TWO toggle intents instead of mark-read/delete:

- `OnToggleSource(source: Source, enabled: Boolean)` — flip one source.
- `OnToggleLanguage(language: String, enabled: Boolean)` — flip all
  sources in one language (the VM iterates per-source via the use case).

No navigational intents — Sources is terminal (the legacy `onFinish`
callback is onboarding-flow-only and is deferred). No effects emitted by
the rework. The MVI surface is `UpdatesIntent` minus the navigational
variants plus a per-language toggle.

`UpdatesEffect`-style sealed interface is still declared (empty sealed
interface — valid Kotlin, OCP-friendly for a future
`Phase 7.x.sources.complaint` slice that adds `ShowError` / similar).

### Read-state visual differentiation

The screen displays enabled vs disabled sources. No special read-state
weight / alpha distinction (unlike History/Updates' read-vs-unread) —
the toggle switch itself shows the state. Material 3 `Switch` primitive
is the read-state indicator.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for
slices touching common/`:ui`/`:composeApp` code, which this one does in
`:ui` + `:composeApp`).

1. **Plan commit** — `PLAN_sources.md` only (1 file).

2. **`:domain` foundation** — 5 files at cap, all new:
   - `domain/.../model/sources/Source.kt` — data class (`api: String`,
     `language: String`, `priority: Int`, `isEnabled: Boolean`). 4 fields.
   - `domain/.../repository/SourcesRepository.kt` — interface with three
     methods: `observeSources()`, `setSourceEnabled(api, enabled)`,
     `setLanguageEnabled(language, enabled)`.
   - `domain/.../usecase/sources/ObserveSourcesUseCase.kt`.
   - `domain/.../usecase/sources/SetSourceEnabledUseCase.kt`.
   - `domain/.../usecase/sources/SetLanguageEnabledUseCase.kt`.

3. **`:data` strangler-fig impl** — 2 files, both new:
   - `data/.../mapper/SourcesMappers.kt` — `SourcesEntity.toDomain()`
     extension (drops `siteState` / `baseUrl` / etc — only api +
     language + priority + isEnabled survive).
   - `data/.../repository/SourcesRepositoryImpl.kt` — class takes
     `legacy: SourcesRepository` constructor arg; `observeSources()`
     maps `legacy.allSources`; `setSourceEnabled` forwards verbatim to
     `legacy.enableDisAbleSource`; `setLanguageEnabled` is implemented
     by filtering the latest `allSources.first()` to the target language
     and forwarding per-source.

4. **`:presentation` MVI surface** — 4 files, all new:
   - `presentation/.../sources/SourcesState.kt` — data class with
     `isLoading: Boolean = true`, `sources: List<Source> = emptyList()`,
     + derived `groupedByLanguage: Map<String, List<Source>>` getter +
     derived `enabledCount: Int` getter for future use.
   - `presentation/.../sources/SourcesIntent.kt` — sealed interface, two
     variants: `OnToggleSource(source: Source, enabled: Boolean)` +
     `OnToggleLanguage(language: String, enabled: Boolean)`.
   - `presentation/.../sources/SourcesEffect.kt` — empty sealed
     interface (OCP-friendly placeholder for future).
   - `presentation/.../sources/SourcesViewModel.kt` — extends
     `MviViewModel<SourcesState, SourcesIntent, SourcesEffect>`;
     constructor takes the three use cases; `init {}` collector
     subscribes to `observeSources()`; `handle(intent)` dispatches
     mutating intents fire-and-forget via `viewModelScope.launch`.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../sources/SourcesScreen.kt` — `@Composable` taking the VM
     + rendering a top bar ("Sources") + LazyColumn of per-language
     groups; each group has a header row with the language name
     endonym + a language-level Switch (driven by "any source in this
     language enabled" predicate), and per-source rows below it with
     api name + per-source Switch. No icons, no animated background,
     no complaint dialog. Stateless inner `SourcesScreenContent`.

6. **`:composeApp` Koin + nav** — 5 files at the cap:
   - `composeApp/.../di/SourcesReworkModule.kt` (NEW) — module
     declaring repo + 3 use cases + viewModel bindings.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `sourcesReworkModule`.
   - `composeApp/.../navigation/routes/SourcesReworkScreenRoute.kt`
     (NEW) — `@Composable fun` taking `NavController` +
     `NavBackStackEntry`; resolves `SourcesViewModel` via
     `koinViewModel()`; calls `SourcesScreen(viewModel)`. No nav
     callbacks (Sources is terminal).
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add
     `object SourcesRework : Screen("...SourcesRework")`.
   - `composeApp/.../App.kt` (MODIFIED) — add
     `composable<Screen.SourcesRework>` block + import.

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §84 — Phase 7.x.sources — Sources screen
     rework` with subsections covering strategy, layer-by-layer surfaces,
     scoped read+toggle posture (load-bearing legacy paths untouched),
     MVI shape (toggle-only intents, no nav, no effects today), files
     added, deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.sources entry with per-file SOLID
     10-point checklist, end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation, MVI contract, strangler-fig
     integrity (one `:data` → `:shared` reach), next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/sources/Source.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/SourcesRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/sources/ObserveSourcesUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/sources/SetSourceEnabledUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/sources/SetLanguageEnabledUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/mapper/SourcesMappers.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/SourcesRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/sources/SourcesViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/sources/SourcesScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/SourcesReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/SourcesReworkScreenRoute.kt`
- `PLAN_sources.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt`
- `ARCHITECTURE.md` — append §84.
- `SOLID_AUDIT.md` — append Phase 7.x.sources entry.

### Untouched (verify by read, not modified)

- `shared/.../repo_settings/domain/SourcesRepository.kt` — legacy facade;
  delegated to by the rework `:data` impl. The load-bearing
  `findRepoByHost` / `activeRepoFlow` / `repoTaps` paths are untouched.
- `shared/.../data/local/dao/SourcesDao.kt` — legacy DAO; not touched.
- `composeApp/.../features/onboarding/sources/SourcesScreen.kt` —
  legacy onboarding screen; preserved.
- `composeApp/.../navigation/routes/SourcesScreenRoute.kt` — legacy
  onboarding route; preserved.

## Reuse

- **Strangler-fig posture**: lifted from
  `UpdatesRepositoryImpl` / `HistoryRepositoryImpl`. Same `:shared`
  dependency, same `single` Koin lifecycle, same "reach into legacy
  until Phase 9.x retirement" justification.
- **MVI base class**: `MviViewModel<S, I, E>` from `:presentation/mvi/`.
- **Koin module shape**: mirrors `updatesReworkModule` — `single` for
  repo, `factory` for each use case, `viewModel` for VM.
- **Nav route shape**: mirrors `StatisticsReworkScreenRoute` — terminal
  screen with no nav callbacks; `koinViewModel()` resolution.
- **Composable layout primitives**: reuse the rework `:ui`'s
  `LocalSpacing`, MaterialTheme color/typography, and the `Switch` /
  `Card` / `LazyColumn` / `Text` primitives. No new design tokens.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid`
- `gradlew.bat :composeApp:compileKotlinIosArm64`
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64`
- `gradlew.bat :composeApp:compileKotlinDesktop` (for commits touching
  `:ui` / `:composeApp` commonMain, which is steps 5 + 6).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Toggle a source on the rework `Screen.SourcesRework` route, navigate to
  the legacy `Screen.Sources` route, verify the toggle reflects.
- Toggle a language-group on the rework route, verify ALL sources in
  that language flip together; verify the same on the legacy route.
- Navigate to Home / Search after toggling on the rework — verify the
  `activeRepo` / `allRepos` selection reflects the new enabled set
  (proves the load-bearing legacy paths weren't disturbed).

Edge cases to mentally model during implementation:

- Empty source set: `observeSources()` emits `emptyList()`. The screen
  renders an empty-state placeholder + the language groups are absent.
- All sources disabled: `groupedByLanguage` populates with all groups
  having `isEnabled = false` rows; per-language Switch shows false.
- Mixed-state language: some sources in the language are enabled, some
  aren't. The per-language Switch's "checked" state is driven by an
  `any { it.isEnabled }` predicate (matches the legacy
  `LanguageToggle` behavior).
- Concurrent toggle race: if the user spams the per-language Switch, the
  `setLanguageEnabled` use case launches a coroutine per click; each
  iterates over the per-source list and forwards. Room serializes the
  writes; the upstream flow re-emits per-write so the screen reflects
  the final state after all toggles settle.

## Deferrals

- **No complaint dialog**: deferred to a future
  `Phase 7.x.sources.complaint` slice (or absorbed into a wider
  feedback-system slice).
- **No add-source dialog**: same as above.
- **No onboarding-flow placement**: the rework slice is a
  standalone-screen rework; the legacy `Screen.Sources` keeps serving
  the onboarding step verbatim. Phase 9.x route-swap can later wire the
  rework into the onboarding flow if desired.
- **No animated background**: the rework slice is minimal `:ui`-stack
  posture (no animated visuals — matches the rework's "lean,
  composable-token-driven" style).
- **No `language-coming-soon` info card**: legacy-only flavor; the
  rework defers any informational rows to a future content-design slice.
- **No `setLanguageEnabledDefault` seed**: the rework route is not the
  primary onboarding path; the legacy route keeps doing the seed for
  fresh installs.
- **No nav graph route-swap**: legacy `Screen.Sources` stays bound to
  the legacy onboarding route. Phase 9.x is its own slice.
- **No `siteState` (WORKING/STOPPED) display**: the legacy screen
  doesn't show this either — it's an internal flag used elsewhere; the
  rework model drops it. A future `Phase 7.x.sources.health` slice can
  add a status indicator if needed.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule. The rework repository interface
  is narrow (3 methods); the legacy facade keeps its 20+ method
  surface — the slice doesn't extend the legacy or duplicate any of
  its methods.
- **OCP**: Empty `SourcesEffect` sealed interface keeps the MVI
  surface OCP-friendly. New variants slot in without VM base-class
  churn.
- **DIP**: `:presentation` depends on `:domain`'s interface, not
  `:data`'s impl. `:data`'s impl depends on legacy `:shared`'s
  `SourcesRepository` (the same strangler-fig boundary as
  History/Updates/Statistics).
- **Layer boundary**: `:domain` (5 new), `:data` (2 new), `:presentation`
  (4 new), `:ui` (1 new), `:composeApp` (2 new + 3 modified). No
  cross-layer reach beyond the strangler-fig `:data` → `:shared`
  permitted boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines.flow`. The
  `setLanguageEnabled` impl needs `.first()` on the
  `observeSources()` flow to enumerate the current per-language source
  set — that's a one-shot suspend snapshot, no banned constructs.
- **MVI contract**: new slice — adds `SourcesState` (2 fields incl.
  `isLoading`, + 2 derived getters) + `SourcesIntent` (2 toggle
  variants) + empty `SourcesEffect`. No reducer mutates `sources`
  directly — upstream flow re-emission drives that. Same fire-and-forget
  posture as History/Updates' mutating intents.
- **Strangler-fig**: ONE `:data` → `:shared` reach (constructor
  injection of legacy `SourcesRepository`). Same boundary as
  `HistoryRepositoryImpl` / `UpdatesRepositoryImpl` /
  `ReadingStatisticsRepositoryImpl`.
- **Load-bearing fixes preserved**: the legacy `findRepoByHost` /
  `activeRepoFlow` / `repoTaps` paths used by the Coil image
  interceptor + Home + Search are UNTOUCHED. The rework `:data`
  impl exposes a deliberately narrow surface (3 methods); the legacy
  facade keeps serving everything else. Image-quality posture (memory
  entries `project_yami_okhttp_fetcher` / `project_yami_avif_decoder` /
  `project_yami_image_quality_buildrequest` /
  `project_yami_desktop_skia_size_cap`) inherits unchanged.
