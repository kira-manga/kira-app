package me.manga.kira.ui.downloads

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import me.manga.kira.domain.model.downloads.DownloadState
import me.manga.kira.domain.model.downloads.DownloadedChapter
import me.manga.kira.presentation.downloads.DownloadsEffect
import me.manga.kira.presentation.downloads.DownloadsIntent
import me.manga.kira.presentation.downloads.DownloadsState
import me.manga.kira.presentation.downloads.DownloadsViewModel
import me.manga.kira.ui.components.KiraEmptyState
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.components.KiraLoadingState
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.active
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.completed
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.download_failed_reason
import me.manga.kira.ui.generated.resources.downloaded
import me.manga.kira.ui.generated.resources.downloads
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.failed
import me.manga.kira.ui.generated.resources.no_active_downloads
import me.manga.kira.ui.generated.resources.no_completed_downloads
import me.manga.kira.ui.generated.resources.no_failed_downloads
import me.manga.kira.ui.generated.resources.np_back
import me.manga.kira.ui.generated.resources.np_cancel_download
import me.manga.kira.ui.generated.resources.np_delete_download
import me.manga.kira.ui.generated.resources.np_download_chapter_title
import me.manga.kira.ui.generated.resources.np_download_complete
import me.manga.kira.ui.generated.resources.np_download_unknown_error
import me.manga.kira.ui.generated.resources.pfix_download_cancelled_by_user
import me.manga.kira.ui.generated.resources.queued
import me.manga.kira.ui.generated.resources.retry
import me.manga.kira.ui.generated.resources.running
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.util.BackHandler
import org.jetbrains.compose.resources.stringResource

/**
 * "Done" status-chip green. The Downloads redesign mockup specifies a greenish "completed" chip
 * (`ds.css --good: #46D6A6`), but this app's [MaterialTheme.colorScheme] has no green semantic
 * slot — its `tertiary` is a coral/salmon, so it cannot stand in for "success green". This single
 * named constant mirrors the design system's `--good` token; `primary` (coral) and `error` still
 * come from `colorScheme.*` as required.
 */
private val GoodGreen = Color(0xFF46D6A6)

