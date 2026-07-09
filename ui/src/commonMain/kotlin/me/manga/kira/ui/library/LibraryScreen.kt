package me.manga.kira.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.error.AppError
import me.manga.kira.ui.components.VerticalGridFastScroller
import me.manga.kira.ui.components.KiraCountBadge
import me.manga.kira.ui.components.KiraCoverImage
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.util.BackHandler
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.backup_export_action
import me.manga.kira.ui.generated.resources.cancel
import me.manga.kira.ui.generated.resources.contentDescription_search
import me.manga.kira.ui.generated.resources.contentDescription_search_clear
import me.manga.kira.ui.generated.resources.contentDescription_search_icon
import me.manga.kira.ui.generated.resources.content_description_close_search
import me.manga.kira.ui.generated.resources.delete
import me.manga.kira.ui.generated.resources.dropdown_button_refresh
import me.manga.kira.ui.generated.resources.error_auth
import me.manga.kira.ui.generated.resources.error_network
import me.manga.kira.ui.generated.resources.error_network_bad_gateway
import me.manga.kira.ui.generated.resources.error_network_bad_request
import me.manga.kira.ui.generated.resources.error_network_forbidden
import me.manga.kira.ui.generated.resources.error_network_gateway_timeout
import me.manga.kira.ui.generated.resources.error_network_no_connectivity
import me.manga.kira.ui.generated.resources.error_network_not_found
import me.manga.kira.ui.generated.resources.error_network_request_timeout
import me.manga.kira.ui.generated.resources.error_network_server
import me.manga.kira.ui.generated.resources.error_network_service_unavailable
import me.manga.kira.ui.generated.resources.error_network_timeout
import me.manga.kira.ui.generated.resources.error_network_unauthorized
import me.manga.kira.ui.generated.resources.error_occurred
import me.manga.kira.ui.generated.resources.error_platform
import me.manga.kira.ui.generated.resources.error_storage
import me.manga.kira.ui.generated.resources.error_validation
import me.manga.kira.ui.generated.resources.filter_all
import me.manga.kira.ui.generated.resources.filter_bookmarked
import me.manga.kira.ui.generated.resources.filter_completed
import me.manga.kira.ui.generated.resources.filter_downloaded
import me.manga.kira.ui.generated.resources.filter_started
import me.manga.kira.ui.generated.resources.filter_unread
import me.manga.kira.ui.generated.resources.hours_ago
import me.manga.kira.ui.generated.resources.last_updated
import me.manga.kira.ui.generated.resources.library_active_downloads
import me.manga.kira.ui.generated.resources.items_count_format
import me.manga.kira.ui.generated.resources.items_plural
import me.manga.kira.ui.generated.resources.items_singular
import me.manga.kira.ui.generated.resources.library_more_options
import me.manga.kira.ui.generated.resources.library_bookmarked_chapters_desc
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_display
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_filter
import me.manga.kira.ui.generated.resources.library_bottom_sheet_tab_sort
import me.manga.kira.ui.generated.resources.library_bulk_removed
import me.manga.kira.ui.generated.resources.library_empty
import me.manga.kira.ui.generated.resources.library_category_liked
import me.manga.kira.ui.generated.resources.library_category_watching
import me.manga.kira.ui.generated.resources.library_cancelled
import me.manga.kira.ui.generated.resources.library_delete_selected_message
import me.manga.kira.ui.generated.resources.library_delete_selected_title
import me.manga.kira.ui.generated.resources.library_density
import me.manga.kira.ui.generated.resources.library_density_comfortable
import me.manga.kira.ui.generated.resources.library_density_compact
import me.manga.kira.ui.generated.resources.library_density_spacious
import me.manga.kira.ui.generated.resources.library_downloaded_chapters_desc
import me.manga.kira.ui.generated.resources.library_empty_desc_format
import me.manga.kira.ui.generated.resources.library_empty_message_format
import me.manga.kira.ui.generated.resources.library_tab_likes
import me.manga.kira.ui.generated.resources.library_tab_watching_now
import me.manga.kira.ui.generated.resources.library_like
import me.manga.kira.ui.generated.resources.library_options
import me.manga.kira.ui.generated.resources.library_open_random_manga
import me.manga.kira.ui.generated.resources.library_remove_from_library
import me.manga.kira.ui.generated.resources.library_source_badge_format
import me.manga.kira.ui.generated.resources.library_card_total_chapters_desc
import me.manga.kira.ui.generated.resources.library_card_read_chapters_desc
import me.manga.kira.ui.generated.resources.library_cover_content_description
import me.manga.kira.ui.generated.resources.library_single_delete_title
import me.manga.kira.ui.generated.resources.library_single_delete_message
import me.manga.kira.ui.generated.resources.library_selected_count
import me.manga.kira.ui.generated.resources.library_stop_watching
import me.manga.kira.ui.generated.resources.library_toggle_sort_direction
import me.manga.kira.ui.generated.resources.library_unlike
import me.manga.kira.ui.generated.resources.library_watch_now
import me.manga.kira.ui.generated.resources.minutes_ago
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.not_updated_yet
import me.manga.kira.ui.generated.resources.searching_placeholder
import me.manga.kira.ui.generated.resources.sort_alphabetic
import me.manga.kira.ui.generated.resources.sort_date_added
import me.manga.kira.ui.generated.resources.sort_last_read
import me.manga.kira.ui.generated.resources.sort_random
import me.manga.kira.ui.generated.resources.sort_total_chapters
import me.manga.kira.ui.generated.resources.sort_unread_count
import me.manga.kira.ui.generated.resources.time_just_now
import me.manga.kira.ui.generated.resources.title_library
import me.manga.kira.ui.generated.resources.days_ago
import me.manga.kira.ui.generated.resources.weeks_ago
import me.manga.kira.ui.generated.resources.months_ago
import me.manga.kira.ui.generated.resources.years_ago
import me.manga.kira.ui.generated.resources.yesterday
import me.manga.kira.domain.model.LibraryManga
import me.manga.kira.domain.model.Manga
import androidx.compose.ui.unit.Dp
import me.manga.kira.domain.model.library.GridDensity
import me.manga.kira.domain.model.library.LibraryCategory
import me.manga.kira.domain.model.library.LibraryDisplay
import me.manga.kira.domain.model.library.LibraryFilter
import me.manga.kira.domain.model.library.LibrarySort
import me.manga.kira.domain.repository.MangaKey
import me.manga.kira.presentation.library.LibraryEffect
import me.manga.kira.presentation.library.LibraryIntent
import me.manga.kira.presentation.library.LibraryState
import me.manga.kira.presentation.library.LibraryViewModel
import me.manga.kira.ui.theme.LocalBottomBarPadding
import me.manga.kira.ui.theme.LocalSpacing
import me.manga.kira.ui.theme.KiraBrand

