package me.manga.kira.presentation.complaint

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.usecase.complaint.DeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.EditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase
import me.manga.kira.domain.usecase.complaint.ReplyToComplaintUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Feedback Manager (user-side complaint LIST + action dialog) ViewModel.
 *
 * Phase 7.x.complaint.foundation rework + Phase 7.x.complaint.actions rework append.
 *
 * **Foundation responsibilities** (unchanged from foundation slice):
 *  - Single-shot load of the user's complaints on `init {}`; re-load on `OnRetry`.
 *  - VM-side derivation of `state.filtered` from `state.all` + `state.searchQuery` +
 *    `state.selectedStatus` (see [applyFilter]).
 *
 * **Actions slice append** (this slice):
 *  - Row tap opens the action dialog at [ActionDialogMode.MENU] and stores the tapped record
 *    as [ComplaintState.activeComplaint] (see [handleRowClick]).
 *  - Sub-mode transitions (`MENU` → `REPLY` / `EDIT` / `DELETE`, and back to `MENU`) update
 *    [ComplaintState.actionDialogMode] without touching [ComplaintState.activeComplaint] (see
 *    [handleSelectAction]).
 *  - `OnSubmitReply` / `OnSubmitEdit` / `OnConfirmDelete` each call the matching domain use
 *    case and route the [Result] through [completeAction]. Success: dismiss dialog, emit
 *    [ComplaintEffect.ShowSuccessMessage], refire `loadList()`. Failure: keep dialog open at
 *    its current sub-mode, emit [ComplaintEffect.ShowErrorMessage].
 *  - `OnDismissActionDialog` clears the dialog substate (mode → `NONE`, activeComplaint → null,
 *    isSubmittingAction → false).
 *
 * **In-flight guard via `isSubmittingAction`**: each action handler short-circuits when
 * `state.isSubmittingAction == true`. The `:ui` dialog also disables its submit buttons while
 * the flag is set, but the VM-side guard is defence-in-depth against intent re-entry from
 * channel queuing (the [MviViewModel] intent channel is unbounded — a tap-spam queues
 * intents). Without the guard, a fast double-tap on Send could enqueue two reply attempts.
 *
 * **`completeAction` shape**: the three actions (reply / edit / delete) share an identical
 * post-result handler — set `isSubmittingAction = false`, branch on `result.isSuccess`,
 * dismiss + emit success + refire `loadList()` OR keep dialog open at current mode + emit
 * error. Extracted into a private helper to keep each action handler short and the
 * success/failure shape consistent. Same posture as the rework's
 * [me.manga.kira.presentation.feedback.FeedbackViewModel.submit] flow.
 *
 * **Refire `loadList()` on success rationale**: reply creates a child record (admin-visible
 * thread), edit overwrites the parent's `subject` / `body`, delete removes the parent.
 * `state.all` and `state.filtered` must reflect the post-mutation truth — refiring
 * `loadList()` is the cheapest correct option (one re-fetch per mutation; the list size is
 * tens-low-hundreds so the cost is negligible). The legacy screen also reloads after each
 * successful mutation via `loadForUser`.
 *
 * **Why call `loadList()` on success even though it sets `isLoading = true`**: a brief
 * loading flicker is acceptable here — the list is dismissed-dialog-then-list, and the user's
 * focus shifts to the new list state anyway. A more sophisticated approach (optimistic update
 * in-VM without re-fetch) would split the source of truth between the VM's projection of the
 * mutation and the Firestore-bound truth — fragile. Refire keeps both consistent.
 *
 * **Dialog dismiss on success but NOT on failure**: contract §6 — the failure path must let
 * the user re-attempt. Keeping the dialog open at its current sub-mode (REPLY / EDIT / DELETE)
 * lets the user adjust input and retry without re-navigating from the list. The legacy screen
 * does the same — dialog stays open on error.
 *
 * **`when (intent)` exhaustiveness**: the `:ui` composable can fire any of the 11
 * [ComplaintIntent] variants (4 foundation + 6 actions + 1 usercopy). The exhaustive `when`
 * ensures a future intent variant is a compile-time error here, exactly the contract we want for
 * OCP sealed-interface extension.
 *
 * **`emit` vs `sendEffect`**: the base [MviViewModel] exposes `emit(effect)` — suspend-clean;
 * the `handle` body is already a suspend context so no extra `viewModelScope.launch {}` is needed.
 *
 * Constructor: per contract §6 DIP — 4 use case interfaces, no impl-typed deps. Koin
 * `viewModel` binding in `complaintReworkModule` resolves the 4 args.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster34.staleKdocSweep.cascade,
 * Task #490, 2026-05-28): two stale citations appear in the refire-on-
 * success + dialog-stays-open-on-error rationale above:
 *  - Lines 53-54 (refire-`loadList()`-on-success rationale, "The legacy
 *    screen also reloads after each successful mutation via
 *    `loadForUser`").
 *  - Lines 64-65 (dialog-dismiss-on-success-but-NOT-on-failure
 *    rationale, "The legacy screen does the same — dialog stays open
 *    on error").
 *  Both classified as STALE-SYMBOL-REFERENCE — Phase 9.x.complaint.
 *  legacyui.retire (§355) DELETED the legacy `:shared`
 *  `ComplaintScreen.kt` along with its 4 sibling helpers as a 5-file
 *  orphan-retire chain. A recursive search of the legacy complaint
 *  folder for a `ComplaintScreen.kt` with the cited `loadForUser`-
 *  refire-on-success / dialog-stays-open-on-error call sites returns
 *  NO MATCHES. HOWEVER — the rework `:ui` `ComplaintScreen` (same
 *  filename, different package: `me.manga.kira.ui.complaint.
 *  ComplaintScreen`) is LIVE as the canonical user-side Feedback-
 *  Manager surface backed by [ComplaintState] + this [ComplaintViewModel]
 *  + [ComplaintIntent] + [ComplaintEffect] quad; both architectural
 *  rationales STAND on their own merits past the §355 fulfilled
 *  landing as LIVE rework realizations: (a) [completeAction] continues
 *  to refire `loadList()` after each successful Reply / Edit / Delete
 *  mutation to re-sync `state.all` + `state.filtered` against the
 *  post-mutation Firestore truth; (b) [completeAction]'s failure
 *  branch continues to keep the dialog open at its current sub-mode
 *  (REPLY / EDIT / DELETE) so the user can adjust input and retry
 *  without re-navigating from the list. This [ComplaintViewModel]
 *  remains LIVE as the canonical user-side Complaint-screen VM
 *  consumed by the rework `:ui` `ComplaintScreen` + `ComplaintActionDialog`.
 *  Original §253-era prose preserved verbatim per the audit-trail-
 *  preservation convention — the citations are historical record of
 *  the design lineage including the `loadForUser`-refire-on-success
 *  and dialog-stays-open-on-error rationales that were subsequently
 *  fulfilled (legacy complaint chain retired) across §355.
 */
class ComplaintViewModel(
    private val observeUserComplaints: ObserveUserComplaintsUseCase,
    private val replyToComplaint: ReplyToComplaintUseCase,
    private val editComplaint: EditComplaintUseCase,
    private val deleteComplaint: DeleteComplaintUseCase,
) : MviViewModel<ComplaintState, ComplaintIntent, ComplaintEffect>(
    initialState = ComplaintState(),
) {

    init {
        loadList()
    }

    override suspend fun handle(intent: ComplaintIntent) {
        when (intent) {
            is ComplaintIntent.OnRetry -> loadList()
            is ComplaintIntent.OnSearchChange -> {
                val query = intent.query
                updateState {
                    it.copy(
                        searchQuery = query,
                        filtered = applyFilter(it.all, query, it.selectedStatus),
                    )
                }
            }
            is ComplaintIntent.OnStatusFilter -> {
                val status = intent.status
                updateState {
                    it.copy(
                        selectedStatus = status,
                        filtered = applyFilter(it.all, it.searchQuery, status),
                    )
                }
            }
            is ComplaintIntent.OnClearSearch -> {
                updateState {
                    it.copy(
                        searchQuery = "",
                        filtered = applyFilter(it.all, "", it.selectedStatus),
                    )
                }
            }
            is ComplaintIntent.OnRowClick -> handleRowClick(intent.complaint)
            is ComplaintIntent.OnDismissActionDialog -> handleDismissDialog()
            is ComplaintIntent.OnSelectAction -> handleSelectAction(intent.mode)
            is ComplaintIntent.OnSubmitReply -> handleSubmitReply(intent.body)
            is ComplaintIntent.OnSubmitEdit -> handleSubmitEdit(intent.subject, intent.body)
            is ComplaintIntent.OnConfirmDelete -> handleConfirmDelete()
            is ComplaintIntent.OnCopyBody -> handleCopyBody()
        }
    }

    private suspend fun handleCopyBody() {
        emit(ComplaintEffect.ShowActionSuccess(ComplaintAction.BODY_COPIED))
    }

    private fun handleRowClick(complaint: ComplaintSummary) {
        if (state.value.isSubmittingAction) return
        updateState {
            it.copy(
                actionDialogMode = ActionDialogMode.MENU,
                activeComplaint = complaint,
            )
        }
    }

    private fun handleDismissDialog() {
        if (state.value.isSubmittingAction) return
        updateState {
            it.copy(
                actionDialogMode = ActionDialogMode.NONE,
                activeComplaint = null,
            )
        }
    }

    private fun handleSelectAction(mode: ActionDialogMode) {
        if (state.value.isSubmittingAction) return
        if (mode == ActionDialogMode.NONE) return
        if (state.value.activeComplaint == null) return
        updateState { it.copy(actionDialogMode = mode) }
    }

    private fun handleSubmitReply(body: String) {
        val current = state.value
        if (current.isSubmittingAction) return
        val parent = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = replyToComplaint(parent, body)
            completeAction(result, action = ComplaintAction.REPLY_SENT)
        }
    }

    private fun handleSubmitEdit(subject: String, body: String) {
        val current = state.value
        if (current.isSubmittingAction) return
        val original = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = editComplaint(original, subject, body)
            completeAction(result, action = ComplaintAction.UPDATED)
        }
    }

    private fun handleConfirmDelete() {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = deleteComplaint(target.id)
            completeAction(result, action = ComplaintAction.DELETED)
        }
    }

    private suspend fun completeAction(result: Result<Unit>, action: ComplaintAction) {
        if (result.isSuccess) {
            updateState {
                it.copy(
                    isSubmittingAction = false,
                    actionDialogMode = ActionDialogMode.NONE,
                    activeComplaint = null,
                )
            }
            emit(ComplaintEffect.ShowActionSuccess(action))
            loadList()
        } else {
            // The throwable (often a raw Firestore SDK string) is logged, never surfaced to the user:
            // the snackbar shows a generic localized error resolved in :ui.
            Logger.withTag(TAG).w(result.exceptionOrNull()) { "complaint action $action failed" }
            updateState { it.copy(isSubmittingAction = false) }
            emit(ComplaintEffect.ShowActionFailure)
        }
    }

    private var loadJob: Job? = null

    private fun loadList() {
        // Cancel-before-relaunch: a fresh load supersedes any in-flight one so two concurrent
        // observeUserComplaints() results can't race on updateState (last-writer-wins could land
        // the older snapshot). Cancel-before-relaunch is the documented house pattern for
        // ordering-sensitive loads (see ReaderViewModel).
        loadJob?.cancel()
        updateState { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            val result = observeUserComplaints()
            if (result.isSuccess) {
                val list = result.getOrNull().orEmpty()
                updateState {
                    it.copy(
                        isLoading = false,
                        error = null,
                        all = list,
                        filtered = applyFilter(list, it.searchQuery, it.selectedStatus),
                    )
                }
            } else {
                // Don't leak the raw exception text into state (the inline error pane shows a
                // generic localized message in :ui); log the cause for diagnostics.
                Logger.withTag(TAG).w(result.exceptionOrNull()) { "loading user complaints failed" }
                updateState {
                    it.copy(
                        isLoading = false,
                        error = LOAD_FAILED,
                        all = emptyList(),
                        filtered = emptyList(),
                    )
                }
            }
        }
    }

    private fun applyFilter(
        all: List<ComplaintSummary>,
        query: String,
        status: ComplaintStatus?,
    ): List<ComplaintSummary> = all.filter { complaint ->
        val matchesSearch = query.isEmpty() ||
            complaint.subject.contains(query, ignoreCase = true) ||
            complaint.body.contains(query, ignoreCase = true) ||
            complaint.id.contains(query, ignoreCase = true)
        val matchesStatus = status == null || complaint.status == status
        matchesSearch && matchesStatus
    }

    private companion object {
        const val TAG = "ComplaintViewModel"

        // Non-leaking sentinel for [ComplaintState.error]; :ui renders a generic localized message
        // whenever error != null (the value itself is never shown to the user).
        const val LOAD_FAILED = "load_failed"
    }
}
