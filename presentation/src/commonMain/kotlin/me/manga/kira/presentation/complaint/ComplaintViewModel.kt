package me.manga.kira.presentation.complaint

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.complaint.ComplaintHistory
import me.manga.kira.domain.model.complaint.ComplaintHistoryStatus
import me.manga.kira.domain.model.complaint.ComplaintOwnerRow
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.UnknownComplaintItem
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
 *    [ComplaintEffect.ShowActionSuccess], refire `loadList()`. Failure: keep the dialog open at
 *    its current sub-mode and set [ComplaintState.actionFailed] for dialog-local feedback.
 *  - `OnDismissActionDialog` clears the dialog substate (mode → `NONE`, activeComplaint → null,
 *    actionFailed → false). Dismissal is ignored while an action is in flight.
 *
 * **In-flight guard via `isSubmittingAction`**: each action handler short-circuits when
 * `state.isSubmittingAction == true`. The `:ui` dialog also disables its submit buttons while
 * the flag is set, but the VM-side guard is defence-in-depth against intent re-entry from
 * channel queuing (the [MviViewModel] intent channel is unbounded — a tap-spam queues
 * intents). Without the guard, a fast double-tap on Send could enqueue two reply attempts.
 *
 * **`completeAction` shape**: the three actions (reply / edit / delete) share an identical
 * post-result handler — set `isSubmittingAction = false`, branch on `result.isSuccess`,
 * dismiss + emit success + refire `loadList()` OR keep dialog open at current mode + set a
 * non-leaking modal error. Extracted into a private helper to keep each action handler short and the
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
    private val loadSerial = Mutex()
    private var loadJob: Job? = null
    private var loadGeneration = 0L

    init {
        loadList()
    }

    override suspend fun handle(intent: ComplaintIntent) {
        if (!state.value.legacyActionsAllowed && intent.isLegacyAction()) return
        when (intent) {
            is ComplaintIntent.OnRetry -> loadList()
            is ComplaintIntent.OnSearchChange -> {
                val query = intent.query
                updateState {
                    it.copy(
                        searchQuery = query,
                        filtered = applyFilter(it.all, query, it.selectedStatus),
                        backendItems = filterBackend(it.history, query, it.selectedStatus),
                    )
                }
            }
            is ComplaintIntent.OnStatusFilter -> {
                val status = intent.status
                updateState {
                    it.copy(
                        selectedStatus = status,
                        filtered = applyFilter(it.all, it.searchQuery, status),
                        backendItems = filterBackend(it.history, it.searchQuery, status),
                    )
                }
            }
            is ComplaintIntent.OnClearSearch -> {
                updateState {
                    it.copy(
                        searchQuery = "",
                        filtered = applyFilter(it.all, "", it.selectedStatus),
                        backendItems = filterBackend(it.history, "", it.selectedStatus),
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
                actionFailed = false,
            )
        }
    }

    private fun handleDismissDialog() {
        if (state.value.isSubmittingAction) return
        updateState {
            it.copy(
                actionDialogMode = ActionDialogMode.NONE,
                activeComplaint = null,
                actionFailed = false,
            )
        }
    }

    private fun handleSelectAction(mode: ActionDialogMode) {
        if (state.value.isSubmittingAction) return
        if (mode == ActionDialogMode.NONE) return
        if (state.value.activeComplaint == null) return
        updateState { it.copy(actionDialogMode = mode, actionFailed = false) }
    }

    private fun handleSubmitReply(body: String) {
        val current = state.value
        if (current.isSubmittingAction) return
        val parent = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true, actionFailed = false) }
        launchSafely {
            val result = replyToComplaint(parent, body)
            completeAction(result, action = ComplaintAction.REPLY_SENT)
        }
    }

    private fun handleSubmitEdit(
        subject: String,
        body: String,
    ) {
        val current = state.value
        if (current.isSubmittingAction) return
        val original = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true, actionFailed = false) }
        launchSafely {
            val result = editComplaint(original, subject, body)
            completeAction(result, action = ComplaintAction.UPDATED)
        }
    }

    private fun handleConfirmDelete() {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true, actionFailed = false) }
        launchSafely {
            val result = deleteComplaint(target.id)
            completeAction(result, action = ComplaintAction.DELETED)
        }
    }

    private suspend fun completeAction(
        result: Result<Unit>,
        action: ComplaintAction,
    ) {
        if (result.isSuccess) {
            updateState {
                it.copy(
                    isSubmittingAction = false,
                    actionDialogMode = ActionDialogMode.NONE,
                    activeComplaint = null,
                    actionFailed = false,
                )
            }
            emit(ComplaintEffect.ShowActionSuccess(action))
            loadList()
        } else {
            // Do not log the throwable or SDK text; the retained dialog resolves a localized error.
            Logger.withTag(TAG).w { "complaint action failed" }
            updateState { it.copy(isSubmittingAction = false, actionFailed = true) }
        }
    }

    private fun loadList() {
        val previous = loadJob
        val generation = ++loadGeneration
        previous?.cancel()
        updateState { it.copy(isLoading = true, error = null) }
        loadJob =
            launchSafely {
                // A canceled intermediate waiter must not let a third retry overtake the oldest
                // response's finally block. The serial section also covers the selected legacy port.
                loadSerial.withLock {
                    previous?.join()
                    currentCoroutineContext().ensureActive()
                    val result =
                        try {
                            observeUserComplaints()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            AppResult.Failure(AppError.Unexpected("complaint_history_failed"))
                        }
                    currentCoroutineContext().ensureActive()
                    if (generation != loadGeneration) return@withLock
                    when (result) {
                        is AppResult.Success -> applyHistory(result.value)
                        is AppResult.Failure -> updateState { it.copy(isLoading = false, error = result.error) }
                    }
                }
            }
    }

    private fun applyHistory(history: ComplaintHistory) {
        val legacy = (history as? ComplaintHistory.Legacy)?.items.orEmpty()
        updateState {
            it.copy(
                isLoading = false,
                error = null,
                history = history,
                all = legacy,
                filtered = applyFilter(legacy, it.searchQuery, it.selectedStatus),
                backendItems = filterBackend(history, it.searchQuery, it.selectedStatus),
                actionDialogMode =
                    if (history is ComplaintHistory.Legacy) {
                        it.actionDialogMode
                    } else {
                        ActionDialogMode.NONE
                    },
                activeComplaint = if (history is ComplaintHistory.Legacy) it.activeComplaint else null,
                isSubmittingAction = history is ComplaintHistory.Legacy && it.isSubmittingAction,
                actionFailed = history is ComplaintHistory.Legacy && it.actionFailed,
            )
        }
    }

    override fun onUnhandledError(
        throwable: Throwable,
        intent: ComplaintIntent?,
    ) {
        // Even unexpected failures must not send transport/content diagnostics to Kermit or UI.
        updateState {
            if (it.isSubmittingAction) {
                it.copy(isSubmittingAction = false, actionFailed = true)
            } else {
                it.copy(isLoading = false, error = AppError.Unexpected("complaint_failed"))
            }
        }
    }

    private fun filterBackend(
        history: ComplaintHistory?,
        query: String,
        status: ComplaintStatus?,
    ): List<ComplaintOwnerRow> =
        (history as? ComplaintHistory.Backend)?.items.orEmpty().filter { row ->
            val subject =
                when (row) {
                    is ComplaintOwnerRow.Report -> row.subject
                    is ComplaintOwnerRow.Reply -> row.subject
                    is ComplaintOwnerRow.NoticeReply, is UnknownComplaintItem -> ""
                }
            val content = row as? ComplaintOwnerRow.Content
            val matches =
                query.isEmpty() ||
                    subject.contains(query, ignoreCase = true) ||
                    content?.fields?.body?.contains(query, ignoreCase = true) == true ||
                    row.id.contains(query, ignoreCase = true)
            val knownStatus = content?.fields?.status as? ComplaintHistoryStatus.Known
            matches && (status == null || knownStatus?.value == status)
        }

    private fun applyFilter(
        all: List<ComplaintSummary>,
        query: String,
        status: ComplaintStatus?,
    ): List<ComplaintSummary> =
        all.filter { complaint ->
            val matchesSearch =
                query.isEmpty() ||
                    complaint.subject.contains(query, ignoreCase = true) ||
                    complaint.body.contains(query, ignoreCase = true) ||
                    complaint.id.contains(query, ignoreCase = true)
            val matchesStatus = status == null || complaint.status == status
            matchesSearch && matchesStatus
        }

    private companion object {
        const val TAG = "ComplaintViewModel"
    }
}

/** Backend rows have no legacy action shape, and injected legacy-shaped intents are rejected too. */
private fun ComplaintIntent.isLegacyAction(): Boolean =
    when (this) {
        ComplaintIntent.OnRetry, is ComplaintIntent.OnSearchChange, is ComplaintIntent.OnStatusFilter,
        ComplaintIntent.OnClearSearch,
        -> false
        else -> true
    }
