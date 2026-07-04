package me.manga.kira.ui.complaint

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.domain.model.complaint.ComplaintSummary
import me.manga.kira.presentation.complaint.ActionDialogMode
import me.manga.kira.presentation.complaint.ComplaintAction
import me.manga.kira.presentation.complaint.ComplaintEffect
import me.manga.kira.presentation.complaint.ComplaintIntent
import me.manga.kira.presentation.complaint.ComplaintState
import me.manga.kira.presentation.complaint.ComplaintViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraErrorState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.back
import me.manga.kira.ui.generated.resources.clear
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.feedback_manager_title
import me.manga.kira.ui.generated.resources.filter_all
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.np_complaint_action_deleted
import me.manga.kira.ui.generated.resources.np_complaint_action_reply_sent
import me.manga.kira.ui.generated.resources.np_complaint_action_updated
import me.manga.kira.ui.generated.resources.np_complaint_body_copied
import me.manga.kira.ui.generated.resources.np_user_feedback_id_format
import me.manga.kira.ui.generated.resources.np_user_feedbacks_found_count
import me.manga.kira.ui.generated.resources.np_user_loading_feedback
import me.manga.kira.ui.generated.resources.np_user_search_complaints_hint
import me.manga.kira.ui.generated.resources.np_no_feedback_message
import me.manga.kira.ui.generated.resources.np_no_feedback_title
import me.manga.kira.ui.generated.resources.np_try_different_search
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.unknown
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/**
 * Feedback Manager screen — Compose entry point for the Complaint MVI slice.
 *
 * Phase 7.x.complaint.foundation rework. Renders [ComplaintState] (loading / error / empty /
 * list with search + filter chips) and dispatches [ComplaintIntent]. Phase 7.x.complaint.actions
 * rework append: row clicks open the [ComplaintActionDialog], and one-shot effects
 * ([ComplaintEffect.ShowSuccessMessage] / [ComplaintEffect.ShowErrorMessage]) drive a Material 3
 * [SnackbarHost] anchored to the Scaffold.
 *
 * **Visual parity vs the legacy `composeApp/.../ComplaintScreen.kt`** (unchanged from
 * foundation slice):
 *  - Layout shape — top app bar with title + back nav, scrolling list of cards each showing
 *    subject + body + status + relative-date, search box at the top of the list, filter-chip
 *    row below the search.
 *  - Pinned-top FAQ rows from legacy `getCustomTopComplaints()` ARE ported: the two entries in
 *    `:data` `PinnedComplaints.PINNED_COMPLAINTS` are prepended to every load and participate in
 *    search/filter like real records.
 *  - **Icon affordances** — Phase 7.x.complaint.iconparity restores the legacy's four
 *    screen-level icon affordances (back-nav, search leading, search-clear trailing,
 *    no-matches placeholder) via the local inline [ImageVector] definitions in
 *    [ComplaintIcons] (`ComplaintArrowBack`, `ComplaintSearch`, `ComplaintClear`,
 *    `ComplaintSearchOff`) — see [ComplaintIcons]'s KDoc for the
 *    dep-graph rationale and the SVG-path-to-DSL transcription notes. The
 *    [ComplaintActionDialog] sub-panels carry the native leading icons (Reply / Edit / Delete /
 *    Info / Warning Material glyphs) alongside the per-row "[type] [status]" textual labels.
 *  - Labels resolve through `stringResource(Res.string.*)` against the `:ui`
 *    compose-resources catalog (UP-3 localization lift), reusing the legacy complaint keys
 *    (feedback_manager_title, status_*, complaint_actions, reply, edit, delete, etc.) so the
 *    hand-authored Arabic translations apply verbatim.
 *
 * **Actions slice additions** (this slice):
 *  - [ComplaintRow] is now `clickable` and fires [ComplaintIntent.OnRowClick]; the VM opens
 *    the dialog at [ActionDialogMode.MENU] with the tapped record as
 *    [ComplaintState.activeComplaint].
 *  - The [ComplaintActionDialog] mounts when `state.actionDialogMode != NONE` AND
 *    `state.activeComplaint != null` (both fields are set/cleared together by the VM — see
 *    [ComplaintState] dialog-mount precondition KDoc).
 *  - A [LaunchedEffect] collects [ComplaintViewModel.effects] and routes each
 *    [ComplaintEffect.ShowSuccessMessage] / [ComplaintEffect.ShowErrorMessage] to the
 *    [SnackbarHostState]. The collector runs as long as the composable is in composition;
 *    snackbars survive recompositions but not screen-departure (same posture as the legacy
 *    Snackbar usage in `ComplaintScreen.kt`).
 *
 * **`viewModel` reference in `LaunchedEffect`**: passing the VM as the key means the collector
 * tears down + restarts if a different VM instance arrives (e.g., on nav-back to a freshly
 * resolved VM). In practice the VM is stable for the lifetime of this screen, but keying on
 * `viewModel` is the safe pattern.
 *
 * **Snackbar duration**: `withDismissAction = false`, no explicit duration — Material 3
 * defaults to `SnackbarDuration.Short`. The legacy uses the same defaults.
 *
 * **State-to-UI mapping** (unchanged from foundation; see [ComplaintState] KDoc for mutual
 * exclusion):
 *  - `state.isLoading` → centered [CircularProgressIndicator].
 *  - `state.error != null` → centered error message + Retry [TextButton].
 *  - `state.all.isEmpty()` (after load) → centered "No feedback submitted yet" placeholder.
 *  - `state.all.isNotEmpty()` → search box + filter row + LazyColumn of cards.
 *  - `state.all.isNotEmpty() && state.filtered.isEmpty()` → "No matches" placeholder under
 *    the search + chips.
 *
 * **Dialog-state-overlap with list `isLoading`**: when the user submits an action, the VM
 * sets `isSubmittingAction = true` (NOT `isLoading`). After success, the VM refires
 * `loadList()` which DOES set `isLoading = true` briefly. The dialog is dismissed before the
 * `loadList` re-runs (the success branch in `completeAction()` clears the dialog substate
 * first, THEN emits the effect, THEN refires `loadList()`), so the brief list-loading
 * flicker happens with the dialog gone — the user sees: dialog → snackbar + loading → list.
 *
 * Stateless inner [ComplaintScreenContent] mirrors the foundation pattern — separating
 * "wire to VM" from "render state".
 *
 * **Audit-trail postscript** (Phase 9.x.complaint.staleKdocSweep.cascade,
 * Task #452, 2026-05-28): the file-level KDoc above and one inline comment
 * carry stale references into the §355-retired legacy Complaint chain:
 *  - Line 67 (Visual parity paragraph) cites
 *    "legacy `composeApp/.../ComplaintScreen.kt`" — the legacy
 *    `composeApp/.../presentation/features/complaint/ui/screens/
 *    ComplaintScreen.kt` was retired in Phase 9.x.complaint.legacyui.retire
 *    (§355).
 *  - Line 72 cites "legacy `getCustomTopComplaints()`" — the legacy
 *    `composeApp/.../presentation/features/complaint/data/
 *    getCustomTopComplaints.kt` was retired in the same §355 commit.
 *  - Line 96 cites "the legacy Snackbar usage in `ComplaintScreen.kt`" —
 *    same legacy file, same §355 retire.
 *  - The inline comment at lines 441-446 (inside `ComplaintRow`) cites
 *    "legacy `ComplaintCard.kt:150` row-footer timestamp display" — the
 *    legacy `composeApp/.../presentation/features/complaint/ui/components/
 *    ComplaintCard.kt` was retired in the same §355 commit.
 * Filesystem checks for all four cited paths return zero hits. The visual-
 * parity, snackbar, and timestamp-restore rationales stand on their own
 * merits — the rework's affordances are documented inline above and the
 * timestamp restore is structurally complete (the `?.let` block at line 447
 * is unchanged). Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the citations are historical record of
 * the design lineage; the screen continues to render correctly through the
 * legacy retire.
 */
