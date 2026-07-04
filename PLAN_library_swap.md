# Phase 9.x.library.swap — Route-swap legacy Screen.Library → rework LibraryScreen (Task #346)

## Context

The Library ladder (§150) closed at rung 19 (`Phase 7.x.library.actionrow`,
Task #345 — commits `30c43a7`/`18a1376`/`d151d72`/`2e5b88b`) with the
per-card 3-button action row port. All 19 rungs of the Library feature-
parity ladder are now complete:

- Rungs 1-15: delete-dialog, refresh button, random button, sort tier-a/b,
  sort persist, filter foundation/persist/tier-b, display foundation/persist,
  category tabs/persist, last-updated, download-progress badge.
- Rungs 16a-16g: display-toggle prefs foundation + 5 per-flag toggles
  (showSource/showCount/showDetails/showButtons/showTabs) + the
  display-bottom-sheet toggle UI.
- Rung 17: cardsource caption (+ 16b₂ ui-gate).
- Rung 18: carddownloaded/cardbookmarks/cardlastread/cardprogress
  (+ 16c₂ / 16d₂ ui-gates) — per-card metadata captions.
- Rung 19: per-card action row (like/watching-now/delete) — closing slice.

With rung 19 landed, the rework `:ui/.../library/LibraryScreen` has full
feature parity with the legacy `:presentation/features/library/ui/screens/
LibraryScreen` for the user-facing route. The route adapter that currently
binds the user-facing `Screen.Library` entry to the LEGACY composable can
now be rewritten to render the REWORK stack — same shape as the prior
swap slices (§138 theme, §140 sources, §286 statistics, §287 about, §288
history, §289 updates, §290 whatsnew, §291 theme, §292 language, §293
complaint, §295 downloads, §301 settings, §305 sources-onboarding).

Block-and-ask triggers (a)-(d) all NOT met:
- (a) No contract library blocker: rework stack is in place and bound.
- (b) Observable behavior delta is intentional — see §"Behavior deltas".
- (c) No compile risk: 2-file rewrite, all referenced symbols verified.
- (d) No unresolvable SOLID violation forced by the design.

## Strategy

Rewrite `composeApp/.../navigation/routes/LibraryScreenRoute.kt` (the
adapter bound to `Screen.Library`) so it resolves the rework
`me.manga.kira.presentation.library.LibraryViewModel` via
`koinViewModel()` and renders the rework
`:ui/.../library/LibraryScreen` instead of the legacy
`:presentation/features/library/ui/screens/LibraryScreen`. Adjust the
single `App.kt` call site to drop the four legacy callbacks (the rework
adapter is the 2-arg `(navController, backStackEntry)` shape — same as
the existing `LibraryReworkScreenRoute`).

Preserve at the adapter level:

- **`WhatsNewViewModel` first-launch redirect orchestration** (lines
  80-109 of the legacy adapter). This is route-host-level orchestration,
  not Library state — it gates whether the user lands on Library at all
  on first launch. Same shape, verbatim: resolve via `koinViewModel()`,
  collect `shouldShowWhatsNew` + `isLoading`, drive two `LaunchedEffect`s
  with the `hasNavigatedToWhatsNew` flag, navigate via `safeNavigate(
  Screen.WhatsNewScreen(true))`.

Drop at the adapter level (now internal to the rework path):

- **`RefreshViewModel` + `onRefreshLibrary` helper + empty-library toast
  fallback** — rework `LibraryViewModel` already owns the refresh state
  via §147/§148 (Tasks #313/#314 `OnRefresh` intent + top-bar refresh
  button). The empty-library guard is also internal to the rework VM
  (no-op when library is empty — no toast, matches the §148 behavior
  that the user already validated).
- **`AlertDialog` delete confirmation** — rework `:ui/.../library/
  LibraryScreen` already owns its own confirm-remove dialog (§144
  Task #312). The legacy adapter's AlertDialog block becomes redundant.
- **`DownloadViewModelv2` parameter** — rework Library doesn't depend on
  this cross-tab queue VM. The download-progress badge is wired via the
  `ObserveActiveDownloadsUseCase` from `downloadsReworkModule` (§161),
  resolved internally by `LibraryReworkModule` (`LibraryViewModel`
  constructor arg `observeDownloads`). No callback needed.
- **`onOpenRandomClick`** — rework Library has its own Random action
  (§149 Task #315 `OnOpenRandom` intent → `LibraryEffect.NavigateToDetails`
  with a random pick). The adapter does NOT need to receive this callback.
- **`onLibraryMangaClick: (Long) -> Unit`** — rework Library uses
  `(Manga) -> Unit` from `LibraryEffect.NavigateToDetails`, NOT a
  `Long` mangaId. The conversion happens in this adapter's
  `onNavigateToDetails` lambda.
- **`onDownloadClick`** — rework Library doesn't have a downloads-tab
  button (legacy had one in the top bar that navigated to
  `Screen.DownloadsScreen`). If a future slice wants a top-bar downloads
  shortcut on rework Library, it goes through `LibraryEffect.NavigateTo
  Downloads` — additive, OCP-friendly. NOT in scope for this slice.

## Approach

Two-file slice (well within the 5-file cap):

1. **`LibraryScreenRoute.kt`** — full rewrite. Becomes near-identical to
   the established `LibraryReworkScreenRoute.kt` shape (lines 53-71)
   plus the `WhatsNewViewModel` first-launch redirect orchestration
   block from the legacy adapter (lines 80-109). Final signature:
   `fun LibraryScreenRoute(navController: NavController, backStackEntry:
   NavBackStackEntry)`. The KDoc rewrites to document the swap rationale,
   the `WhatsNewViewModel` preservation, and the schema-mismatch handling
   (`Long` mangaId → `(mangaUrl, api)` tuple via the rework `Manga`
   model's `.url` and `.api` fields).

2. **`App.kt`** — single call-site simplification at lines 361-382.
   The `composable<Screen.Library>` block becomes:

   ```kotlin
   composable<Screen.Library> { backStackEntry ->
       SideEffect { onBottomBarVisibleChange(true) }
       LibraryScreenRoute(
           navController = navController,
           backStackEntry = backStackEntry,
       )
   }
   ```

   Drops the three callback param blocks (`downloadViewModel`,
   `onOpenRandomClick`, `onLibraryMangaClick`). Bottom-bar visibility
   stays `true` (parity).

### Behavior deltas (intentional, documented)

The rework path renders the rework `:ui/library/LibraryScreen` which is
visually and behaviorally a port of the legacy screen with the following
intentional differences carried over from the prior 19 ladder rungs:

- **Nav target schema**: `Screen.LibraryMangaDetails(mangaId: Long)` →
  `Screen.MangaDetails(mangaUrl: String, api: Int)`. Same mismatch as
  the §138/§140/§295 prior swaps — the rework graph uses URL+API as the
  manga identity, not the Room PK. Same Manga, different routing.
  The `Screen.MangaDetails` adapter handles fetching/loading manga from
  the URL+API tuple just like the legacy `LibraryMangaScreenRoute`
  handles it from the Room ID.
- **Random action**: in-screen button (§149) instead of an outer-scope
  callback. Visually identical (shuffle icon → top-bar action), same
  behavior (random pick navigates to manga details, no-op when empty).
- **Refresh**: in-screen pull-to-refresh + top-bar button (§147/§148).
  Empty-library state: no toast, button is a no-op (intentional — the
  legacy "No manga in your library yet!" toast on the random action was
  validated by the user as removable on the rework path during the §149
  random-button slice).
- **Delete dialog**: in-`:ui` confirm-remove dialog (§144) instead of
  the adapter-owned AlertDialog. Same UX (titled "Delete manga", body
  text from the same resource string, Confirm/Cancel buttons).

### Why `WhatsNewViewModel` stays at the adapter

The first-launch What's-New redirect is NOT a Library concern — it's a
launch-orchestration concern that happens to be hosted on the Library
route because Library is the user-facing start destination
(`App.kt:292` `rootStart = if (firstLaunch) Screen.Welcome else
Screen.Library`). After a Welcome→Theme→Sources onboarding pass, the
user lands here, and *iff* the app version-code bumped since their last
launch, this VM's `shouldShowWhatsNew` flips true and the adapter
navigates them to `Screen.WhatsNewScreen(true)`.

Hosting this logic in the Library VM would conflate "library state"
with "app-launch redirect plumbing" — SRP violation. Hosting it in
`App.kt` would push composition state into the root composable.
The adapter is the right home — it's parallel to how the legacy
adapter has hosted it for the entire project lifetime.

### Why the parallel `Screen.LibraryRework` route stays

This slice does NOT retire the `Screen.LibraryRework` enum case or its
`composable<Screen.LibraryRework>` block at `App.kt:587-593`. Reasons:

- Retirement is a follow-on slice (mirrors §285 RepoSettings retirement
  pattern — the post-swap retire-the-parallel slice is its own cleanup).
- The `Screen.LibraryRework` route is debug-only and not referenced from
  any user-facing entry; leaving it in place pending retirement is safe.
- 5-file cap respected by deferring the cleanup.

A follow-on `Phase 9.x.library.retire` slice will:

- Delete `Screen.LibraryRework` enum case.
- Delete the `composable<Screen.LibraryRework>` block.
- Delete `LibraryReworkScreenRoute.kt`.
- Delete the legacy `:presentation/features/library/ui/screens/
  LibraryScreen` and any of its now-unreachable supporting files
  (legacy `LibraryViewModel`, `RefreshViewModel` if no other caller,
  legacy `LibraryScreen` helper composables).

Out of scope for this slice.

## Files added

(None — single-file rewrite of an existing route adapter, single-block
edit to App.kt.)

## Files modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/
  LibraryScreenRoute.kt` — full rewrite. ~80 lines (post-rewrite size).
  Renders the rework `:ui` LibraryScreen via Koin-bound rework
  `LibraryViewModel`. Preserves the `WhatsNewViewModel` first-launch
  redirect block. Drops the legacy 5-callback signature in favor of
  the 2-arg `(navController, backStackEntry)` shape.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — single
  call-site edit at the `composable<Screen.Library>` block (lines
  361-382). Drops the three callback param blocks. ~3 effective
  lines of change (the block shrinks from ~22 lines to ~7 lines).
  No import changes needed (the imports `State`, `MangaDisplayItem`,
  `Screen.LibraryMangaDetails`, `toastShower.showShort`, `safeNavigate`
  used by the dropped random callback may still be needed by other
  blocks in App.kt — verify by post-edit search; if any become unused,
  remove them as part of the same edit).

## Files untouched (verify by read, not modified)

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/
  LibraryReworkScreenRoute.kt` — the existing rework adapter stays
  (will be retired in the follow-on slice).
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/
  Screen.kt` — `Screen.Library` enum case unchanged (this is the user-
  facing route — same binding, new renderer). `Screen.LibraryRework`
  also unchanged.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/
  LibraryReworkModule.kt` — Koin bindings unchanged. The rework
  `LibraryViewModel` is already bound by this module; this slice just
  changes WHO resolves it.
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/library/LibraryScreen.kt`
  — rework composable unchanged.
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/
  library/LibraryViewModel.kt` — rework VM unchanged.
- Legacy `:presentation/features/library/ui/screens/LibraryScreen.kt`
  — preserved on disk (retire follow-on slice).
- Legacy `:presentation/features/library/ui/viewmodel/LibraryViewModel.kt`
  — preserved on disk (retire follow-on slice).
- All other route adapters and bottom-bar entries — unchanged.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_library_swap.md` only (1 file, mirroring the
   plan-file pattern used by every prior multi-commit slice).

2. **Source commit** — 2 files:
   - `composeApp/.../navigation/routes/LibraryScreenRoute.kt`
     (MODIFIED) — full rewrite. KDoc documents the swap rationale,
     `WhatsNewViewModel` preservation, schema-mismatch handling, and
     cross-references §150 ladder + §179 closing rung.
   - `composeApp/.../App.kt` (MODIFIED) — single-block edit at the
     `composable<Screen.Library>` site.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §180 — Phase 9.x.library.swap` section
     covering strategy, files modified, behavior deltas (intentional),
     `WhatsNewViewModel` preservation rationale, parallel-route
     retirement deferral, cross-references to §150/§179.
   - `SOLID_AUDIT.md` — Phase 9.x.library.swap entry mirroring §138
     audit structure: per-file SOLID 10-point checklist, end-of-slice
     verdict, build gates, layer boundaries, behavior preservation
     (legacy composable retired from the user route; rework composable
     now user-reachable), MVI contract integrity (no surface change),
     strangler-fig integrity (no new reaches), next-candidate
     enumeration.

## Critical files

### Modified

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/navigation/routes/
  LibraryScreenRoute.kt` — full rewrite, ~80 lines.
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/App.kt` — single-
  block call-site edit.

### Untouched (verify by read)

- `LibraryReworkScreenRoute.kt`, `LibraryReworkModule.kt`, rework
  `LibraryScreen` / `LibraryViewModel`, legacy `LibraryScreen` /
  `LibraryViewModel`, `Screen.kt`.

## Reuse

- **Route-adapter shape**: lifted from `LibraryReworkScreenRoute.kt`
  (the §164 Phase 8.y debug adapter). The new adapter is essentially
  the same shape plus the `WhatsNewViewModel` first-launch redirect
  block preserved from the legacy adapter.
- **`WhatsNewViewModel` orchestration**: lifted verbatim from legacy
  `LibraryScreenRoute.kt:80-109` (post-summary line range). Same VM,
  same `shouldShowWhatsNew` + `isLoading` collection, same two
  `LaunchedEffect`s with `hasNavigatedToWhatsNew` flag, same
  `safeNavigate(Screen.WhatsNewScreen(true))` target.
- **Manga URL/API mapping**: lifted from `LibraryReworkScreenRoute.kt
  :62-68` verbatim — `Screen.MangaDetails(mangaUrl = manga.url, api =
  manga.api)`. The rework `Manga` model carries both fields directly.
- **`koinViewModel()` resolution**: lifted from the established route-
  adapter pattern used by all 14 prior swap slices.

## Verification

After the source commit (step 2):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android. Required
  because the adapter file is in `:composeApp/commonMain` and links
  into the Android target.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64. Required
  because `:composeApp/commonMain` links into the iOS framework.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS sim
  arm64. Same rationale.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop. Required
  because `:composeApp/commonMain` is also linked into the Desktop
  entry point.

All four gates required because the adapter file is in
`:composeApp/commonMain` and links into every target.

On-device smoke tests (Windows-impossible, deferred to user's Mac):

1. Fresh install on a fresh device: launch app, complete Welcome →
   Theme → Sources → RepoSettings onboarding, verify landing on
   rework Library (NOT legacy Library).
2. Tap a manga card: verify navigation to rework Manga Details
   (`Screen.MangaDetails`) NOT legacy `LibraryMangaDetails`.
3. Pull-to-refresh: verify refresh state is owned by the rework
   `LibraryViewModel` (the `LibraryEffect.RefreshingLibrary` or
   `state.isRefreshing` field, per §147/§148 wiring).
4. Tap the random button: verify random pick navigates to rework
   Manga Details.
5. Tap the delete button on a card's action row: verify the rework
   in-screen ConfirmRemoveDialog renders (NOT the legacy
   adapter-owned AlertDialog).
6. Re-launch app after a version-code bump: verify the
   `WhatsNewViewModel` first-launch redirect to
   `Screen.WhatsNewScreen(true)` still fires from this adapter.
7. Verify download-progress badge in top bar reflects active downloads
   (§161 wiring).

### Edge cases to mentally model

- **Empty library**: rework Library renders the empty-state composable
  (no toast). Random button is a no-op when empty; refresh button is
  a no-op when empty (rework VM handles both internally).
- **WhatsNew double-fire guard**: `hasNavigatedToWhatsNew` flag in the
  adapter prevents loops if the VM's `shouldShowWhatsNew` flips true →
  false → true within a session (the second-true is gated by the flag).
- **Process death + state restoration**: the rework `LibraryViewModel`
  is bound `viewModel{}` in Koin (ViewModelStore-aware), so
  configuration changes preserve state. `hasNavigatedToWhatsNew`
  is a Compose `remember{}` cell — survives recomposition but not
  process death; on process death the user would see What's New
  again, which is acceptable (matches the legacy behavior).
- **Concurrent download badge update + random nav**: badge state is
  flow-driven (`combine` in `:data`); random nav fires a one-shot
  effect. No race — they're orthogonal state surfaces.

## Deferrals

- **No retirement of legacy `LibraryScreen` / `LibraryViewModel`** —
  stays on disk until the follow-on `Phase 9.x.library.retire` slice
  (mirrors §285 RepoSettings retirement pattern).
- **No retirement of `Screen.LibraryRework` enum case** — same
  follow-on slice.
- **No retirement of `LibraryReworkScreenRoute.kt`** — same follow-on
  slice.
- **No retirement of `RefreshViewModel`** — still bound by Koin, still
  referenced by the legacy `LibraryScreen`. Cleanup happens when the
  legacy screen is retired (follow-on slice).
- **No retirement of `DownloadViewModelv2` cross-tab plumbing** — still
  used by `LibraryMangaScreenRoute` and `MangaDetailsScreenRoute`.
  This slice only drops it from the Library adapter call site.
- **No top-bar Downloads shortcut on rework Library** — legacy adapter
  had one (`onDownloadClick → Screen.DownloadsScreen`). Rework Library
  doesn't surface it today. If user feedback wants this, a future
  additive `Phase 7.x.library.downloadshortcut` slice adds it via
  `LibraryEffect.NavigateToDownloads`. OCP-friendly.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: The route adapter has two jobs — bridge the `Screen.Library`
  nav entry to the rework `:ui` LibraryScreen + host the
  `WhatsNewViewModel` first-launch redirect orchestration. Both are
  route-host-level concerns; neither belongs in `:ui` or `:presentation`.
- **OCP**: The rework `LibraryScreen` composable's signature is closed
  (`(LibraryViewModel, (Manga) -> Unit, Modifier)`). The adapter opens
  the extension surface by passing the nav callback.
- **DIP**: Adapter depends on the rework `LibraryViewModel` via
  `koinViewModel()` (not on a concrete `:data` impl), on
  `WhatsNewViewModel` via `koinViewModel()` (a `:presentation` VM),
  and on `NavController` (Compose nav). No concrete `:data` / `:shared`
  reaches from this file post-swap.
- **Layer boundary**: `:composeApp` only — the route adapter is the
  canonical home for this kind of swap. App.kt edit is also
  `:composeApp/commonMain`.
- **Banned features**: No `!!`, `Any`, `lateinit`, `Thread`.
  `collectAsState`, `LaunchedEffect`, `remember`, `koinViewModel`,
  `safeNavigate` — all Compose-canonical.
- **MVI contract**: No surface change to `LibraryIntent` /
  `LibraryEffect` / `LibraryState`. This is purely a nav-graph
  rewiring + a hosted side-effect block.
- **Strangler-fig**: No new `:data` → `:shared` reaches. The Library
  slice's existing strangler-fig boundary (`:data`
  `LibraryRepositoryImpl` over `:shared` `MangaDao` + `LibraryDeo`)
  is preserved unchanged.
- **Load-bearing fixes preserved**: This slice does NOT touch the Coil
  ImageLoader, the OkHttp interceptor, the Reader's per-request
  listener, the Reader's decoder hints, or any of the prior
  load-bearing image-quality posture. Library card covers continue
  to render via the same Coil pipeline used since §144 / §211. No
  load-bearing risk.
