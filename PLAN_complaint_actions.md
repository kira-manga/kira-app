# Phase 7.x.complaint.actions — reply / edit / delete inline dialogs

## Context

Foundation slice (Phase 7.x.complaint.foundation, Task #251, HEAD `4ec723b`) landed the user-side LIST + search + filter MVI surface with an explicitly-empty `ComplaintEffect` sealed interface designed as the OCP extensibility hook for exactly this slice. The foundation's row-tap is a documented no-op; this slice wires it to a `ComplaintActionDialog` mirroring the legacy 696-line `composeApp/.../complaint/ui/components/ComplaintActionDialog.kt` 3-mode flow (Reply / Edit / Delete) on top of the rework MVI.

Block-and-ask triggers (a-d) NOT met. (a) No contract library — extends an existing rework slice. (b) No observable behaviour change beyond intentional simplifications documented below. (c) No compile risk — pure additive intents/effects/state fields + new use case bindings; existing API is preserved. (d) No SOLID violation — additions are OCP-pure (sealed-interface variant appends, state field appends, repository sibling interface).

## Approach

Three legacy `:shared` use cases (`SendComplaintUseCase`, `UpdateComplaintUseCase`, `DeleteComplaintUseCase`) sit behind a `ComplaintRepository` Firestore facade. They throw on failure (legacy plain-return + `require()` validation: subject not blank, body ≥ 8 chars on `Send`). The strangler-fig `:data` impl wraps all three in `runCatching {}` to surface failures as `Result.failure` — same posture as Phase 7.x.complaint.foundation's `ComplaintListRepositoryImpl`.

### Domain surface

New sibling interface `ComplaintActionRepository` (alongside the existing `ComplaintListRepository`) — same ISP rationale: READ and WRITE are separate concerns; a fatter interface would force readers to depend on write methods they never call.

```kotlin
interface ComplaintActionRepository {
    suspend fun replyToComplaint(parent: ComplaintSummary, body: String): Result<Unit>
    suspend fun editComplaint(original: ComplaintSummary, subject: String, body: String): Result<Unit>
    suspend fun deleteComplaint(id: String): Result<Unit>
}
```

The reply takes the **parent summary** rather than just the id because the legacy `SendComplaintUseCase` requires a full `Complaint` (the wire-side type). Constructing one needs `userId`, `type`, `subject`, and the new `body`. Passing the parent summary lets the impl extract these without re-fetching. Same logic for `editComplaint` — the impl needs the full record to reconstruct a legacy `Complaint` for `updateComplaint`.

Three thin pass-through use cases (`ReplyToComplaintUseCase`, `EditComplaintUseCase`, `DeleteComplaintUseCase`). Named matching legacy where possible; the rework's `DeleteComplaintUseCase` lives in `:domain.usecase.complaint` (vs. legacy's `presentation.features.complaint.usecase`) — different package, no clash.

### MVI extensions (additive)

**Intent** — 6 new variants on top of the foundation's 4:
- `OnRowClick(summary: ComplaintSummary)` — opens the dialog at the menu mode
- `OnDismissActionDialog` — closes entirely
- `OnSelectAction(mode: ActionDialogMode)` — switches dialog sub-mode (MENU / REPLY / EDIT / DELETE)
- `OnSubmitReply(body: String)`
- `OnSubmitEdit(subject: String, body: String)`
- `OnConfirmDelete`

**Effect** — 2 new variants (foundation was empty by design):
- `ShowSuccessMessage(message: String)` — drives a Snackbar in `:ui`
- `ShowErrorMessage(message: String)` — drives a Snackbar in `:ui`

**State** — 3 new fields + 1 new enum:
- `actionDialogMode: ActionDialogMode = ActionDialogMode.NONE`
- `activeComplaint: ComplaintSummary? = null` — the row the user tapped
- `isSubmittingAction: Boolean = false` — disables submit buttons during in-flight calls
- New `enum class ActionDialogMode { NONE, MENU, REPLY, EDIT, DELETE }` declared in same file as `ComplaintState` (sibling pattern).

**ViewModel** — constructor expands from 1 to 4 args (observeUserComplaints + 3 action use cases). Each action handler:
1. Sets `isSubmittingAction = true`.
2. Calls the use case (suspend) on `viewModelScope`.
3. On success → emit `ShowSuccessMessage`, close dialog, refire `loadList()` so the list reflects the mutation.
4. On failure → emit `ShowErrorMessage`, clear `isSubmittingAction`, keep dialog open at current mode.

