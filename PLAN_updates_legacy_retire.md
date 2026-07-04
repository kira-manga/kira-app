# Phase 9.aa.updates.legacy_retire — Retire legacy Updates UI files + orphan UiState (Task #310)

## Context

Phase 9.z.dead_vm_retire (§144, Task #309) retired the now-unreachable
`:shared` `NotificationsViewModel` + `TextViewModel` and committed the
docs close-out. The §144 next-candidate enumeration identified the
"stale-KDoc doc-sweep" as the next ≤5-files candidate.

Pre-flight reachability recon for the doc-sweep surfaced a higher-
value candidate: the legacy `composeApp/.../features/notifications/ui/
screens/` subtree contains 2 files (`UpdatesScreen.kt` + `UpdateItem.
kt`) that are themselves unreachable post-§129 (Phase 7.x.updates.swap,
commit `5b44b9c`). Plus the `:shared/.../features/notifications/data/
NotificationsUiState.kt` data class is referenced ONLY by the legacy
`UpdatesScreen.kt` (as a parameter type) — once the legacy screen
goes, the UiState data class is also orphan.

This slice retires the legacy UI subtree + the orphan UiState in a
single sweep. Mirrors §142's `Phase 9.x.onboarding.legacy_retire`
posture (delete unreachable legacy UI files for a feature whose route
was swapped) — same "delete dead UI now that the route-swap is
permanent" rationale.

## Reachability verification

Performed via Grep (post-§144):

### Legacy `UpdatesScreen.kt`

`^import .*notifications\.ui\.screens\.UpdatesScreen` symbol-grep:
NO MATCHES anywhere in the codebase. The legacy screen function
`fun UpdatesScreen(...)` (11-parameter stateless composable taking
`NotificationsUiState` + 10 callbacks) is not imported by any route
adapter, App.kt entry, or other production code.

Cross-platform check (`**/*.{swift,h,m,kts,gradle,xml}`): NO MATCHES.

### Legacy `UpdateItem.kt`

`UpdateItem` symbol-grep across `**/*.kt`:
- `composeApp/.../UpdateItem.kt:43` — own KDoc reference.
- `domain/.../EnqueueDownloadUseCase.kt:26` — KDoc comment text only,
  referencing the legacy file by name as historical context.

`^import .*UpdateItem|fun UpdateItem|@Composable.*UpdateItem`
symbol-grep: NO MATCHES. The `UpdateItem` composable is not
imported by any external file; it's a private helper sibling of
`UpdatesScreen.kt`.

### `NotificationsUiState.kt`

`NotificationsUiState` symbol-grep across `**/*.kt`:
- `shared/.../NotificationsUiState.kt:9` — class declaration itself.
- `composeApp/.../UpdatesScreen.kt:54, 76` — import + parameter type
  in the legacy screen (about to be deleted).
- `shared/.../NotificationRepository.kt:26` — KDoc comment text only.

Once the legacy `UpdatesScreen.kt` is deleted, `NotificationsUiState`
has ZERO code consumers — only the KDoc reference in
`NotificationRepository.kt:26` remains, which becomes a stale comment
acceptable per §142's stale-reference policy.

The rework `:presentation/.../updates/UpdatesState.kt` is a distinct
data class (different package, different fields tuned to the MVI
contract) and is unaffected.

### `NotificationRepository.kt`

`NotificationRepository` is still live — bound by `SharedModule.kt`
and consumed by the rework `:data` `UpdatesRepositoryImpl` strangler-
fig. Out of scope for this slice. (Its retirement would require a
future `Phase 9.bb.notificationrepo_retire` slice that also retires
the underlying `NotificationDao` + `notifications` Room table — much
larger scope.)

## Approach

Pure-retirement sweep. Three files DELETED total:

- **DELETED**: `composeApp/.../features/notifications/ui/screens/
  UpdatesScreen.kt` (legacy stateless `@Composable fun UpdatesScreen
  (uiState, ...10 callbacks)` taking `NotificationsUiState`).
- **DELETED**: `composeApp/.../features/notifications/ui/screens/
  UpdateItem.kt` (private sibling helper composable; the legacy
  screen's per-row item renderer).
- **DELETED**: `shared/.../features/notifications/data/
  NotificationsUiState.kt` (the data class only consumed by the
  legacy screen).

No files MODIFIED — all changes are deletions. The post-deletion
codebase compiles because every consumer of these symbols had
already been rewired to the rework path by §129 + §144.

### Layer surfaces

- **`:core`** — unchanged.
- **`:domain`** — unchanged. KDoc comment in
  `EnqueueDownloadUseCase.kt:26` referencing the retired `UpdateItem`
  by name + KDoc comment in `UpdatesRepository.kt` referencing
  `NotificationRepository` (still-live facade) remain. The
  `UpdateItem` KDoc becomes stale — acceptable per §142's policy.
- **`:data`** — unchanged. The rework `UpdatesRepositoryImpl`
  continues to bridge `NotificationRepository` (which stays bound).
- **`:presentation`** — unchanged. The rework
  `UpdatesViewModel` + `UpdatesState` are distinct classes in the
  `:presentation/.../updates/` package; the retired
  `NotificationsUiState` was in the `:shared/.../notifications/data/`
  package — different module, different name.
- **`:ui`** — unchanged. The rework `:ui/.../updates/UpdatesScreen.kt`
  is a completely separate file in a separate module (`:ui`, not
  `:composeApp`).
- **`:platform`** — unchanged.
- **`:composeApp`** — 2 files DELETED. KDoc comment in
  `UpdatesScreenRoute.kt:17` referencing the retired `Notifications
  ViewModel` (already stale per §144) + KDoc comments in
  `UpdatesReworkModule.kt:22, 34` referencing the same retired VM
  remain as stale comments per §142's policy.
- **`:shared`** — 1 file DELETED (the orphan `NotificationsUiState.
  kt`). KDoc comment in `NotificationRepository.kt:26` referencing
  the retired UiState becomes stale per §142's policy.

### Strangler-fig boundary

This retirement does NOT change the strangler-fig boundary — the
`:shared` `NotificationRepository` remains the cell of truth for the
`notifications` Room table; the rework `:data` `UpdatesRepositoryImpl`
continues to bridge to it; the rework `:presentation` `UpdatesViewModel`
continues to depend on `:domain` `UpdatesRepository`. The retirement
removes 3 leaf files (UI screen + sibling helper + UiState data class)
that no longer participate in the boundary.

## Commit roadmap

Three commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_updates_legacy_retire.md` only (1 file).
2. **Retirement sweep** — 3 files DELETED:
   - `composeApp/.../features/notifications/ui/screens/UpdatesScreen.kt`
   - `composeApp/.../features/notifications/ui/screens/UpdateItem.kt`
   - `shared/.../features/notifications/data/NotificationsUiState.kt`
3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append `## §145 — Phase 9.aa.updates.legacy_retire`.
   - `SOLID_AUDIT.md` — append Phase 9.aa entry.

## Critical files

### Deleted

- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/notifications/ui/screens/UpdatesScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/notifications/ui/screens/UpdateItem.kt`
- `shared/src/commonMain/kotlin/me/manga/yamiapk/presentation/features/notifications/data/NotificationsUiState.kt`

### Modified

- `ARCHITECTURE.md` — append §145 (close-out commit).
- `SOLID_AUDIT.md` — append Phase 9.aa entry (close-out commit).

### Untouched (verified by recon)

- `shared/.../NotificationRepository.kt` — still bound + still
  consumed by the rework `:data` `UpdatesRepositoryImpl` strangler-
  fig. Out of scope.
- `composeApp/.../UpdatesScreenRoute.kt` — already rewired to the
  rework path post-§129; nothing here changes. KDoc references to
  the now-retired classes remain as stale comments per §142 policy.
- `composeApp/.../UpdatesReworkModule.kt` — KDoc references remain
  stale per policy; the binding logic continues to work since it
  only consumes `NotificationRepository` (still live).
- `:ui/.../updates/UpdatesScreen.kt` (rework path) — distinct file
  in a distinct module; unaffected.
- `:presentation/.../updates/UpdatesState.kt` (rework path) —
  distinct file; unaffected.
- `domain/.../EnqueueDownloadUseCase.kt` — KDoc reference to
  `UpdateItem` remains as a stale comment per policy.

## Verification

After the retirement sweep:

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS
  simulator arm64.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (because
  `:shared/commonMain` change links into the Desktop entry point).

If any gate is RED, the retirement uncovered a transitive consumer
the recon missed. Two recovery options:
(a) extend the deletion scope to retire the consumer, or
(b) restore the deleted files (if the consumer is itself a load-
bearing facade), in which case this slice is parked pending a wider
recon. The recon above is thorough — (b) is not expected.

On-device smoke (deferred to user's Mac):
- `Screen.Updates` (bottom-nav tab) — verify the rework
  `UpdatesScreen` renders the identical notifications feed (mark-
  read / mark-all-read / delete / undo / per-row download work
  identically to the post-§144 baseline). Same Room table, same
  rework path; the deletion only affects code that wasn't running.

## Deferrals

- **Stale KDoc doc-sweep** — KDoc comment text in
  `domain/.../EnqueueDownloadUseCase.kt:26` (re: `UpdateItem`) +
  `shared/.../NotificationRepository.kt:26` (re:
  `NotificationsUiState`) + the pre-existing stale references from
  §143/§144 (Onboarding/NotificationsViewModel/TextViewModel) all
  remain stale. A future doc-sweep slice (still queued) can refresh
  them. Acceptable per §142's policy.
- **`NotificationRepository` retirement** — still live; out of
  scope. Would require retiring the underlying `NotificationDao` +
  `notifications` Room table + migrating all the rework `:data`
  `UpdatesRepositoryImpl` queries to a new abstraction. Much larger
  scope; multi-week slice if attempted.
- **Phase 9.x.onboarding.cleanup** — collapse step 3 / step 4
  (cosmetic / structural; no observable behaviour delta beyond
  removing the duplicate Finish-button surface). Deferred.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: This slice has one rule (retire 3 unreachable files in
  one sweep). No new files added, no behaviour changes, no
  abstraction introduced or removed.
- **OCP**: Removing closed-under-modification legacy code doesn't
  affect open/closed posture. The rework path
  (`UpdatesScreenRoute.kt` → `UpdatesViewModel` → `UpdatesState` →
  `:ui/.../UpdatesScreen.kt`) is unchanged.
- **DIP**: No dependency-direction changes. The deleted files were
  all leaf consumers (UI + a data class); their removal doesn't
  affect any inversion.
- **Layer boundary**: changes touch `:composeApp` (2 files deleted)
  + `:shared` (1 file deleted). No `:domain` / `:data` /
  `:presentation` / `:ui` / `:platform` / `:core` reach.
- **Banned features**: N/A — no code added.
- **MVI contract**: N/A — the retired UI files were NOT MVI
  surfaces (they predate the rework's MVI contract; the legacy
  `UpdatesScreen.kt` takes `NotificationsUiState` + 10 plain
  callbacks, not an `Intent`/`State`/`Effect` triple).
- **Strangler-fig**: shrinks `:composeApp/.../notifications/`
  subtree to empty + shrinks `:shared/.../notifications/data/`
  subtree to empty. The remaining `:shared/.../notifications/
  domain/NotificationRepository.kt` continues to participate in
  the boundary.
- **Load-bearing fixes preserved**: this slice does NOT touch the
  Coil ImageLoader, the Reader's per-request listener, the Reader's
  decoder hints, the OkHttp interceptor, or any of the prior load-
  bearing image-quality posture (the retired files have no images).
  No load-bearing risk.

## Visual delta

None. The retired UI files were no-longer-rendered-anywhere post-
§129 — no user sees their output today. The sweep is invisible to
end-users; it only cleans the codebase.
