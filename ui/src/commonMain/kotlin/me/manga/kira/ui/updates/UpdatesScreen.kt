package me.manga.kira.ui.updates

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.manga.kira.domain.model.updates.UpdateEntry
import me.manga.kira.presentation.updates.RecencyBucket
import me.manga.kira.presentation.updates.RowDownloadStatus
import me.manga.kira.presentation.updates.UpdatesEffect
import me.manga.kira.presentation.updates.UpdatesIntent
import me.manga.kira.presentation.updates.UpdatesState
import me.manga.kira.presentation.updates.UpdatesViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraErrorState
import me.manga.kira.ui.components.KiraLoadingState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.chapter_number
import me.manga.kira.ui.generated.resources.contentDescription_delete_all
import me.manga.kira.ui.generated.resources.contentDescription_mark_all_as_read
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.download
import me.manga.kira.ui.generated.resources.downloaded
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.mark_read
import me.manga.kira.ui.generated.resources.no_updates
import me.manga.kira.ui.generated.resources.notifications_group_last_week
import me.manga.kira.ui.generated.resources.notifications_group_older
import me.manga.kira.ui.generated.resources.notifications_group_today
import me.manga.kira.ui.generated.resources.notifications_group_yesterday
import me.manga.kira.ui.generated.resources.np_download_enqueue_failed
import me.manga.kira.ui.generated.resources.np_undo_failed
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.title_notifications
import me.manga.kira.ui.generated.resources.undo
import me.manga.kira.ui.generated.resources.update_deleted
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Updates screen — Compose entry point for the Updates MVI slice.
 *
 * Phase 7.x.updates rework. Renders [UpdatesState] (a flat list grouped by notification-date)
 * and dispatches [UpdatesIntent]. One-shot navigational [UpdatesEffect]s are collected via a
 * [LaunchedEffect] and forwarded through `onNavigateToDetails` / `onNavigateToReader` — same
 * posture as [me.manga.kira.ui.history.HistoryScreen] and
 * [me.manga.kira.ui.library.LibraryScreen]: the route adapter in `:composeApp` owns the
 * nav-graph specifics, the screen stays nav-host-agnostic.
 *
 * **Visual parity vs the legacy `composeApp/.../UpdatesScreen.kt`**:
 *  - Layout shape — top bar with title + two top-bar actions ("Mark all read" + "Clear all"),
 *    scrolling list with date group headers ("Today" / "Yesterday" / "X days ago" /
 *    "MMM d, yyyy"), each row showing a cover thumbnail, manga title, chapter label,
 *    relative-date subtitle, and a per-row "Mark read" + "Delete" actions.
 *  - **Action affordances stay labelled [TextButton]s** ("Mark read" / "Download" / "Delete" /
 *    "Mark all read" / "Clear all"), not IconButtons yet. `:ui` now ships
 *    `compose.materialIconsExtended` (UP-2a) and [me.manga.kira.ui.components.KiraIconButton]
 *    exists, but Updates was not in the UP-2 icon-conversion set — converting these is tracked
 *    under UP-2/UP-4 parity work, not this localization slice.
 *
 *    **P2 parity-fix update (Updates feed audit)**: the TWO TOP-BAR bulk actions were since
 *    converted to native-parity glyph-only IconButtons — DeleteSweep (delete-all) FIRST then
 *    DoneAll (mark-all-read), matching native order + iconography — and the bar title now uses
 *    `title_notifications` ("Notification") at 24sp Bold on a `background` container (porting
 *    native's TopAppBarCom) instead of "Updates" with the stock Material3 style. The PER-ROW
 *    affordances (mark-read / delete) ride the swipe-to-dismiss gestures and the per-row
 *    download affordance is an IconButton; native has no per-row text buttons either. The new
 *    top-bar strings ship en + Arabic in `values*strings_pfix_p2_updates.xml`
 *  - **Read-state styling** — read rows render the title + chapter content at 0.4 alpha; unread
 *    rows render at full opacity and carry a leading `primary` dot before the chapter line. The
 *    title weight is a constant `titleMedium` default (no read/unread weight toggle), matching
 *    native's opacity-only read-state signal (parity fix updates-refresh #3 / center #2).
 *  - Labels resolve through compose-resources `stringResource(Res.string.*)` against the `:ui`
 *    catalog (Phase 11.ui.UP-3i). Relative-date keys (today/yesterday/N-ago) are reused from the
 *    legacy catalog (Arabic shipped); the Updates-specific labels are new (en-only pending
 *    trusted Arabic). The group-header absolute-date fallback keeps English month abbreviations.
 *  - Design tokens use [LocalSpacing] + Material 3 directly; legacy used ad-hoc `.dp`
 *    literals.
 *
 * **Date grouping**: identical algorithm to [me.manga.kira.ui.history.HistoryScreen] —
 * `groupBy { it.notificationDate }` + sort-by-date-desc + rebuild into a `LinkedHashMap`.
 * Group labels use the same "Today / Yesterday / N days ago / MMM d, yyyy" formatter. The
 * shared 4-tier label idiom is the §83.3 convergence point: Updates and History converge on
 * one date-label formatter in `:ui` rather than each layer reinventing it.
 *
 * **Top-bar action enablement**:
 *  - "Mark all read" is enabled iff [UpdatesState.hasUnreadItems] — derived state (no extra
 *    field). Disabling when every item is already read keeps the action affordance honest.
 *  - "Clear all" is enabled iff [UpdatesState.visibleItems] is non-empty. The state can never
 *    have items while `isLoading == true` in practice (the upstream Room flow emits the current
 *    table contents on subscription), but the affordance check is on
 *    `visibleItems.isNotEmpty()` rather than `!isEmpty` so it correctly disables during the
 *    initial-emission gap AND when every item is staged for soft-delete (Phase
 *    7.x.updates.undosnackbar).
 *
 * **Delete-with-undo snackbar** (Phase 7.x.updates.undosnackbar): the per-row "Delete" button
 * dispatches [UpdatesIntent.OnRequestDelete] (not the original [UpdatesIntent.OnDeleteEntry]).
 * The VM stages the entry's id in [UpdatesState.pendingDeleteIds] so the row disappears from
 * [UpdatesState.visibleItems] immediately, and emits [UpdatesEffect.ShowUndoSnackbar]. This
 * screen's effect collector calls [SnackbarHostState.showSnackbar] with `actionLabel = "Undo"`
 * and `withDismissAction = true`; the returned [SnackbarResult] is translated back into either
 * [UpdatesIntent.OnUndoDelete] (action performed — the row is RE-INSERTED via the restore use
 * case; the delete was already applied on request, native deleteWithUndo parity) or
 * [UpdatesIntent.OnConfirmDelete] (dismissed — staging cleanup only, the delete is already
 * final). Mirrors the
 * legacy `composeApp/.../UpdatesScreen.kt` swipe-to-dismiss snackbar (lines 147-174) but
 * routes the staging through the MVI surface so all mutations flow through
 * [me.manga.kira.presentation.updates.UpdatesViewModel.handle].
 *
 * **Per-row Download button** (Phase 7.x.updates.downloadbutton.wire): each row exposes a
 * labelled [TextButton] that dispatches [UpdatesIntent.OnDownloadClick] when the entry is
 * not yet downloaded. When [UpdateEntry.isDownloaded] is `true`, the button is disabled and
 * labelled "Downloaded" — visual parity with the legacy `DownloadDone` icon affordance. On
 * enqueue failure (missing `saved_chapters` row, WorkManager rejection, etc.) the VM emits
 * [UpdatesEffect.ShowError]; this screen's effect collector funnels the message into the same
 * [SnackbarHostState] that backs [UpdatesEffect.ShowUndoSnackbar]. Text-only label keeps the
 * `:ui` module free of `compose.materialIconsExtended` (same icon-strategy posture as the
 * existing Mark-read / Delete buttons — see the icon-omission note above).
 *
 * **Cover thumbnail**: plain `AsyncImage(model = url)` — the singleton ImageLoader (set in
 * `:composeApp/App.kt` via `setSingletonImageLoaderFactory`) carries the AVIF decoder,
 * OkHttp fetcher, max-bitmap-size override, HighQualitySkiaImageDecoder, and the
 * `CoilSourceHeaderInterceptor` that transparently attaches per-source headers (cf. memory
 * `project_yami_okhttp_fetcher` / `project_yami_desktop_skia_size_cap`). No per-screen
 * `ImageRequest.Builder` needed.
 *
 * **Empty state**: when [UpdatesState.isEmpty] (not loading and no items), render a centered
 * "No updates yet" placeholder. Both top-bar actions stay disabled in that state.
 *
 * Stateless inner [UpdatesScreenContent] mirrors the [me.manga.kira.ui.history.HistoryScreen]
 * pattern — separating "wire to VM" from "render state" so previews / tests can feed canned
 * state without spinning up a real VM. SRP-clean separation.
 *
 * **Audit-trail postscript** (Phase 9.x.updates.staleKdocSweep.cascade,
 * Task #456, 2026-05-28): two stale citations into the §310-retired legacy
 * `composeApp/.../UpdatesScreen.kt` appear above:
 *  - Line 72: "Visual parity vs the legacy `composeApp/.../UpdatesScreen.kt`"
 *    (the visual-parity preamble for the layout-shape bullet list).
 *  - Line 115: "Mirrors the legacy `composeApp/.../UpdatesScreen.kt`
 *    swipe-to-dismiss snackbar (lines 147-174)" — line-anchored citation
 *    in the delete-with-undo paragraph.
 * The legacy `composeApp/.../UpdatesScreen.kt` was retired in
 * Phase 9.aa.updates.legacy_retire (§310 sweep, commit `8e99e4b`
 * "delete unreachable legacy UpdatesScreen + UpdateItem +
 * NotificationsUiState"); verified by a filesystem check returning zero
 * hits for that path. The visual-parity, icon-omission, read-state styling,
 * date-grouping convergence, and delete-with-undo rationales stand on their
 * own merits — they're documented exhaustively inline above and are
 * independent of which legacy file originally rendered them. Phase 10's
 * i18n + icon-strategy decision remains the canonical opportunity to swap
 * the inline labels. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citations are historical record
 * of the design lineage; the screen continues to render correctly through
 * the legacy retire.
 */
@Composable
fun UpdatesScreen(
    viewModel: UpdatesViewModel,
    onNavigateToDetails: (UpdatesEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (UpdateEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    UpdatesScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateToDetails = onNavigateToDetails,
        onNavigateToReader = onNavigateToReader,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UpdatesScreenContent(
    state: UpdatesState,
    effects: Flow<UpdatesEffect>,
    onIntent: (UpdatesIntent) -> Unit,
    onNavigateToDetails: (UpdatesEffect.NavigateToDetails) -> Unit,
    onNavigateToReader: (UpdateEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Snackbar copy resolved in composable scope — stringResource can't be called inside the
    // coroutine launched from the effect collector. Captured by the LaunchedEffect below.
    val updateDeletedMessage = stringResource(Res.string.update_deleted)
    val undoLabel = stringResource(Res.string.undo)
    val downloadFailedMessage = stringResource(Res.string.np_download_enqueue_failed)
    val undoFailedMessage = stringResource(Res.string.np_undo_failed)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is UpdatesEffect.NavigateToDetails -> onNavigateToDetails(effect)
                is UpdatesEffect.NavigateToReader -> onNavigateToReader(effect.entry)
                is UpdatesEffect.ShowUndoSnackbar -> {
                    scope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = updateDeletedMessage,
                            actionLabel = undoLabel,
                            withDismissAction = true,
                            duration = SnackbarDuration.Short,
                        )
                        when (result) {
                            SnackbarResult.ActionPerformed ->
                                onIntent(UpdatesIntent.OnUndoDelete(effect.entry))
                            SnackbarResult.Dismissed ->
                                onIntent(UpdatesIntent.OnConfirmDelete(effect.entry))
                        }
                    }
                }
                UpdatesEffect.ShowDownloadEnqueueFailed -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = downloadFailedMessage,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
                UpdatesEffect.ShowUndoFailed -> {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = undoFailedMessage,
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // Redesign 2026-06: the plain Material3 TopAppBar is replaced by a Home-style big-title
        // header (large bold title + circular action buttons), mirroring HomeScreen.HomeHeader.
        // The two bulk actions remain DoneAll (mark-all-read) + DeleteSweep (clear-all) with the
        // SAME intents, enablement, and content descriptions as before — only the chrome changes.
        topBar = { UpdatesHeader(state = state, onIntent = onIntent) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The floating bottom nav overlays content from the app root; its inset reaches the list via
        // LocalBottomBarPadding. Zero the Scaffold insets so the bottom isn't double-counted.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when {
                state.isLoading -> KiraLoadingState()
                // Backlog M1 (deliberate deviation from native): native UpdatesScreen.kt:98-106
                // rendered only an inline error Text with NO recovery affordance — and the VM's
                // `.catch {}` terminates the observe collector, so the screen stayed dead until
                // re-entered. Render the design-system error state with a Retry button instead;
                // OnRetry re-subscribes the collector. The VM carries a typed AppError (never raw
                // exception text); the generic localized message keeps :ui owning the i18n.
                state.loadError != null -> KiraErrorState(
                    message = stringResource(Res.string.error_occurred),
                    retryLabel = stringResource(Res.string.retry),
                    onRetry = { onIntent(UpdatesIntent.OnRetry) },
                )
                state.isEmpty -> KiraEmptyState(title = stringResource(Res.string.no_updates))
                else -> UpdatesList(
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }
}

/**
 * Redesign 2026-06 header — ports [me.manga.kira.ui.home.HomeScreen]'s `HomeHeader` look to the
 * Updates screen: a large bold title with circular action buttons, replacing the plain Material3
 * [TopAppBar]. Owns its own status-bar inset (the bar it replaced did too).
 *
 * Behaviour is byte-identical to the previous top-bar: the title is still
 * `title_notifications` ("Notification" — the same copy as the bottom-nav tab); the two trailing
 * actions are the SAME bulk operations — mark-all-read ([UpdatesIntent.OnMarkAllAsRead], DoneAll
 * glyph) and clear-all ([UpdatesIntent.OnDeleteAll], DeleteSweep glyph), each gated on
 * `state.visibleItems.isNotEmpty()` and carrying the same content descriptions
 * ([Res.string.contentDescription_mark_all_as_read] / [Res.string.contentDescription_delete_all]).
 * The mockup orders mark-all-read before clear-all; the underlying intents are unchanged.
 */
@Composable
private fun UpdatesHeader(
    state: UpdatesState,
    onIntent: (UpdatesIntent) -> Unit,
) {
    val hasItems = state.visibleItems.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.title_notifications),
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 28.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HeaderAction(
                icon = Icons.Filled.DoneAll,
                contentDescription = stringResource(Res.string.contentDescription_mark_all_as_read),
                enabled = hasItems,
                // Native parity: both bulk actions enable purely on list non-emptiness, so
                // "mark all read" stays tappable even when everything is already read.
                onClick = { onIntent(UpdatesIntent.OnMarkAllAsRead) },
            )
            HeaderAction(
                icon = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(Res.string.contentDescription_delete_all),
                enabled = hasItems,
                onClick = { onIntent(UpdatesIntent.OnDeleteAll) },
            )
        }
    }
}

/**
 * Circular header action button — the Updates-screen twin of [me.manga.kira.ui.home.HomeScreen]'s
 * `HeaderAction` (rounded `surfaceVariant` square with a centered 20dp glyph). Adds an `enabled`
 * flag so the bulk actions can dim + ignore taps when the feed is empty, matching the previous
 * IconButton enablement.
 */
@Composable
private fun HeaderAction(
    icon: ImageVector,
    contentDescription: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Redesign 2026-06: recency-section label (Today / Yesterday / Last week), matching the bold
 *  19sp `.sec h2` treatment from [me.manga.kira.ui.home.HomeScreen]'s `SectionHeader`. */
@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
    )
}

/**
 * Native-parity recency-bucket header labels (GAP-UPD-07). The bucketing itself is owned by the
 * presentation layer ([UpdatesState.groupedVisibleItems] / [RecencyBucket]); this only maps each
 * bucket to its localized group-header [StringResource], keeping the i18n in `:ui` per the MVI
 * contract while the grouping logic lives in exactly one place.
 */
private val RecencyBucket.headerKey: StringResource
    get() = when (this) {
        RecencyBucket.TODAY -> Res.string.notifications_group_today
        RecencyBucket.YESTERDAY -> Res.string.notifications_group_yesterday
        RecencyBucket.LAST_WEEK -> Res.string.notifications_group_last_week
        RecencyBucket.OLDER -> Res.string.notifications_group_older
    }

@Composable
private fun UpdatesList(
    state: UpdatesState,
    onIntent: (UpdatesIntent) -> Unit,
) {
    // Display-ready groups produced by the presentation layer (single authoritative bucketing).
    val grouped = state.groupedVisibleItems
    // Parity fix (notifications-center #4): native LazyColumn animates list-size changes via
    // Modifier.animateContentSize() (UpdatesScreen.kt:111), with each row additionally using
    // animateItem() + animateContentSize() (UpdatesScreen.kt:164-167) for animated
    // insert/remove/resize. Restore both here.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .animateContentSize(),
        contentPadding = PaddingValues(bottom = 8.dp + LocalBottomBarPadding.current),
    ) {
        grouped.forEach { (bucket, bucketEntries) ->
            // Redesign 2026-06: the recency-bucket label (Today / Yesterday / Last week / Older)
            // adopts HomeScreen.SectionHeader's bold 19sp `.sec h2` look. The bucketing source and
            // the localized header strings are unchanged — only the type treatment + padding move.
            item(key = "header-${bucket.name}") {
                SectionHeader(stringResource(bucket.headerKey))
            }

            items(items = bucketEntries, key = { it.id }) { entry ->
                UpdatesRow(
                    entry = entry,
                    downloadStatus = state.downloadStatusFor(entry.chapterId, entry.isDownloaded),
                    onChapterClick = { onIntent(UpdatesIntent.OnChapterClick(entry)) },
                    onMangaClick = { onIntent(UpdatesIntent.OnMangaClick(entry)) },
                    onMarkReadClick = { onIntent(UpdatesIntent.OnMarkAsRead(entry)) },
                    onDownloadClick = { onIntent(UpdatesIntent.OnDownloadClick(entry)) },
                    onDeleteClick = { onIntent(UpdatesIntent.OnRequestDelete(entry)) },
                    // animateItem() must be called in LazyItemScope; thread it onto the row's
                    // SwipeToDismissBox to mirror native's per-item animateItem()+animateContentSize.
                    // Redesign 2026-06: the `.feed` mockup insets each card 16dp horizontally with a
                    // 12dp inter-card gap (rendered here as bottom padding so the swipe-reveal stays
                    // clipped to the card's rounded bounds).
                    modifier = Modifier
                        .animateItem()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * Per-row container wrapping [UpdatesRowContent] in a [SwipeToDismissBox] (GAP-UPD-05).
 *
 * Native parity: swipe-right (StartToEnd) marks the chapter read; swipe-left (EndToStart)
 * deletes-with-undo. `confirmValueChange` always returns `false` so the box never settles into
 * a dismissed state — the side-effect is performed via the MVI intent + Room re-emission and the
 * row snaps back, exactly as the legacy `SwipeToDismissBox` did (old `UpdatesScreen.kt:163-214`,
 * "perform side-effect, don't dismiss"). Swipe-right toggles read state on every row (the
 * underlying use case is `isRead = NOT isRead`), matching native's swipe-to-toggle behavior.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpdatesRow(
    entry: UpdateEntry,
    downloadStatus: RowDownloadStatus,
    onChapterClick: () -> Unit,
    onMangaClick: () -> Unit,
    onMarkReadClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // confirmValueChange is deprecated, but here it's a deliberate "fire the action at the swipe
    // threshold, then return false to snap back without settling" idiom that matches native parity
    // (see the body comment). The documented replacement (LaunchedEffect on currentValue + reset())
    // makes the row fully settle then bounce back — a different swipe feel — so this is retained.
    @Suppress("DEPRECATION")
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                // Parity fix (updates-refresh #7): native UpdatesScreen.kt:128-130 always calls
                // markAsRead on swipe-right, and the underlying DAO query is a TOGGLE
                // (UPDATE notifications SET isRead = NOT isRead) — so swiping a read row un-reads
                // it. The rework gated this on !isRead, blocking the un-read toggle; drop the
                // guard to restore native's swipe-to-toggle behavior exactly. (The VM's
                // OnMarkAsRead handler has no guard, so this is the only gate.)
                SwipeToDismissBoxValue.StartToEnd -> onMarkReadClick()
                SwipeToDismissBoxValue.EndToStart -> onDeleteClick()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            // Always reject settling: the row performs the side-effect and snaps back, mirroring
            // the native confirmValueChange=false semantics.
            false
        },
    )

    // Redesign 2026-06: each row is now a rounded Card (the `.row` mockup look). Clip the whole
    // SwipeToDismissBox to the card's corner radius so the swipe-reveal background stays within the
    // rounded bounds rather than bleeding to full-bleed rectangle corners.
    val cardShape = RoundedCornerShape(20.dp)
    SwipeToDismissBox(
        state = dismissState,
        // Parity fix (notifications-center #4): native applies fillMaxWidth().animateItem()
        // .animateContentSize() to each row's SwipeToDismissBox (UpdatesScreen.kt:164-167). The
        // animateItem() is supplied by the LazyItemScope caller via `modifier`.
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .animateContentSize(),
        backgroundContent = { SwipeBackground(dismissState.dismissDirection) },
    ) {
        UpdatesRowContent(
            entry = entry,
            downloadStatus = downloadStatus,
            cardShape = cardShape,
            onChapterClick = onChapterClick,
            onMangaClick = onMangaClick,
            onDownloadClick = onDownloadClick,
        )
    }
}

/**
 * Animated swipe-reveal background (GAP-UPD-05). Mirrors the native 300ms color crossfade:
 * mark-read (StartToEnd) reveals a `primary @ 0.2` background with a leading Done icon; delete
 * (EndToStart) reveals an `error @ 0.2` background with a trailing Delete icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeBackground(direction: SwipeToDismissBoxValue) {
    val targetColor = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error.copy(alpha = 0.2f)
        // Parity fix (updates-refresh #8 / notifications-center #5): native settles to
        // `colorScheme.background` (UpdatesScreen.kt:174), not transparent.
        SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.background
    }
    // Parity fix (updates-refresh #8 / notifications-center #5): native animates the reveal color
    // with an explicit TweenSpec(300ms, FastOutSlowInEasing) (UpdatesScreen.kt:176-180), not the
    // animateColorAsState default spring.
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "swipeBg",
    )
    // Parity fix (notifications-center #3 + #5): full-bleed Row (no rounded clip) with
    // `Arrangement.SpaceBetween` and a leading + trailing icon slot — native renders a Done icon
    // at the start (swipe-right) and a Delete icon at the end (swipe-left), each backed by a 24dp
    // Spacer placeholder when its slot is inactive, with 20dp horizontal padding and OUTLINED
    // (not Filled) glyphs at 24dp (UpdatesScreen.kt:181-210).
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (direction == SwipeToDismissBoxValue.StartToEnd) {
            Icon(
                imageVector = Icons.Outlined.Done,
                contentDescription = stringResource(Res.string.mark_read),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Spacer(Modifier.size(24.dp))
        }
        if (direction == SwipeToDismissBoxValue.EndToStart) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(Res.string.delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun UpdatesRowContent(
    entry: UpdateEntry,
    downloadStatus: RowDownloadStatus,
    cardShape: Shape,
    onChapterClick: () -> Unit,
    onMangaClick: () -> Unit,
    onDownloadClick: () -> Unit,
) {
    val spacing = LocalSpacing.current
    // Parity fix (updates-refresh #3 / notifications-center #2): native UpdateItem.kt:78-110
    // conveys read-state with the 0.4 alpha dim (+ the unread dot) ONLY — the title weight is a
    // constant titleMedium default, never toggled to SemiBold. Dropped the read/unread
    // FontWeight switch so the single read-state signal matches native exactly.
    val contentAlpha = if (entry.isRead) 0.4f else 1f
    // Redesign 2026-06: each row is a rounded surface card (the `.row` mockup look) — an opaque
    // `surface` fill clipped to [cardShape]. The wrapping SwipeToDismissBox is clipped to the same
    // shape so the swipe-reveal background stays within the rounded bounds.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, cardShape)
            .clickable(onClick = onChapterClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
            // GAP-UPD-02: native parity 50×50 square thumbnail with 8dp corners (was 72×108
            // portrait). The singleton ImageLoader attaches per-source headers transparently via
            // CoilSourceHeaderInterceptor (GAP-UPD-01 — host-match equivalence to native's
            // buildImageRequest, documented in the screen KDoc).
            val coverShape = RoundedCornerShape(12.dp)
            AsyncImage(
                model = entry.mangaImageUrl,
                contentDescription = entry.mangaTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(50.dp)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant, coverShape)
                    .clickable(onClick = onMangaClick),
            )

            // Parity fix (updates-refresh #14): native UpdateItem.kt:73-76 puts a 16dp gap between
            // the cover and the text column (Column.padding(start = 16.dp)). LocalSpacing.md is
            // 12dp, so use spacing.lg (16dp) to match native's leading gap exactly.
            Spacer(Modifier.width(spacing.lg))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(
                    text = entry.mangaTitle,
                    style = MaterialTheme.typography.titleMedium,
                    // Parity fix (updates-refresh #1 / notifications-center #1): native
                    // UpdateItem.kt:78-86 pins the title to titleMedium at fontSize = 16.sp and a
                    // single line (maxLines = 1) with no weight toggle. The rework was wrapping to
                    // two lines with an unpinned size; match native's single-line 16sp treatment.
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // GAP-UPD-06: native parity unread indicator — an 8dp `primary` `CircleShape`
                // dot precedes the chapter number on unread rows (omitted on read rows).
                // Parity fix (updates-refresh #14 spacing): native UpdateItem.kt:90-101 places the
                // 8dp unread dot and a 6dp Spacer BEFORE the chapter text (and no gap on read
                // rows), rather than a uniform spacedBy gap. Mirror native's explicit 6dp dot gap.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!entry.isRead) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    // Native parity (UpdateItem.kt:102-110): render the chapter line via the
                    // localized "Chapter %1$s" template (chapterNumber stores only the raw
                    // number, e.g. "72"), not the bare number.
                    Text(
                        text = stringResource(Res.string.chapter_number, entry.chapterNumber),
                        style = MaterialTheme.typography.bodyMedium,
                        // Parity fix (updates-refresh #2): native UpdateItem.kt:102-110 pins the
                        // chapter line to bodyMedium at an explicit fontSize = 12.sp. bodyMedium's
                        // default is 14.sp, so the override is load-bearing for parity.
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(spacing.sm))

            // GAP-UPD-03: per-row download affordance with queued/running spinner states.
            DownloadAffordance(status = downloadStatus, onDownloadClick = onDownloadClick)
    }
}

/**
 * Trailing per-row download affordance (GAP-UPD-03). Native parity (old `UpdateItem.kt:113-137`):
 *  - [RowDownloadStatus.RUNNING] → 24dp spinner tinted `primary` (this chapter is downloading);
 *  - [RowDownloadStatus.QUEUED] → 24dp spinner tinted `onSurfaceVariant` (queued, not yet
 *    running). Deliberate divergence from native (backlog L4): native tints the queued spinner
 *    `onPrimary`, which is white-on-white against the light-theme row background — an invisible
 *    spinner, i.e. a faithfully-ported native bug. `onSurfaceVariant` is the design system's
 *    muted-content-on-surface token, visibly distinct from the RUNNING `primary` accent in both
 *    themes (pinned by `DownloadAffordanceVisibilityTest`'s pixel capture).
 *  - [RowDownloadStatus.DONE] → disabled download-done icon;
 *  - [RowDownloadStatus.IDLE] → download icon button (enqueues on tap).
 *
 * `internal` (not private) so desktopTest can render it directly for the pixel-level
 * visibility check — same testability posture as [UpdatesScreenContent].
 */
@Composable
internal fun DownloadAffordance(
    status: RowDownloadStatus,
    onDownloadClick: () -> Unit,
) {
    when (status) {
        RowDownloadStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
        )
        RowDownloadStatus.QUEUED -> CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Parity fix (updates-refresh #13 / notifications-center #13): native UpdateItem.kt:124-136
        // renders a single 48dp IconButton(enabled = !isDownloaded) and tints the glyph
        // `primary` when downloaded (DownloadDone) and `onSurface @ 0.7 alpha` when idle
        // (Download). The rework was relying on the default IconButton size + default
        // LocalContentColor/disabled tint; pin the 48dp size and the accent tints to match.
        RowDownloadStatus.DONE -> IconButton(
            onClick = {},
            enabled = false,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.DownloadDone,
                contentDescription = stringResource(Res.string.downloaded),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        RowDownloadStatus.IDLE -> IconButton(
            onClick = onDownloadClick,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(Res.string.download),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}