### UI extensions

New file `ui/.../complaint/ComplaintActionDialog.kt` — Dialog containing 4 inner content composables:
- `ActionMenuContent` — shows the 3 affordance buttons + a complaint preview card. Edit/Delete are hidden when `activeComplaint.status == ComplaintStatus.PINNED` (matches legacy).
- `ReplyContent` — `OutlinedTextField` with 500-char cap + send button.
- `EditContent` — two `OutlinedTextField`s (subject + body) with 1000-char cap + save button.
- `DeleteConfirmationContent` — warning card + preview + Delete-forever button.

`ComplaintScreen` modifications:
- Wire row click in `ComplaintRow` to dispatch `OnRowClick(summary)`.
- Mount `ComplaintActionDialog` when `state.actionDialogMode != ActionDialogMode.NONE && state.activeComplaint != null`.
- Wrap content in a `Scaffold` with `SnackbarHostState` + `LaunchedEffect(Unit) { vm.effects.collect { ... } }` to observe new effects.

### Koin wiring

Single module file (`ComplaintReworkModule.kt`) is modified in place to:
- Add `single<ComplaintActionRepository> { ComplaintActionRepositoryImpl(send=get(), update=get(), delete=get()) }`
- Add 3 `factory` bindings for the new use cases
- Update `viewModel { ComplaintViewModel(get(), get(), get(), get()) }` to pass all 4 deps

Legacy `SendComplaintUseCase` / `UpdateComplaintUseCase` / `DeleteComplaintUseCase` are bound by `SharedModule` (same module that binds `GetUserComplaintUseCase` consumed by the foundation). No new platform-module changes; no new :shared dependency wiring.

### Reply-metadata simplification

The legacy reply preserves `parent.metadata + mapOf("replyto" to parent.id)`. The rework reply emits ONLY `mapOf("replyto" to parent.id)`. Rationale:
1. `Map<String, Any>` is banned at the `:domain` boundary (`Any` in contract §6). `ComplaintSummary` deliberately drops the metadata field per the foundation slice's documented decision.
2. The metadata in the parent represents the moment of the parent's submission (device info, app version at THAT time). Inheriting it onto a reply submitted weeks later misattributes the reply's device/version context.
3. Admin diagnostics can still correlate via the `replyto` key — the parent record still has its own metadata.

This is a small observable behaviour change documented in the close-out.

### Subject preservation on reply

