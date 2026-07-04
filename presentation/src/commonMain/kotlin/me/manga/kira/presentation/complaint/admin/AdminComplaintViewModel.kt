package me.manga.kira.presentation.complaint.admin

import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.usecase.complaint.AddClosureReasonUseCase
import me.manga.kira.domain.usecase.complaint.AdminDeleteComplaintUseCase
import me.manga.kira.domain.usecase.complaint.AdminEditComplaintUseCase
import me.manga.kira.domain.usecase.complaint.ChangeComplaintStatusUseCase
import me.manga.kira.domain.usecase.complaint.ObserveAllComplaintsUseCase
import me.manga.kira.presentation.mvi.MviViewModel

/**
 * Admin Complaint Dashboard (admin-side complaint LIST) ViewModel.
 *
 * Phase 7.x.complaint.admin rework. Mirrors the user-side
 * [me.manga.kira.presentation.complaint.ComplaintViewModel] foundation pattern (load-once +
 * client-side filter), with two key differences:
 *  1. **Data scope**: fetches ALL complaints via [ObserveAllComplaintsUseCase] (vs the user-side's
 *     [me.manga.kira.domain.usecase.complaint.ObserveUserComplaintsUseCase]).
 *  2. **Two filter dimensions**: status AND type (vs user-side's status-only) — matching legacy
 *     admin parity (legacy `AdminComplaintScreen.kt:140-141`).
 *
 * **Foundation responsibilities** (this slice):
 *  - Single-shot load of all complaints on `init {}`; re-load on `OnRetry`.
 *  - VM-side derivation of `state.filtered` from `state.all` + `state.searchQuery` +
 *    `state.selectedStatus` + `state.selectedType` + `state.selectedAppVersion` +
 *    `state.selectedSort` (see [applyFilterAndSort]).
 *  - Search across `subject` / `body` / `id` / `userId` (case-insensitive) — matches legacy admin
 *    semantics (legacy admin searches userId too, vs user-side which doesn't).
 *
 * **Admin actions**: status change, closure reason, edit, delete, and body-copy each call a
 * domain use case (or `:ui` clipboard for copy) and route the [Result] through [completeAction].
 * Further work (bulk-update, bulk-delete) stays deferred via OCP §6 — sealed
 * [AdminComplaintIntent] / [AdminComplaintEffect] accept new variants without breaking the surface.
 *
 * **`effects`**: the two [AdminComplaintEffect] variants (`ShowSuccessMessage` /
 * `ShowErrorMessage`) drive action-result snackbars; inline list-load failure still lives in
 * [AdminComplaintState.error] with a Retry affordance.
 *
 * **`when (intent)` exhaustiveness**: the `:ui` composable can fire any of the
 * [AdminComplaintIntent] variants (list/search/filter/sort foundation plus the action-dialog
 * surface). The exhaustive `when` ensures a future intent variant is a compile-time error here,
 * exactly the contract we want for OCP sealed-interface extension.
 *
 * Constructor: per contract §6 DIP — five use case interfaces, no impl-typed deps. Koin
 * `viewModel` binding in `complaintAdminReworkModule` resolves the args.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster29.staleKdocSweep.cascade,
 * Task #485, 2026-05-28): one stale citation appears above:
 *  - Line 24 ("matching legacy admin parity (legacy
 *    `AdminComplaintScreen.kt:140-141`)"). STALE-SYMBOL-REFERENCE —
 *    Phase 9.x.admincomplaint.swap (§365) re-pointed
 *    `Screen.ComplaintAdmin`'s rendering adapter to the rework `:ui`
 *    `AdminComplaintScreen` backed by this rework
 *    [AdminComplaintViewModel]; Phase 9.x.admincomplaint.retire (§366)
 *    then DELETED the legacy admin chain (legacy admin VM + legacy
 *    `AdminComplaintScreen.kt` + 2 admin helper composables + the
 *    legacy Koin binding) in its entirety. A recursive search of the
 *    `:shared` legacy admin folder for a file named
 *    `AdminComplaintScreen.kt` returns NO MATCHES (the cite-target no
 *    longer exists on disk). The two-filter-dimensions admin-parity
 *    rationale (status AND type, vs user-side status-only) stands on
 *    its own merits past §§365 + 366 — the parity claim is encoded in
 *    [AdminComplaintState] (the `selectedStatus` + `selectedType` +
 *    `selectedAppVersion` triple) and [applyFilterAndSort]'s
 *    multi-axis filter logic, NOT in any surviving legacy file. The
 *    line 32 "matches legacy admin semantics (legacy admin searches
 *    userId too, vs user-side which doesn't)" claim is a behavioural-
 *    parity description of the same retired surface; the userId-search
 *    is encoded in [applyFilterAndSort]'s case-insensitive search
 *    branch and stands independent of the retired cite-target. The
 *    SRP / DIP / OCP sub-sections all stand on their own merits past
 *    the §§365 + 366 fulfilled landings. The [AdminComplaintViewModel]
 *    remains LIVE as the canonical admin-side Complaint list VM
 *    consumed by the rework `:ui` `AdminComplaintScreen` + the
 *    `AdminComplaintReworkScreenRoute` adapter. Original §253-era prose
 *    preserved verbatim per the audit-trail-preservation convention —
 *    the citation is historical record of the design lineage including
 *    the now-retired legacy admin chain.
 */