/**
 * Library screen — Compose entry point for the Library MVI slice.
 *
 * Renders [LibraryState] and dispatches [LibraryIntent]. One-shot [LibraryEffect]s are
 * collected once via [LaunchedEffect] and surfaced through the snackbar host or routed
 * out via [onNavigateToDetails].
 *
 * **Scope discipline:** the first end-to-end :presentation → :ui screen. Intentionally
 * minimal — grid of cards with title placeholders, an inline search field, a
 * selection-mode action row, loading/error/empty states. Pull-to-refresh wraps the content
 * via Material 3 [PullToRefreshBox], dispatching [LibraryIntent.OnRefresh] and consuming
 * `state.isRefreshing` (§148). Image loading, filter/sort sheets, and visual parity with
 * the legacy `LibraryScreen` land in subsequent micro-slices as the domain extends. Until
 * then the legacy screen remains the user-facing binding in `:composeApp`; the Phase 8
 * swap only happens at parity.
 *
 * **Audit-trail postscript** (Phase 9.x.library.staleKdocSweep.cascade,
 * Task #454, 2026-05-28): two stale line-anchored citations into the
 * §347-retired legacy Library UI chain appear in per-composable KDocs
 * below:
 *  - The [DeleteSelectedDialog] KDoc cites "the legacy
 *    `LibraryScreenRoute.kt:111-172` `AlertDialog` posture".
 *  - The [DownloadProgressBadge] KDoc cites "the legacy
 *    `LibraryScreen.kt:153-160` `AnimatedPreloader` spinner-icon
 *    button".
 * The legacy `composeApp/.../features/library/ui/routes/LibraryScreenRoute.kt`
 * and `composeApp/.../features/library/ui/screens/LibraryScreen.kt` were
 * both retired in Phase 9.x.library.retire (§347, commit `b5a8bcb`
 * "(3/5): legacy Library UI retire"); verified by a filesystem check
 * returning zero hits for both paths. The MVI-pure dialog-visibility
 * posture and the static-count badge design stand on their
 * own merits — both are documented inline above and are independent of
 * which legacy file originally implemented the parity precedent.
 * Additionally, the file-level KDoc paragraph above predicts "the
 * legacy screen remains the user-facing binding in `:composeApp`; the
 * Phase 8 swap only happens at parity" — that prediction landed at
 * §346 (Phase 9.x.library.swap) and the legacy retire followed at §347.
 * The "until then" framing is preserved verbatim per the audit-trail-
 * preservation convention — the language describes the staging that
 * existed at write-time; the swap+retire has since closed the loop.
 * Original §253-era prose preserved verbatim — the citations are
 * historical record of the design lineage; the rework screen continues
 * to render correctly through the legacy retire.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onNavigateToDetails: (Manga) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToBackupExport: (List<MangaKey>) -> Unit,
    modifier: Modifier = Modifier,
    // #32: optional source-aware cover model. The :composeApp route adapter supplies an
    // ImageRequest carrying per-source Cloudflare auth headers (via rememberSourceImageRequest);
    // null (default) keeps the plain coverUrl. @Composable because the request builder reads
    // CompositionLocals + koin + hydrates headers in a LaunchedEffect.
    coverModel: @Composable ((LibraryManga) -> Any?)? = null,
) {
    val state by viewModel.state.collectAsState()
    LibraryScreenContent(
        state = state,
        effects = viewModel.effects,
        onIntent = viewModel::submit,
        onNavigateToDetails = onNavigateToDetails,
        onNavigateToDownloads = onNavigateToDownloads,
        onNavigateToBackupExport = onNavigateToBackupExport,
        modifier = modifier,
        coverModel = coverModel,
    )
}

/**
 * Stateless host — split from [LibraryScreen] so previews and tests can feed canned state
 * without spinning up a real ViewModel. "Wire to VM" vs "render state" are separate
 * responsibilities (SRP).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreenContent(
    state: LibraryState,
    effects: Flow<LibraryEffect>,
    onIntent: (LibraryIntent) -> Unit,
    onNavigateToDetails: (Manga) -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToBackupExport: (List<MangaKey>) -> Unit,
    modifier: Modifier = Modifier,
    // #32: source-aware cover model slot (see [LibraryScreen]).
    coverModel: @Composable ((LibraryManga) -> Any?)? = null,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Launch snackbars off the effect collector so showing one never blocks a later navigation
    // effect for the snackbar's duration (the user couldn't leave a screen while a snackbar showed).
    val scope = rememberCoroutineScope()
    // UP-6: visibility for the tabbed [LibraryOptionsSheet] (Filter / Sort / Display).
    // Screen-local state, not lifted into `LibraryState` — the sheet's open/closed boolean is
    // pure UI ephemera; MVI state carries only persisted-data fields (filter / sort / direction /
    // density / the 5 `display.show*` booleans). This single sheet replaces the pre-UP-6 trio of
    // top-bar Filter/Sort/Density `DropdownMenu`s + the separate Display `AlertDialog`.
    var showOptionsSheet by remember { mutableStateOf(false) }

    // §150 / Phase 11.ui.UP-3: snackbar strings resolved in composable scope and captured by the
    // [LaunchedEffect] collector — `stringResource` cannot run inside the coroutine collector.
    val errorMessages = rememberAppErrorMessages()
    val emptyLibraryMessage = stringResource(Res.string.library_empty)

    LaunchedEffect(Unit) { onIntent(LibraryIntent.OnEnter) }

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is LibraryEffect.NavigateToDetails -> onNavigateToDetails(effect.manga)
                is LibraryEffect.NavigateToBackupExport -> onNavigateToBackupExport(effect.keys)
                is LibraryEffect.ShowError -> scope.launch { snackbarHostState.showSnackbar(errorMessages(effect.error)) }
                is LibraryEffect.ShowBulkRemoveSuccess -> scope.launch {
                    snackbarHostState.showSnackbar(
                        getString(Res.string.library_bulk_removed, effect.count),
                    )
                }
                is LibraryEffect.ShowEmptyLibraryRefresh -> scope.launch {
                    snackbarHostState.showSnackbar(emptyLibraryMessage)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            LibraryTopBar(
                state = state,
                onIntent = onIntent,
                onOpenOptions = { showOptionsSheet = true },
                onNavigateToDownloads = onNavigateToDownloads,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // The floating bottom nav overlays content from the app root; its inset reaches the grid via
        // LocalBottomBarPadding (added to the grid contentPadding below). Zero the Scaffold insets so
        // the bottom isn't double-counted (LibraryTopBar owns the status-bar inset).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { onIntent(LibraryIntent.OnRefresh) },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    state.isEmpty -> EmptyLibraryMessage(
                        isSearching = state.isSearching,
                        category = state.category,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> LibraryGrid(
                        items = state.items,
                        selection = state.selection,
                        isInSelectionMode = state.isInSelectionMode,
                        gridDensity = state.gridDensity,
                        itemsPerRow = state.itemsPerRow,
                        display = state.display,
                        onIntent = onIntent,
                        coverModel = coverModel,
                    )
                }
            }
        }
    }

    if (state.isDeleteDialogVisible) {
        DeleteSelectedDialog(
            count = state.selection.size,
            onConfirm = { onIntent(LibraryIntent.OnDeleteSelectedConfirm) },
            onDismiss = { onIntent(LibraryIntent.OnDeleteSelectedDismiss) },
        )
    }

    // GAP-LIB-15: per-card single-delete confirmation gate. Shown while
    // `state.pendingSingleDelete != null` (the user tapped a card's trash icon). Restores native
    // parity — the legacy per-card delete opened a route-level delete-confirmation AlertDialog
    // before removing. Confirm dispatches the actual delete; dismiss clears the pending target.
    if (state.pendingSingleDelete != null) {
        SingleDeleteDialog(
            onConfirm = { onIntent(LibraryIntent.OnSingleDeleteConfirm) },
            onDismiss = { onIntent(LibraryIntent.OnSingleDeleteDismiss) },
        )
    }

    // UP-6: the tabbed Filter / Sort / Display options sheet (restores the native app's single
    // bottom sheet). Consolidates the filter/sort/density choices + the 5 `display.show*` toggles;
    // every selection dispatches the same `LibraryIntent` variants the pre-UP-6 scattered menus did.
    if (showOptionsSheet) {
        LibraryOptionsSheet(
            filter = state.filter,
            sort = state.sort,
            sortDirection = state.sortDirection,
            itemsPerRow = state.itemsPerRow,
            display = state.display,
            onIntent = onIntent,
            onDismiss = { showOptionsSheet = false },
        )
    }
}

/**
 * Destructive-action confirmation gate for the bulk-delete flow.
 *
 * Mirrors the legacy `LibraryScreenRoute.kt:111-172` `AlertDialog` posture (Material 3
 * `AlertDialog` with an error-coloured confirm button), adapted to the rework's
 * multi-select bulk-delete shape:
 *
 *  - Legacy fires the dialog for a SINGLE manga via `onToggleDelete(manga)` → captures
 *    `mangaToDelete` in composition-local `mutableStateOf` → renders the dialog → on
 *    confirm calls `vm.removeManga(it)`. Composition-local state because the legacy
 *    VM didn't model dialog visibility.
 *  - Rework fires the dialog for the WHOLE selection set via the [LibraryIntent.
 *    OnDeleteSelected] intent → VM stages `isDeleteDialogVisible = true` in
 *    [LibraryState] → screen observes state and renders → on confirm dispatches
 *    [LibraryIntent.OnDeleteSelectedConfirm] which performs the actual
 *    `BulkRemoveFromLibraryUseCase` call.
 *
 * The MVI-pure shape (dialog visibility in state, not in composition) means
 * configuration changes (rotation, theme change) preserve the dialog's open/closed
 * status without surprising the user. The legacy composition-local approach would
 * also persist across `remember` survivals, but only because the screen never
 * actually navigates away while the dialog is visible — a fragile invariant the
 * rework's MVI surface enforces architecturally.
 *
 * @param count number of selected items the user is about to delete; surfaces in the
 *              body text so the user knows the destructive scope.
 * @param onConfirm dispatches [LibraryIntent.OnDeleteSelectedConfirm].
 * @param onDismiss dispatches [LibraryIntent.OnDeleteSelectedDismiss] (cancel button,
 *                  outside tap, system back).
 */