Legacy reply copies `parent.subject` verbatim to the reply (which becomes the new complaint's `subject`). Rework preserves the same posture — reply has the SAME subject as the parent so the admin's threaded view groups them naturally.

### Type preservation on reply

Legacy reply copies `parent.type` verbatim. Rework preserves.

### userId on reply

Legacy reply copies `parent.userId` verbatim. Since the user-side rework only operates on the current user's own complaints, the reply is correctly attributed. (If a future admin-side rework reuses this surface, that slice will need to address the attribution semantics — out of scope here.)

## Commit roadmap

Seven commits, all ≤5 files per the standing cap. Build gates after every source commit (Android + iOS Arm64 + iOS SimulatorArm64; Desktop for slices touching common/desktop code).

1. **Plan** — `PLAN_complaint_actions.md` only (1 file).

2. **`:domain`** — 4 new files:
   - `domain/.../repository/ComplaintActionRepository.kt`
   - `domain/.../usecase/complaint/ReplyToComplaintUseCase.kt`
   - `domain/.../usecase/complaint/EditComplaintUseCase.kt`
   - `domain/.../usecase/complaint/DeleteComplaintUseCase.kt`

3. **`:data`** — 1 new file:
   - `data/.../repository/ComplaintActionRepositoryImpl.kt` — strangler-fig over 3 legacy use cases + reverse legacy-status / legacy-type mappers (inverse of foundation's forward mappers).

4. **`:presentation`** — 4 modified files:
   - `presentation/.../complaint/ComplaintState.kt` (append `ActionDialogMode` enum + 3 state fields)
   - `presentation/.../complaint/ComplaintIntent.kt` (append 6 variants)
   - `presentation/.../complaint/ComplaintEffect.kt` (append 2 variants)
   - `presentation/.../complaint/ComplaintViewModel.kt` (3 new ctor deps, handle 6 new intents, emit effects)

5. **`:ui`** — 1 new file + 1 modified file:
   - `ui/.../complaint/ComplaintActionDialog.kt` (NEW)
   - `ui/.../complaint/ComplaintScreen.kt` (MODIFIED — wire row click, mount dialog, snackbar host + effect collector)

6. **`:composeApp` Koin** — 1 modified file:
   - `composeApp/.../di/ComplaintReworkModule.kt` (append action-repo + 3 use case bindings; update VM binding to 4-arg)

7. **Close-out** — 2 modified files:
   - `ARCHITECTURE.md` — new `§95 — Phase 7.x.complaint.actions`
   - `SOLID_AUDIT.md` — new `Phase 7.x.complaint.actions` entry

## Critical files

### New
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/ComplaintActionRepository.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/ReplyToComplaintUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/EditComplaintUseCase.kt`
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/DeleteComplaintUseCase.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/ComplaintActionRepositoryImpl.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/ComplaintActionDialog.kt`
- `PLAN_complaint_actions.md`

### Modified
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintEffect.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/ComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/ComplaintScreen.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ComplaintReworkModule.kt`
- `ARCHITECTURE.md`
- `SOLID_AUDIT.md`

### Untouched
- `shared/.../complaint/repository/ComplaintRepository.kt` — legacy facade; strangler-fig delegates to it via the 3 legacy use cases.
- `shared/.../complaint/usecase/{Send,Update,Delete}ComplaintUseCase.kt` — legacy use cases; constructor-injected into the rework `:data` impl.
- `composeApp/.../complaint/ui/components/ComplaintActionDialog.kt` — legacy dialog; preserved for legacy route. Phase 9.x route-swap retires it.
- `composeApp/.../navigation/Screen.kt` / `App.kt` — no nav changes; `Screen.ComplaintRework` already exists from foundation.
- `composeApp/.../navigation/routes/ComplaintReworkScreenRoute.kt` — no signature change; same `koinViewModel()` resolution.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: every new file = one rule (1 interface, 3 use cases, 1 impl, 1 dialog composable; 4 modified files extend one rule each).
- **OCP**: new repo is a sibling (closed-but-open extension of the slice's surface). MVI variants are appended; existing variants unchanged. Repository methods are append-only on the new interface.
- **LSP**: action use cases are thin pass-throughs; `Result<Unit>` semantics consistent across all 3.
- **ISP**: `ComplaintActionRepository` is WRITE-only — readers don't depend on it; the existing `ComplaintListRepository` is READ-only — writers don't depend on it. Two interfaces, two responsibilities.
- **DIP**: `:presentation` depends on the 3 use case classes from `:domain`, not on the `:data` impl. The impl is bound in Koin at composition.
- **Banned types**: no `!!`, no `Any`, no `lateinit`, no `Thread`. The `metadata` field of legacy `Complaint` (the only `Map<String, Any>` in this stack) is constructed inside the `:data` impl using `mapOf("replyto" to parent.id)` — the value side is a `String`, so the legacy type is satisfied without exposing `Any` at the `:domain` boundary.

## Verification

After every source commit (steps 2-6):
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android.
- `gradlew.bat :composeApp:compileKotlinIosArm64` + `:composeApp:compileKotlinIosSimulatorArm64` — iOS (commonMain changes link into the framework).
- `gradlew.bat :composeApp:compileKotlinDesktop` — Desktop (`:ui` changes link into the Desktop entry point).

Windows-impossible smoke tests (deferred to user's Mac):
- Tap a complaint row → menu dialog opens at MENU mode.
- Reply → typing < 8 chars + submitting surfaces an error snackbar (legacy `require()` validation throws → `Result.failure`).
- Edit → save updates Firestore; list reloads with the edited subject/body.
- Delete → confirmation → row disappears from the list after reload.
- PINNED complaint shows only Reply (no Edit/Delete) in the menu — matches legacy.

## Deferrals

- **No admin-side rework** — the legacy admin route `ComplaintAdmin` is a separate flow with its own dialog wiring (Update-status, Custom-closure-reason, etc.). Not part of this user-side slice.
- **No metadata inheritance on reply** — see §"Reply-metadata simplification" above.
- **No pull-to-refresh** — the `OnRetry` intent handles the manual reload case; pull-to-refresh would be a future polish slice.
- **No optimistic UI updates** — the action submits, then `loadList()` refetches. Net effect is a brief loading flicker. Optimistic updates (locally remove the deleted row, locally update the edited row) would be a future polish.
- **No nav-route swap** — legacy `Screen.Complaint` stays bound to the legacy route. Phase 9.x retires it.
- **No load-bearing image-quality fixes touched** — Complaint actions are pure text mutations. No risk.
