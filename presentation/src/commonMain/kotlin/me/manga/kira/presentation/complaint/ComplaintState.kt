package me.manga.kira.presentation.complaint

import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.presentation.mvi.MviState

/**
 * State for the rework user-side Feedback Manager screen.
 *
 * Phase 7.x.complaint.foundation rework. Holds the full list of the user's submitted
 * complaint records, the in-progress search/filter selections, and the derived [filtered]
 * projection that the `:ui` composable renders directly.
 *
 * Phase 7.x.complaint.actions append: also holds the action-dialog substate
 * ([actionDialogMode], [activeComplaint], [isSubmittingAction]) so the dialog mount in `:ui`
 * is a pure projection of state (no `remember { mutableStateOf(...) }` for dialog flow control
 * — that would split the source of truth and make the composable non-stateless).
 *
 * **Why store [filtered] explicitly rather than deriving in the composable**: the rework `:ui`
 * layer is contractually stateless — a pure projection of state. Storing search/filter
 * selections in Compose `remember` (as the legacy screen does, lines 95-103 of legacy
 * `ComplaintScreen.kt`) would split the source of truth between VM and view, making the view
 * non-stateless. Recomputing in the VM on each `OnSearchChange` / `OnStatusFilter` intent
 * keeps the composable stateless and lets future tests assert on `state.filtered` directly.
 *
 * Field semantics:
 *  - [isLoading]: `true` from construction until the first fetch resolves (success or
 *    failure). Mutually exclusive with [error] and a populated [all].
 *  - [error]: non-null when the fetch failed. The `:ui` composable shows an inline error
 *    message + Retry button (which fires `ComplaintIntent.OnRetry`).
 *  - [all]: the immutable backing list — the full set of complaints from the last successful
 *    fetch. Not modified by search/filter intents — only by `OnRetry`.
 *  - [filtered]: derived from [all] + [searchQuery] + [selectedStatus]. The composable renders
 *    this list directly; an empty [filtered] with a non-empty [all] signals "no matches" and
 *    the composable shows an inline "no matches" message; an empty [filtered] with an empty
 *    [all] signals "empty list" and the composable shows an inline empty-state message.
 *  - [searchQuery]: free-form text. Empty string disables search. Substring matching against
 *    `subject`/`body`/`id` (case-insensitive) — matches legacy semantics (lines 97-101 of
 *    legacy screen).
 *  - [selectedStatus]: `null` means "all statuses", anything else filters to that status.
 *    Single-select with toggle-off — clicking the chip again clears the filter.
 *  - [actionDialogMode]: which dialog sub-screen is rendered. [ActionDialogMode.NONE] means
 *    no dialog is mounted; the others map to the legacy `DialogAction` enum (Menu / Reply /
 *    Edit / Delete). The transitions are driven by intents (see [ComplaintIntent]).
 *  - [activeComplaint]: the row the user tapped to open the action dialog. Required for
 *    every sub-mode (the Menu shows the preview card; Reply / Edit / Delete each operate on
 *    this record). Null when [actionDialogMode] == [ActionDialogMode.NONE].
 *  - [isSubmittingAction]: `true` while a Reply / Edit / Delete request is in-flight. Drives
 *    the dialog's submit buttons' `enabled = false` + loading spinner. Cleared on success
 *    (alongside dialog dismissal) or on failure (dialog stays open at current mode).
 *
 * **Mutually exclusive states**:
 *  - `isLoading == true` → "Loading…" indicator. [all] and [filtered] are empty, [error] is null.
 *  - `error != null` → inline error. [isLoading] is false, [all] and [filtered] are empty.
 *  - `isLoading == false && error == null && all.isEmpty()` → empty-state. No complaints
 *    submitted.
 *  - `isLoading == false && error == null && all.isNotEmpty()` → list. [filtered] may be a
 *    subset of [all] depending on [searchQuery] / [selectedStatus].
 *
 * **Dialog mount precondition**: the `:ui` composable mounts [ComplaintActionDialog] only when
 *  `actionDialogMode != ActionDialogMode.NONE && activeComplaint != null`. Both should be set
 *  together by `OnRowClick` and cleared together by `OnDismissActionDialog`; the precondition
 *  protects against the (currently unreachable) invariant violation.
 *
 * Contract §6 SRP: one rule — "the projection of one complaint-list screen + its action
 * dialog". No business logic; derivation lives in the VM's intent handlers; rendering lives in
 * the `:ui` composable.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster31.staleKdocSweep.cascade,
 * Task #487, 2026-05-28): three fulfilled-forecast / stale citations
 * appear in this file:
 *  - Lines 21-22 (class-level KDoc, "lines 95-103 of legacy
 *    `ComplaintScreen.kt`"). STALE-SYMBOL-REFERENCE — Phase 9.x.
 *    complaint.legacyui.retire (§355) DELETED the legacy `:shared`
 *    `ComplaintScreen.kt` along with its 4 sibling helpers as a
 *    5-file orphan chain. A recursive search of the legacy
 *    complaint folder for a screen file with the cited line range
 *    returns NO MATCHES.
 *  - Lines 38-39 (class-level KDoc, "lines 97-101 of legacy
 *    screen"). STALE-SYMBOL-REFERENCE — same §355 retire as above
 *    (the cited legacy `ComplaintScreen.kt` lines 97-101 are
 *    bundled in the same 5-file retire chain).
 *  - Lines 134-135 ([ActionDialogMode.MENU] KDoc, "lines 206-241
 *    of legacy `ComplaintActionDialog.kt`"). STALE-SYMBOL-REFERENCE —
 *    Phase 9.x.complaint.legacyui.retire (§355) DELETED the cited
 *    legacy `ComplaintActionDialog.kt` (one of the 5 files in the
 *    same retire chain).
 *  HOWEVER — the rework `:ui` `ComplaintActionDialog` (same
 *  filename, different package: `me.manga.kira.ui.complaint.
 *  ComplaintActionDialog`) is LIVE as the canonical user-side
 *  Complaint-action dialog backed by [ComplaintState] +
 *  [ActionDialogMode] + [ComplaintViewModel]. All three architectural
 *  rationales — the search/filter source-of-truth posture (VM-
 *  derived [filtered] rather than `:ui`-side `remember`), the
 *  substring-matching semantics (subject/body/id case-insensitive),
 *  and the PINNED-status hide-Edit-Delete rule — STAND on their own
 *  merits past the §355 fulfilled landing. The [ComplaintState] +
 *  [ActionDialogMode] declarations remain LIVE as the canonical
 *  user-side Complaint-screen state ADT + action-dialog selector
 *  consumed by [ComplaintViewModel] + the rework `:ui`
 *  `ComplaintScreen`. Original §253-era prose preserved verbatim
 *  per the audit-trail-preservation convention — the citations are
 *  historical record of the design lineage including the source-
 *  of-truth + PINNED-gating rationales that were subsequently
 *  fulfilled (legacy complaint chain retired) across §355.
 */
