# Phase 7.x.complaint.admin.actions — admin mutations slice

## Context

The foundation slice (§101, commits `8896e49`..`c38fc7a`) landed the
admin Complaint dashboard rework: load + display + search + 2-axis filter.
The next-candidate block in §101.9 / SOLID_AUDIT recommended this slice
as the natural continuation, mirroring the user-side foundation → actions
slice progression (§94 → §95).

The foundation slice deferred 6 admin mutations + statistics + sort +
app-version filter + long-press body-copy. This slice ports the three
**per-row admin mutations** that are the legacy admin's primary daily
workflow:

1. **Status change** — the quintessential admin action. Legacy
   `AdminComplaintViewModel.updateComplaintStatus` (lines 65-98).
2. **Delete** — parity with the legacy admin `DeleteConfirmationDialog`.
   Legacy `AdminComplaintViewModel.deleteComplaint` (lines 195-224).
3. **Closure reason** — admin-specific specialization of "set status to
   CLOSED + provide reason". Legacy `AdminComplaintViewModel.addClosureReason`
   (lines 139-190) + `ClosureReasonDialog` in `StatusChangeDialog.kt`
   lines 351-512.

Deferred to future slices:
- **Edit** — admin edit of someone else's complaint subject/body. Least
  common admin action, conceptually overlaps with user-side edit. Defer.
- **Bulk update / bulk delete** — multi-select infrastructure not yet
  present in the rework foundation. Separate `admin.bulk` slice.
- **Statistics aggregation card** — pure aggregation display.
  `admin.statistics` slice.
- **Sort dropdown (7 modes)** + **app-version filter** — also separate.
- **Long-press body-copy** — pure UI affordance, `admin.copy` slice.

## Approach

Mirror the user-side `Phase 7.x.complaint.actions` slice (§95) shape:
- Sibling `:domain` repository (`AdminComplaintActionRepository`) — NOT
  an extension of the user-side `ComplaintActionRepository`. Predicted
  in the foundation slice's KDoc (cross-slice OCP §6 anticipation). Same
  posture as the foundation's `AdminComplaintListRepository` sibling.
- Three use cases pass-through-style:
  - `ChangeComplaintStatusUseCase(complaint, newStatus) → Result<Unit>`
  - `AddClosureReasonUseCase(complaint, reason) → Result<Unit>`
  - `AdminDeleteComplaintUseCase(id) → Result<Unit>`
- `:data` impl strangler-fig over legacy `:shared` `UpdateComplaintUseCase`
  + `DeleteComplaintUseCase`. Closure-reason auto-CLOSE logic ported
  from legacy `addClosureReason` (lines 156-163).
- `:presentation` MVI surface extends foundation:
  - State: append `actionDialogMode`, `activeComplaint`, `isSubmittingAction`,
    `pendingNewStatus` fields.
  - Intent: append 8 new variants (`OnRowClick`, `OnDismissActionDialog`,
    `OnSelectAction(mode)`, `OnSelectStatus(status)`, `OnConfirmStatusChange`,
    `OnSubmitClosureReason(reason)`, `OnConfirmDelete`).
  - Effect: append `ShowSuccessMessage(text)`, `ShowErrorMessage(text)`.
  - VM: inject 3 new use cases; handle 8 new intents via the same
    `completeAction()` shape as user-side `ComplaintViewModel`.
- `:ui`:
  - NEW `AdminComplaintActionDialog.kt` — single composable, 4 sub-modes
    (Menu / Status / ClosureReason / Delete) like user-side
    `ComplaintActionDialog`'s 4 sub-modes (Menu / Reply / Edit / Delete).
  - MOD `AdminComplaintScreen.kt` — mount dialog when
    `actionDialogMode != NONE`; wire row tap to `OnRowClick`;
    `LaunchedEffect` collects `effects` and shows snackbars.
- `:composeApp` Koin wiring — extend `complaintAdminReworkModule` with
  3 new repo/use case/VM-arg bindings.

### Sibling repository ISP-clean split