@Composable
private fun DeleteSelectedDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.library_delete_selected_title, count),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.library_delete_selected_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cancel), fontWeight = FontWeight.Medium)
            }
        },
    )
}

/**
 * Per-card single-delete confirmation gate (GAP-LIB-15).
 *
 * Mirrors [DeleteSelectedDialog]'s posture (Material 3 [AlertDialog], error-coloured confirm
 * button) but for the single-card delete path: the user tapped one card's trash icon and the VM
 * staged [LibraryState.pendingSingleDelete]. Restores native parity — the legacy per-card delete
 * opened a route-level delete-confirmation `AlertDialog` (warning the user that all progress /
 * read-status / bookmarks / downloads are permanently lost) BEFORE `removeManga`. The pre-GAP-LIB-15
 * rework deleted directly with no confirmation — a destructive data-loss regression this closes.
 *
 * No `count` parameter (single, fixed scope), so the title is the singular "Delete manga?".
 *
 * @param onConfirm dispatches [LibraryIntent.OnSingleDeleteConfirm].
 * @param onDismiss dispatches [LibraryIntent.OnSingleDeleteDismiss] (cancel / outside tap / back).
 */
@Composable
private fun SingleDeleteDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(Res.string.library_single_delete_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Text(
                text = stringResource(Res.string.library_single_delete_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.delete),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(Res.string.cancel), fontWeight = FontWeight.Medium)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryTopBar(
    state: LibraryState,
    onIntent: (LibraryIntent) -> Unit,
    onOpenOptions: () -> Unit,
    onNavigateToDownloads: () -> Unit,
) {
    // Library parity fix (audit p1/library finding 3): search is hidden behind a toggle that
    // takes over the top bar (native LibraryScreen.kt:94,118-135). Screen-local UI ephemera —
    // not lifted into LibraryState (same posture as the LibraryOptionsSheet visibility boolean).
    // The search QUERY still lives in state.searchQuery / OnSearchQueryChange; only the bar's
    // shown/hidden flag is local here.
    var showSearchBar by remember { mutableStateOf(false) }
    // Library parity fix (audit p1/library): system-back closes the search bar instead of leaving
    // the screen — native LibraryScreen.kt:106-112 `BackHandler(enabled = showSearchBar){ showSearchBar
    // = false; viewModel.onSearchChanged("") }`. Clearing the query mirrors native's onSearchChanged("").
    BackHandler(enabled = showSearchBar) {
        showSearchBar = false
        onIntent(LibraryIntent.OnSearchQueryChange(""))
    }
    // System-back clears an active multi-select instead of leaving the screen — mirrors
    // DetailsScreen's chapter-selection BackHandler (DetailsScreen.kt:482).
    BackHandler(enabled = state.isInSelectionMode) {
        onIntent(LibraryIntent.OnSelectionClear)
    }
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        if (state.isInSelectionMode) {
            TopAppBar(
                title = { Text(stringResource(Res.string.library_selected_count, state.selection.size)) },
                actions = {
                    // feature/backup — export the selected mangas to a backup file.
                    TextButton(onClick = { onIntent(LibraryIntent.OnExportSelected) }) {
                        Text(stringResource(Res.string.backup_export_action))
                    }
                    TextButton(onClick = { onIntent(LibraryIntent.OnDeleteSelected) }) {
                        Text(stringResource(Res.string.delete))
                    }
                    TextButton(onClick = { onIntent(LibraryIntent.OnSelectionClear) }) {
                        Text(stringResource(Res.string.cancel))
                    }
                },
            )
        } else {
            if (showSearchBar) {
                // Library parity fix (audit p1/library finding 3): the toggled SearchAppBar that
                // replaces the whole title bar — Close nav icon (exits + clears query), leading
                // Search glyph, trailing clear-X when non-blank, ImeAction.Search, transparent
                // borders/container. Mirrors native SearchAppBar.kt verbatim.
                LibrarySearchBar(
                    query = state.searchQuery,
                    onQueryChange = { onIntent(LibraryIntent.OnSearchQueryChange(it)) },
                    onClose = {
                        showSearchBar = false
                        onIntent(LibraryIntent.OnSearchQueryChange(""))
                    },
                )
            } else {
                // Redesign 2026-06: normal-mode top bar replaced with a Home-style header
                // (eyebrow + large bold title + circular HeaderAction buttons), mirroring
                // HomeScreen.HomeHeader / HeaderAction. The existing normal-mode actions are
                // preserved 1:1 — Search toggle, the Tune options sheet, the active-downloads
                // badge, and the Refresh/Open-Random overflow — only their chrome changed from
                // M3 TopAppBar IconButtons to rounded-14dp surfaceVariant action squares.
                // statusBarsPadding() keeps the header clear of the status bar (the M3
                // TopAppBar it replaced owned that inset).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.title_library),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Library parity fix (audit p1/library finding 3): the Search action
                        // that toggles the search bar on (native LibraryScreen.kt:140-141).
                        LibraryHeaderAction(
                            icon = KiraIcons.Search,
                            contentDescription = stringResource(Res.string.contentDescription_search),
                            onClick = { showSearchBar = true },
                        )
                        // UP-6: single options entry point — opens the tabbed [LibraryOptionsSheet]
                        // (Filter / Sort / Display). Replaces the pre-UP-6 trio of Filter/Sort/Density
                        // dropdown TextButtons + the Display TextButton that crowded this actions row.
                        LibraryHeaderAction(
                            icon = KiraIcons.Tune,
                            contentDescription = stringResource(Res.string.library_options),
                            onClick = onOpenOptions,
                        )
                        // The active-downloads indicator keeps its bespoke animated progress-ring
                        // treatment (it is a motion cue, not a plain glyph) and stays conditional
                        // on a positive count — unchanged behaviour.
                        DownloadProgressBadge(
                            count = state.activeDownloadCount,
                            onClick = onNavigateToDownloads,
                        )
                        // GAP-LIB-14: group Refresh + Open-Random under a MoreVert overflow
                        // DropdownMenu, matching native (which keeps the top bar uncluttered by
                        // collapsing both into the overflow rather than two inline TextButtons).
                        LibraryOverflowMenu(onIntent = onIntent)
                    }
                }
            }
            // §150 rung 16f (Task #341): gate the §158 CategoryTabs row visibility on
            // `state.display.showTabs`. Closes the 5/5 per-flag vertical ladder
            // (showSource → showCount → showDetails → showButtons → showTabs). Single
            // screen-level `if` (not per-card) — when `showTabs = false`, the NAN /
            // LIKED / WATCHING_NOW tab row hides; `state.category` is untouched so the
            // grid keeps filtering by the user's previously-selected category. The legacy
            // route's display sheet reads the same `library_show_tabs` disk cell, so
            // a flip from either route is observed by both. Default
            // `LibraryDisplay(showTabs = true)` preserves pre-rung-16 behaviour.
            if (state.display.showTabs) {
                CategoryTabs(
                    category = state.category,
                    onIntent = onIntent,
                )
            }
            // P3 parity fix (audit p3/library, "Last-updated row layout & typography" +
            // "Items-count header label"): native renders the "Last updated: X" label and the
            // "N items" count in a SINGLE `SpaceBetween` Row (native LibraryItems.kt:135-168) —
            // last-updated on the start, count on the end, gated on `showCount`. The pre-P3 rework
            // rendered them as two separate full-width rows. This restores native's combined header.
            LibraryHeaderRow(
                lastUpdated = state.lastUpdated,
                itemCount = state.items.size,
                showCount = state.display.showCount,
            )
        }
    }
}

