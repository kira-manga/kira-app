package me.manga.kira.ui.complaint.admin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.presentation.complaint.admin.AdminActionDialogMode
import me.manga.kira.presentation.complaint.admin.AdminComplaintAction
import me.manga.kira.presentation.complaint.admin.AdminComplaintEffect
import me.manga.kira.presentation.complaint.admin.AdminComplaintIntent
import me.manga.kira.presentation.complaint.admin.AdminComplaintState
import me.manga.kira.presentation.complaint.admin.AdminComplaintStatistics
import me.manga.kira.presentation.complaint.admin.AdminComplaintViewModel
import me.manga.kira.presentation.complaint.admin.AdminSortMode
import me.manga.kira.ui.complaint.ClosureReasonCard
import me.manga.kira.ui.complaint.ComplaintArrowBack
import me.manga.kira.ui.complaint.ComplaintStatusChip
import me.manga.kira.ui.complaint.displayName
import me.manga.kira.ui.complaint.formatComplaintTimestamp
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraErrorState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.admincomplaint_by_app_version
import me.manga.kira.ui.generated.resources.admincomplaint_by_status
import me.manga.kira.ui.generated.resources.admincomplaint_filter_app_version
import me.manga.kira.ui.generated.resources.admincomplaint_filter_sort
import me.manga.kira.ui.generated.resources.admincomplaint_filter_status
import me.manga.kira.ui.generated.resources.admincomplaint_filter_type
import me.manga.kira.ui.generated.resources.admincomplaint_no_complaints
import me.manga.kira.ui.generated.resources.admincomplaint_no_matches
import me.manga.kira.ui.generated.resources.admincomplaint_row_user
import me.manga.kira.ui.generated.resources.admincomplaint_search_placeholder
import me.manga.kira.ui.generated.resources.admincomplaint_sort_app_version_asc
import me.manga.kira.ui.generated.resources.admincomplaint_sort_app_version_desc
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.clear
import me.manga.kira.ui.generated.resources.desc_newest_first
import me.manga.kira.ui.generated.resources.desc_oldest_first
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.filter_all
import me.manga.kira.ui.generated.resources.np_complaint_action_closure_added
import me.manga.kira.ui.generated.resources.np_complaint_action_deleted
import me.manga.kira.ui.generated.resources.np_complaint_action_status_updated
import me.manga.kira.ui.generated.resources.np_complaint_action_updated
import me.manga.kira.ui.generated.resources.np_complaint_body_copied
import me.manga.kira.ui.generated.resources.np_admin_active_filter_search
import me.manga.kira.ui.generated.resources.np_admin_active_filter_status
import me.manga.kira.ui.generated.resources.np_admin_active_filter_type
import me.manga.kira.ui.generated.resources.np_admin_active_filter_version
import me.manga.kira.ui.generated.resources.np_admin_active_filters_prefix
import me.manga.kira.ui.generated.resources.np_admin_complaint_management
import me.manga.kira.ui.generated.resources.np_admin_complaints_found_count
import me.manga.kira.ui.generated.resources.np_admin_feedback_id_format
import me.manga.kira.ui.generated.resources.np_complaints_statistics
import me.manga.kira.ui.generated.resources.np_hide_statistics
import me.manga.kira.ui.generated.resources.np_loading_complaints
import me.manga.kira.ui.generated.resources.np_no_complaints_message
import me.manga.kira.ui.generated.resources.np_no_complaints_title
import me.manga.kira.ui.generated.resources.np_reply_to_complaint_id
import me.manga.kira.ui.generated.resources.np_show_statistics
import me.manga.kira.ui.generated.resources.np_total_complaints_label
import me.manga.kira.ui.generated.resources.np_try_different_filters
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.sort_status
import me.manga.kira.ui.generated.resources.sort_type
import me.manga.kira.ui.generated.resources.sort_user_id
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Admin Complaint Dashboard screen — Compose entry point for the AdminComplaint MVI slice.
 *
 * Phase 7.x.complaint.admin rework. Renders [AdminComplaintState] (loading / error /
 * empty / list with search + 2-axis filter chips) and dispatches [AdminComplaintIntent]. The
 * legacy admin counterpart at `composeApp/.../admin/complaint/AdminComplaintScreen.kt` is a
 * 919-line surface with statistics card, status-change dialog, edit dialog, closure-reason
 * dialog, delete dialog, sort dropdown (7 modes), app-version filter, long-press body-copy, and
 * monogram-style admin chips. Later slices have since ported the statistics card, sort dropdown,
 * app-version filter, and Material icons on top of the original load + display + search + 2-axis
 * filter foundation.
 *
 * **Visual parity vs the legacy admin screen**:
 *  - Layout shape — top app bar with title + Back action, scrolling list of cards each showing
 *    subject + body + status + type + userId (truncated 8-char) + relative date placeholder.
 *  - Search box at the top of the list filters across subject / body / id / userId.
 *  - Two filter-chip rows (status + type) below the search.
 *  - **Sort dropdown** ([SortDropdown]) — 7 sort modes over the filtered list, replacing the
 *    earlier reliance on native Firestore order.
 *  - **App-version filter** — a FilterChip row fed by `ComplaintSummary.appVersion`, the
 *    structured field the summary now carries (no banned `Any` metadata map needed).
 *  - **Statistics card** (Phase 7.x.complaint.admin.stats extension): rendered as the FIRST item
 *    in the LazyColumn above the search/filter section. Shows total count + per-status row from
 *    [AdminComplaintState.statistics] — reflects the FULL `state.all` inventory, NOT the filtered
 *    subset (matches legacy posture where `AdminStatisticsCard(complaints = allComplaints)` is
 *    passed the full list). Only renders when `state.all` is non-empty (the empty-state and
 *    loading/error branches skip the LazyColumn entirely).
 *  - **Material icons** — the screen uses Material glyphs directly
 *    (Visibility / VisibilityOff / FilterAlt / ArrowDropDown) for the filter and sort affordances.
 *  - User-facing labels resolve through compose-resources `stringResource(Res.string.*)`
 *    (UP-3 localization track).
 *
 * **State-to-UI mapping** (mirrors user-side foundation pattern):
 *  - `state.isLoading` → centered [CircularProgressIndicator].
 *  - `state.error != null` → centered error message + Retry [TextButton].
 *  - `state.all.isEmpty()` (after load) → centered "No complaints submitted" placeholder.
 *  - `state.all.isNotEmpty()` → search box + filter rows + LazyColumn of cards.
 *  - `state.all.isNotEmpty() && state.filtered.isEmpty()` → "No matches" placeholder under the
 *    search + chips.
 *
 * **`onBack` callback**: the `:ui` module deliberately depends on `:presentation` but NOT
 * `androidx.navigation`. Back navigation is bridged by the `:composeApp` route adapter
 * ([me.manga.kira.navigation.routes.AdminComplaintReworkScreenRoute]).
 *
 * Stateless inner [AdminComplaintScreenContent] mirrors the foundation pattern — separating
 * "wire to VM" from "render state".
 *
 * **Audit-trail postscript** (Phase 9.x.cluster4.staleKdocSweep.cascade,
 * Task #459, 2026-05-28): a stale citation into the §366-retired legacy
 * admin Complaint surface appears above:
 *  - Lines 72-77 (foundation-slice preamble): "The legacy admin counterpart
 *    at `composeApp/.../admin/complaint/AdminComplaintScreen.kt` is a
 *    919-line surface ...".
 * The legacy `composeApp/.../admin/complaint/AdminComplaintScreen.kt`
 * (along with its sibling legacy admin VM + helpers + Koin binding) was
 * retired in Phase 9.x.admincomplaint.retire (§366 sweep, commit `48a5c2b`
 * "(1/2): delete orphan legacy admin VM + screen + 2 helpers + drop Koin
 * binding"); verified by a filesystem check returning zero hits for that
 * path. The foundation-subset rationale (load + display + search + 2-axis
 * filter only; intentional sort-dropdown omission, intentional
 * app-version-filter omission, intentional icon omission, inline literal
 * strings, statistics-card-from-`state.all` posture) and the layered
 * MVI / state-to-UI mapping all stand on their own merits — the rework
 * `:ui` design language's icon-set posture, the `ComplaintSummary`'s
 * banned-`Any`-driven `metadata` drop, the Phase 10 i18n lift strategy,
 * and the legacy parity baselines for filter/sort/stats fan-out are
 * documented inline above and independent of which legacy file
 * originally implemented the parity precedent. Original §253-era prose
 * preserved verbatim per the audit-trail-preservation convention — the
 * citation is historical record of the design lineage; the rework
 * AdminComplaintScreen continues to render the admin dashboard correctly
 * through the legacy retire.
 */
@Composable
fun AdminComplaintScreen(
    viewModel: AdminComplaintViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()

    // Effects carry only the semantic action; resolve localized copy here (stringResource is not
    // callable inside the suspend LaunchedEffect block).
    val statusUpdatedMessage = stringResource(Res.string.np_complaint_action_status_updated)
    val closureAddedMessage = stringResource(Res.string.np_complaint_action_closure_added)
    val deletedMessage = stringResource(Res.string.np_complaint_action_deleted)
    val updatedMessage = stringResource(Res.string.np_complaint_action_updated)
    val bodyCopiedMessage = stringResource(Res.string.np_complaint_body_copied)
    val actionFailureMessage = stringResource(Res.string.error_occurred)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            val message = when (effect) {
                is AdminComplaintEffect.ShowActionSuccess -> when (effect.action) {
                    AdminComplaintAction.STATUS_UPDATED -> statusUpdatedMessage
                    AdminComplaintAction.CLOSURE_REASON_ADDED -> closureAddedMessage
                    AdminComplaintAction.DELETED -> deletedMessage
                    AdminComplaintAction.UPDATED -> updatedMessage
                    AdminComplaintAction.BODY_COPIED -> bodyCopiedMessage
                }
                AdminComplaintEffect.ShowActionFailure -> actionFailureMessage
            }
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    AdminComplaintScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdminComplaintScreenContent(
    state: AdminComplaintState,
    snackbarHostState: SnackbarHostState,
    onIntent: (AdminComplaintIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorMessage = state.error
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // GAP-CMP-A3 — native `AdminComplaintScreen.kt:162-188` uses a CenterAlignedTopAppBar
            // with a Bold title ("Admin Complaint Management") and a background container colour.
            // The bar stays pinned in the Scaffold (the rework cluster's consistent topology) — the
            // native scroll-away behaviour (bar as a LazyColumn item) is intentionally not ported;
            // pinned is acceptable per the finding. Title alignment / weight / text / container all
            // reconciled to native here.
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.np_admin_complaint_management),
                        fontWeight = FontWeight.Bold,
                    )
                },
                // GAP-CMP-14 — leading navigationIcon back arrow (was an actions-slot TextButton),
                // harmonizing with the user-side ComplaintScreen + native `AdminComplaintScreen.kt`.
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ComplaintArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
                // GAP-CMP-15 — show/hide-statistics toggle, mirroring native's Visibility /
                // VisibilityOff IconButton.
                actions = {
                    IconButton(onClick = { onIntent(AdminComplaintIntent.OnToggleStatsCard) }) {
                        Icon(
                            imageVector = if (state.showStats) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = stringResource(
                                if (state.showStats) {
                                    Res.string.np_hide_statistics
                                } else {
                                    Res.string.np_show_statistics
                                },
                            ),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            // GAP-CMP-26 / GAP-CMP-27 — reuse the shared design-system state views
            // (KiraLoadingState / KiraErrorState / KiraEmptyState) instead of the prior hand-rolled
            // centered spinner / error-text + Retry / plain "no complaints" Text, matching the
            // user-side ComplaintScreen and the rest of the rework cluster (memory: design-system).
            when {
                state.isLoading -> {
                    // GAP-CMP-A5 — native `AdminComplaintScreen.kt:192-201` LoadingState carries a
                    // "Loading complaints…" message under the spinner. The shared KiraLoadingState
                    // (in `:ui/components`, out of this slice's edit scope) takes no message, so
                    // render a local spinner + message column here to add the native message line.
                    AdminLoadingState(message = stringResource(Res.string.np_loading_complaints))
                }
                errorMessage != null -> {
                    KiraErrorState(
                        // state.error is a non-leaking sentinel; show a generic localized message.
                        message = stringResource(Res.string.error_occurred),
                        retryLabel = stringResource(Res.string.retry),
                        onRetry = { onIntent(AdminComplaintIntent.OnRetry) },
                    )
                }
                state.all.isEmpty() -> {
                    // GAP-CMP-A5 — native empty state shows a title ("No Complaints Found") AND a
                    // supporting message ("No complaints have been submitted yet."). Route both.
                    KiraEmptyState(
                        title = stringResource(Res.string.np_no_complaints_title),
                        message = stringResource(Res.string.np_no_complaints_message),
                    )
                }
                else -> {
                    AdminComplaintList(
                        state = state,
                        onIntent = onIntent,
                    )
                }
            }

            val activeComplaint = state.activeComplaint
            if (state.actionDialogMode != AdminActionDialogMode.NONE && activeComplaint != null) {
                AdminComplaintActionDialog(
                    complaint = activeComplaint,
                    mode = state.actionDialogMode,
                    isSubmitting = state.isSubmittingAction,
                    onIntent = onIntent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdminComplaintList(
    state: AdminComplaintState,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val availableAppVersions = remember(state.all) {
        // Parseable numeric versions sort first (descending, component-wise); anything with no
        // numeric parts falls into a separate, lexicographically-descending group. Partitioning
        // keeps the comparator a consistent total order (no per-pair strategy switch).
        val (numeric, nonNumeric) = state.all.mapNotNull { it.appVersion }.distinct()
            .partition { it.split(".").any { part -> part.toIntOrNull() != null } }
        val numericSorted = numeric.sortedWith { a, b ->
            val aParts = a.split(".").mapNotNull { it.toIntOrNull() }
            val bParts = b.split(".").mapNotNull { it.toIntOrNull() }
            var result = 0
            for (i in 0 until maxOf(aParts.size, bParts.size)) {
                val comparison = (bParts.getOrNull(i) ?: 0).compareTo(aParts.getOrNull(i) ?: 0)
                if (comparison != 0) {
                    result = comparison
                    break
                }
            }
            result
        }
        numericSorted + nonNumeric.sortedDescending()
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        // GAP-CMP-15 — StatisticsCard renders only when state.showStats (toggled from the
        // TopAppBar Visibility action), matching native's `showStats`-gated card.
        if (state.showStats) {
            item(key = "__stats__") {
                StatisticsCard(statistics = state.statistics)
            }
        }

        item(key = "__search_filter__") {
            SearchAndFilterSection(
                searchQuery = state.searchQuery,
                selectedStatus = state.selectedStatus,
                selectedType = state.selectedType,
                selectedAppVersion = state.selectedAppVersion,
                availableAppVersions = availableAppVersions,
                selectedSort = state.selectedSort,
                resultsCount = state.filtered.size,
                onIntent = onIntent,
            )
        }

        val noMatches = state.filtered.isEmpty() &&
            (state.searchQuery.isNotEmpty() ||
                state.selectedStatus != null ||
                state.selectedType != null ||
                state.selectedAppVersion != null)

        if (noMatches) {
            item(key = "__no_matches__") {
                // GAP-CMP-A5 — native `AdminComplaintScreen.kt:260-270` renders the no-results
                // state as a full EmptyState: a large FilterAlt icon + "No results found" title +
                // "Try adjusting your search or filters" message. Route through the shared
                // KiraEmptyState (was a compact SearchOff icon + terse "No matches" one-liner).
                KiraEmptyState(
                    title = stringResource(Res.string.no_results_found),
                    message = stringResource(Res.string.np_try_different_filters),
                    icon = Icons.Default.FilterAlt,
                    modifier = Modifier.padding(top = spacing.lg),
                )
            }
        } else {
            items(items = state.filtered, key = { it.id.ifEmpty { it.subject + it.createdAt } }) { complaint ->
                AdminComplaintRow(
                    complaint = complaint,
                    onClick = { onIntent(AdminComplaintIntent.OnRowClick(complaint)) },
                    onLongClickBody = { onIntent(AdminComplaintIntent.OnCopyBody) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilterSection(
    searchQuery: String,
    selectedStatus: ComplaintStatus?,
    selectedType: ComplaintType?,
    selectedAppVersion: String?,
    availableAppVersions: List<String>,
    selectedSort: AdminSortMode,
    resultsCount: Int,
    onIntent: (AdminComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { onIntent(AdminComplaintIntent.OnSearchChange(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.admincomplaint_search_placeholder)) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    TextButton(onClick = { onIntent(AdminComplaintIntent.OnClearSearch) }) {
                        Text(stringResource(Res.string.clear))
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        Text(
            text = stringResource(Res.string.admincomplaint_filter_status),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            item(key = "status-all") {
                FilterChip(
                    selected = selectedStatus == null,
                    onClick = { onIntent(AdminComplaintIntent.OnStatusFilter(null)) },
                    label = { Text(stringResource(Res.string.filter_all)) },
                )
            }
            items(items = ComplaintStatus.entries.toTypedArray(), key = { it.name }) { status ->
                FilterChip(
                    selected = selectedStatus == status,
                    onClick = {
                        val toggled = if (selectedStatus == status) null else status
                        onIntent(AdminComplaintIntent.OnStatusFilter(toggled))
                    },
                    label = { Text(status.displayName()) },
                )
            }
        }

        Text(
            text = stringResource(Res.string.admincomplaint_filter_type),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            item(key = "type-all") {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { onIntent(AdminComplaintIntent.OnTypeFilter(null)) },
                    label = { Text(stringResource(Res.string.filter_all)) },
                )
            }
            items(items = ComplaintType.entries.toTypedArray(), key = { it.name }) { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = {
                        val toggled = if (selectedType == type) null else type
                        onIntent(AdminComplaintIntent.OnTypeFilter(toggled))
                    },
                    label = { Text(type.displayName()) },
                )
            }
        }

        if (availableAppVersions.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.admincomplaint_filter_app_version),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                item(key = "appversion-all") {
                    FilterChip(
                        selected = selectedAppVersion == null,
                        onClick = { onIntent(AdminComplaintIntent.OnAppVersionFilter(null)) },
                        label = { Text(stringResource(Res.string.filter_all)) },
                    )
                }
                items(items = availableAppVersions, key = { it }) { version ->
                    FilterChip(
                        selected = selectedAppVersion == version,
                        onClick = {
                            val toggled = if (selectedAppVersion == version) null else version
                            onIntent(AdminComplaintIntent.OnAppVersionFilter(toggled))
                        },
                        label = { Text("v$version") },
                    )
                }
            }
        }

        Text(
            text = stringResource(Res.string.admincomplaint_filter_sort),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        SortDropdown(
            selectedSort = selectedSort,
            onSelect = { onIntent(AdminComplaintIntent.OnSortChange(it)) },
        )

        // GAP-CMP-A-COUNT — native `AdminComplaintScreen.kt:554-558` uses a single
        // `complaints_found_count` = "%d complaints found" form (no separate singular/plural
        // strings). Reconciled to the native wording.
        Text(
            text = stringResource(Res.string.np_admin_complaints_found_count, resultsCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // GAP-CMP-A-FILTERS — native `AdminComplaintScreen.kt:560-575` renders an
        // "Active filters: <Status: X, Type: Y, Version: vZ, Search: "q">" summary line in
        // bodySmall/primary under the results count whenever any filter is active. Ported here.
        val activeFilters = buildList {
            selectedStatus?.let {
                add(stringResource(Res.string.np_admin_active_filter_status, it.displayName()))
            }
            selectedType?.let {
                add(stringResource(Res.string.np_admin_active_filter_type, it.displayName()))
            }
            selectedAppVersion?.let {
                add(stringResource(Res.string.np_admin_active_filter_version, it))
            }
            if (searchQuery.isNotEmpty()) {
                add(stringResource(Res.string.np_admin_active_filter_search, searchQuery))
            }
        }
        if (activeFilters.isNotEmpty()) {
            Text(
                text = stringResource(
                    Res.string.np_admin_active_filters_prefix,
                    activeFilters.joinToString(", "),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(spacing.xs))
    }
}

@Composable
private fun SortDropdown(
    selectedSort: AdminSortMode,
    onSelect: (AdminSortMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // GAP-CMP-18 — use a real dropdown-arrow icon (Icons.Filled.ArrowDropDown) instead of the
        // prior literal "▾" glyph affix; the 7 sort modes are unchanged.
        OutlinedButton(onClick = { expanded = true }) {
            Text(text = sortLabel(selectedSort))
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            AdminSortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortLabel(mode)) },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun sortLabel(mode: AdminSortMode): String = when (mode) {
    AdminSortMode.DATE_DESC -> stringResource(Res.string.desc_newest_first)
    AdminSortMode.DATE_ASC -> stringResource(Res.string.desc_oldest_first)
    AdminSortMode.STATUS -> stringResource(Res.string.sort_status)
    AdminSortMode.TYPE -> stringResource(Res.string.sort_type)
    AdminSortMode.USER_ID -> stringResource(Res.string.sort_user_id)
    AdminSortMode.APP_VERSION -> stringResource(Res.string.admincomplaint_sort_app_version_asc)
    AdminSortMode.APP_VERSION_DESC -> stringResource(Res.string.admincomplaint_sort_app_version_desc)
}

/**
 * GAP-CMP-A5 — local loading state with a spinner + a "Loading complaints…" message under it,
 * mirroring native `LoadingState.kt` (icon + message). The shared design-system
 * [me.manga.kira.ui.components.KiraLoadingState] exposes no message slot (and lives in
 * `:ui/components`, outside this slice's edit scope), so the message-bearing loading layout is
 * rendered here. Fills the parent and centres its content like the shared state views.
 */
@Composable
private fun AdminLoadingState(message: String) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = spacing.md),
        )
    }
}

@Composable
private fun StatisticsCard(statistics: AdminComplaintStatistics) {
    val spacing = LocalSpacing.current
    // GAP-CMP-08 — elevated Card r16/elev2, aligned with the cluster card style.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // GAP-CMP-A4 — native `AdminComplaintScreen.kt:600-605` title is "Complaints
            // Statistics", titleMedium, Bold, primary-coloured (was "Statistics" SemiBold
            // onSurface).
            Text(
                text = stringResource(Res.string.np_complaints_statistics),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            // GAP-CMP-A4 — native `AdminComplaintScreen.kt:610-623` renders the total as a
            // SpaceBetween "Total Complaints" label + Bold count row (was a single inline
            // "Total complaints: N" string).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(Res.string.np_total_complaints_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = statistics.total.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val nonZeroBuckets = statistics.byStatus.entries.filter { it.value > 0 }
            if (nonZeroBuckets.isNotEmpty()) {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = stringResource(Res.string.admincomplaint_by_status),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                nonZeroBuckets.forEach { (status, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // GAP-CMP-A4 — native renders a coloured StatusChip pill in the by-status
                        // breakdown (visually consistent with the cards) rather than plain text.
                        ComplaintStatusChip(status = status)
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // GAP-CMP-16 — "By app version" mirrors native `AdminComplaintScreen.kt:581-688`:
            // shown only when >1 distinct app version is present, capped to the TOP-5 by count
            // (descending). Status rows above already gate on count>0.
            val nonZeroVersions = statistics.byAppVersion.entries.filter { it.value > 0 }
            val versionBuckets = nonZeroVersions
                .sortedByDescending { it.value }
                .take(5)
            if (nonZeroVersions.size > 1) {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = stringResource(Res.string.admincomplaint_by_app_version),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                versionBuckets.forEach { (version, count) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "v$version",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AdminComplaintRow(
    complaint: ComplaintSummary,
    onClick: () -> Unit,
    onLongClickBody: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // LocalClipboardManager is deprecated in favor of LocalClipboard, but the replacement's
    // Clipboard.setClipEntry is suspend and ClipEntry has no common text factory in CMP 1.11.1
    // (only ClipEntry(nativeClipEntry), platform-specific) — migrating commonMain needs new
    // expect/actual construction, out of scope for this source-only deprecation pass. Retained.
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    // GAP-CMP-08 — elevated Card (2.dp elevation, RoundedCornerShape(16.dp)) matching native
    // `ComplaintCard.kt:30-37`, aligned across the cluster (user + admin + stats).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            // GAP-CMP-05 — type display-name is the PRIMARY line (maxLines 2), subject is the
            // secondary line, matching native `AdminComplaintCard.kt` header emphasis.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = complaint.type.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(spacing.xs))
                ComplaintStatusChip(status = complaint.status)
            }
            if (complaint.subject.isNotEmpty()) {
                Text(
                    // GAP-CMP-A-SUBJ — native `AdminComplaintScreen.kt:724-728` renders the subject
                    // in bodyMedium/onSurfaceVariant (the KMP row had it at bodySmall). Reconciled to
                    // bodyMedium for typography parity.
                    text = complaint.subject,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // GAP-CMP-12 — reply-to-complaint reference line (from metadata["replyto"], threaded
            // onto ComplaintSummary.replyToId by the :data mapper). Matches native
            // `AdminComplaintScreen.kt:713-768` header `replyToId` text.
            complaint.replyToId?.let { replyToId ->
                Text(
                    text = stringResource(Res.string.np_reply_to_complaint_id, replyToId),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = complaint.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(complaint.body))
                        onLongClickBody()
                    },
                ),
            )
            // GAP-CMP-23 — inline closure-reason card on CLOSED / PINNED complaints that carry a
            // non-blank `reason` (threaded onto ComplaintSummary.reason by the :data mapper).
            // Mirrors native `AdminComplaintScreen.kt:818-825` (body → closure → footer order),
            // reusing the same ClosureReasonCard the user-side ComplaintRow renders.
            val reason = complaint.reason
            if (!reason.isNullOrBlank() &&
                (complaint.status == ComplaintStatus.CLOSED || complaint.status == ComplaintStatus.PINNED)
            ) {
                ClosureReasonCard(reason = reason)
            }
            // GAP-CMP-05 — the type display-name moved to the PRIMARY header line above, so the
            // bottom metadata row now carries only user-id + app-version (matching native
            // `AdminComplaintScreen.kt:752-768`, which shows `user_id_format` + the version chip
            // without a redundant type label).
            Row(
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.admincomplaint_row_user, complaint.userId.take(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                // GAP-CMP-12 — app-version chip with native styling: tertiaryContainer Surface,
                // Monospace, r4 (matches native `AdminComplaintScreen.kt` app-version chip) rather
                // than the prior inline Monospace text.
                complaint.appVersion?.let { version ->
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = "v$version",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            // Phase 7.x.complaint.admindate — lift the user-side `formatComplaintTimestamp`
            // helper into the admin row footer. Legacy admin row at
            // `composeApp/.../admin/complaint/AdminComplaintScreen.kt:861` renders
            // `formatTimestamp(complaint.createdAt?.toEpochMilliseconds() ?: 0L)` in a Column
            // alongside the ID line; the rework's `Instant?` is checked directly so admin-
            // pinned FAQ entries (createdAt == null, from PinnedComplaints.kt) omit the
            // Text entirely — same null-omission posture as the user-side ComplaintRow.
            //
            // GAP-CMP-A1 — native `AdminComplaintScreen.kt:835-847` renders a footer Column of
            // the timestamp followed by a truncated complaint-id line ("ID: <8 chars>…",
            // Monospace, bodySmall, onSurfaceVariant) — a key piece of moderation metadata. The
            // KMP row previously dropped the id. Restore it under the timestamp (omitted when id
            // is empty, e.g. pinned-FAQ / pre-write records).
            Column {
                complaint.createdAt?.let { createdAt ->
                    Text(
                        text = formatComplaintTimestamp(createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (complaint.id.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.np_admin_feedback_id_format, complaint.id.take(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