The foundation's `AdminComplaintListRepository` is a SIBLING of
`ComplaintListRepository`. This slice's `AdminComplaintActionRepository`
is the WRITE counterpart, sibling to the user-side
`ComplaintActionRepository`. Two reasons:

1. **Different write scope semantics**: user-side WRITES are "act on MY
   complaint" (auth-gated by Firestore rules to the caller's userId).
   Admin WRITES are "act on ANY complaint" (admin-gated client-side).
2. **Different mutation methods**: user-side has reply/edit/delete; admin
   has status-change/closure-reason/delete. Only delete overlaps and the
   legacy `:shared` `DeleteComplaintUseCase` doesn't care who calls it.

Per ISP §6, fattening either interface to include the other's methods
would force consumers to depend on methods they never call. Two clean
siblings.

### Why no `Edit` here

Edit (changing someone else's subject/body) is the least-common admin
action and conceptually overlaps with the user-side edit. The 3-action
size matches user-side §95's 3 actions exactly. Edit is a future
`admin.edit` slice — sealed `AdminComplaintIntent` accepts a future
`OnSubmitEdit(subject, body)` variant via OCP §6 extension.

### Status-change dialog input

User-side status-change is FORBIDDEN (the user can't change their own
complaint's status). Admin status-change is the primary workflow. The
dialog needs:
- Current status display.
- 8 radio buttons for new status (matching `ComplaintStatus.entries`).
- Update button enabled only when `selectedStatus != current.status`.

`pendingNewStatus` field in state tracks the radio selection (state-of-
intent before the user confirms). On confirm, the VM submits via
`ChangeComplaintStatusUseCase` and reloads on success.

### Closure-reason dialog input + auto-close

Legacy `addClosureReason` (lines 156-163) auto-changes status to CLOSED
if current is OPEN or IN_PROGRESS, otherwise preserves status. The
rework's `AddClosureReasonUseCase` replicates this:
- If `original.status in (OPEN, IN_PROGRESS)`: write status = CLOSED.
- Else: preserve `original.status`.
- Store reason in metadata under `reason` key + `reasonAddedBy` (admin
  user id) + `reasonAddedAt` (epoch ms).

The legacy stores these as `Map<String, Any>` metadata. The rework's
domain interface accepts a `String` reason; the `:data` impl
constructs the metadata map at the boundary (banned `Any` does NOT leak
into `:domain` — it stays inside the strangler-fig adapter, same
posture as `ComplaintActionRepositoryImpl`'s reply metadata
construction).

### `completeAction` helper reuse pattern

Lift the helper shape from user-side `ComplaintViewModel.completeAction`
(lines 190-208) — the rework MVI convention for "after a write,
dismiss dialog + emit success snackbar + refire load, OR keep dialog
open + emit error snackbar". The admin VM uses the identical shape with
admin-specific success messages.

### Refire `loadList()` on success

Same rationale as user-side. The legacy admin updates local state
imperatively (e.g., `updatedList = currentState.data.map { ... }`); the
rework refires `loadList()` for a single source of truth. Tens-low-
hundreds of complaints, one Firestore re-fetch is negligible.

### Snackbar via `effects` channel

Same posture as user-side actions slice — `AdminComplaintEffect` gains
`ShowSuccessMessage(text)` + `ShowErrorMessage(text)` variants; the
`:ui` screen wires a `SnackbarHost` and collects effects in a
`LaunchedEffect`.

## Commit roadmap

Seven commits, all ≤5 files per the standing cap.

1. **Plan commit** — `PLAN_complaint_admin_actions.md` only (1 file).

2. **`:domain` foundation** — 4 files, all new:
   - `domain/.../repository/AdminComplaintActionRepository.kt` — sibling
     of `ComplaintActionRepository`; 3 methods (`changeStatus`,
     `addClosureReason`, `deleteComplaint`).
   - `domain/.../usecase/complaint/ChangeComplaintStatusUseCase.kt`.
   - `domain/.../usecase/complaint/AddClosureReasonUseCase.kt`.
   - `domain/.../usecase/complaint/AdminDeleteComplaintUseCase.kt`.

3. **`:data` strangler-fig impl** — 1 file, new:
   - `data/.../repository/AdminComplaintActionRepositoryImpl.kt` —
     ctor takes `:shared` `LegacyUpdateComplaintUseCase` +
     `LegacyDeleteComplaintUseCase` + `LegacyUserIdProvider` (for the
     `reasonAddedBy` metadata field on closure-reason).

4. **`:presentation` MVI extension** — 4 files, all modified:
   - `presentation/.../complaint/admin/AdminComplaintState.kt` — add
     `actionDialogMode`, `activeComplaint`, `isSubmittingAction`,
     `pendingNewStatus`; add `AdminActionDialogMode` enum (NONE, MENU,
     STATUS, CLOSURE_REASON, DELETE).
   - `presentation/.../complaint/admin/AdminComplaintIntent.kt` —
     append 7 new variants: `OnRowClick(complaint)`,
     `OnDismissActionDialog`, `OnSelectAction(mode)`,
     `OnSelectStatus(status)`, `OnConfirmStatusChange`,
     `OnSubmitClosureReason(reason)`, `OnConfirmDelete`.
   - `presentation/.../complaint/admin/AdminComplaintEffect.kt` —
     append `ShowSuccessMessage(text)` + `ShowErrorMessage(text)` (drop
     the "empty sealed interface" today).
   - `presentation/.../complaint/admin/AdminComplaintViewModel.kt` —
     inject 3 new use cases; extend `handle(intent)` with 7 new
     branches; add `completeAction()` helper (lifted from user-side
     shape).

5. **`:ui` action dialog + screen mount** — 2 files:
   - `ui/.../complaint/admin/AdminComplaintActionDialog.kt` (NEW) —
     internal composable with 4 sub-mode branches (Menu / Status /
     ClosureReason / Delete).
   - `ui/.../complaint/admin/AdminComplaintScreen.kt` (MOD) — wire row
     tap to `OnRowClick`; mount dialog when
     `state.actionDialogMode != NONE && state.activeComplaint != null`;
     add `SnackbarHost` + `LaunchedEffect` collecting `viewModel.effects`.

6. **`:composeApp` Koin wiring** — 1 file, modified:
   - `composeApp/.../di/ComplaintAdminReworkModule.kt` — add
     `single<AdminComplaintActionRepository> { AdminComplaintAction
     RepositoryImpl(...) }`; add `factory` for the 3 use cases; extend
     `viewModel { AdminComplaintViewModel(..., changeStatus = get(),
     addClosureReason = get(), adminDelete = get()) }`.

7. **Close-out** — 2 files modified:
   - `ARCHITECTURE.md` — append §102 covering strategy, layer surfaces,
     MVI extension shape, strangler-fig boundary, files added/modified,
     deferrals.
   - `SOLID_AUDIT.md` — per-file SOLID 10-point checklist for all 13
     touched files + end-of-slice verdict + build gates + next-
     candidate block.

## Critical files

### New

- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/AdminComplaintActionRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/ChangeComplaintStatusUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/AddClosureReasonUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/AdminDeleteComplaintUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AdminComplaintActionRepositoryImpl.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintActionDialog.kt`
- `PLAN_complaint_admin_actions.md`

### Modified

- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ComplaintAdminReworkModule.kt`
- `ARCHITECTURE.md`
- `SOLID_AUDIT.md`

### Untouched (verify by read, not modified)

- `shared/.../UpdateComplaintUseCase.kt` — legacy facade; the strangler-
  fig delegates to its `invoke(complaint)` but does not modify it.
- `shared/.../DeleteComplaintUseCase.kt` — legacy facade; delegate-only.
- `composeApp/.../admin/complaint/AdminComplaintScreen.kt` — legacy
  admin screen with the 4 dialog mounts; preserved for the legacy route.
- `composeApp/.../admin/complaint/StatusChangeDialog.kt` — legacy
  dialogs; visual reference only, not modified.
- `domain/.../repository/ComplaintActionRepository.kt` — user-side write
  surface; not modified (sibling repo, not extension).

## Reuse

- **Sibling repository pattern**: from `AdminComplaintListRepository`
  (foundation slice) — same ISP §6 rationale.
- **`completeAction` shape**: from user-side `ComplaintViewModel` —
  identical post-result handler (dismiss + success + refire OR keep open
  + error).
- **`Legacy*` import aliases**: from `ComplaintActionRepositoryImpl` —
  prevent ambiguity with `:domain`-side same-name types.
- **Dialog composable shape**: from `ComplaintActionDialog` — single
  composable with internal sub-mode branching, status-gated affordances,
  validation parity, icon-free posture.
- **`SnackbarHost` + `LaunchedEffect` pattern**: from user-side
  `ComplaintScreen` actions slice.

## Verification

After every source commit (steps 2-6):

- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile
  gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS sim.
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (slice
  touches `:ui` and `:composeApp` commonMain).

On-device smoke tests (Windows-impossible, deferred to user's Mac):

- Login as admin, navigate to Settings → Feedback → admin dashboard.
- Tap a row → action menu appears (Status / ClosureReason / Delete).
- Status change: select new status, tap Update → snackbar "Status
  updated", list refreshes, row reflects new status.
- Closure reason: enter reason, tap Add → snackbar "Closure reason
  added", row reflects status=CLOSED (if was OPEN/IN_PROGRESS).
- Delete: tap Delete forever → snackbar "Complaint deleted", row gone.

Edge cases:

- In-flight double-tap on Update / Add / Delete: `isSubmittingAction`
  guard short-circuits at the VM (defence-in-depth vs intent channel
  re-entry).
- Dialog dismiss during in-flight: `onDismissRequest` no-op when
  `isSubmittingAction = true` (same posture as user-side).
- Status change to same status: Update button disabled (no-op).
- Closure reason with blank text: Add button disabled.
- Closure reason on an already CLOSED/RESOLVED/PINNED complaint:
  status preserved (no auto-CLOSE), reason stored in metadata only.

## Deferrals

- **Edit action**: deferred to a future `admin.edit` slice. Sealed
  `AdminComplaintIntent` accepts `OnSubmitEdit(subject, body)` later.
- **Bulk update / bulk delete**: deferred to `admin.bulk` (depends on
  multi-select infra).
- **Statistics aggregation card**: deferred to `admin.statistics`.
- **Sort dropdown + app-version filter**: deferred separately.
- **Long-press body-copy**: deferred to `admin.copy`.
- **i18n lift**: inline literals — Phase 10 lifts both legacy and
  rework consumers in one pass.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each new file has one rule (one interface, one use case, one
  impl, one MVI surface part, one composable).
- **OCP**: sealed `AdminComplaintIntent` accepts new variants without
  breaking the foundation's 5. Closed under modification, open under
  extension.
- **LSP**: `AdminComplaintActionRepositoryImpl` is fully substitutable
  for `AdminComplaintActionRepository`; no narrowed contracts.
- **ISP**: 3 admin-only methods. Sibling of user-side write repo, not
  extension. The user-side `:presentation` doesn't depend on admin
  methods; admin `:presentation` doesn't depend on user-side methods.
- **DIP**: `:presentation` depends on `:domain` use cases /
  repositories, never on `:data` impls. `:data` strangler-fig depends
  on `:shared` legacy use cases — same posture as foundation.
- **Banned features**: no `!!`, `Any` in `:domain` / `:presentation`;
  no `lateinit`; no `Thread`. `Any` appears ONLY inside `:data`
  strangler-fig where legacy `metadata: Map<String, Any>?` is bridged
  (same posture as `ComplaintActionRepositoryImpl`).
- **MVI contract**: extends foundation. New variants on State, Intent,
  Effect. `when (intent)` stays exhaustive — adding a future
  `OnSubmitEdit` is a compile-time error here, exactly the OCP §6
  enforcement we want.
- **Strangler-fig**: ONE additional `:data` → `:shared` reach (the
  Update + Delete + UserIdProvider constructor injection). Same
  posture, same boundary.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, AVIF decoder, HighQualitySkiaImageDecoder, OkHttp
  fetcher, or any `:platform` actual. Admin actions are pure
  Firestore-bound text mutations.