/**
 * Downloads screen — Compose entry point for the Downloads MVI slice.
 *
 * Phase 7.x.downloads.foundation rework + Phase 7.x.downloads.actions
 * rework append.
 *
 * Renders [DownloadsState]'s pre-partitioned `active` / `failed` /
 * `completed` buckets behind a 3-tab `TabRow`. The selected tab is
 * read from `state.selectedTab` and toggled by dispatching
 * [DownloadsIntent.OnTabSelect]. The legacy
 * `composeApp/.../features/download/ui/screens/DownloadsScreen.kt`
 * partitions inline in its route file (lines 48-60); the rework lifts
 * the partition into the VM (see
 * [me.manga.kira.presentation.downloads.DownloadsViewModel]).
 *
 * **Per-row mutation surface (actions slice)** — the four user-visible
 * mutations mirror native's `DownloadItemCard` / `RunningDownloadItemCard`
 * affordance choices exactly (downloads-offline P3, Component):
 *  - **RUNNING rows** (Active tab) render an `Icons.Default.Cancel`
 *    [IconButton] (error tint) inline at the end of the title row that
 *    dispatches [DownloadsIntent.OnCancelRunning] (interruptible-in-
 *    flight semantics — the worker checks the DAO state mid-fetch).
 *  - **QUEUED rows** (Active tab) render a "Cancel" [TextButton] in the
 *    error color that dispatches [DownloadsIntent.OnCancel] (queue-prune
 *    semantics — distinct from running cancel).
 *  - **COMPRESSING rows** render NO action (native parity,
 *    downloads-offline P2): native's Active tab queries only RUNNING +
 *    QUEUED, so a compressing chapter surfaces in no tab. The VM bucket
 *    filter mirrors this, so a COMPRESSING row is never rendered here.
 *  - **FAILED rows** (Failed tab) render a "Retry" [TextButton] + a
 *    `Delete` [IconButton] (error tint) pair dispatching
 *    [DownloadsIntent.OnRetry] / [DownloadsIntent.OnDelete].
 *  - **SUCCESS rows** (Completed tab) render an inert `Icons.Outlined.Done`
 *    check ([Icon], primary tint) followed by a `Delete` [IconButton]
 *    (error tint) dispatching [DownloadsIntent.OnDelete].
 *
 * **Action affordance styles match native, not a uniform style**: native mixes
 * [TextButton] labels (QUEUED "Cancel", FAILED "Retry") with [IconButton] glyphs
 * (running-card Cancel, Delete, the inert SUCCESS Done check) and an [IconButton]
 * back arrow in the top bar — this screen reproduces that exact split rather than
 * forcing every action to one style. Glyphs come from `Icons.Default.Cancel` /
 * `Icons.Outlined.Done` and the shared [KiraIcons] map (`Delete`, `Back`).
 *
 * **Effects** — the VM emits [DownloadsEffect.ShowError] on a
 * mutation failure (e.g., "download row not found" from a race
 * between observe and retry, or a Room write failure). The screen
 * surfaces these via a [SnackbarHost] anchored to the [Scaffold].
 * Success is silent — Room re-emits on every write, so the row's
 * state change (FAILED → QUEUED on retry; row vanishing on delete)
 * is the user-visible confirmation; a success snackbar would be
 * redundant chrome.
 *
 * **Visual parity vs the native screen**:
 *  - Top bar shows the "Downloads" title + a back arrow [IconButton]
 *    ([KiraIcons.Back] = `Icons.AutoMirrored.Filled.ArrowBack`, onBackground
 *    tint), matching native's `TopAppBarCom` (background container color,
 *    bold 24.sp onBackground title).
 *  - 3 tabs: "Active" / "Failed" / "Completed".
 *  - Each bucket renders a [LazyColumn] of cards. RUNNING rows render an
 *    8.dp [LinearProgressIndicator] inside a 24.dp box with the progress %
 *    (12.sp / onSurface) overlaid centered ON the bar (parity with native's
 *    `RunningDownloadItemCard`).
 *  - Card shape, padding, surfaces match the native "floating card" look
 *    (NP Phase 2 GAP-DL-03): rounded 8.dp Card on `background` with a
 *    4.dp `onSurface@0.9f` ambient/spot shadow (clip=false); the running
 *    card carries the native 6.dp/12.dp outer padding. `:ui` uses
 *    [LocalSpacing] tokens for inner padding — `spacing.md` resolves to
 *    12.dp, matching native's literal 12.dp inner card padding.
 *
 * **Empty per-bucket** (intentional UX enhancement over native,
 * downloads-offline P3, State): when a bucket's list is empty (e.g., no
 * failed downloads), the shared [KiraEmptyState] renders a centred
 * illustrated placeholder. Native renders a blank [LazyColumn] for an
 * empty bucket — the placeholder is a deliberate KMP improvement so an
 * empty tab is self-explanatory rather than a void; the empty strings are
 * localized (Arabic variants present).
 *
 * **Loading state** (KMP-only, tied to the deferred paging restore,
 * downloads-offline P3, State): the shared [KiraLoadingState] (a centred
 * spinner) covers the whole content area while `state.isLoading` is true
 * (default until the first Room emission). Native has no initial-load
 * state — its only spinner is the Paging append-loading indicator at the
 * list tail. There is no append spinner here because the rework consumes a
 * single Room `Flow` rather than `PagingData`; reintroducing the append
 * spinner/error row is part of the deferred cross-cutting paging restore.
 *
 * **Stateless inner `DownloadsScreenContent`**: separates "wire to
 * VM" from "render state" so previews / tests can feed canned state
 * without spinning up a real VM. The snackbar host is hoisted into
 * the wrapper so the inner content stays VM-free.
 *
 * **No nav effects today**: the screen is terminal (a leaf in the
 * nav graph). Future actions (e.g., on retry-then-open-reader) slot
 * into a new `DownloadsEffect` variant + collector branch here.
 *
 * **Audit-trail postscript** (Phase 9.x.downloads.staleKdocSweep.cascade.peers,
 * Task #451, 2026-05-28): the paragraph at lines 61-65 above cites "the
 * legacy `composeApp/.../features/download/ui/screens/DownloadsScreen.kt`
 * partitions inline in its route file (lines 48-60); the rework lifts the
 * partition into the VM". That legacy file was retired in
 * Phase 9.x.downloads.legacyui.retire (§352); verified by a filesystem check
 * returning zero hits for that path. The "lifts the partition into the VM"
 * SRP split stands on its own merits — having the VM own the 3-bucket
 * projection keeps this composable a near-stateless renderer regardless of
 * what the legacy did at the route layer. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the line-anchored
 * citation is historical record of the design lineage; the screen continues
 * to render correctly through the legacy retire.
 */