@Composable
fun ComplaintScreen(
    viewModel: ComplaintViewModel,
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
    val replySentMessage = stringResource(Res.string.np_complaint_action_reply_sent)
    val updatedMessage = stringResource(Res.string.np_complaint_action_updated)
    val deletedMessage = stringResource(Res.string.np_complaint_action_deleted)
    val bodyCopiedMessage = stringResource(Res.string.np_complaint_body_copied)
    val actionFailureMessage = stringResource(Res.string.error_occurred)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            val message = when (effect) {
                is ComplaintEffect.ShowActionSuccess -> when (effect.action) {
                    ComplaintAction.REPLY_SENT -> replySentMessage
                    ComplaintAction.UPDATED -> updatedMessage
                    ComplaintAction.DELETED -> deletedMessage
                    ComplaintAction.BODY_COPIED -> bodyCopiedMessage
                }
                ComplaintEffect.ShowActionFailure -> actionFailureMessage
            }
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
    }

    ComplaintScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComplaintScreenContent(
    state: ComplaintState,
    snackbarHostState: SnackbarHostState,
    onIntent: (ComplaintIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorMessage = state.error
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(Res.string.feedback_manager_title),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = ComplaintArrowBack,
                            contentDescription = stringResource(Res.string.back),
                        )
                    }
                },
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
            // centered spinner / error-text + Retry / plain "no feedback" Text. Brings the user-side
            // Complaint surface in line with WhatsNew + the rest of the rework cluster (memory:
            // design-system) — richer error glyph + Retry button, icon + title empty state.
            when {
                state.isLoading -> {
                    // GAP-CMP-U-LOAD — native `LoadingState.kt:26-91` renders a message
                    // ("Loading feedback…", native `loading_feedback`) under the spinner; the shared
                    // KiraLoadingState (in `:ui/components`, outside this slice's edit scope) exposes
                    // no message slot, so render a local spinner + message column here to carry the
                    // native loading label. (The bespoke rotating-Refresh + LinearProgressIndicator
                    // chrome is intentionally not ported — the rework cluster standardises on the
                    // design-system spinner; only the message line is restored for copy parity.)
                    ComplaintLoadingState(
                        message = stringResource(Res.string.np_user_loading_feedback),
                    )
                }
                errorMessage != null -> {
                    KiraErrorState(
                        // state.error is a non-leaking sentinel; show a generic localized message.
                        message = stringResource(Res.string.error_occurred),
                        retryLabel = stringResource(Res.string.retry),
                        onRetry = { onIntent(ComplaintIntent.OnRetry) },
                    )
                }
                state.all.isEmpty() -> {
                    // GAP-CMP-U2 — native `EmptyState.kt:26-64` renders an Inbox icon + a
                    // titleLarge title ("No Feedback Yet") + a bodyMedium supporting line
                    // ("When feedback is submitted, it will appear here."). Route the supporting
                    // message through KiraEmptyState (icon defaults to KiraIcons.Empty = Inbox).
                    KiraEmptyState(
                        title = stringResource(Res.string.np_no_feedback_title),
                        message = stringResource(Res.string.np_no_feedback_message),
                    )
                }
                else -> {
                    ComplaintList(
                        state = state,
                        onIntent = onIntent,
                    )
                }
            }

            val activeComplaint = state.activeComplaint
            if (state.actionDialogMode != ActionDialogMode.NONE && activeComplaint != null) {
                ComplaintActionDialog(
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
private fun ComplaintList(
    state: ComplaintState,
    onIntent: (ComplaintIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.sm),
    ) {
        item(key = "__search_filter__") {
            SearchAndFilterSection(
                searchQuery = state.searchQuery,
                selectedStatus = state.selectedStatus,
                resultsCount = state.filtered.size,
                onIntent = onIntent,
            )
        }

        if (state.filtered.isEmpty() &&
            (state.searchQuery.isNotEmpty() || state.selectedStatus != null)
        ) {
            item(key = "__no_matches__") {
                // GAP-CMP-U3 — native `ComplaintScreen.kt:196-206` renders the no-results state
                // as a full EmptyState (large SearchOff icon + "No results found" title +
                // "Try a different search term or filter" message), NOT a compact one-liner.
                // Route through the shared KiraEmptyState with the local SearchOff glyph.
                KiraEmptyState(
                    title = stringResource(Res.string.no_results_found),
                    message = stringResource(Res.string.np_try_different_search),
                    icon = ComplaintSearchOff,
                    modifier = Modifier.padding(top = spacing.lg),
                )
            }
        } else {
            items(items = state.filtered, key = { it.id.ifEmpty { "" } + "|" + it.subject + it.createdAt }) { complaint ->
                ComplaintRow(
                    complaint = complaint,
                    onClick = { onIntent(ComplaintIntent.OnRowClick(complaint)) },
                    onLongClickBody = { onIntent(ComplaintIntent.OnCopyBody) },
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
    resultsCount: Int,
    onIntent: (ComplaintIntent) -> Unit,
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
            onValueChange = { onIntent(ComplaintIntent.OnSearchChange(it)) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(Res.string.np_user_search_complaints_hint)) },
            leadingIcon = {
                Icon(
                    imageVector = ComplaintSearch,
                    contentDescription = null,
                )
            },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onIntent(ComplaintIntent.OnClearSearch) }) {
                        Icon(
                            imageVector = ComplaintClear,
                            contentDescription = stringResource(Res.string.clear),
                        )
                    }
                }
            } else {
                null
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        ) {
            item(key = "filter-all") {
                FilterChip(
                    selected = selectedStatus == null,
                    onClick = { onIntent(ComplaintIntent.OnStatusFilter(null)) },
                    label = { Text(stringResource(Res.string.filter_all)) },
                )
            }
            items(items = ComplaintStatus.entries.toTypedArray(), key = { it.name }) { status ->
                FilterChip(
                    selected = selectedStatus == status,
                    onClick = {
                        val toggled = if (selectedStatus == status) null else status
                        onIntent(ComplaintIntent.OnStatusFilter(toggled))
                    },
                    label = { Text(status.displayName()) },
                )
            }
        }

        Text(
            text = stringResource(Res.string.np_user_feedbacks_found_count, resultsCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(spacing.xs))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ComplaintRow(
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
    // GAP-CMP-08 — native `ComplaintCard.kt:30-37` uses an elevated Card (2.dp elevation,
    // RoundedCornerShape(16.dp), default elevated surface container) rather than the prior
    // r12/surfaceVariant. Aligned to native across the whole cluster (user + admin + stats).
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
            // GAP-CMP-05 — native `ComplaintCard.kt:44-65`: the type display-name is the PRIMARY
            // line (titleMedium/SemiBold, maxLines 2) and the subject is the secondary line
            // (bodySmall/onSurfaceVariant). The prior layout inverted this (subject primary 1-line,
            // type secondary).
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
                    text = complaint.subject,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // GAP-CMP device-metadata row — native `ComplaintCard.kt:67-95`: a SpaceBetween Row of
            // up to two InfoItems read from the legacy `Complaint.metadata` map (threaded onto
            // ComplaintSummary.osVersion / .manufacturer by the :data read-path mappers):
            //  - osVersion -> Android icon + `apiLevelToAndroidVersion(apiLevel)` (only when the
            //    stored value parses to an Int, mirroring native's `?.toIntOrNull()?.let { }`).
            //  - manufacturer -> PhoneAndroid icon + the raw value (only when non-blank).
            // The row is emitted only when at least one of the two is present (pinned-FAQ entries
            // and pre-metadata submissions carry neither).
            val apiLevel = complaint.osVersion?.toIntOrNull()
            val manufacturer = complaint.manufacturer?.takeIf { it.isNotBlank() }
            if (apiLevel != null || manufacturer != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (apiLevel != null) {
                        ComplaintInfoItem(
                            icon = Icons.Default.Android,
                            text = apiLevelToAndroidVersion(apiLevel),
                        )
                    }
                    if (manufacturer != null) {
                        ComplaintInfoItem(
                            icon = Icons.Default.PhoneAndroid,
                            text = manufacturer,
                        )
                    }
                }
            }
            // GAP-CMP-06 — native `ComplaintCard.kt:102-108` body is bodyMedium maxLines 10 (the
            // admin row stays 3 per native `AdminComplaintCard.kt:799-816`).
            Text(
                text = complaint.body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        clipboardManager.setText(AnnotatedString(complaint.body))
                        onLongClickBody()
                    },
                ),
            )
            // GAP-CMP-02 — render the ClosureReasonCard on CLOSED / PINNED complaints that carry
            // a `reason` in metadata (threaded onto ComplaintSummary.reason by the :data mapper;
            // also populated on the static admin-pinned FAQ entries). Mirrors native
            // `ComplaintCard.kt:111-117`.
            val reason = complaint.reason
            if (reason != null &&
                (complaint.status == ComplaintStatus.CLOSED || complaint.status == ComplaintStatus.PINNED)
            ) {
                ClosureReasonCard(reason = reason)
            }
            // GAP-CMP-04 — footer Row SpaceBetween of the relative timestamp + the 8-char
            // Monospace short-id, mirroring native `ComplaintCard.kt:122-139`
            // (`formatTimestamp(createdAt)` + `feedback_id_format`(id.take(8)) Monospace). The
            // timestamp restore (Phase 7.x.complaint.date) is preserved: null createdAt (e.g.,
            // pinned-FAQ entries from PinnedComplaints.kt) renders a Spacer instead so the short-id
            // stays right-aligned. The short-id is omitted when id is empty (pre-write records).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val createdAt = complaint.createdAt
                if (createdAt != null) {
                    Text(
                        text = formatComplaintTimestamp(createdAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(0.dp))
                }
                if (complaint.id.isNotEmpty()) {
                    Text(
                        // GAP-CMP-U-IDFMT — native `ComplaintCard.kt:133-137` footer short-id uses
                        // `feedback_id_format` = "ID: %s…" (8-char take, Monospace), not "#%s".
                        // Reconciled to the native "ID: …" form.
                        text = stringResource(Res.string.np_user_feedback_id_format, complaint.id.take(8)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * GAP-CMP-U-LOAD — local loading state with a spinner + a "Loading feedback…" message under it,
 * mirroring native `LoadingState.kt` (icon + message). The shared design-system
 * [me.manga.kira.ui.components.KiraLoadingState] exposes no message slot (and lives in
 * `:ui/components`, outside this slice's edit scope), so the message-bearing loading layout is
 * rendered here — the same posture the admin-side `AdminLoadingState` uses. Fills the parent and
 * centres its content like the shared state views.
 */
@Composable
private fun ComplaintLoadingState(message: String) {
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

/**
 * Icon + label pair for the complaint card's device-metadata row — port of native
 * `ComplaintComponents.kt:127-146` `InfoItem`. A 16.dp `onSurfaceVariant`-tinted [icon] followed
 * by a single-line ellipsized bodySmall [text]. Used for the Android-version and manufacturer
 * cells in [ComplaintRow]'s device row (GAP-CMP device-metadata parity).
 */
@Composable
private fun ComplaintInfoItem(icon: ImageVector, text: String) {
    val spacing = LocalSpacing.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Maps an Android API level to a human-readable version label — KMP port of native
 * `utils/apiLevelToAndroidVersion.kt`. Mapping table copied verbatim (API 14-34). The two
 * non-version branches mirror native's `R.string` lookups: `0` -> `filter_all` ("All"), any
 * unrecognized level -> `unknown` ("Unknown"). `@Composable` because the fallback branches
 * resolve string resources, exactly like the native helper.
 */
@Composable
private fun apiLevelToAndroidVersion(apiLevel: Int): String = when (apiLevel) {
    34 -> "Android 14"
    33 -> "Android 13"
    32 -> "Android 12L"
    31 -> "Android 12"
    30 -> "Android 11"
    29 -> "Android 10"
    28 -> "Android 9 (Pie)"
    27 -> "Android 8.1 (Oreo)"
    26 -> "Android 8.0 (Oreo)"
    25 -> "Android 7.1.1 (Nougat)"
    24 -> "Android 7.0 (Nougat)"
    23 -> "Android 6.0 (Marshmallow)"
    22 -> "Android 5.1 (Lollipop)"
    21 -> "Android 5.0 (Lollipop)"
    20 -> "Android 4.4W (KitKat Wear)"
    19 -> "Android 4.4 (KitKat)"
    18 -> "Android 4.3 (Jelly Bean)"
    17 -> "Android 4.2 (Jelly Bean)"
    16 -> "Android 4.1 (Jelly Bean)"
    15 -> "Android 4.0.3 (Ice Cream Sandwich)"
    14 -> "Android 4.0 (Ice Cream Sandwich)"
    0 -> stringResource(Res.string.filter_all)
    else -> stringResource(Res.string.unknown)
}