/**
 * Toggleable full-width search bar that takes over the Library top bar (Library parity fix,
 * audit p1/library finding 3). Mirrors native `SearchAppBar.kt` verbatim:
 *  - [Close] navigation icon → exits search and clears the query (via [onClose]).
 *  - Leading [Search] glyph inside the field.
 *  - Trailing clear-[Close] icon, shown only when the query is non-blank → clears the query.
 *  - [ImeAction.Search] with the soft keyboard hidden on submit.
 *  - Transparent borders + container so the rounded-12 field blends into the app bar
 *    (`searching_placeholder` hint, `labelLarge` text style).
 *
 * Search-on-submit is a no-op beyond hiding the keyboard: filtering is live-per-keystroke
 * through [onQueryChange] → `LibraryIntent.OnSearchQueryChange` (same as native, whose `onSearch`
 * lambda only manages keyboard/query state — the list filters reactively on the query field).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val spacing = LocalSpacing.current
    val keyboardController = LocalSoftwareKeyboardController.current
    TopAppBar(
        navigationIcon = {
            KiraIconButton(
                icon = KiraIcons.Close,
                contentDescription = stringResource(Res.string.content_description_close_search),
                onClick = onClose,
            )
        },
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = {
                    Text(
                        text = stringResource(Res.string.searching_placeholder),
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelLarge,
                leadingIcon = {
                    Icon(
                        imageVector = KiraIcons.Search,
                        contentDescription = stringResource(Res.string.contentDescription_search_icon),
                    )
                },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        KiraIconButton(
                            icon = KiraIcons.Close,
                            contentDescription = stringResource(Res.string.contentDescription_search_clear),
                            onClick = { onQueryChange("") },
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() },
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = spacing.sm),
            )
        },
    )
}

/**
 * Library top-bar overflow menu (GAP-LIB-14) — a [KiraIcons.Overflow] (MoreVert) icon button that
 * opens a [DropdownMenu] with "Refresh" and "Open Random Manga" items. Mirrors the native Library
 * top bar, which collapses both actions under a MoreVert overflow to keep the bar uncluttered
 * (the pre-GAP-LIB-14 rework rendered them as two inline `TextButton`s).
 *
 * Menu open/closed is screen-local ephemera (not MVI state) — same posture as the
 * [LibraryOptionsSheet] visibility boolean. Each item dispatches its existing intent
 * ([LibraryIntent.OnRefresh] / [LibraryIntent.OnOpenRandom]) and closes the menu.
 */
@Composable
private fun LibraryOverflowMenu(onIntent: (LibraryIntent) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // Redesign 2026-06: the overflow anchor uses the same circular HeaderAction chrome as the
    // sibling Search / options actions (was a bare KiraIconButton). The DropdownMenu anchors to
    // this Box and its Refresh / Open-Random items + intents are unchanged.
    Box {
        LibraryHeaderAction(
            icon = KiraIcons.Overflow,
            contentDescription = stringResource(Res.string.library_more_options),
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.dropdown_button_refresh)) },
                onClick = {
                    expanded = false
                    onIntent(LibraryIntent.OnRefresh)
                },
            )
            DropdownMenuItem(
                // P3 parity fix (audit p3/library, "Open Random Manga action"): native's overflow item
                // reads "Open Random Manga" (dropdown_button_open_random_manga), not the terse "Random".
                text = { Text(stringResource(Res.string.library_open_random_manga)) },
                onClick = {
                    expanded = false
                    onIntent(LibraryIntent.OnOpenRandom)
                },
            )
        }
    }
}

/**
 * Circular header-action button used by the redesigned normal-mode [LibraryTopBar] — a rounded-14dp
 * `surfaceVariant` square (42.dp) with a centered 20.dp glyph. Mirrors `HomeScreen.HeaderAction`
 * verbatim so the Library and Home headers read as one family. Stateless: takes the glyph, an
 * a11y [contentDescription], and the click callback the original [KiraIconButton] carried.
 */
@Composable
private fun LibraryHeaderAction(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(42.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Localized display label for the sort mode. Mirrors the legacy
 * `LibraryViewModel.kt:133-141` `SortType.displayName` mapping for all six modes.
 * Lives in `:ui` (not `:domain`) so the enum stays platform-neutral; resolves each label
 * through `stringResource(...)` (Phase 11.ui.UP-3 localization lift) — hence `@Composable`.
 */
@Composable
internal fun librarySortLabel(sort: LibrarySort): String = when (sort) {
    LibrarySort.ALPHABETIC -> stringResource(Res.string.sort_alphabetic)
    LibrarySort.DATE_ADDED -> stringResource(Res.string.sort_date_added)
    LibrarySort.UNREAD_COUNT -> stringResource(Res.string.sort_unread_count)
    LibrarySort.TOTAL_CHAPTERS -> stringResource(Res.string.sort_total_chapters)
    LibrarySort.LAST_READ -> stringResource(Res.string.sort_last_read)
    LibrarySort.RANDOM -> stringResource(Res.string.sort_random)
}

/**
 * Localized display label for the filter axis. Same `:ui`-resident posture and
 * `stringResource(...)` lift (Phase 11.ui.UP-3) as [librarySortLabel].
 */
@Composable
internal fun libraryFilterLabel(filter: LibraryFilter): String = when (filter) {
    LibraryFilter.ALL -> stringResource(Res.string.filter_all)
    LibraryFilter.DOWNLOADED -> stringResource(Res.string.filter_downloaded)
    LibraryFilter.UNREAD -> stringResource(Res.string.filter_unread)
    LibraryFilter.STARTED -> stringResource(Res.string.filter_started)
    LibraryFilter.COMPLETED -> stringResource(Res.string.filter_completed)
    LibraryFilter.BOOKMARKED -> stringResource(Res.string.filter_bookmarked)
}

/**
 * Localized display label for the grid density. Same `:ui`-resident posture and
 * `stringResource(...)` lift (Phase 11.ui.UP-3) as [librarySortLabel] / [libraryFilterLabel].
 */
@Composable
internal fun gridDensityLabel(density: GridDensity): String = when (density) {
    GridDensity.COMPACT -> stringResource(Res.string.library_density_compact)
    GridDensity.COMFORTABLE -> stringResource(Res.string.library_density_comfortable)
    GridDensity.SPACIOUS -> stringResource(Res.string.library_density_spacious)
}

/**
 * Mapping from the platform-neutral [GridDensity] enum to the actual `Dp` cell-`minSize`
 * consumed by [GridCells.Adaptive]. Lives in `:ui` (not `:domain`) because `dp` is a Compose
 * UI concern; `:domain` stays free of `androidx.compose.ui.unit` references.
 *
 * Calibration:
 *  - [GridDensity.COMFORTABLE] → 120.dp, byte-for-byte identical to the pre-§156 hardcoded value
 *    in [LibraryGrid]. Preserves visual parity for the default case.
 *  - [GridDensity.COMPACT]     → 96.dp, ~80% of the comfortable cell. Yields roughly one
 *    additional column on a typical phone width (~360.dp content area: 3 → 4 columns).
 *  - [GridDensity.SPACIOUS]    → 160.dp, ~133% of the comfortable cell. Yields roughly one
 *    fewer column on the same width (3 → 2 columns).
 *
 * Exhaustive `when` — adding a new [GridDensity] entry forces this arm to extend in the same
 * commit (compile-time OCP enforcement).
 */
private fun GridDensity.minSize(): Dp = when (this) {
    GridDensity.COMPACT -> 96.dp
    GridDensity.COMFORTABLE -> 120.dp
    GridDensity.SPACIOUS -> 160.dp
}

/**
 * Category-tab row: a Material 3 [TabRow] surfacing the three [LibraryCategory] choices
 * (NAN / LIKED / WATCHING_NOW) below the search field. Tapping a tab dispatches
 * [LibraryIntent.OnCategoryChange]; the VM updates `state.category` and re-applies the
 * view pipeline (category → filter → sort → reverse) against the unfiltered snapshot.
 *
 * Tabs (not a dropdown) match the legacy `FilterTabs` posture and the §150 ladder rung 9
 * "category-tabs foundation" intent — the category axis switches the user's view scope
 * (all / liked / watching now) and benefits from one-tap toggling, unlike the secondary
 * filter axis which lives in a dropdown.
 *
 * Selected index is derived from `LibraryCategory.entries.indexOf(category)` (stable enum
 * order: NAN → LIKED → WATCHING_NOW). `containerColor = MaterialTheme.colorScheme.surface`
 * matches the rest of [LibraryTopBar] so the tabs sit flush with the surface above.
 *
 * §150 ladder rung 9 (category-tabs foundation). Persistence will follow in a separate
 * `category.persist` slice mirroring the §154/§157 shape; for now the choice survives only
 * for the lifetime of the ViewModel.
 */
@Composable
private fun CategoryTabs(
    category: LibraryCategory,
    onIntent: (LibraryIntent) -> Unit,
) {
    // Redesign 2026-06: the category axis renders as coral PILLS (mockup `.pills`) instead of an
    // M3 TabRow. Selected = coral→amber brand gradient fill + white text; unselected =
    // surfaceVariant + onSurfaceVariant. The categories enumeration and the
    // `LibraryIntent.OnCategoryChange` dispatch are unchanged (same NAN / LIKED / WATCHING_NOW
    // entries, same selection semantics). Horizontally scrollable so the row never clips on
    // narrow widths.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryCategory.entries.forEach { option ->
            CategoryPill(
                label = libraryCategoryLabel(option),
                selected = option == category,
                onClick = { onIntent(LibraryIntent.OnCategoryChange(option)) },
            )
        }
    }
}

