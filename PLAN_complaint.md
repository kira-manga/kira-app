# Phase 7.x.complaint.foundation — user-side Complaint list screen rework

## Context

Phase 7.x.language.request (Task #250) closed at commit `e5ea0c6` introducing
`FeedbackRepository` (`:domain`) + `FeedbackRepositoryImpl` (`:data`) that
strangler-figs over legacy `SendComplaintUseCase` + `UserIdProvider` +
`DeviceInfoProvider` to write language-request complaints to Firestore. The
SOLID_AUDIT close-out flagged Phase 7.x.complaint as the natural follow-on:
the legacy `composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt`
is the user-side "Feedback Manager" — a LIST view of the complaints the user
has previously submitted (including the language-requests issued via the
slice we just closed).

The legacy screen is 294 lines and combines:
- LIST rendering (LazyColumn over `Complaint`)
- Search (substring match against `subject`/`body`/`id`)
- Status filter (FilterChip row, single-select with toggle-off)
- Pinned-top rows (`getCustomTopComplaints()` — hardcoded server-status FAQ items)
- Per-item click → `ComplaintActionDialog` (Reply / Edit / Delete)

The legacy `:shared`/`ComplaintViewModel` exposes 5 actions
(`submit`, `loadAll`, `loadForUser`, `updateComplaint`, `sendComplaint`,
`deleteComplaint`) over `:shared`/`ComplaintRepository` (5 methods).

This slice ports the **LIST + search + filter** core only — the minimum
viable screen — and defers the action dialog, admin view, and pinned rows
to follow-on slices. Same scoping strategy as Phase 7.x.language
(foundation = picker) → Phase 7.x.language.request (mutation flow on top).

Block-and-ask triggers (a)-(d) all NOT met: no contract library blocker;
observable behaviour preserved on legacy route (rework route is parallel +
debug-guarded); compile-safe (introduces new types in `:domain`/`:data`/
`:presentation`/`:ui` + appends to existing `:composeApp` aggregators); no
SOLID violations forced by the design.

## Approach

### Repository shape: sibling, not extension

`FeedbackRepository` (`:domain`) currently has one method:
`suspend fun sendLanguageRequest(body: String): Result<Unit>`. Its KDoc
explicitly anticipates this exact decision (§6 ISP justification on lines
27-32 of `domain/.../FeedbackRepository.kt`): *"If a future complaint
surface needs a generic `sendComplaint(type, subject, body)` shape, that's
a sibling repository (e.g., `ComplaintRepository`), not a fatter
interface."*

This slice introduces `ComplaintListRepository` (sibling) with
`suspend fun loadUserComplaints(): Result<List<ComplaintSummary>>`. The
read-side and write-side surfaces stay separated by ISP — the LIST screen
doesn't care about `sendLanguageRequest`, and the language picker doesn't
care about reads. Two interfaces, two responsibilities.

### Model: `:domain`-side `ComplaintSummary`, not legacy `Complaint`

The legacy `Complaint` data class lives in `:shared`'s
`presentation/features/complaint/model/`. Surfacing it through `:domain`
would force `:domain` → `:shared` (layer-hygiene violation). We introduce
a parallel `:domain`-side `ComplaintSummary` data class with the same
fields and an `Instant?` `createdAt` (already KMP-portable from Phase 4.4).

Mapping happens in the `:data` impl: each legacy `Complaint` → one
`ComplaintSummary`. The legacy enum types (`ComplaintStatus`,
`ComplaintType`) are KMP-portable already (no Android deps remaining, per
their migration KDocs) — we can either reuse them across the `:domain`
boundary OR mirror them. **Decision: mirror them in `:domain`**, for the
same layer-hygiene reason. Same posture as Phase 7.x.statistics's
`ReadingStatistics` data class (separate from any legacy statistics
shape).

### Fetch-once posture (matches legacy)

Legacy `loadForUser()` is `suspend` (single-fetch into a `StateFlow`) —
not a `Flow`. Our `:data` impl wraps the suspend call in `runCatching`
and returns `Result<List<ComplaintSummary>>`. The `:presentation` VM
calls it in `init {}` once + on `OnRetry` intent. No continuous
observation — same posture as Details slice.

If a future user-action (submit/edit/delete) needs to refresh the list,
the action's effect callback or a separate `OnRefresh` intent triggers
the reload. Foundation slice has neither; deferred to follow-on.

### Search + filter: VM state, not derived in compose

The legacy filters happen INSIDE the composable's `LazyColumn` body —
the `filtered` `List<Complaint>` is recomputed each composition from
`allComplaints + searchQuery + selectedStatus`. The rework moves this
**into the VM** as derived state (each `OnSearchChange` /
`OnStatusFilter` intent recomputes a `filtered` list, stored in
`ComplaintState`). The `:ui` composable consumes `state.filtered`
directly — no `remember { mutableStateOf }` for `searchQuery` /
`selectedStatus` in the composable.

**Why VM-side derivation?** Per the rework's MVI contract: the `:ui`
layer is a pure projection of state. Holding search/filter state in
Compose `remember` would split the source of truth between VM and view,
making the view non-stateless. The VM-side approach also lets us add a
`OnClear` intent (clear search + reset filter) cleanly later.

The cost is a tiny per-keystroke recompute (`launch { filter() }` in the
VM intent handler) — negligible for the LIST sizes we're dealing with
(hundreds at most).

### Strangler-fig boundary

The `:data` impl reaches into `:shared`'s `GetUserComplaintUseCase` +
`UserIdProvider`. Both are pre-bound `single` in `SharedModule` /
`PlatformModule.android|ios|desktop` respectively. No new platform-
specific code. Same posture as `FeedbackRepositoryImpl` (3 reaches)
and `LanguageRepositoryImpl` (1 reach).

### MVI surface decision: `Effect` empty for now

`ComplaintEffect` is declared as an empty sealed interface. The
foundation slice has no fire-and-forget side effects (no toasts, no
nav, no snackbars — every transition is reflected in state). The
empty sealed interface is OCP-friendly: a future action slice that
needs `ShowReplySnackbar` / `NavigateToComplaintDetail` slots in
without ripping out the surface. Same posture as Phase 7.x.statistics's
empty `StatisticsIntent` / `StatisticsEffect`.

### Pinned-top rows: deferred

Legacy calls `getCustomTopComplaints()` (a `@Composable` returning 2
hardcoded `Complaint` instances for server-status FAQ entries). These
are presentation-layer fixtures, not user data. For the foundation
slice we drop them entirely; if user-visible requirements emerge,
re-add as a sibling `ObserveSystemAnnouncementsUseCase` (read-only,
deterministic, no Firestore round-trip).

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every
source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for the
`:ui` commit, which touches `:ui/commonMain`).