@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    // Effect carries no i18n text; resolve a generic localized error here (stringResource is not
    // callable inside the suspend collector block).
    val actionFailedMessage = stringResource(Res.string.error_occurred)

    LaunchedEffect(viewModel) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                DownloadsEffect.ShowActionFailed ->
                    scope.launch { snackbarHostState.showSnackbar(actionFailedMessage) }
            }
        }
    }

    DownloadsScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onIntent = viewModel::submit,
        onBack = onBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DownloadsScreenContent(
    state: DownloadsState,
    snackbarHostState: SnackbarHostState,
    onIntent: (DownloadsIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Native parity (downloads-offline P3, Interaction): native registers
    // `BackHandler { onBack() }` (DownloadsScreen.kt:74) so the system back gesture/button
    // routes through the same onBack as the toolbar arrow. On Android this hooks the real
    // OnBackPressedDispatcher; on iOS/desktop the shared expect/actual is a no-op (the host
    // edge-swipe / window chrome owns back there).
    BackHandler { onBack() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // Native parity (downloads-offline P3, Component): native uses the shared
            // TopAppBarCom wrapper (TopAppBarCom.kt) — a background container color and a bold
            // 24.sp onBackground title (titleLarge.copy(fontSize = 24.sp), maxLines = 1,
            // ellipsized). :ui depends on raw Material3 TopAppBar (TopAppBarCom lives in the
            // legacy module), so reproduce that styling inline here.
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                title = {
                    Text(
                        text = stringResource(Res.string.downloads),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = KiraIcons.Back,
                            contentDescription = stringResource(Res.string.np_back),
                            tint = MaterialTheme.colorScheme.onBackground,
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
            if (state.isLoading) {
                KiraLoadingState()
                return@Box
            }
            Column(modifier = Modifier.fillMaxSize()) {
                val tabLabels = listOf(
                    stringResource(Res.string.active),
                    stringResource(Res.string.failed),
                    stringResource(Res.string.completed),
                )
                SecondaryTabRow(
                    selectedTabIndex = state.selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                ) {
                    tabLabels.forEachIndexed { index, title ->
                        Tab(
                            selected = state.selectedTab == index,
                            onClick = { onIntent(DownloadsIntent.OnTabSelect(index)) },
                            text = { Text(title) },
                        )
                    }
                }
                when (state.selectedTab) {
                    0 -> DownloadBucketList(
                        items = state.active,
                        emptyLabel = stringResource(Res.string.no_active_downloads),
                        onIntent = onIntent,
                    )
                    1 -> DownloadBucketList(
                        items = state.failed,
                        emptyLabel = stringResource(Res.string.no_failed_downloads),
                        onIntent = onIntent,
                    )
                    else -> DownloadBucketList(
                        items = state.completed,
                        emptyLabel = stringResource(Res.string.no_completed_downloads),
                        onIntent = onIntent,
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadBucketList(
    items: List<DownloadedChapter>,
    emptyLabel: String,
    onIntent: (DownloadsIntent) -> Unit,
) {
    if (items.isEmpty()) {
        KiraEmptyState(title = emptyLabel)
        return
    }
    val spacing = LocalSpacing.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        // Redesign (downloads mockup): rounded "row" cards stacked with a consistent 12.dp
        // gutter — the mockup's `.feed { gap:12px }`. The running card sits at the top of the
        // Active bucket (the "Downloading now" hero); queued/completed/failed are plain rows.
        contentPadding = PaddingValues(vertical = spacing.md, horizontal = spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        items(items = items, key = { it.chapterId }) { item ->
            if (item.state == DownloadState.RUNNING) {
                RunningDownloadCard(item = item, onIntent = onIntent)
            } else {
                DownloadCard(item = item, onIntent = onIntent)
            }
        }
    }
}

/**
 * The redesign's shared rounded "row" surface — the mockup's `.row` card:
 * a 16.dp rounded card on `surface` with a hairline outline, a cover thumbnail on the
 * leading edge, a flexible body, and an optional trailing slot. [DownloadedChapter] carries
 * no cover-image URL (see its field set), so the leading thumbnail is a tinted placeholder box
 * — never an invented state field.
 */
@Composable
private fun DownloadRowCard(
    coverGradientTop: Color,
    coverGradientBottom: Color,
    body: @Composable () -> Unit,
    trailing: @Composable (() -> Unit)?,
) {
    val spacing = LocalSpacing.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                shape = RoundedCornerShape(16.dp),
            )
            .padding(spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.md),
    ) {
        DownloadCoverPlaceholder(
            gradientTop = coverGradientTop,
            gradientBottom = coverGradientBottom,
        )
        Box(modifier = Modifier.weight(1f)) { body() }
        if (trailing != null) trailing()
    }
}

/**
 * Leading cover thumbnail stand-in. The mockup shows a 64x86 rounded poster, but
 * [DownloadedChapter] exposes no cover URL — so this renders a tinted placeholder box (a soft
 * vertical wash derived from theme tokens), matching the cover footprint without inventing state.
 */
@Composable
private fun DownloadCoverPlaceholder(
    gradientTop: Color,
    gradientBottom: Color,
) {
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to gradientTop,
                    1f to gradientBottom,
                ),
            ),
    )
}

/**
 * Glanceable status chip — the mockup's `.chip`. Done = greenish (tertiary-ish green tint),
 * Queued = muted (surface-variant), Failed = error-tinted. Colours derive from theme tokens only.
 */
@Composable
private fun DownloadStatusChip(
    label: String,
    contentColor: Color,
    containerColor: Color,
    borderColor: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(containerColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DownloadCard(
    item: DownloadedChapter,
    onIntent: (DownloadsIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    DownloadRowCard(
        coverGradientTop = MaterialTheme.colorScheme.surfaceVariant,
        coverGradientBottom = MaterialTheme.colorScheme.surface,
        body = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.np_download_chapter_title,
                        item.number,
                        item.mangaTitle,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = statusLabel(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (item.state == DownloadState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                DownloadStatusChipFor(item)
            }
        },
        trailing = { DownloadRowAction(item = item, onIntent = onIntent) },
    )
}

/**
 * Picks the right glanceable status chip per [DownloadState] (mockup: muted Queued, green Done,
 * error Failed). RUNNING never reaches here (rendered by [RunningDownloadCard]); COMPRESSING is
 * unreachable in the live flow (VM routes it into no bucket) but kept exhaustive.
 */
@Composable
private fun DownloadStatusChipFor(item: DownloadedChapter) {
    when (item.state) {
        DownloadState.QUEUED -> DownloadStatusChip(
            label = stringResource(Res.string.queued),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
        )
        DownloadState.SUCCESS, DownloadState.COMPRESSING, DownloadState.DOWNLOADED -> DownloadStatusChip(
            // "Done" — greenish chip. There is no dedicated "Done" string key; reuse the existing
            // localized "downloaded" label (also what statusLabel maps SUCCESS/COMPRESSING/DOWNLOADED
            // to). DOWNLOADED (iOS background: pages on disk, finalization pending) shows the same
            // reassuring green "Downloaded" chip while it finishes.
            label = stringResource(Res.string.downloaded),
            contentColor = GoodGreen,
            containerColor = GoodGreen.copy(alpha = 0.12f),
            borderColor = GoodGreen.copy(alpha = 0.25f),
        )
        DownloadState.FAILED -> DownloadStatusChip(
            label = stringResource(Res.string.failed),
            contentColor = MaterialTheme.colorScheme.error,
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.13f),
            borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.28f),
        )
        DownloadState.RUNNING -> {
            // Unreachable: RUNNING rows are rendered by RunningDownloadCard.
        }
    }
}

/**
 * Trailing action glyph per [DownloadState] — the mockup's `.iconbtn`. Preserves every action and
 * intent exactly: QUEUED → cancel (Close, [DownloadsIntent.OnCancel]); FAILED → retry (Refresh,
 * [DownloadsIntent.OnRetry], coral-on) + delete ([DownloadsIntent.OnDelete]); SUCCESS → delete
 * ([DownloadsIntent.OnDelete]). The QUEUED "Cancel" / FAILED "Retry" text-buttons of the prior
 * design become the mockup's icon affordances; the intents dispatched are unchanged.
 */
@Composable
private fun DownloadRowAction(
    item: DownloadedChapter,
    onIntent: (DownloadsIntent) -> Unit,
) {
    when (item.state) {
        DownloadState.QUEUED -> {
            DownloadIconButton(
                icon = KiraIcons.Close,
                contentDescription = stringResource(Res.string.cancel),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onIntent(DownloadsIntent.OnCancel(item)) },
            )
        }
        DownloadState.COMPRESSING, DownloadState.DOWNLOADED -> {
            // Native parity (downloads-offline P2, BusinessLogic): COMPRESSING exposes no action and
            // is never routed into a rendered bucket by the VM. DOWNLOADED (iOS background: pages on
            // disk, finalization pending) likewise exposes no action — it finishes on its own; the
            // user can't cancel/delete mid-finalize. Renders nothing.
        }
        DownloadState.FAILED -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Retry — accented ("on") icon button, dispatches OnRetry (was a "Retry" TextButton).
                DownloadIconButton(
                    icon = KiraIcons.Refresh,
                    contentDescription = stringResource(Res.string.retry),
                    tint = MaterialTheme.colorScheme.primary,
                    accented = true,
                    onClick = { onIntent(DownloadsIntent.OnRetry(item)) },
                )
                DownloadIconButton(
                    icon = KiraIcons.Delete,
                    contentDescription = stringResource(Res.string.np_delete_download),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { onIntent(DownloadsIntent.OnDelete(item)) },
                )
            }
        }
        DownloadState.SUCCESS -> {
            // Native parity (GAP-DL-02): an inert Done check ahead of the Delete action.
            Row(
                horizontalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Done,
                    contentDescription = stringResource(Res.string.np_download_complete),
                    tint = MaterialTheme.colorScheme.primary,
                )
                DownloadIconButton(
                    icon = KiraIcons.Delete,
                    contentDescription = stringResource(Res.string.np_delete_download),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { onIntent(DownloadsIntent.OnDelete(item)) },
                )
            }
        }
        DownloadState.RUNNING -> {
            // RUNNING rows are rendered by RunningDownloadCard which carries its own cancel
            // affordance — the non-running DownloadCard never reaches this branch.
        }
    }
}