/**
 * A single category pill (redesign 2026-06). Selected pills fill with the [KiraBrand.Gradient]
 * (coral → amber) under white text; unselected pills use `surfaceVariant` under
 * `onSurfaceVariant`. Fully rounded (999) capsule mirroring the mockup `.pill`. Pure projection —
 * a label, a selected flag, and a click callback (which dispatches `OnCategoryChange` at the call
 * site).
 */
@Composable
private fun CategoryPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val baseModifier = Modifier
        .clip(shape)
        .clickable(onClick = onClick)
    val pillModifier = if (selected) {
        baseModifier.background(brush = KiraBrand.Gradient, shape = shape)
    } else {
        baseModifier.background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape)
    }
    Box(
        modifier = pillModifier.padding(horizontal = 16.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * Localized display label for the category axis. Same `:ui`-resident posture and
 * `stringResource(...)` lift (Phase 11.ui.UP-3) as [librarySortLabel] / [libraryFilterLabel] /
 * [gridDensityLabel]. NAN renders as "All" because the user-facing semantic is "no
 * category narrowing applied" — the literal enum name `NAN` is an implementation detail.
 */
@Composable
private fun libraryCategoryLabel(category: LibraryCategory): String = when (category) {
    LibraryCategory.NAN -> stringResource(Res.string.filter_all)
    LibraryCategory.LIKED -> stringResource(Res.string.library_category_liked)
    LibraryCategory.WATCHING_NOW -> stringResource(Res.string.library_category_watching)
}

/**
 * Active-downloads indicator: an animated indeterminate progress ring shown in the top-bar action
 * row whenever [count] is positive, drawn in the primary colour to surface in-flight work.
 * Composes nothing when [count] is zero (keeps the idle top-bar width stable).
 *
 * P2 parity fix (audit p2/library, "Top bar download indicator"): native surfaces active downloads
 * with a looping `Lottie` animation (`R.raw.download_anim`) inside an `IconButton` (native
 * `LibraryScreen.kt:143-150` + `AnimatedPreloader.kt`) — an eye-catching *motion* cue signalling
 * "work in progress", binary (no count). The rework had replaced it with a static icon + count
 * badge, dropping the motion cue. Lottie + the `download_anim` raw asset are Android-only and not
 * available in `:ui` commonMain, so this restores the motion cue with an indeterminate
 * [CircularProgressIndicator] (the audit's explicitly-sanctioned alternative) tinted primary, and
 * retains the numeric count centered inside the ring (the audit's "optionally retain the count
 * overlay") for richer feedback than native's binary indicator.
 *
 * Status indicator (§161, Task #327), paired with [LibraryHeaderRow] in the same status-collector
 * posture (see `LibraryViewModel.init {}` `observeDownloads`). Tapping navigates to the Downloads
 * screen via [onClick] — restoring the legacy app's tap-to-navigate behaviour (the native Library
 * top bar's download indicator opened the Downloads screen).
 */
@Composable
private fun DownloadProgressBadge(count: Int, onClick: () -> Unit) {
    if (count <= 0) return
    val description = stringResource(Res.string.library_active_downloads)
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * Library header status row — "Last updated: <relative time>" on the start, "N items" count on the
 * end, laid out in a single `SpaceBetween` [Row] (P3 parity, audit p3/library "Last-updated row
 * layout & typography" + "Items-count header label"). Mirrors native `LibraryItems.kt:135-168`: one
 * `Row(padding horizontal 16.dp vertical 8.dp, SpaceBetween, CenterVertically)` holding the
 * italic+Medium+12sp last-updated label and (when [showCount]) the Medium+12sp count label.
 *
 * The "Last updated" cell is fed by the Android-only legacy `LibraryRefreshWorker`, which persists a
 * refresh-completion timestamp into the `library_last_updated` SharedPrefs cell (the rework's
 * `LibraryRefreshRepositoryImpl` schedules the same worker via tag `LibraryRefresh`, so the Android
 * refresh path writes the cell uniformly across legacy + rework routes).
 *
 * Fallback: when [lastUpdated] is `null` the label renders "Not updated yet" (matches legacy
 * `LibraryItems.kt:141-152` parity). On iOS / Desktop the worker doesn't exist, so the label always
 * renders the fallback — same posture as the legacy `LibraryViewModel.lastUpdatedFlow` on those
 * targets.
 *
 * The [itemCount] is the size of the currently-visible (post-filter, post-sort) library snapshot, so
 * it reflects exactly what the grid renders; [showCount] gates its visibility on the native "Show
 * Items Count" display toggle.
 *
 * Inline relative-time formatting (vs. routing through the legacy `:composeApp`
 * `LocalDateTime.timeAgo()` helper at `core/util/date/Date.kt:65-99`): the legacy helper lives
 * in `:composeApp` and depends on `:composeApp`'s `stringResource(R.string....)` IDs — pulling
 * it into `:ui` would be a cross-layer leak. The inline [formatRelativeTime] branches here mirror
 * the legacy's bucketing semantics (`<1m`, minutes, hours, days, weeks, months, years) and now
 * resolve each bucket through `:ui`'s own `stringResource(...)` catalog (Phase 11.ui.UP-3
 * localization lift) — same posture as [librarySortLabel] / [libraryFilterLabel] /
 * [libraryCategoryLabel].
 *
 * §150 ladder rung 11 (`lastupdated` display) — read-only status indicator. There is no
 * `LibraryIntent` to mutate `state.lastUpdated`; the cell-of-truth writer is the external
 * refresh worker, not the VM. See `LibraryPrefsRepository.observeLastUpdated` KDoc.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun LibraryHeaderRow(lastUpdated: Instant?, itemCount: Int, showCount: Boolean) {
    val label = if (lastUpdated == null) {
        stringResource(Res.string.not_updated_yet)
    } else {
        stringResource(Res.string.last_updated, formatRelativeTime(lastUpdated, Clock.System.now()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showCount) {
            // P3 parity (audit p3/library, "Items-count header label"): native uses an Android
            // `plurals` resource rendering "1 item" / "N items". CMP `pluralStringResource` is not
            // used anywhere in this module (the rework deliberately keeps single-form templates for
            // cross-locale simplicity), so the singular/plural distinction is reproduced here via the
            // existing `items_count_format` ("%1$d %2$s") + `items_singular` / `items_plural` strings
            // (the same ones the items-per-row slider caption uses) — matching native's "1 item" vs
            // "N items" wording without introducing a `<plurals>` element.
            val noun = if (itemCount == 1) {
                stringResource(Res.string.items_singular)
            } else {
                stringResource(Res.string.items_plural)
            }
            Text(
                text = stringResource(Res.string.items_count_format, itemCount, noun),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bucket the elapsed wall-clock duration between [past] and [now] into a coarse English label
 * (`"just now"`, `"5 minutes ago"`, `"3 hours ago"`, `"yesterday"`, `"4 days ago"`,
 * `"2 weeks ago"`, `"3 months ago"`, `"1 year ago"`). Pure function — no Compose state, no
 * dispatcher pinning.
 *
 * Branches mirror the legacy `LocalDateTime.timeAgo()` helper at
 * `composeApp/.../core/util/date/Date.kt:65-99` so the rework's status label renders
 * identically to the legacy "Last updated" Text under the same wall-clock conditions.
 *
 * Negative deltas (clock skew between worker write and current device time) map to
 * `"just now"` rather than a future-tense label — same posture as the legacy helper. The
 * rework's VM observes the value from the Android-only worker's local-time write, so
 * cross-device skew is not a meaningful concern.
 */
@OptIn(ExperimentalTime::class)
@Composable
private fun formatRelativeTime(past: Instant, now: Instant): String {
    val seconds = (now - past).inWholeSeconds.coerceAtLeast(0L)
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    val weeks = days / 7
    val months = days / 30
    val years = days / 365
    return when {
        seconds < 60 -> stringResource(Res.string.time_just_now)
        minutes < 60 -> stringResource(Res.string.minutes_ago, minutes.toInt())
        hours < 24 -> stringResource(Res.string.hours_ago, hours.toInt())
        days == 1L -> stringResource(Res.string.yesterday)
        days < 7 -> stringResource(Res.string.days_ago, days.toInt())
        weeks < 5 -> stringResource(Res.string.weeks_ago, weeks.toInt())
        days < 365 -> stringResource(Res.string.months_ago, months.toInt())
        else -> stringResource(Res.string.years_ago, years.toInt())
    }
}

/**
 * Empty-library placeholder (P2 parity fix, audit p2/library "Empty library state").
 *
 * Mirrors native `EmptyLibraryPlaceholder.kt:26-50` verbatim: a centered [Column] (fillMaxSize,
 * 32.dp padding) with an [Icons.Outlined.Inbox] icon (72.dp, `onBackground` @0.7f alpha), a 16.dp
 * [Spacer], and a `titleLarge` Bold centered message naming the active category —
 * "Your <Library / Watching Now / Likes> is empty" (native `empty_library_message` = "Your %1$s is
 * empty"). The tab name follows native `LibraryItems.kt:92-96`: NAN → "Library", WATCHING_NOW →
 * "Watching Now", LIKED → "Likes".
 *
 * The search case keeps a distinct, simpler "no results" caption (a rework enhancement the audit
 * explicitly preserves — native has no search-empty distinction).
 */
@Composable
private fun EmptyLibraryMessage(
    isSearching: Boolean,
    category: LibraryCategory,
    modifier: Modifier = Modifier,
) {
    if (isSearching) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.no_results_found),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val tabName = libraryTabName(category)
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.Inbox,
            contentDescription = stringResource(Res.string.library_empty_desc_format, tabName),
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = stringResource(Res.string.library_empty_message_format, tabName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * Active-category tab display name used by the empty-library placeholder, mirroring native
 * `LibraryItems.kt:92-96` (NAN → "Library", WATCHING_NOW → "Watching Now", LIKED → "Likes"). These
 * are the native FilterTabs *display* names — distinct from the in-tab-row category labels
 * ([libraryCategoryLabel], "All" / "Liked" / "Watching") — to reproduce native's empty-state copy
 * exactly.
 */
@Composable
private fun libraryTabName(category: LibraryCategory): String = when (category) {
    LibraryCategory.NAN -> stringResource(Res.string.title_library)
    LibraryCategory.WATCHING_NOW -> stringResource(Res.string.library_tab_watching_now)
    LibraryCategory.LIKED -> stringResource(Res.string.library_tab_likes)
}

@Composable
private fun LibraryGrid(
    items: List<LibraryManga>,
    selection: Set<MangaKey>,
    isInSelectionMode: Boolean,
    gridDensity: GridDensity,
    itemsPerRow: Int,
    display: LibraryDisplay,
    onIntent: (LibraryIntent) -> Unit,
    coverModel: @Composable ((LibraryManga) -> Any?)? = null,
) {
    val spacing = LocalSpacing.current
    // Library parity fix (audit p1/library finding 2): mirror native LibraryItems.kt:184-201 —
    // a fixed column count when the user pinned one (itemsPerRow in 1..8) else the adaptive
    // cell. Native: `if (itemsPerRow > 0) GridCells.Fixed(itemsPerRow) else GridCells.Adaptive(...)`.
    // The adaptive (Auto) branch keeps using the existing GridDensity.minSize() cell so the
    // density control still governs Auto-mode cover size.
    val columns = if (itemsPerRow > 0) {
        GridCells.Fixed(itemsPerRow)
    } else {
        GridCells.Adaptive(minSize = gridDensity.minSize())
    }
    // Library parity fix (audit p1/library finding 2): the native Library grid wraps its
    // LazyVerticalGrid in VerticalGridFastScroller (native LibraryItems.kt:188-228 via
    // LazyVerticalScrollerWithScrollBar) so long libraries get a draggable quick-jump thumb. Pass the
    // SAME LazyGridState to the scroller and the grid, and the SAME columns/arrangement/contentPadding
    // the grid uses (the scroller maps the thumb position onto a grid index using these).
    val gridState = rememberLazyGridState()
    val horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    // Bottom inset clears the floating nav so the last library row stays reachable; the grid still
    // scrolls edge-to-edge underneath the capsule. (Shared by the fast-scroller and the grid so the
    // thumb maps onto the same content rect.)
    val contentPadding = PaddingValues(
        start = spacing.sm,
        end = spacing.sm,
        top = spacing.sm,
        bottom = spacing.sm + LocalBottomBarPadding.current,
    )
    VerticalGridFastScroller(
        state = gridState,
        columns = columns,
        arrangement = horizontalArrangement,
        contentPadding = contentPadding,
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = columns,
            contentPadding = contentPadding,
            horizontalArrangement = horizontalArrangement,
            verticalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            items(
                items = items,
                // Key by the DB-unique `url` (SavedMangaEntity has `unique = true` on url). The
                // (api,language,title) tuple is NOT unique, so two saved manga with the same title
                // but different URLs would collide and crash LazyVerticalGrid with duplicate keys.
                key = { it.manga.url },
            ) { item ->
                LibraryCard(
                    item = item,
                    isSelected = item.manga.toKey() in selection,
                    isInSelectionMode = isInSelectionMode,
                    display = display,
                    onIntent = onIntent,
                    coverModel = coverModel,
                )
            }
        }
    }
}

@OptIn(ExperimentalTime::class, ExperimentalFoundationApi::class)
@Composable
private fun LibraryCard(
    item: LibraryManga,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    display: LibraryDisplay,
    onIntent: (LibraryIntent) -> Unit,
    coverModel: @Composable ((LibraryManga) -> Any?)? = null,
) {
    val spacing = LocalSpacing.current
    val placeholderTint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Card(
        // Redesign 2026-06: cover corners 8 → 14.dp to match the new poster language (Home grid /
        // search cards). Still the owner-approved cover-only card (no frame); only the radius changed.
        shape = RoundedCornerShape(14.dp),
        // UI tweak (user request): the card is the full-bleed cover only — no surrounding
        // container frame/box. The card surface is transparent (so the surfaceVariant band that
        // used to frame the cover is gone) and elevation is flat (no drop shadow). Selection is
        // shown with a primary border instead of a container tint.
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onIntent(LibraryIntent.OnItemClick(item.manga)) },
                onLongClick = { onIntent(LibraryIntent.OnItemLongClick(item.manga.toKey())) },
            ),
    ) {
        Column {
            // §179 rung 19 (Task #345): per-card action row overlay. Three IconButtons —
            // watch-later / like / delete — stacked vertically on the cover's right edge,
            // gated on `display.showButtons` (rung 16e per-flag prefs cell — see
            // [LibraryIntent.OnToggleShowButtons] KDoc) AND `!isInSelectionMode` (the
            // long-press grid swap takes over card taps for selection toggling — the icons
            // would intercept those, so they hide while the user is mid-selection — same
            // intent as the legacy MangaCard, which is wrapped in a selection overlay at the
            // screen level rather than gated at the card; see legacy
            // `composeApp/.../features/library/ui/screens/MangaCard.kt:180-271`).
            //
            // Layout: `BoxWithConstraints` reads the cover's resolved width to size each
            // button as `cardWidth * 0.22f` (clamped to 4..40 dp) — same adaptive formula
            // the legacy MangaCard uses (`buttonSize = (cardWidth * 0.22f).coerceIn(4.dp,
            // 40.dp)`) so the row stays usable across all five `GridDensity` choices.
            //
            // Iconography: real Material icons via [KiraIconButton] (favorite, watch-later,
            // delete). Visual state flip on the affinity icons (filled ↔ outline) gives
            // the user immediate feedback on each tap — `item.isLiked` / `item.isWatchingNow`
            // flow through from the `observeLibrary()` re-emit per the strangler-fig DAO
            // write (see [LibraryRepositoryImpl.toggleLiked] / `.toggleWatchingNow` KDocs).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val cardWidth: Dp = maxWidth
                val buttonSize: Dp = (cardWidth * 0.22f).coerceIn(4.dp, 40.dp)
                LibraryCardCover(
                    coverUrl = item.manga.coverUrl,
                    title = item.manga.title,
                    placeholderTint = placeholderTint,
                    // #32: source-aware request (per-source auth headers) when the route adapter
                    // supplies one; null falls back to the plain coverUrl inside KiraCoverImage.
                    model = coverModel?.invoke(item),
                )
                // GAP-LIB-17: source brand badge overlaid top-start on the cover. Mirrors the
                // native MangaCard — a small rounded-4dp Card tinted with the source brand color
                // (api.COLORS) at 80% alpha, showing "api - language" in contrast-aware text.
                // Gated on `display.showSource` (the native `showSource` toggle). Placement moved
                // from a below-cover caption to this on-cover overlay to match native.
                if (display.showSource && item.manga.api.isNotBlank()) {
                    LibrarySourceBadge(
                        api = item.manga.api,
                        language = item.manga.language,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(spacing.xs),
                    )
                }
                // Library parity fix (audit p1/library finding 1) + GAP-LIB-18: the bottom overlay
                // (title + optional 4 count badges) drawn over the cover's bottom gradient scrim,
                // mirroring native MangaCard.kt:286-331 — a Column aligned BottomStart with a
                // verticalGradient(Transparent -> Black 0.8f) background and 8.dp padding. The
                // manga title is rendered HERE (white, Bold, 14sp, maxLines 2, ellipsis) overlaid
                // on the cover, NOT in a Row below the cover. The detail badges (List / RemoveRedEye
                // / Download / BookmarkAdd, all white) follow below the title, gated on
                // `display.showDetails` exactly like native's `if (showDetails)` IconWithCount row.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)),
                            ),
                        )
                        .padding(8.dp),
                ) {
                    Text(
                        text = item.manga.title,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    if (display.showDetails) {
                        LibraryCardDetailBadges(
                            totalChapters = item.totalChapters,
                            readCount = (item.totalChapters - item.unreadCount).coerceIn(0, item.totalChapters),
                            downloadedCount = item.downloadedCount,
                            bookmarkedCount = item.bookmarkedCount,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (display.showButtons && !isInSelectionMode) {
                    // P2 parity fix (audit p2/library, "Per-card action buttons placement &
                    // styling"): native anchors the action Column to the TOP-END of the cover, just
                    // under the source badge (native MangaCard.kt:160-282 — a single TopStart column
                    // holding a badge Row then a Row aligned End with the button Column). The rework
                    // had centered it (CenterEnd). When the source badge is shown we offset the
                    // buttons down by roughly the badge band (`buttonSize`) so they sit below it,
                    // matching native's stacked badge-then-buttons layout; with the badge hidden the
                    // badge Row collapses in native, so the buttons sit flush at the top.
                    val badgeShown = display.showSource && item.manga.api.isNotBlank()
                    LibraryCardActionRow(
                        isLiked = item.isLiked,
                        isWatchingNow = item.isWatchingNow,
                        buttonSize = buttonSize,
                        spacerSize = (cardWidth * 0.01f).coerceIn(1.dp, 3.dp),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(spacing.xs)
                            .padding(top = if (badgeShown) buttonSize + spacing.xs else 0.dp),
                        onToggleWatchingNow = {
                            onIntent(LibraryIntent.OnToggleWatchingNow(item.manga.toKey()))
                        },
                        onToggleLike = {
                            onIntent(LibraryIntent.OnToggleLike(item.manga.toKey()))
                        },
                        onSingleDelete = {
                            // GAP-LIB-15: route through the per-card delete-CONFIRMATION step rather
                            // than deleting directly. Mirrors the legacy route-level delete-confirm
                            // AlertDialog; the actual removal is gated behind OnSingleDeleteConfirm.
                            onIntent(LibraryIntent.OnSingleDeleteRequest(item.manga.toKey()))
                        },
                    )
                }
            }
            // UI tweak (user request): the redundant below-cover count Row (unread / downloaded /
            // bookmarked badges) was removed. The same total/read/downloaded/bookmarked information
            // is already shown as the on-cover [LibraryCardDetailBadges] overlay above (gated on
            // showDetails), so the external row was duplicated clutter. The card now renders ONLY
            // the cover (with its title + badge overlay) — matching native MangaCard.kt:286-331,
            // which has no below-cover caption either.
        }
    }
}

/**
 * Library-grid cover thumbnail — delegates to the shared design-system [KiraCoverImage]
 * (Phase 11.ui.UP-4), which backs onto Coil's singleton [coil3.ImageLoader] and adds the bottom
 * gradient [scrim] + per-cell broken-image error glyph this card was missing versus the native app.
 *
 * `scrim = true` here: the parent overlays the [LibraryCardActionRow] icons on the cover, and the
 * scrim restores the native card's polished cover treatment + lifts the icons' contrast. The
 * shared component uses `AsyncImage` (not `SubcomposeAsyncImage`) so the grid adds no per-cell
 * subcomposition, and shows **no loading spinner** (a calm tinted-box-fills-in signal — 50
 * simultaneous spinners would read as noise); see [KiraCoverImage] for the full rationale.
 *
 * Library-specific posture preserved: explicitly NOT applying [androidx.compose.ui.draw.blur] for
 * adult content. Legacy `LibraryScreen` does not blur adult covers in the library view; the blur
 * gate (`shouldBlur` in §51 / §54) is a Details-screen concern only — bookmarking is the opt-in.
 *
 * P3 a11y parity (audit p3/library): the cover is labelled "Cover of <title>" for TalkBack,
 * mirroring native MangaCard's cover `AsyncImage` (`contentDescription = manga_cover_description`,
 * "Cover of %1$s"). The rework had passed `contentDescription = null`, leaving the cover unlabelled.
 */
@Composable
private fun LibraryCardCover(
    coverUrl: String,
    title: String,
    placeholderTint: Color,
    model: Any? = null,
) {
    KiraCoverImage(
        coverUrl = coverUrl,
        model = model,
        contentDescription = stringResource(Res.string.library_cover_content_description, title),
        // GAP-LIB-19: pin the cover to the native 1:1.5 portrait aspect ratio (the legacy
        // MangaCard cover is `aspectRatio(1f / 1.5f)`). The shared [KiraCoverImage] default
        // 0.7f is slightly wider than native; this restores the exact portrait proportion.
        aspectRatio = 1f / 1.5f,
        placeholderTint = placeholderTint,
        scrim = true,
    )
}

/**
 * Source brand badge (GAP-LIB-17) — a small rounded-4dp [Card] overlaid top-start on the cover,
 * tinted with the source brand color ([libraryBrandColor]) at 80% alpha, showing the
 * "api - language" label (via `library_source_badge_format`) in contrast-aware text
 * (white on dark brand colors, black on light, per [isDarkBrand]). Mirrors the native MangaCard
 * source badge (`MangaCard.kt:172-193`) verbatim: 8sp Bold text, 6dp/2dp inner padding.
 */
@Composable
private fun LibrarySourceBadge(
    api: String,
    language: String,
    modifier: Modifier = Modifier,
) {
    val brand = api.libraryBrandColor
    val textColor = if (brand.isDarkBrand()) Color.White else Color.Black
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        colors = CardDefaults.cardColors(containerColor = brand.copy(alpha = 0.8f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Text(
            text = stringResource(Res.string.library_source_badge_format, api, language),
            color = textColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The 4 on-cover count badges (GAP-LIB-18) — total chapters / read / downloaded / bookmarked —
 * laid out in a bottom-aligned [Row] over the cover's gradient scrim, all white to read against
 * the dark scrim. Mirrors the native MangaCard `showDetails` `IconWithCount` row
 * (`MangaCard.kt:309-330`): icons `Outlined.List` / `Outlined.RemoveRedEye` / `Outlined.Download`
 * / `Outlined.BookmarkAdd`, each weighted equally across the row.
 *
 * Reuses the shared [KiraCountBadge] (icon + count) with white tint; the four entries always
 * render (matching native, which shows every badge including zero counts) so the row reads as a
 * stable 4-column metric strip.
 */
@Composable
private fun LibraryCardDetailBadges(
    totalChapters: Int,
    readCount: Int,
    downloadedCount: Int,
    bookmarkedCount: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        KiraCountBadge(
            icon = Icons.AutoMirrored.Outlined.List,
            contentDescription = stringResource(Res.string.library_card_total_chapters_desc),
            count = totalChapters,
            adaptive = true,
            tint = Color.White,
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.Start),
        )
        KiraCountBadge(
            icon = Icons.Outlined.RemoveRedEye,
            contentDescription = stringResource(Res.string.library_card_read_chapters_desc),
            count = readCount,
            adaptive = true,
            tint = Color.White,
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.Start),
        )
        KiraCountBadge(
            icon = Icons.Outlined.Download,
            contentDescription = stringResource(Res.string.library_downloaded_chapters_desc),
            count = downloadedCount,
            adaptive = true,
            tint = Color.White,
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.Start),
        )
        KiraCountBadge(
            icon = Icons.Outlined.BookmarkAdd,
            contentDescription = stringResource(Res.string.library_bookmarked_chapters_desc),
            count = bookmarkedCount,
            adaptive = true,
            tint = Color.White,
            modifier = Modifier.weight(1f).wrapContentWidth(Alignment.Start),
        )
    }
}

/**
 * Per-card action row — three vertically-stacked action buttons rendered on the cover's right
 * edge: watch-later, like, delete. Each calls its supplied callback; the parent gates visibility
 * on `display.showButtons && !isInSelectionMode` so the row only renders when the user has enabled
 * the buttons surface AND is not currently multi-selecting (during selection the card surface
 * absorbs taps for the long-press grid swap, and the icons would intercept).
 *
 * P2 parity fix (audit p2/library, "Per-card action buttons placement & styling"): mirrors native
 * `MangaCard.kt:199-282` verbatim — each button is a circular [Box] of `size(buttonSize)` with a
 * `RoundedCornerShape(50)` translucent background (surface @0.8f for watch / like,
 * errorContainer @0.8f for delete) and the icon centered at `buttonSize * 0.55f`. The rework had
 * rendered plain [KiraIconButton]s with no background chip and an `onErrorContainer` delete glyph.
 * Tints are native's: watch = primary, like = [Color.Red], delete = [Color.White]. Buttons are
 * separated by [Spacer]s of the adaptive `spacerSize` (= cardWidth * 0.01f coerced 1..3dp).
 *
 * Affinity icons flip visual state to surface the toggle (mirrors native):
 *   - watching-now: [KiraIcons.WatchingNowOn] (filled WatchLater) when active, else
 *     [KiraIcons.WatchingNowOff] (Schedule)
 *   - like: [KiraIcons.FavoriteFilled] when `isLiked`, else [KiraIcons.FavoriteOutline]
 *   - delete: [KiraIcons.Delete], invariant (one-shot action)
 *
 * Stateless / pure projection — takes raw booleans, the resolved sizes, and three nullary
 * callbacks; never holds state, never reaches the VM directly.
 */
@Composable
private fun LibraryCardActionRow(
    isLiked: Boolean,
    isWatchingNow: Boolean,
    buttonSize: Dp,
    spacerSize: Dp,
    modifier: Modifier = Modifier,
    onToggleWatchingNow: () -> Unit,
    onToggleLike: () -> Unit,
    onSingleDelete: () -> Unit,
) {
    val iconSize = buttonSize * 0.55f
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        CardActionButton(
            icon = if (isWatchingNow) KiraIcons.WatchingNowOn else KiraIcons.WatchingNowOff,
            contentDescription = if (isWatchingNow) {
                stringResource(Res.string.library_stop_watching)
            } else {
                stringResource(Res.string.library_watch_now)
            },
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            tint = MaterialTheme.colorScheme.primary,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onToggleWatchingNow,
        )
        Spacer(modifier = Modifier.size(spacerSize))
        CardActionButton(
            icon = if (isLiked) KiraIcons.FavoriteFilled else KiraIcons.FavoriteOutline,
            contentDescription = if (isLiked) {
                stringResource(Res.string.library_unlike)
            } else {
                stringResource(Res.string.library_like)
            },
            backgroundColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            // Redesign 2026-06: the like/favorite heart reads in the coral brand color (was raw
            // Color.Red) so it sits in-family with the sibling watch/delete actions.
            tint = MaterialTheme.colorScheme.primary,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onToggleLike,
        )
        Spacer(modifier = Modifier.size(spacerSize))
        CardActionButton(
            icon = KiraIcons.Delete,
            contentDescription = stringResource(Res.string.library_remove_from_library),
            backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
            tint = Color.White,
            buttonSize = buttonSize,
            iconSize = iconSize,
            onClick = onSingleDelete,
        )
    }
}

/**
 * A single per-card action button (P2 parity fix, audit p2/library): a circular
 * [RoundedCornerShape](50) [Box] of [buttonSize] filled with the translucent [backgroundColor],
 * with the [icon] centered at [iconSize] and tinted [tint]. Mirrors the native MangaCard button
 * Box (`MangaCard.kt:211-279`) — a clickable circular chip rather than a bare icon button.
 */
@Composable
private fun CardActionButton(
    icon: ImageVector,
    contentDescription: String,
    backgroundColor: Color,
    tint: Color,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(buttonSize)
            .clip(RoundedCornerShape(50))
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

private fun Manga.toKey(): MangaKey = MangaKey(api = api, language = language, title = title)

/**
 * Pre-resolves all [AppError] user-facing messages in composable scope and returns a pure
 * `(AppError) -> String` mapper the snackbar [LaunchedEffect] collector can call — `stringResource`
 * cannot run inside the coroutine collector, so every branch is resolved up-front (Phase 11.ui.UP-3).
 */
@Composable
private fun rememberAppErrorMessages(): (AppError) -> String {
    val network = stringResource(Res.string.error_network)
    val net = rememberNetworkErrorMessages()
    val storage = stringResource(Res.string.error_storage)
    val validation = stringResource(Res.string.error_validation)
    val auth = stringResource(Res.string.error_auth)
    val platform = stringResource(Res.string.error_platform)
    val cancelled = stringResource(Res.string.library_cancelled)
    val unexpected = stringResource(Res.string.error_occurred)
    return { error ->
        when (error) {
            is AppError.Network -> net.messageFor(error, fallback = network)
            is AppError.Storage -> storage
            is AppError.Validation -> validation
            is AppError.Auth -> auth
            is AppError.Platform -> platform
            is AppError.Cancelled -> cancelled
            is AppError.Unexpected -> unexpected
        }
    }
}

/**
 * P1 parity: native distinguishes network failures by HTTP status code / transport failure
 * (`State.kt` `httpStatusMessage` + `fromException`) rather than collapsing every
 * [AppError.Network] into one string. Pre-resolves the per-code messages in composable scope; the
 * returned holder's [NetworkErrorMessages.messageFor] is a plain `when` callable from the snackbar
 * collector. Codes native does not name individually fall back to the generic network string.
 */
@Composable
private fun rememberNetworkErrorMessages(): NetworkErrorMessages = NetworkErrorMessages(
    noConnectivity = stringResource(Res.string.error_network_no_connectivity),
    timeout = stringResource(Res.string.error_network_timeout),
    badRequest = stringResource(Res.string.error_network_bad_request),
    unauthorized = stringResource(Res.string.error_network_unauthorized),
    forbidden = stringResource(Res.string.error_network_forbidden),
    notFound = stringResource(Res.string.error_network_not_found),
    requestTimeout = stringResource(Res.string.error_network_request_timeout),
    server = stringResource(Res.string.error_network_server),
    badGateway = stringResource(Res.string.error_network_bad_gateway),
    serviceUnavailable = stringResource(Res.string.error_network_service_unavailable),
    gatewayTimeout = stringResource(Res.string.error_network_gateway_timeout),
)

private class NetworkErrorMessages(
    val noConnectivity: String,
    val timeout: String,
    val badRequest: String,
    val unauthorized: String,
    val forbidden: String,
    val notFound: String,
    val requestTimeout: String,
    val server: String,
    val badGateway: String,
    val serviceUnavailable: String,
    val gatewayTimeout: String,
) {
    fun messageFor(error: AppError.Network, fallback: String): String = when (error) {
        is AppError.Network.NoConnectivity -> noConnectivity
        is AppError.Network.Timeout -> timeout
        is AppError.Network.Serialization -> fallback
        is AppError.Network.Http -> when (error.statusCode) {
            400 -> badRequest
            401 -> unauthorized
            403 -> forbidden
            404 -> notFound
            408 -> requestTimeout
            500 -> server
            502 -> badGateway
            503 -> serviceUnavailable
            504 -> gatewayTimeout
            else -> fallback
        }
    }
}