1. **Plan commit** — `PLAN_complaint.md` only (this file, 1 file).

2. **`:domain` foundation** — 3 files, all new:
   - `domain/.../model/complaint/ComplaintSummary.kt` — data class with
     8 fields: `id: String`, `userId: String`, `type: ComplaintType`,
     `subject: String`, `body: String`, `createdAt: Instant?`,
     `status: ComplaintStatus`.
   - `domain/.../model/complaint/ComplaintStatus.kt` +
     `ComplaintType.kt` — two enums mirroring the legacy values 1:1.
     (Actually 2 files — putting both in one model dir.) **Wait — that's
     2 model enums + 1 model data class = 3 files for the model alone.**
     Plus the repository interface and the use case = 5 files. Hits the
     cap exactly.
   - **Revised model split**: combine the two enums + the data class
     into ONE file `ComplaintSummary.kt` (file-level top-level enum
     declarations alongside the data class — legal Kotlin, used in other
     rework slices' model files). That gives: 1 model file + 1 repo
     interface + 1 use case = 3 files.
   - `domain/.../repository/ComplaintListRepository.kt` — interface
     with `suspend fun loadUserComplaints(): Result<List<ComplaintSummary>>`.
   - `domain/.../usecase/complaint/ObserveUserComplaintsUseCase.kt` —
     `suspend operator fun invoke(): Result<List<ComplaintSummary>>`
     delegating to the repository. (Despite the "Observe" name, it's a
     suspend fetch — the name matches the legacy's `loadForUser` intent
     even though there's no `Flow` involved. If a future slice wraps a
     real Flow, the name fits without rename.)

3. **`:data` strangler-fig impl** — 1 file, new:
   - `data/.../repository/ComplaintListRepositoryImpl.kt` — class takes
     `legacy: GetUserComplaintUseCase` + `userIdProvider: UserIdProvider`
     constructor args; implements `loadUserComplaints()` by:
     ```kotlin
     runCatching {
         legacy(userIdProvider.getUserId())
             .map { it.toSummary() }
     }
     ```
     where `toSummary()` is a private extension. KDoc mirrors
     `FeedbackRepositoryImpl`'s strangler-fig + DIP rationale.

4. **`:presentation` MVI** — 4 files, all new (at the 5-file cap with
   margin for one Koin-binding update if needed; here all 4 are new MVI
   parts).
   - `presentation/.../complaint/ComplaintState.kt` — data class with
     `isLoading: Boolean = true`, `error: String? = null`,
     `all: List<ComplaintSummary> = emptyList()`,
     `filtered: List<ComplaintSummary> = emptyList()`,
     `searchQuery: String = ""`,
     `selectedStatus: ComplaintStatus? = null`.
   - `presentation/.../complaint/ComplaintIntent.kt` — sealed interface
     with 4 variants: `OnRetry: ComplaintIntent`, `OnSearchChange(query)`,
     `OnStatusFilter(status: ComplaintStatus?)`, `OnClearSearch`.
   - `presentation/.../complaint/ComplaintEffect.kt` — empty sealed
     interface (extensibility hook).
   - `presentation/.../complaint/ComplaintViewModel.kt` — extends
     `MviViewModel<ComplaintState, ComplaintIntent, ComplaintEffect>`;
     constructor takes one dep
     (`observeUserComplaints: ObserveUserComplaintsUseCase`);
     `init {}` calls `loadList()` (private helper that launches the
     suspend fetch in `viewModelScope`, folds the `Result`, and updates
     state); `handle()` dispatches: `OnRetry` → `loadList()`;
     `OnSearchChange` / `OnStatusFilter` / `OnClearSearch` → state mutate
     + filter recompute.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../complaint/ComplaintScreen.kt` — `@Composable` taking a
     `ComplaintViewModel` and rendering the LazyColumn + search box +
     filter chips + per-item card. Mirrors the legacy screen's layout
     using the rework `:ui` design tokens. Empty-state and error-state
     are handled inline (no separate `EmptyState` / `ErrorState`
     components ported — kept inline to keep the file count tight).
     Click handlers on cards are no-ops for the foundation slice.

6. **`:composeApp` Koin + nav** — exactly 5 files at the cap:
   - `composeApp/.../di/ComplaintReworkModule.kt` (NEW) — module
     declaring `single<ComplaintListRepository> { ComplaintListRepositoryImpl(get(), get()) }`
     + `factory { ObserveUserComplaintsUseCase(get()) }` +
     `viewModel { ComplaintViewModel(get()) }`.
   - `composeApp/.../di/ReworkModules.kt` (MODIFIED) — append
     `complaintReworkModule` to `allReworkModules()`.
   - `composeApp/.../navigation/routes/ComplaintReworkScreenRoute.kt`
     (NEW) — `@Composable fun` taking `NavController` +
     `NavBackStackEntry`; resolves `ComplaintViewModel` via
     `koinViewModel()`; calls `ComplaintScreen(viewModel)`. No nav
     callbacks beyond back (Statistics-style terminal route — actions
     deferred).
   - `composeApp/.../navigation/Screen.kt` (MODIFIED) — add `object
     ComplaintRework : Screen("me.manga.kira.navigation.Screen.
     ComplaintRework")`.
   - `composeApp/.../App.kt` (MODIFIED) — add `composable<Screen.
     ComplaintRework> { ... ComplaintReworkScreenRoute(...) }`
     alongside the existing `composable<Screen.Complaint>` entry +
     the import.

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §94 — Phase 7.x.complaint.foundation —
     user-side Complaint list screen rework` with subsections covering
     strategy, layer-by-layer surfaces, repository-naming rationale
     (sibling not extension), MVI shape (VM-side filtering), strangler-
     fig boundary, files added, deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.complaint.foundation entry with per-
     file SOLID 10-point checklist, end-of-slice verdict, build gates,
     layer boundaries, behaviour preservation (legacy route preserved;
     rework route is parallel + debug-guarded), MVI contract (new slice,
     6-field state, 4 intents, empty effects), strangler-fig integrity
     (one new `:data` impl reaches `:shared` twice — `GetUserComplaintUseCase`
     + `UserIdProvider`, same posture as `FeedbackRepositoryImpl`),
     next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/complaint/ComplaintSummary.kt` (data class + 2 enum top-levels in same file)
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/ComplaintListRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/ObserveUserComplaintsUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ComplaintListRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/ComplaintScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ComplaintReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/ComplaintReworkScreenRoute.kt`
- `PLAN_complaint.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `complaintReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `ComplaintRework` case.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.ComplaintRework>` block + import.
- `ARCHITECTURE.md` — append §94.
- `SOLID_AUDIT.md` — append Phase 7.x.complaint.foundation entry.

### Untouched (verify by read, not modified)

- `shared/.../complaint/repository/ComplaintRepository.kt` — legacy facade;
  the strangler-fig reads through `GetUserComplaintUseCase` (which delegates
  to it), not directly.
- `shared/.../complaint/usecase/GetUserComplaintUseCase.kt` — legacy use
  case; consumed via `get()` in the rework Koin module, not modified.
- `shared/.../complaint/viewmodel/ComplaintViewModel.kt` — legacy VM;
  preserved for the legacy route. Phase 9.x retirement is its own slice.
- `composeApp/.../features/complaint/ui/screens/ComplaintScreen.kt` —
  legacy screen; preserved for the legacy route.
- `composeApp/.../navigation/routes/ComplaintScreenRoute.kt` — legacy
  route; preserved.

## Reuse

- **Strangler-fig posture**: lifted directly from `FeedbackRepositoryImpl`
  + `LanguageRepositoryImpl`. Same `:shared` reach pattern, same `single`
  Koin lifecycle, same "reach into legacy until Phase 9.x retirement"
  justification.
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `presentation/.../mvi/`. Same superclass every rework VM extends.
- **Koin module shape**: mirrors `languageReworkModule` — `single` for
  repo, `factory` for use case, `viewModel` for VM.
- **Nav route shape**: mirrors `LibraryReworkScreenRoute` —
  `koinViewModel()` resolution + composable wrapper.
- **Composable layout primitives**: reuse the rework `:ui`'s existing
  `LocalSpacing` design tokens, MaterialTheme color/typography. No new
  design tokens introduced.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required for
  commit 5 (`:ui` composable) and commit 6 (`:composeApp` nav graph).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to `Screen.ComplaintRework`,
  verify the user's complaint list renders identically to the legacy
  `Screen.Complaint` screen.
- Verify search and filter behaviours match the legacy 1:1 (same
  substring-match semantics, same single-select status toggle).

Edge cases to mentally model during implementation:

- Empty user complaints: list is empty after fetch — VM updates state
  with `all = filtered = []`. UI renders an inline empty-state Text
  (no separate EmptyState component).
- Fetch failure: `Result.failure(throwable)` — VM sets
  `error = throwable.message ?: "Unknown error"` + `isLoading = false`.
  UI renders inline error + Retry button (which fires `OnRetry`).
- Search returns no results: `filtered = []` but `all.isNotEmpty()` —
  UI renders inline "No matches" (no EmptyState component port).
- Concurrent `OnSearchChange` + `OnStatusFilter`: VM serializes via
  the `MviViewModel`'s `submit` channel, so each intent updates state
  atomically. No race condition.

## Deferrals

- **No ComplaintActionDialog** — Reply / Edit / Delete actions are NOT
  ported. Card clicks no-op for the foundation slice. Follow-on slice
  Phase 7.x.complaint.actions adds the dialog + 3 effects + 3 intents
  + 3 use cases.
- **No admin view** — `loadAll()` / `GetAllComplaintUseCase` not ported.
  Follow-on slice Phase 7.x.complaint.admin would add a parallel
  `ObserveAllComplaintsUseCase` if/when admin role gains a rework
  surface.
- **No pinned-top rows** — `getCustomTopComplaints()` server-status
  FAQ entries dropped from the foundation slice. Follow-on slice if
  user-visible requirements emerge.
- **No i18n lift** — display names for `ComplaintStatus`/`ComplaintType`
  remain English literals in the rework UI; Phase 10's i18n lift will
  handle both legacy and rework consumers in one pass.
- **No nav graph route-swap** — legacy `Screen.Complaint` stays bound to
  the legacy route. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one data class + 2 enums in one
  model file; one interface; one use case; one impl; one MVI surface
  part; one composable; one Koin module; one nav adapter).
- **OCP**: Sealed interfaces (Intent + Effect) closed under modification
  but open under extension. Repository interface is closed; impl is
  substitutable via Koin binding. Action-slice additions slot into
  `ComplaintIntent` without touching the existing variants.
- **ISP**: Read-side and write-side surfaces are separated —
  `FeedbackRepository` (write-only, sendLanguageRequest) and
  `ComplaintListRepository` (read-only, loadUserComplaints). The
  language picker doesn't drag in unused read methods; the complaint
  list doesn't drag in unused write methods. **The KDoc on
  `FeedbackRepository` explicitly anticipated this exact split**
  (lines 27-32).
- **DIP**: `:presentation` depends on `:domain`'s interface, not
  `:data`'s impl. `:data`'s impl depends on legacy `:shared`'s
  `GetUserComplaintUseCase` because that's where the Firestore-bound
  read lives — same strangler-fig posture as `FeedbackRepositoryImpl`,
  documented in its KDoc.
- **Layer boundary**: changes touch `:domain` (3 new files), `:data`
  (1 new file), `:presentation` (4 new files), `:ui` (1 new file),
  `:composeApp` (2 new + 3 modified incl. close-out). No cross-layer
  reach beyond the strangler-fig `:data` → `:shared` permitted
  boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. All flow
  operations are pure `kotlinx.coroutines`. `runCatching` for failure
  wrapping (uniform with the language.request slice).
- **MVI contract**: new slice — adds `ComplaintState` + 4-variant
  `ComplaintIntent` + empty `ComplaintEffect`. The state's `filtered`
  field is derived (recomputed on each filter-related intent) but
  stored as a field for stateless-view consumption.
- **Strangler-fig**: TWO `:data` → `:shared` reaches (`GetUserComplaintUseCase`
  + `UserIdProvider`). Both pre-bound `single`. Same boundary class as
  `FeedbackRepositoryImpl` (3 reaches) — no new platform-specific code.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, Reader code paths, AVIF decoder, OkHttp interceptor, or
  any prior load-bearing image-quality posture (Complaint list has no
  images). No load-bearing risk.
