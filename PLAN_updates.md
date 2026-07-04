# Phase 7.x.updates — Updates screen rework (`:domain` → `:data` → `:presentation` → `:ui` + Koin/nav wiring)

## Context

The History slice (Phase 7.x.history, Task #239) closed cleanly at
HEAD `cc38a43`. Per the `/goal` Stop hook Rule 3, the next non-blocked
candidate is a fresh feature rework. Updates is chosen because:

- Same legacy posture as History: a legacy `:shared` repository
  (`NotificationRepository`, despite the user-facing "Updates" name —
  the feature is internally named "notifications") + legacy Room
  entity (`ChapterNotification`) + DAO already bound by `SharedModule`.
  The legacy screen renders a date-grouped lazy list with per-row CRUD
  + navigation — the canonical list-screen shape History established
  (§82.4).
- Slightly larger than History (5 repository methods vs 3 — adds
  `markAsRead` and `markAllAsRead`), exercising the same MVI shape
  with two additional mutating intents. Adds the "list screen with
  per-row read-state toggle" coverage to the rework pattern catalogue.
- Block-and-ask triggers (a)-(d) all NOT met: no contract library
  blocker, no observable behaviour change (legacy route preserved;
  rework route is parallel), no compile risk, no SOLID violation
  forced by the design.

The legacy screen at `composeApp/.../features/notifications/ui/
screens/UpdatesScreen.kt` consumes `NotificationsViewModel.uiState`
(StateFlow<NotificationsUiState> — pre-grouped) + a second VM
(`DownloadViewModelv2`, for the per-row download-button + spinner
state). The legacy domain facade at `shared/.../features/
notifications/domain/NotificationRepository.kt` exposes the grouped
flow + 6 mutating methods (markAsRead, markAllAsRead, delete,
deleteAll, restore — the restore is undo-snackbar support — and the
implied write via `LibraryRefreshWorker`/`ChapterNotificationHelper`).

The rework mirrors the History pattern: clean `:domain` interface +
5 use cases → strangler-fig `:data` impl over the same legacy
`NotificationRepository` (aliased `as LegacyNotificationRepository`
to avoid the name clash) → `:presentation` MVI surface (6 intents
+ 2 nav effects) → `:ui` composable → `:composeApp` Koin module +
parallel nav route. The worker that WRITES into the notifications
table stays legacy — it lives in `app/` (Android-only) and is
outside the rework's read+mutate boundary.

## Approach

### Domain model — `UpdateEntry`

A pure data class mirroring the 14 fields of the legacy
`ChapterNotification` Room entity (sans Room annotations). Fields:
`id, api, language, mangaId, mangaTitle, mangaImageUrl, mangaUrl,
chapterId, chapterNumber, chapterUrl, notificationDate: LocalDate,
isRead: Boolean, isDownloaded: Boolean, localImagePaths: List<String>
`. Same posture as `HistoryEntry` — carry the full reader nav-arg
tuple on the domain model to avoid an extra DAO round-trip at click
time + crash-safety vs deletion races. `notificationDate` is
`LocalDate` (not `LocalDateTime` — the legacy notification entity
only stores the date, not the time-of-day).

### Date-grouping posture — flat domain model, regroup in `:ui`

The legacy `NotificationRepository.getGroupedNotifications()` returns
a pre-grouped `Flow<List<Pair<String, List<ChapterNotification>>>>`
with **four** legacy English-literal group labels ("Today",
"Yesterday", "Last Week", "Older"). The rework flattens this at the
`:data` boundary (`legacy.getGroupedNotifications().map { groups ->
groups.flatMap { it.second }.map { it.toDomain() } }`) and re-groups
in `:ui` using a single date-based formatter parameterised on the
`UpdateEntry.notificationDate` accessor.

The regrouping algorithm matches History's exactly: `groupBy { entry
-> entry.notificationDate }` + `sortedByDescending { it.key }` +
`associate { it.key to it.value }` into a `LinkedHashMap`. Group
labels use the same "Today / Yesterday / N days ago / MMM d, yyyy"
formatter — the rework's two list screens converge on a single
date-label idiom (the legacy's four-bucket "Today/Yesterday/Last
Week/Older" labels are coarser; the rework's 4-tier "Today/Yesterday/
N days ago/MMM d, yyyy" is finer-grained and matches the History
screen the user just learned). Both legacy and rework consume the
same Room rows, so the entry counts agree by construction; only the
group-label granularity differs (intentional convergence on the
History idiom). If parity-with-legacy on labels matters later, swap
the label formatter — the algorithm is one function.

### Repository contract

```kotlin
interface UpdatesRepository {
    fun observeUpdates(): Flow<List<UpdateEntry>>
    suspend fun markAsRead(entry: UpdateEntry)
    suspend fun markAllAsRead()
    suspend fun deleteEntry(entry: UpdateEntry)
    suspend fun deleteAll()
}
```

Five operations. `markAsRead(entry)` takes the full entry (not just
an id) for the same reasons `deleteEntry` did in History: the
strangler-fig impl maps the entry to the legacy `ChapterNotification`
via a mapper, and the legacy DAO `markAsRead(id)` actually takes an
id — but the rework keeps a uniform "operations take the domain
entity" surface, with the impl extracting the id internally. Inserts
are NOT on this interface (ISP) because the rework reads + mutates
— the worker is the sole writer.

### Use cases

Five thin pass-through use cases (one per repository method) —
matches History's pattern, scaled to 5:

- `ObserveUpdatesUseCase(repo)` — `operator fun invoke(): Flow<List<UpdateEntry>>`
- `MarkUpdateAsReadUseCase(repo)` — `suspend operator fun invoke(entry: UpdateEntry)`
- `MarkAllUpdatesAsReadUseCase(repo)` — `suspend operator fun invoke()`
- `DeleteUpdateEntryUseCase(repo)` — `suspend operator fun invoke(entry: UpdateEntry)`
- `DeleteAllUpdatesUseCase(repo)` — `suspend operator fun invoke()`

### MVI surface

**`UpdatesState`** — `items: List<UpdateEntry>`, `isLoading: Boolean`.
No `error` field (same posture as `HistoryState`: Room observe-site
doesn't throw; `UPDATE`/`DELETE` SQL is structurally infallible).
Derived `isEmpty: Boolean` getter (true when not loading and items
empty). No `pendingDeletion: UpdateEntry?` — undo-snackbar is
DEFERRED (see deferrals below).

**`UpdatesIntent`** — sealed interface with 6 variants:
- `OnMarkAsRead(entry: UpdateEntry)` — fires mark-read via use case.
- `OnMarkAllAsRead` — fires mark-all via use case.
- `OnDeleteEntry(entry: UpdateEntry)` — fires delete via use case.
- `OnDeleteAll` — fires clear-all via use case.
- `OnMangaClick(entry: UpdateEntry)` — emits `NavigateToDetails`.
- `OnChapterClick(entry: UpdateEntry)` — emits `NavigateToReader`.

**`UpdatesEffect`** — sealed interface with 2 variants:
- `NavigateToDetails(api: String, mangaUrl: String)` — the legacy
  `Screen.MangaDetails` payload (same shape as `HistoryEffect`).
- `NavigateToReader(entry: UpdateEntry)` — carries the entry so
  the route adapter constructs the full legacy
  `Screen.ChapterImagesFragment` argument shape. The entry's
  `chapterId` field IS the chapterId nav arg directly (the legacy
  `ChapterNotification` already stores it as `chapterId`, no
  History-style "id doubles as chapterId" quirk).

**`UpdatesViewModel`** — extends `MviViewModel<State, Intent, Effect>`;
constructor takes the 5 use cases; `init {}` collector subscribes to
`observeUpdates()` and projects each emission into `items`;
`handle(intent)` reducer dispatches each variant — mutations fire
`viewModelScope.launch { useCase() }` (fire-and-forget, upstream
re-emits with mutation reflected); clicks `emit(effect)`.

### `:ui` composable

`UpdatesScreen` follows the History template:
- Stateless inner `UpdatesScreenContent` taking `state` + lambdas
- `LaunchedEffect` collecting `viewModel.effects` and translating
  to nav callbacks (`onNavigateToDetails: (api, mangaUrl) -> Unit`,
  `onNavigateToReader: (UpdateEntry) -> Unit`)
- `Scaffold` + `TopAppBar` ("Updates" title + two `TextButton`s:
  "Mark all read" + "Clear all", each `enabled` based on items state)
- Three-state body: loading spinner / empty placeholder / `LazyColumn`
  of date-grouped rows
- Each row: `Card` with 72x108dp `AsyncImage` cover + `Column` of
  title / chapter / relative date / read-state indicator
  (`isRead`-driven typography weight — unread = bold, read =
  regular; legacy uses opacity, the rework uses font-weight to
  avoid the `:ui` material-icons dep) + two `TextButton`s
  ("Mark read" if `!entry.isRead`, "Delete")

Icons OMITTED (same `:ui` minimal-dep posture as History/Statistics —
`compose.materialIconsExtended` is ~6 MB; substitute labelled
`TextButton`s).

### `:composeApp` Koin + nav

Five-file commit at the cap (matches History's commit 6 exactly):
- `di/UpdatesReworkModule.kt` (NEW)
- `di/ReworkModules.kt` (MOD — append `updatesReworkModule`)
- `navigation/Screen.kt` (MOD — add `Screen.UpdatesRework`)
- `navigation/routes/UpdatesReworkScreenRoute.kt` (NEW) — forwards
  `onNavigateToDetails` to `Screen.MangaDetails` (LEGACY route) and
  `onNavigateToReader` to `Screen.ChapterImagesFragment` (LEGACY
  route) — same posture as History's adapter, until Phase 9.x
  route-swap.
- `App.kt` (MOD — add `composable<Screen.UpdatesRework>` block +
  import)

### Strangler-fig boundary

The `:data` impl reaches into `:shared`'s legacy
`NotificationRepository` — SAME posture as History's
`HistoryRepositoryImpl`, Statistics's `ReadingStatisticsRepositoryImpl`,
and Session's `ReadingSessionRepositoryImpl`. The existing
`:data/build.gradle.kts` `:shared` dep carries through. The legacy
`NotificationRepository` is imported `as LegacyNotificationRepository`
to disambiguate from the rework interface that shares the name —
identical disambiguation to History.

## Commit roadmap

Eight commits — one more than History because the 5-use-case `:domain`
surface exceeds the 5-files/commit cap and must split. All ≤5 files
per commit per the standing cap. Build gates after every source
commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for slices
touching `:ui`/`:composeApp`).

1. **Plan commit** — `PLAN_updates.md` only (1 file).

2a. **`:domain` core** — 5 files at the cap, all new:
   - `domain/.../model/updates/UpdateEntry.kt`
   - `domain/.../repository/UpdatesRepository.kt` — interface with
     5 method signatures
   - `domain/.../usecase/updates/ObserveUpdatesUseCase.kt`
   - `domain/.../usecase/updates/DeleteUpdateEntryUseCase.kt`
   - `domain/.../usecase/updates/DeleteAllUpdatesUseCase.kt`

2b. **`:domain` mark-read use cases** — 2 files, all new:
   - `domain/.../usecase/updates/MarkUpdateAsReadUseCase.kt`
   - `domain/.../usecase/updates/MarkAllUpdatesAsReadUseCase.kt`

3. **`:data` strangler-fig impl** — 2 files, all new:
   - `data/.../mapper/UpdateMappers.kt` — pair of `toDomain()` /
     `toEntity()` extension functions on `ChapterNotification` and
     `UpdateEntry`. Field-for-field copies.
   - `data/.../repository/UpdatesRepositoryImpl.kt` — class takes
     `legacy: LegacyNotificationRepository` constructor arg;
     implements 5 methods. `observeUpdates()` is `legacy.
     getGroupedNotifications().map { groups -> groups.flatMap {
     it.second }.map { it.toDomain() } }` — flattens the legacy
     grouping at the boundary; `:ui` regroups. `markAsRead(entry)`
     forwards `legacy.markAsRead(entry.id)` (legacy is id-based,
     internal). `deleteEntry(entry)` round-trips
     `entry.toEntity()` to legacy `delete(notification)`. Others
     verbatim.

4. **`:presentation` MVI** — 4 files, all new:
   - `presentation/.../updates/UpdatesState.kt`
   - `presentation/.../updates/UpdatesIntent.kt` — sealed interface
     with 6 variants.
   - `presentation/.../updates/UpdatesEffect.kt` — sealed interface
     with 2 nav variants.
   - `presentation/.../updates/UpdatesViewModel.kt` — extends
     `MviViewModel<S, I, E>`; constructor takes 5 use cases;
     `init {}` collector + 6-branch `handle(intent)` reducer.

5. **`:ui` composable** — 1 file, new:
   - `ui/.../updates/UpdatesScreen.kt`. Mirrors the History
     template; adds the per-row "Mark read" `TextButton` (visible
     when `!entry.isRead`) and the toolbar "Mark all read" action.
     The font-weight `FontWeight.Bold`-vs-`FontWeight.Normal`
     branch on `entry.isRead` is the only visual signal of
     read-state — no opacity, no icons.

6. **`:composeApp` Koin + nav** — 5 files at the cap (mirrors
   History's commit 6 exactly):
   - `di/UpdatesReworkModule.kt` (NEW)
   - `di/ReworkModules.kt` (MOD)
   - `navigation/Screen.kt` (MOD)
   - `navigation/routes/UpdatesReworkScreenRoute.kt` (NEW)
   - `App.kt` (MOD)

7. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §83 — Phase 7.x.updates — Updates
     screen rework` covering strategy, layer-by-layer surfaces,
     date-grouping posture (flat-domain + ui-regroup with the
     History idiom), MVI shape, the 5-use-case split rationale,
     strangler-fig boundary, files added, deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.updates entry with per-file
     SOLID 10-point checklist across all 19 new/modified files
     (7 :domain + 2 :data + 4 :presentation + 1 :ui + 5
     :composeApp), end-of-slice verdict, build gates, layer
     boundaries, behaviour preservation, MVI contract,
     strangler-fig integrity, load-bearing fixes preserved,
     next-candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/model/updates/UpdateEntry.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/UpdatesRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/updates/ObserveUpdatesUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/updates/MarkUpdateAsReadUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/updates/MarkAllUpdatesAsReadUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/updates/DeleteUpdateEntryUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/updates/DeleteAllUpdatesUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/mapper/UpdateMappers.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/UpdatesRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/updates/UpdatesState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/updates/UpdatesIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/updates/UpdatesEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/updates/UpdatesViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/updates/UpdatesScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/UpdatesReworkModule.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/UpdatesReworkScreenRoute.kt`
- `PLAN_updates.md`

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ReworkModules.kt` — append `updatesReworkModule`.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/Screen.kt` — add `UpdatesRework` object.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — add `composable<Screen.UpdatesRework>` block + import.
- `ARCHITECTURE.md` — append §83.
- `SOLID_AUDIT.md` — append Phase 7.x.updates entry.

### Untouched (verify by read, not modified)

- `shared/.../features/notifications/domain/NotificationRepository.kt` — legacy
  facade; the strangler-fig delegates to its methods but does not modify it.
- `shared/.../data/local/dao/NotificationDao.kt` — legacy DAO; not touched
  (the `:data` impl goes through the legacy facade, not the DAO directly,
  same posture as History/Statistics/Session).
- `shared/.../data/local/entity/ChapterNotification.kt` — legacy Room entity;
  preserved verbatim.
- `app/.../work/LibraryRefreshWorker.kt` — Android-only legacy worker that
  WRITES into the notifications table. Out of the rework's read+mutate
  boundary; preserved verbatim.
- `composeApp/.../features/notifications/ui/screens/UpdatesScreen.kt` —
  legacy screen; preserved for the legacy route. Phase 9.x route-swap
  retires it later.
- `composeApp/.../navigation/routes/UpdatesScreenRoute.kt` — legacy
  route; preserved.

## Reuse

- **Strangler-fig posture**: lifted directly from `data/.../
  HistoryRepositoryImpl.kt`'s class shape + KDoc structure. Same
  `:shared` dependency, same `single` Koin lifecycle, same
  import-alias trick (`as LegacyNotificationRepository`).
- **Domain model shape**: lifted from `HistoryEntry` (14 fields,
  same "carry the full reader nav-arg tuple" rationale).
- **MVI base class**: extends `MviViewModel<S, I, E>` from
  `:presentation/mvi/`.
- **Koin module shape**: mirrors `historyReworkModule` — `single`
  for repo, `factory` for each of 5 use cases, `viewModel` for VM.
- **Nav route shape**: mirrors `HistoryReworkScreenRoute` — adapter
  resolves VM via `koinViewModel()`, forwards two nav callbacks
  to LEGACY route targets (until Phase 9.x route-swap).
- **`:ui` layout**: mirrors `HistoryScreen` — `Scaffold` +
  `TopAppBar` + three-state body + grouped `LazyColumn` + `Card`
  row with `AsyncImage` cover + text + `TextButton` actions. Adds
  one toolbar action ("Mark all read") and one per-row action
  ("Mark read", conditional on `!entry.isRead`).
- **Date-grouping**: lifts the helper functions from
  `HistoryScreen` (`groupByDate`, `formatGroupLabel`,
  `formatRelativeDate`, `monthAbbrev`) verbatim — same algorithm.
  The two screens converge on a single date-label idiom; the
  helpers may be hoisted into a `:ui/.../shared/` module in a
  later cleanup slice but stay file-private for now to avoid
  premature abstraction.

## Verification

After every source commit (steps 2a-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (required
  for commits 5+6 since `:ui` + `:composeApp/commonMain` link into
  Desktop entry).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Build with the rework debug flag, navigate to
  `Screen.UpdatesRework` via the developer trigger, verify the
  notification list renders identically to the legacy
  `Screen.Updates` screen for the same user data (group label
  granularity intentionally differs — see §83.3).
- Mark a notification as read on the rework route, confirm the
  legacy route reflects the change (both routes share the
  `notifications` Room table — the crossover invariant).
- Delete a notification on the rework route, confirm the legacy
  route reflects the deletion.
- Trigger a `LibraryRefreshWorker` run (or wait for one); verify
  new notifications appear on BOTH routes.

Edge cases to mentally model during implementation:

- Empty updates table: `isLoading = true` initially, then
  `isEmpty = true` after first emission. The empty-state branch
  renders centered "No updates yet" text; toolbar actions stay
  disabled.
- All entries read: the per-row "Mark read" button hides on every
  row (only visible when `!entry.isRead`); the toolbar "Mark all
  read" still renders but `enabled = items.any { !it.isRead }`
  — disabled when everything's already read.
- Mark-as-read race: user taps "Mark read" then immediately taps
  the row to open the reader. The `viewModelScope.launch
  { markAsRead(entry) }` fires concurrently with the
  `emit(NavigateToReader(entry))` — both complete; the nav
  happens with the entry's previous `isRead = false` value but
  the table update lands shortly after. Acceptable (the user is
  navigating away anyway).
- Concurrent worker writes: `LibraryRefreshWorker` inserts new
  notifications mid-screen. The upstream Room flow re-emits;
  `init {}` collector picks up the new list; new rows appear at
  the top (sorted by date desc).

## Deferrals

- **No download button + spinner per row.** The legacy renders a
  download-state-aware button on each row (driven by a second VM,
  `DownloadViewModelv2`). Wiring this requires a `:domain`
  `DownloadsRepository` + use cases + impl — that's the natural
  scope of Phase 7.x.downloads (a future slice). This slice
  preserves the click-to-read + click-to-details navigation; the
  download-from-notification action stays only on the legacy
  route until the Downloads rework lands.
- **No undo-snackbar after delete.** The legacy has a
  `deleteWithUndo(notification)` flow + a 5-second window with
  a "Undo" snackbar action that calls `restore(notification)`.
  The rework substitutes immediate-delete (same posture as
  History — direct `TextButton` "Delete"). If a future UX brief
  reinstates the undo, a `pendingDeletion: UpdateEntry?` field
  + an `UndoableDelete(entry)` effect variant + a `restore(...)`
  repository method slot in without rewiring.
- **No swipe-to-mark-read / swipe-to-delete gestures.** The
  legacy uses `SwipeToDismissBox` for both. The rework
  substitutes labelled `TextButton`s (matches History).
- **No i18n lift.** Labels ("Updates", "Mark all read",
  "Clear all", "Mark read", "Delete", "No updates yet", "Today",
  "Yesterday", "N days ago", month abbreviations) stay inlined;
  Phase 10 i18n lift swaps both legacy and rework consumers in
  one pass.
- **No icons.** Same `:ui` minimal-dep posture as History/
  Statistics — `compose.materialIconsExtended` is ~6 MB
  deliberately excluded.
- **No per-row checkbox / multi-select bulk-action mode.** The
  legacy doesn't have this; the rework doesn't add it. The
  toolbar "Mark all read" + "Clear all" cover the bulk surface.
- **No notification-channel toggle / settings link.** Out of
  scope (settings rework is its own future slice).
- **No nav graph route-swap.** Legacy `Screen.Updates` stays
  bound to the legacy route. Phase 9.x route-swap is its own
  slice; this slice only adds the parallel
  `Screen.UpdatesRework`.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: Each new file has one rule (one ADT, one interface,
  one use case, one impl, one MVI surface part, one composable,
  one Koin module, one nav adapter).
- **OCP**: `UpdatesIntent` / `UpdatesEffect` are sealed
  interfaces; future variants slot in as new cases. Empty
  variants are NOT used here (unlike Statistics) — the slice
  has real user actions today.
- **DIP**: `:presentation` depends on `:domain`'s 5 use cases,
  not `:data`'s impl. `:data`'s impl depends on legacy
  `:shared`'s `NotificationRepository` — same strangler-fig
  posture as `HistoryRepositoryImpl` /
  `ReadingStatisticsRepositoryImpl` /
  `ReadingSessionRepositoryImpl`. Koin binds the impl at the
  composition root.
- **Layer boundary**: changes touch `:domain` (7 new files —
  split across 2a/2b), `:data` (2 new files), `:presentation`
  (4 new files), `:ui` (1 new file), `:composeApp` (2 new + 3
  modified incl. close-out). No cross-layer reach beyond the
  strangler-fig `:data` → `:shared` permitted boundary.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`.
  All flow operations are pure `kotlinx.coroutines.flow`.
- **MVI contract**: new slice — adds `UpdatesState` (2 fields
  + derived `isEmpty`), `UpdatesIntent` (6 variants),
  `UpdatesEffect` (2 nav variants). The `handle(intent)`
  reducer is a 6-branch `when` — sealed-with-6-subtypes is
  exhaustive.
- **Strangler-fig**: ONE `:data` → `:shared` reach (constructor
  injection of legacy `NotificationRepository` in
  `UpdatesRepositoryImpl`). Same boundary as History/
  Statistics/Session — already-established posture.
- **Load-bearing fixes preserved**: Updates screen renders
  cover thumbnails via the same singleton `ImageLoader`
  (`:composeApp/App.kt`'s `setSingletonImageLoaderFactory`)
  that carries the AVIF decoder, OkHttp fetcher,
  max-bitmap-size override, HighQualitySkiaImageDecoder,
  RGB_565 + allowHardware(false), and
  `CoilSourceHeaderInterceptor`. Plain
  `AsyncImage(model = url)` posture — no per-screen
  `ImageRequest.Builder`. No load-bearing risk; the slice
  is purely additive at the composition root.
