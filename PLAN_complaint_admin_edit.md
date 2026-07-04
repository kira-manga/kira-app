# Phase 7.x.complaint.admin.edit — admin edit-complaint slice

## Context

Phase 7.x.complaint.admin.actions (Task #259) shipped the admin mutations
triad MINUS edit: status-change, closure-reason, delete. The admin repo
KDoc (`AdminComplaintActionRepository.kt:69-72`) explicitly predicted this
follow-on slice: "edit is deferred to a future Phase 7.x.complaint.admin.edit
slice. A future `editComplaint(original, subject, body)` method slots in
via OCP §6 extension when that slice lands." The user-side EDIT flow
already lives at §95 (Phase 7.x.complaint.actions, Task #252).

This slice extends the admin mutations sealed surface with the fourth
action — admin can now mutate any user's complaint title/body. It is the
symmetric admin counterpart of user-side §95 edit.

Block-and-ask triggers (a)-(d) all NOT met:
- (a) No contract library blocker — the foundation classes/files are in
  place; we extend, not invent.
- (b) Observable behaviour change is **additive** (a new admin
  affordance), not a regression. The legacy admin route continues to
  expose its own edit dialog; the rework route adds it via the EDIT mode
  on the existing AdminComplaintActionDialog.
- (c) No compile risk — sealed-interface extension is OCP-clean.
- (d) No SOLID violation forced; sibling-repo extension preserves ISP.

## Approach

Mirror the user-side `EditComplaintUseCase` shape onto the admin sibling
repository. Key difference from user-side: **admin-side edit preserves
the legacy `metadata` field** (closure-reason / reasonAddedBy /
reasonAddedAt) across the edit, by re-fetching the legacy `Complaint` via
the already-injected `LegacyGetAllComplaintUseCase` (same pattern the
existing `changeStatus` / `addClosureReason` methods use).

User-side `editComplaint` writes `metadata = null` because the user
doesn't have closure metadata to preserve; the admin write pattern is
distinctly different and reuses the `fetchLegacyById(id)` helper that
already exists on `AdminComplaintActionRepositoryImpl`.

The `:presentation` MVI surface gains:
- `AdminComplaintIntent.OnSubmitEdit(subject: String, body: String)` —
  fires the new use case.
- `AdminActionDialogMode.EDIT` — fifth enum variant; the dialog renders
  an Edit content composable when active.
- VM handler `handleSubmitEdit(subject, body)` — exact shape of
  `handleSubmitClosureReason`, calls `editComplaint` use case + reuses
  the existing `completeAction()` post-mutation flow.

The `:ui` dialog gains:
- A new `EDIT` branch in the outer `when (mode)`.
- A new `EditContent` private composable (subject + body OutlinedTextFields,
  ≤1000 char body cap, validation parity with user-side).
- A new "Edit" affordance in `ActionSelectionContent` — placed between
  "Change status" and "Add closure reason" (status-action ordering).

The `:composeApp/.../di/ComplaintAdminReworkModule.kt` gains:
- A new `factory { AdminEditComplaintUseCase(get()) }` entry.
- An extended VM constructor binding to thread the new use case dep.

### Naming convention

User-side: `EditComplaintUseCase`. Admin-side: **`AdminEditComplaintUseCase`**
— matches the existing `AdminDeleteComplaintUseCase` naming pattern
(Admin prefix when an analogously-named use case already exists in
another scope).

### Strangler-fig boundary

No new `:data` → `:shared` reach. The existing `LegacyGetAllComplaintUseCase`
+ `LegacyUpdateComplaintUseCase` deps already on
`AdminComplaintActionRepositoryImpl` cover the new method — admin edit is
fundamentally "re-fetch full legacy, mutate subject/body, send back through
legacy update". Zero new dependency edges; only one new method.

### Metadata-preservation rationale

User-side edit (`ComplaintActionRepositoryImpl.editComplaint` lines 98-114)
sets `metadata = null` — but the admin route MUST preserve metadata to
keep the closure-reason audit trail (reason / reasonAddedBy / reasonAddedAt)
intact across the edit. Otherwise editing a CLOSED complaint would erase
the audit fields that the legacy admin VM (lines 139-190) goes out of its
way to preserve.

The `fetchLegacyById(id)` helper already on `AdminComplaintActionRepositoryImpl`
(line 113-115) does exactly this — fetch the full legacy `Complaint`,
mutate, write back. Reuse it verbatim.

## Commit roadmap

Six commits, ≤5 files per cap. Build gates after every source commit
(Android + iOS Arm64 + iOS SimulatorArm64).

1. **Plan commit** — `PLAN_complaint_admin_edit.md` only (1 file).

2. **`:domain` extension** — 2 files:
   - `domain/.../repository/AdminComplaintActionRepository.kt` (MODIFIED) —
     add `editComplaint(original, subject, body): Result<Unit>` method.
     Update the KDoc: remove the "No edit method here" deferral block and
     replace with a metadata-preservation note. KDoc keeps the ISP §6
     sibling-vs-extension paragraph.
   - `domain/.../usecase/complaint/AdminEditComplaintUseCase.kt` (NEW) —
     thin pass-through. Three params (original, subject, body), one call
     to `repository.editComplaint(...)`. Mirrors user-side
     `EditComplaintUseCase`'s shape verbatim.

3. **`:data` impl** — 1 file:
   - `data/.../repository/AdminComplaintActionRepositoryImpl.kt` (MODIFIED) —
     add `editComplaint(original, subject, body)` override using
     `fetchLegacyById` + `legacyUpdate(legacy.copy(subject = ..., body = ...))`.
     Metadata field passes through unchanged (legacy.copy default behaviour).

4. **`:presentation` MVI + Koin** — 4 files:
   - `presentation/.../complaint/admin/AdminComplaintIntent.kt` (MODIFIED) —
     add `data class OnSubmitEdit(val subject: String, val body: String) : AdminComplaintIntent`.
   - `presentation/.../complaint/admin/AdminComplaintState.kt` (MODIFIED) —
     add `EDIT` variant to `AdminActionDialogMode` enum. Update its KDoc
     to list the new variant.
   - `presentation/.../complaint/admin/AdminComplaintViewModel.kt` (MODIFIED) —
     add `private val adminEditComplaint: AdminEditComplaintUseCase` ctor
     param. Add `is OnSubmitEdit -> handleSubmitEdit(intent.subject, intent.body)`
     branch in `when (intent)`. Add `private fun handleSubmitEdit` —
     same shape as `handleSubmitClosureReason`, calls `adminEditComplaint(target, subject, body)`
     + `completeAction(result, successMessage = "Complaint updated")`.
   - `composeApp/.../di/ComplaintAdminReworkModule.kt` (MODIFIED) — add
     `factory { AdminEditComplaintUseCase(get()) }`. Update the
     `viewModel { ... }` block to thread the new use case (one extra `get()`).

5. **`:ui` dialog** — 1 file:
   - `ui/.../complaint/admin/AdminComplaintActionDialog.kt` (MODIFIED) —
     1. Add `AdminActionDialogMode.EDIT -> EditContent(...)` branch in
        outer `when (mode)`.
     2. Add `OutlinedButton` "Edit" affordance in `ActionSelectionContent`
        between "Change status" and "Add closure reason". OutlinedButton
        (matches existing status-action ordering).
     3. Add `private fun EditContent(complaint, isSubmitting, onIntent)`
        composable — same shape as user-side `EditContent` (subject +
        body OutlinedTextFields, ≤1000 char body cap, Save/Cancel buttons,
        `rememberSaveable(complaint.id)` for the form fields).
     4. Update outer KDoc — remove "No Edit affordance (vs user-side)"
        paragraph; replace with a note that admin edit is now available
        and preserves metadata across edit.

6. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — new `## §103 — Phase 7.x.complaint.admin.edit`
     with subsections covering strategy, metadata-preservation
     rationale, files added/modified, build gates, deferrals.
   - `SOLID_AUDIT.md` — Phase 7.x.complaint.admin.edit entry with per-
     file SOLID 10-point checklists, end-of-slice verdict, build
     gates, next-candidate block.

## Critical files

### New
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/usecase/complaint/AdminEditComplaintUseCase.kt`
- `PLAN_complaint_admin_edit.md`

### Modified
- `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/AdminComplaintActionRepository.kt`
- `data/src/commonMain/kotlin/me/manga/yamiapk/data/repository/AdminComplaintActionRepositoryImpl.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintState.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `composeApp/src/commonMain/kotlin/me/manga/yamiapk/di/ComplaintAdminReworkModule.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintActionDialog.kt`
- `ARCHITECTURE.md` (append §103)
- `SOLID_AUDIT.md` (append Phase 7.x.complaint.admin.edit)

### Untouched
- `shared/.../UpdateComplaintUseCase.kt` — legacy facade; the strangler-fig
  reuses it through the already-injected `legacyUpdate` field, no changes.
- `presentation/.../complaint/AdminComplaintEffect.kt` — reuses the
  existing `ShowSuccessMessage` / `ShowErrorMessage` effects; no new
  variants.
- `composeApp/.../navigation/routes/AdminComplaintReworkScreenRoute.kt` —
  the nav adapter is unaffected; the dialog mounts inside `AdminComplaintScreen`.
- All legacy `composeApp/.../admin/complaint/*Dialog.kt` files — preserved
  for the legacy route. Phase 9.x route-swap retires them later.

## Reuse

- **fetchLegacyById helper**: already on `AdminComplaintActionRepositoryImpl`
  for `changeStatus` + `addClosureReason`. Reused verbatim for `editComplaint`.
- **completeAction post-mutation flow**: VM helper that handles the
  success/failure → state + effect + reload sequence. Reused unchanged.
- **rememberSaveable form pattern**: lifted from user-side `EditContent`
  (lines 354-480 of `ComplaintActionDialog.kt`). Two `var by rememberSaveable(complaint.id)`
  fields for subject and body.
- **Save button validation**: non-blank subject AND non-blank body AND
  body length ≤ 1000 chars. Identical to user-side.
- **Snackbar wiring**: already in place on `AdminComplaintScreen.kt`
  from Phase 7.x.complaint.admin.actions. No changes needed.
- **In-flight guard**: `state.isSubmittingAction` already prevents
  double-submission; reused verbatim.

## Verification

After every source commit (steps 2-5):
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator.

Desktop compile not required (no Desktop-specific code in this slice; the
`:ui` change is `commonMain`).

On-device smoke tests (Windows-impossible, deferred to user's Mac):
- Tap a complaint row → action menu shows 4 actions including "Edit".
- Tap Edit → EditContent dialog opens with subject + body pre-populated.
- Mutate subject + body → tap Save → success snackbar + dialog closes +
  list refires.
- Tap row of a CLOSED complaint with closure metadata → Edit → mutate
  subject → Save → re-tap the row → STATUS_CHANGE dialog's "Current"
  line still shows CLOSED (status preserved) + closure reason is
  visible via subsequent closure-reason dialog (metadata preserved).
- Tap Edit with blank subject → Save button disabled.
- Tap Edit with > 1000 char body → counter shows error colour, Save
  button disabled.

Edge cases mentally modelled:
- **Concurrent admin actions**: `isSubmittingAction` guards re-entry.
  Tapping Edit while a previous status-change is in flight is silently
  dropped by the existing guard.
- **Empty subject + non-empty body**: Save button disabled (matches
  user-side).
- **Edit a PINNED complaint**: no client-side gate (unlike user-side
  which hides Edit for PINNED). Admin has override authority — same
  posture as the existing Delete (no PINNED gate).
- **Metadata Map<String, Any>?**: handled inside `:data` via legacy
  type. `:domain` boundary never sees the Map; the `Any` ban is
  preserved.

## Deferrals

- **No type-change in admin edit**: user-side EditContent doesn't change
  type; admin doesn't either. If type-change becomes a requirement, a
  separate slice extends `editComplaint` with an optional `newType:
  ComplaintType?` param. OCP §6 extension hook.
- **No bulk edit**: legacy admin doesn't have it; rework doesn't add it.
- **No i18n lift**: snackbar copy "Complaint updated" stays inline.
  Phase 10 i18n lift handles all rework snackbars in one pass.
- **No nav graph route-swap**: legacy `Screen.ComplaintAdmin` stays
  bound to the legacy route. Phase 9.x route-swap is its own slice.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: each file has one rule. The new use case has one rule (issue
  an admin edit intent). The new VM method has one rule (route an admin
  edit submission). The new dialog branch has one rule (render the edit
  form for the active complaint).
- **OCP**: sealed-interface extension — `AdminComplaintIntent` and
  `AdminActionDialogMode` accept the new variant. The existing 6
  intents + 5 enum variants are unchanged.
- **ISP**: `AdminComplaintActionRepository` gains a fourth method;
  every consumer (3 existing admin use cases) is unaffected — they
  don't depend on the new method. The new use case is the only consumer
  of the new method.
- **DIP**: `:presentation` depends on `:domain` `AdminEditComplaintUseCase`.
  `:data` impl visible only via Koin binding. Layer boundary unchanged.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. The legacy
  `Map<String, Any>?` metadata is touched only inside `:data` (via the
  legacy `Complaint` type) and never leaks to `:domain`.
- **MVI contract**: new intent variant (`OnSubmitEdit`). No new effects
  — reuses `ShowSuccessMessage` / `ShowErrorMessage`. Exhaustive `when`
  in the VM means the new variant is a compile-time anchor.
- **Strangler-fig**: zero new `:data` → `:shared` reach. Reuses
  `legacyGetAll` + `legacyUpdate` already injected.
- **Load-bearing fixes preserved**: this slice does NOT touch the Coil
  ImageLoader, AVIF decoder, HighQualitySkiaImageDecoder, `:platform`,
  or any image stack. Pure Firestore-bound text mutation.