class AdminComplaintViewModel(
    private val observeAllComplaints: ObserveAllComplaintsUseCase,
    private val changeStatus: ChangeComplaintStatusUseCase,
    private val addClosureReason: AddClosureReasonUseCase,
    private val adminDeleteComplaint: AdminDeleteComplaintUseCase,
    private val adminEditComplaint: AdminEditComplaintUseCase,
) : MviViewModel<AdminComplaintState, AdminComplaintIntent, AdminComplaintEffect>(
    initialState = AdminComplaintState(),
) {

    init {
        loadList()
    }

    override suspend fun handle(intent: AdminComplaintIntent) {
        when (intent) {
            is AdminComplaintIntent.OnRetry -> loadList()
            is AdminComplaintIntent.OnToggleStatsCard -> updateState { it.copy(showStats = !it.showStats) }
            is AdminComplaintIntent.OnSearchChange -> {
                val query = intent.query
                updateState {
                    it.copy(
                        searchQuery = query,
                        filtered = applyFilterAndSort(it.all, query, it.selectedStatus, it.selectedType, it.selectedAppVersion, it.selectedSort),
                    )
                }
            }
            is AdminComplaintIntent.OnClearSearch -> {
                updateState {
                    it.copy(
                        searchQuery = "",
                        filtered = applyFilterAndSort(it.all, "", it.selectedStatus, it.selectedType, it.selectedAppVersion, it.selectedSort),
                    )
                }
            }
            is AdminComplaintIntent.OnStatusFilter -> {
                val status = intent.status
                updateState {
                    it.copy(
                        selectedStatus = status,
                        filtered = applyFilterAndSort(it.all, it.searchQuery, status, it.selectedType, it.selectedAppVersion, it.selectedSort),
                    )
                }
            }
            is AdminComplaintIntent.OnTypeFilter -> {
                val type = intent.type
                updateState {
                    it.copy(
                        selectedType = type,
                        filtered = applyFilterAndSort(it.all, it.searchQuery, it.selectedStatus, type, it.selectedAppVersion, it.selectedSort),
                    )
                }
            }
            is AdminComplaintIntent.OnAppVersionFilter -> {
                val appVersion = intent.appVersion
                updateState {
                    it.copy(
                        selectedAppVersion = appVersion,
                        filtered = applyFilterAndSort(it.all, it.searchQuery, it.selectedStatus, it.selectedType, appVersion, it.selectedSort),
                    )
                }
            }
            is AdminComplaintIntent.OnSortChange -> {
                val mode = intent.mode
                updateState {
                    it.copy(
                        selectedSort = mode,
                        filtered = applyFilterAndSort(it.all, it.searchQuery, it.selectedStatus, it.selectedType, it.selectedAppVersion, mode),
                    )
                }
            }
            is AdminComplaintIntent.OnRowClick -> handleRowClick(intent.complaint)
            is AdminComplaintIntent.OnDismissActionDialog -> handleDismissDialog()
            is AdminComplaintIntent.OnSelectAction -> handleSelectAction(intent.mode)
            is AdminComplaintIntent.OnSubmitStatusChange -> handleSubmitStatusChange(intent.newStatus)
            is AdminComplaintIntent.OnSubmitClosureReason -> handleSubmitClosureReason(intent.reason)
            is AdminComplaintIntent.OnConfirmDelete -> handleConfirmDelete()
            is AdminComplaintIntent.OnSubmitEdit -> handleSubmitEdit(intent.type, intent.subject, intent.body)
            is AdminComplaintIntent.OnCopyBody -> handleCopyBody()
        }
    }

    private fun handleRowClick(complaint: ComplaintSummary) {
        if (state.value.isSubmittingAction) return
        updateState {
            it.copy(
                actionDialogMode = AdminActionDialogMode.MENU,
                activeComplaint = complaint,
            )
        }
    }

    private fun handleDismissDialog() {
        if (state.value.isSubmittingAction) return
        updateState {
            it.copy(
                actionDialogMode = AdminActionDialogMode.NONE,
                activeComplaint = null,
            )
        }
    }

    private fun handleSelectAction(mode: AdminActionDialogMode) {
        if (state.value.isSubmittingAction) return
        if (mode == AdminActionDialogMode.NONE) return
        if (state.value.activeComplaint == null) return
        updateState { it.copy(actionDialogMode = mode) }
    }

    private fun handleSubmitStatusChange(newStatus: ComplaintStatus) {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = changeStatus(target, newStatus)
            completeAction(result, action = AdminComplaintAction.STATUS_UPDATED)
        }
    }

    private fun handleSubmitClosureReason(reason: String) {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = addClosureReason(target, reason)
            completeAction(result, action = AdminComplaintAction.CLOSURE_REASON_ADDED)
        }
    }

    private fun handleConfirmDelete() {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = adminDeleteComplaint(target.id)
            completeAction(result, action = AdminComplaintAction.DELETED)
        }
    }

    private fun handleSubmitEdit(type: ComplaintType, subject: String, body: String) {
        val current = state.value
        if (current.isSubmittingAction) return
        val target = current.activeComplaint ?: return
        updateState { it.copy(isSubmittingAction = true) }
        viewModelScope.launch {
            val result = adminEditComplaint(target, type, subject, body)
            completeAction(result, action = AdminComplaintAction.UPDATED)
        }
    }

    private suspend fun handleCopyBody() {
        emit(AdminComplaintEffect.ShowActionSuccess(AdminComplaintAction.BODY_COPIED))
    }

    private suspend fun completeAction(result: Result<Unit>, action: AdminComplaintAction) {
        if (result.isSuccess) {
            updateState {
                it.copy(
                    isSubmittingAction = false,
                    actionDialogMode = AdminActionDialogMode.NONE,
                    activeComplaint = null,
                )
            }
            emit(AdminComplaintEffect.ShowActionSuccess(action))
            loadList()
        } else {
            // The throwable (often a raw Firestore SDK string) is logged, never surfaced to the
            // operator: the snackbar shows a generic localized error resolved in :ui.
            Logger.withTag(TAG).w(result.exceptionOrNull()) { "admin complaint action $action failed" }
            updateState { it.copy(isSubmittingAction = false) }
            emit(AdminComplaintEffect.ShowActionFailure)
        }
    }

    private var loadJob: Job? = null

    private fun loadList() {
        // Cancel-before-relaunch: a fresh load supersedes any in-flight one so two concurrent
        // observeAllComplaints() results can't race on updateState (last-writer-wins could land the
        // older snapshot of all/filtered/statistics). Cancel-before-relaunch is the documented
        // house pattern for ordering-sensitive loads (see ReaderViewModel).
        loadJob?.cancel()
        updateState { it.copy(isLoading = true, error = null) }
        loadJob = viewModelScope.launch {
            val result = observeAllComplaints()
            if (result.isSuccess) {
                val list = result.getOrNull().orEmpty()
                updateState {
                    it.copy(
                        isLoading = false,
                        error = null,
                        all = list,
                        filtered = applyFilterAndSort(list, it.searchQuery, it.selectedStatus, it.selectedType, it.selectedAppVersion, it.selectedSort),
                        statistics = computeStatistics(list),
                    )
                }
            } else {
                // Don't leak the raw exception text into state (the inline error pane shows a
                // generic localized message in :ui); log the cause for diagnostics.
                Logger.withTag(TAG).w(result.exceptionOrNull()) { "loading all complaints failed" }
                updateState {
                    it.copy(
                        isLoading = false,
                        error = LOAD_FAILED,
                        all = emptyList(),
                        filtered = emptyList(),
                        statistics = AdminComplaintStatistics(),
                    )
                }
            }
        }
    }

    private fun computeStatistics(list: List<ComplaintSummary>): AdminComplaintStatistics {
        return AdminComplaintStatistics(
            total = list.size,
            byStatus = list.groupBy { it.status }.mapValues { it.value.size },
            byAppVersion = list.mapNotNull { it.appVersion }.groupingBy { it }.eachCount(),
        )
    }

    private fun applyFilterAndSort(
        all: List<ComplaintSummary>,
        query: String,
        status: ComplaintStatus?,
        type: ComplaintType?,
        appVersion: String?,
        sort: AdminSortMode,
    ): List<ComplaintSummary> {
        val filtered = all.filter { complaint ->
            val matchesSearch = query.isEmpty() ||
                complaint.subject.contains(query, ignoreCase = true) ||
                complaint.body.contains(query, ignoreCase = true) ||
                complaint.id.contains(query, ignoreCase = true) ||
                complaint.userId.contains(query, ignoreCase = true)
            val matchesStatus = status == null || complaint.status == status
            val matchesType = type == null || complaint.type == type
            val matchesAppVersion = appVersion == null || complaint.appVersion == appVersion
            matchesSearch && matchesStatus && matchesType && matchesAppVersion
        }
        return when (sort) {
            AdminSortMode.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
            AdminSortMode.DATE_ASC -> filtered.sortedBy { it.createdAt }
            AdminSortMode.STATUS -> filtered.sortedBy { it.status.ordinal }
            AdminSortMode.TYPE -> filtered.sortedBy { it.type.ordinal }
            AdminSortMode.USER_ID -> filtered.sortedBy { it.userId }
            AdminSortMode.APP_VERSION -> filtered.sortedBy { it.appVersion }
            AdminSortMode.APP_VERSION_DESC -> filtered.sortedByDescending { it.appVersion }
        }
    }

    private companion object {
        const val TAG = "AdminComplaintVM"

        // Non-leaking sentinel for [AdminComplaintState.error]; :ui renders a generic localized
        // message whenever error != null (the value itself is never shown to the user).
        const val LOAD_FAILED = "load_failed"
    }
}
