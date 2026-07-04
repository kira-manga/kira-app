# Phase 7.x.complaint.admin.copy — admin long-press body-copy sub-slice

## Context

§104's next-candidate block recommended **Phase 7.x.complaint.admin.copy** as the strategic next step — port the legacy admin's long-press body-copy affordance onto the rework admin Complaint dashboard. Smallest remaining deferral from the admin slice's queue (after the §102/§103/§104 actions/edit/sort triad). Pure quality-of-life feature: admin long-presses a complaint row's body text → it lands on the clipboard → snackbar confirms.

Legacy reference: `composeApp/.../admin/complaint/AdminComplaintScreen.kt:830-842` uses `LocalClipboardManager.current.setText(AnnotatedString(complaint.body))` inside a `combinedClickable` long-press lambda, followed by `onShowMessage(titleCopiedMessage)` to drive a snackbar. The rework keeps the same posture: clipboard write happens at the `:ui` boundary (where Compose's `CompositionLocal` lives), snackbar travels through the MVI surface for parity with the other admin actions' snackbars.

§102's `AdminComplaintEffect.kt` KDoc line 24 already anticipated `CopyToClipboard(text: String)` as a possible variant — but the simpler design used here doesn't need it. The clipboard write is a side effect at the `:ui` boundary (composition-local access); only the snackbar emission needs to travel through the VM. So no new `Effect` variant — reuse the existing `ShowSuccessMessage`.

## Approach

Pure 3-file slice: `:presentation` (intent + VM handler) + `:ui` (long-press detector on body Text). ZERO `:domain` / `:data` / Koin / nav / Effect touches.

### MVI shape (additive)

- New `AdminComplaintIntent.OnCopyBody : AdminComplaintIntent` — payload-free `data object`. The clipboard write happens at `:ui` (composition-local access); the intent's purpose is to centralize the snackbar emission via the VM.
- New `:ui` long-press handler on the body `Text` inside `AdminComplaintRow`:
  - `combinedClickable(onClick = {}, onLongClick = { clipboardManager.setText(AnnotatedString(complaint.body)); onLongClick() })`
  - `onClick = no-op` matches legacy behavior — tap on the body row consumes the gesture (does NOT propagate up to the Card's clickable, so tapping the body does NOT open the action menu).
  - The `onLongClick` lambda in the row dispatches `AdminComplaintIntent.OnCopyBody`.
- VM `OnCopyBody` handler: `emit(AdminComplaintEffect.ShowSuccessMessage("Copied to clipboard"))`. No state mutation, no `isSubmittingAction` gating — copy is a non-destructive read; allowing it during a pending mutation is harmless.

### Clipboard API choice

Use the existing `androidx.compose.ui.platform.LocalClipboardManager` (the API the legacy uses). It carries a deprecation warning suggesting `LocalClipboard` (the new suspend-based API), but the deprecation is uniform across the codebase (legacy and other rework features that touch the clipboard would all need to migrate together). Phase 10's i18n + API migration sweep can swap to `LocalClipboard` in one pass.

`AnnotatedString(complaint.body)` is the standard `LocalClipboardManager.setText` payload; matches legacy verbatim.

### Tap-consumption vs propagation

`combinedClickable` on the body `Text` consumes the tap — it does NOT bubble to the parent Card's `clickable`. This means:
- Tap on the body row → no action (legacy parity; legacy `onClick = { /* no-op */ }` documents this exact posture).
- Tap on the subject row, user-id row, or any card padding → opens action menu (existing rework foundation behavior).
- Long-press on the body → clipboard write + snackbar.

Alternative considered: apply `combinedClickable` to the entire Card (replacing the current `clickable(onClick = onClick)`), so long-press anywhere on the card copies the body. Decided against — semantically confusing to long-press the subject row and have the BODY copied. The body-specific affordance matches legacy ergonomics and is intuitive.

### Snackbar copy

Inline English literal: `"Copied to clipboard"`. Matches the rework's other snackbar copy ("Status updated" / "Closure reason added" / "Complaint deleted" / "Complaint updated"). Phase 10 i18n lifts to `Res.string.complaint_copied`.

## Commit roadmap

Three commits, all under the 5-file cap:

1. **Plan commit** — `PLAN_complaint_admin_copy.md` only (1 file).

2. **Source bundle** — 3 files modified:
   - MOD `presentation/.../complaint/admin/AdminComplaintIntent.kt` — add `data object OnCopyBody : AdminComplaintIntent` variant under a new "Phase 7.x.complaint.admin.copy" section comment.
   - MOD `presentation/.../complaint/admin/AdminComplaintViewModel.kt` — add `is AdminComplaintIntent.OnCopyBody -> handleCopyBody()` arm in the exhaustive `when`. New private `handleCopyBody()` emits `ShowSuccessMessage("Copied to clipboard")`.
   - MOD `ui/.../complaint/admin/AdminComplaintScreen.kt` — imports (`combinedClickable`, `LocalClipboardManager`, `AnnotatedString`, `ExperimentalFoundationApi`); `AdminComplaintRow` gains an `onLongClick: () -> Unit` parameter + captures `clipboardManager` from the composition local + wraps body Text with `combinedClickable`; `AdminComplaintList`'s `AdminComplaintRow` call passes `onLongClick = { onIntent(AdminComplaintIntent.OnCopyBody) }`.
   - Build gates: Android + iOS Arm64 + iOS SimulatorArm64. All must pass before the close-out commit.

3. **Close-out** — 2 files:
   - `ARCHITECTURE.md` — append §105 with strategy / layer-by-layer / MVI shape rationale / strangler-fig boundary (ZERO touches) / tap-consumption rationale / files / build gates / deferrals / next-candidate block.
   - `SOLID_AUDIT.md` — append Phase 7.x.complaint.admin.copy entry with per-file SOLID 10-point checklists (4 files: plan + 3 source) + end-of-slice verdict + next-candidate block recommending Phase 7.x.complaint.admin.stats.

## Critical files

### New
- `PLAN_complaint_admin_copy.md` (this file)

### Modified
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintIntent.kt`
- `presentation/src/commonMain/kotlin/me/manga/yamiapk/presentation/complaint/admin/AdminComplaintViewModel.kt`
- `ui/src/commonMain/kotlin/me/manga/yamiapk/ui/complaint/admin/AdminComplaintScreen.kt`
- `ARCHITECTURE.md` — §105
- `SOLID_AUDIT.md` — Phase 7.x.complaint.admin.copy entry

### Untouched (verify by read, not modified)
- `presentation/.../complaint/admin/AdminComplaintState.kt` — no state field needed (copy is fire-and-forget).
- `presentation/.../complaint/admin/AdminComplaintEffect.kt` — no new effect variant needed (`ShowSuccessMessage` is reused for the snackbar; clipboard write happens at `:ui` boundary).
- `composeApp/.../di/ComplaintAdminReworkModule.kt` — no new Koin bindings.
- `composeApp/.../navigation/routes/AdminComplaintReworkScreenRoute.kt` — no new route adapter (clipboard manager is a Compose composition local; the `:ui` accesses it directly).
- `domain/`, `data/`, `:shared` — untouched. ZERO strangler-fig boundary impact.

## Reuse

- **`combinedClickable`** — already on the `:ui` classpath via `androidx.compose.foundation`. Used by the legacy verbatim; the rework uses the same API.
- **`LocalClipboardManager`** — Compose composition local. Works on Android, iOS, Desktop without any actual/expect bridging. Deprecation warning (use `LocalClipboard` for suspend support) is uniform with the rest of the codebase — deferred to a future API migration sweep.
- **`AnnotatedString`** — `androidx.compose.ui.text.AnnotatedString`. Standard clipboard payload type.
- **`AdminComplaintEffect.ShowSuccessMessage`** — existing snackbar emission machinery from §102. Reused for the copy confirmation snackbar; no new effect variant.
- **`emit()` in MVI base class** — existing one-shot emission machinery. Reused.
- **`handle*` private methods in VM** — existing handler pattern (one private method per intent). Reused for `handleCopyBody`.

## Verification

After the source-bundle commit (commit 2):
- `gradlew.bat :composeApp:compileDebugKotlinAndroid` — Android compile gate.
- `gradlew.bat :composeApp:compileKotlinIosArm64` — iOS arm64.
- `gradlew.bat :composeApp:compileKotlinIosSimulatorArm64` — iOS simulator arm64.

iOS framework linking + on-device smoke tests deferred to user's Mac. Smoke-test checklist when user is on Mac:
- Long-press a complaint body in the rework admin dashboard.
- Verify the clipboard receives the body text (paste into another app).
- Verify the snackbar shows "Copied to clipboard".
- Verify tapping the body row does NOT open the action menu (legacy parity).
- Verify tapping the subject row, user-id row, or card padding DOES open the action menu (foundation behavior preserved).

Edge cases:
- Empty body string — `clipboardManager.setText(AnnotatedString(""))` is a valid no-op on all platforms; snackbar still fires (the rework matches legacy here).
- Very long body — `AnnotatedString` has no practical length limit; the platform clipboard may have one (e.g., Android API 33+'s "Sensitive content" warning at >65KB) but the rework defers OS-level UX to the platform.
- Long-press during an in-flight mutation (e.g., status change is submitting) — allowed; copy is a passive read.

## Deferrals

- **No `CopyToClipboard(text)` effect variant** — the clipboard write happens at the `:ui` boundary (composition-local access); only the snackbar travels through MVI. If a future slice needs to copy from outside the row composable (e.g., a dialog's "Copy" button), the effect variant slots in via OCP §6 as a sibling to `ShowSuccessMessage`.
- **No per-field copy affordance** — only body is copyable. Subject / user-id / status / type don't get long-press copy in this slice (mirrors legacy). A future slice can add a row-level "Copy details" action menu item that copies a formatted summary.
- **No `LocalClipboard` (new suspend API) migration** — deferred to a Phase 10 API-migration sweep that covers all clipboard call sites (legacy + rework) uniformly.
- **No i18n** — `"Copied to clipboard"` is hardcoded English. Phase 10 lift.

## SOLID & layer-boundary notes (pre-flight)

- **SRP**: 3 files modified, each with ≤1 logical addition (1 intent variant / 1 handler / 1 long-press detector). No multi-purpose mutations.
- **OCP**: `AdminComplaintIntent` sealed interface extends additively — `OnCopyBody` is a 13th variant. `AdminComplaintEffect` unchanged (no extension needed).
- **LSP**: No new types substitute existing ones. The new intent variant is substitutable in `submit(intent)`.
- **ISP**: `OnCopyBody` is payload-free — minimum viable. No unnecessary args.
- **DIP**: `:presentation` depends on no new types. `:ui` depends on the new intent + `LocalClipboardManager` (Compose composition local, not a `:platform` interface). The clipboard manager dependency is justified at the `:ui` layer because composition locals are the Compose way to expose platform services to composables.
- **Layer boundary**: ZERO `:domain` / `:data` / Koin / nav touches. Surface is `:presentation` (Intent + VM) + `:ui` (Screen). The strangler-fig boundary stays where §104 left it.
- **Banned features**: no `!!`, `Any`, `lateinit`, `Thread`. `AnnotatedString` is a `kotlin.String` wrapper; `LocalClipboardManager.current` is a non-nullable composition local; the long-press lambda is a `() -> Unit`.
- **MVI contract**: AdminComplaintIntent widens from 13 to 14 variants; AdminComplaintEffect unchanged (2 variants); AdminComplaintState unchanged (11 fields). The VM's exhaustive `when` arm is the only intent-handling surface mutation. The base `MviViewModel`'s `submit` surface continues to compile.
- **Strangler-fig**: ZERO new `:data` → `:shared` reaches. Continues §104's "zero new boundary" posture.
- **Load-bearing fixes preserved**: this slice does NOT touch Coil ImageLoader, Reader per-request listener, decoder hints, OkHttp interceptor, or any prior load-bearing image-quality posture (admin complaint surface has no images). No load-bearing risk.