data class ComplaintState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val all: List<ComplaintSummary> = emptyList(),
    val filtered: List<ComplaintSummary> = emptyList(),
    val searchQuery: String = "",
    val selectedStatus: ComplaintStatus? = null,
    val actionDialogMode: ActionDialogMode = ActionDialogMode.NONE,
    val activeComplaint: ComplaintSummary? = null,
    val isSubmittingAction: Boolean = false,
) : MviState

/**
 * Action-dialog substate selector — mirrors the legacy `DialogAction` enum
 * (`shared/.../complaint/model/DialogAction.kt`, retired in Phase
 * 9.x.complaint.legacymodels.retire — see ARCHITECTURE §206; the rework owns the source of
 * truth for this state-machine selector now).
 *
 * Phase 7.x.complaint.actions: introduced as a sibling of [ComplaintState] (same file —
 * tightly-coupled enum that has no consumers outside this slice). The `:ui` dialog branches
 * on this enum to render Menu / Reply / Edit / Delete content (see
 * [me.manga.kira.ui.complaint.ComplaintActionDialog]).
 *
 * Variants:
 *  - [NONE]: no dialog mounted. The `:ui` composable skips the dialog entirely.
 *  - [MENU]: shows the 3 action affordances (Reply / Edit / Delete) + a complaint preview
 *    card. Initial mode after `OnRowClick`. Edit + Delete are hidden when
 *    `activeComplaint.status == ComplaintStatus.PINNED` (matches legacy logic at lines 206-241
 *    of legacy `ComplaintActionDialog.kt`).
 *  - [REPLY]: shows a text input (500-char cap) + Send button.
 *  - [EDIT]: shows subject + body inputs (1000-char cap on body) + Save button.
 *  - [DELETE]: shows a warning card + complaint preview + Delete-forever button.
 *
 * **Why not just reuse legacy `DialogAction`?**: legacy lives in `:shared` which the `:domain`
 * / `:presentation` layers can reach (foundation already does so via the strangler-fig in
 * `:data`), but doing so for a pure presentation-shape enum would leak a `:shared` type into
 * the rework MVI surface. The rework owns its own enum — clean layer boundary. The enum names
 * differ slightly (legacy has `NONE/REPLY/EDIT/DELETE` only; rework adds `MENU` because the
 * legacy collapses the menu-vs-reply distinction with a `currentAction == NONE` shortcut that
 * doubles for both "no dialog" and "dialog at menu"). The rework's explicit `MENU` variant
 * disambiguates and makes the state machine clearer.
 */
enum class ActionDialogMode {
    NONE,
    MENU,
    REPLY,
    EDIT,
    DELETE,
}