/**
 * The mockup's `.iconbtn`: a 42.dp rounded-square tappable glyph. [accented] = the mockup's
 * `.iconbtn.on` (coral-tinted soft fill + border) used for the Retry affordance.
 */
@Composable
private fun DownloadIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
    accented: Boolean = false,
) {
    val container = if (accented) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    }
    val borderColor = if (accented) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    }
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(container)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(13.dp)),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun RunningDownloadCard(
    item: DownloadedChapter,
    onIntent: (DownloadsIntent) -> Unit,
) {
    val spacing = LocalSpacing.current
    val progressFraction = item.progress.coerceIn(0, 100) / 100f
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction,
        animationSpec = tween(durationMillis = 300),
    )
    // Redesign (downloads mockup, "Downloading now"): the prominent running card — cover + chapter
    // title + coral LinearProgressIndicator + right-aligned percent, with a pause/cancel icon
    // button. Behaviour is unchanged: the icon dispatches OnCancelRunning (interruptible in-flight).
    DownloadRowCard(
        coverGradientTop = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
        coverGradientBottom = MaterialTheme.colorScheme.surface,
        body = {
            Column {
                Text(
                    text = stringResource(
                        Res.string.np_download_chapter_title,
                        item.number,
                        item.mangaTitle,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = statusLabel(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(spacing.sm))
                // Coral progress bar (primary track) — the mockup's `.bar > i` accent fill.
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(999.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                // Percent — right-aligned, accent, bold (mockup: 72% in --accent at flex-end).
                Text(
                    text = "${item.progress.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        },
        trailing = {
            // Pause/cancel — the mockup's accented `.iconbtn.on`. Dispatches OnCancelRunning,
            // identical to the prior design's running-card cancel.
            DownloadIconButton(
                icon = Icons.Filled.Cancel,
                contentDescription = stringResource(Res.string.np_cancel_download),
                tint = MaterialTheme.colorScheme.error,
                accented = true,
                onClick = { onIntent(DownloadsIntent.OnCancelRunning(item)) },
            )
        },
    )
}

@Composable
private fun statusLabel(item: DownloadedChapter): String = when (item.state) {
    DownloadState.QUEUED -> stringResource(Res.string.queued)
    DownloadState.RUNNING -> stringResource(Res.string.running)
    // Native parity (downloads-offline P2): native's DownloadItemCard maps COMPRESSING to the
    // localized "downloaded" label (it has no dedicated "Compressing" string). With COMPRESSING
    // routed into no tab by the VM, this branch is unreachable in the live flow; it stays here
    // only to keep the `when` exhaustive and mirrors native's label choice if ever rendered.
    DownloadState.COMPRESSING -> stringResource(Res.string.downloaded)
    // DOWNLOADED (iOS background: pages on disk, finalization pending) reuses the "downloaded" label
    // — the pages are downloaded; the CBZ is just being packaged.
    DownloadState.DOWNLOADED -> stringResource(Res.string.downloaded)
    DownloadState.SUCCESS -> stringResource(Res.string.downloaded)
    DownloadState.FAILED -> {
        // Native parity: DownloadItemCard substitutes the localized R.string.unknown
        // ("Unknown") when errorMsg is null/blank, then renders "Failed: %s". The iOS/Desktop
        // engine persists a locale-independent sentinel for a user-cancel; map it to the localized
        // string here so the reason tracks the current app locale instead of showing raw English.
        val reason = item.errorMsg
        val msg = when {
            reason == DownloadedChapter.CANCELLED_BY_USER_SENTINEL ->
                stringResource(Res.string.pfix_download_cancelled_by_user)
            !reason.isNullOrBlank() -> reason
            else -> stringResource(Res.string.np_download_unknown_error)
        }
        stringResource(Res.string.download_failed_reason, msg)
    }
}
